"""Local website-rule tests. Every write targets a temporary fixture, never system hosts."""
import ctypes as c
import io
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
import hosts_rules as hosts


class DomainTest(unittest.TestCase):
    def test_normalizes_and_deduplicates_exact_unicode_names(self):
        self.assertEqual(['example.com', 'xn--fsqu00a.xn--0zwm56d'],
                         hosts.domains([' EXAMPLE.com. ', 'example.com', '例子.测试']))
        self.assertEqual('www.example.com', hosts.domain('www.example.com'))

    def test_rejects_injections_urls_addresses_wildcards_and_local_names(self):
        invalid = [None, 1, '', '*', '*.example.com', 'https://example.com', 'example.com:443',
                   'example.com/path', 'example.com\n0.0.0.0 other.com', 'example.com#comment',
                   'example.com\tother.com', 'example.com other.com', 'example.com\x00',
                   '127.0.0.1', '::1', 'localhost', 'router.local', 'x.localhost', 'x.arpa',
                   '-example.com', 'example-.com', 'example..com', 'a' * 64 + '.com',
                   ('a' * 63 + '.') * 4 + 'com']
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                hosts.domain(value)
        with self.assertRaises(ValueError):
            hosts.domains(['example.com'] * (hosts.MAX_DOMAINS + 1))


class DocumentTest(unittest.TestCase):
    def test_roundtrip_preserves_opaque_bytes_bom_newlines_and_no_final_newline(self):
        documents = [b'', b'# local notes', b'127.0.0.1 localhost\r\n# note\r\n',
                     b'# opaque legacy comment \x81\xff\n10.1.1.1 internal.corp',
                     hosts.BOM + b'# UTF-8 \xe4\xb8\xad\xe6\x96\x87\r\n']
        for original in documents:
            with self.subTest(original=original):
                changed = hosts.render(original, ['example.com', 'www.example.com'])
                remaining, names = hosts.split_block(changed)
                self.assertEqual(original, remaining)
                self.assertEqual(['example.com', 'www.example.com'], names)
                self.assertEqual(original, hosts.render(changed, []))
                self.assertEqual(changed, hosts.render(changed, names))
                self.assertIn(b'0.0.0.0 example.com', changed)
                self.assertIn(b':: example.com', changed)

    def test_existing_unrelated_mappings_and_later_external_edits_survive_withdrawal(self):
        original = b'# policy\n10.2.3.4 intranet.example.org\n'
        changed = hosts.render(original, ['example.com']) + b'10.2.3.5 later.example.org\n'
        self.assertEqual(original + b'10.2.3.5 later.example.org\n', hosts.render(changed, []))

    def test_conflicting_unowned_mappings_are_not_overwritten(self):
        for data in (b'10.1.2.3 EXAMPLE.com. alias.example.org # test\n',
                     b'::1 example.com\n'):
            with self.assertRaisesRegex(ValueError, '冲突'):
                hosts.render(data, ['example.com'])
        self.assertEqual(b'10.1.2.3 example.com\n', hosts.render(b'10.1.2.3 example.com\n', []))

    def test_damaged_or_user_edited_managed_sections_fail_without_removal(self):
        correct = hosts.render(b'# outside\n', ['example.com'])
        documents = [hosts.BEGIN + b'\n', hosts.END + b'\n', correct + correct,
                     correct.replace(b':: example.com\n', b''),
                     correct.replace(hosts.NOTE, b'# a user added this line'),
                     correct.replace(b':: example.com', b'1.2.3.4 other.com'),
                     correct.replace(hosts.END, hosts.END + b' edited'),
                     correct.replace(b'0.0.0.0 example.com\n', b'0.0.0.0 example.com\n' * 2)]
        for data in documents:
            with self.subTest(data=data), self.assertRaises(ValueError):
                hosts.render(data, [])

    def test_utf16_nulls_and_oversized_documents_are_never_converted(self):
        for data in (b'\xff\xfe' + 'note'.encode('utf-16le'), b'# bad\0text', b'a' * (hosts.MAX_BYTES + 1)):
            with self.assertRaises(ValueError):
                hosts.render(data, ['example.com'])


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'hosts'
        self.original = b'# pre-existing\r\n127.0.0.1 localhost\r\n'
        self.path.write_bytes(self.original)
        self.store = hosts.HostsFile(self.path, self.root / 'backups')

    def test_write_verifies_bytes_and_retains_backups_when_restoring_only_our_rules(self):
        before = self.store.read()
        backup = self.store.replace(before, ['example.com'])
        self.assertEqual(self.original, backup.read_bytes())
        self.assertEqual(['example.com'], hosts.split_block(self.path.read_bytes())[1])
        with self.path.open('ab') as out:
            out.write(b'10.0.0.4 later.example.org\r\n')
        second = self.store.read()
        backup2 = self.store.replace(second, [])
        self.assertEqual(second.data, backup2.read_bytes())
        self.assertEqual(self.original + b'10.0.0.4 later.example.org\r\n', self.path.read_bytes())
        self.assertTrue(backup.exists())

    def test_unchanged_rules_do_not_create_backups_or_write(self):
        before = self.store.read()
        self.assertIsNone(self.store.replace(before, []))
        self.assertFalse(self.store.backup_dir.exists())
        self.assertEqual(before, self.store.read())

    def test_revision_change_before_commit_never_clobbers_an_external_edit(self):
        before = self.store.read()
        self.path.write_bytes(b'# external edit\n')
        with self.assertRaisesRegex(ValueError, '其他程序'):
            self.store.replace(before, ['example.com'])
        self.assertEqual(b'# external edit\n', self.path.read_bytes())
        self.assertFalse(self.store.backup_dir.exists())

    def test_revision_is_checked_again_after_backup_and_staging(self):
        before = self.store.read()
        backup = self.store._backup
        def edit(data):
            result = backup(data)
            self.path.write_bytes(b'# edited while staging\n')
            return result
        with mock.patch.object(self.store, '_backup', side_effect=edit):
            with self.assertRaisesRegex(OSError, '其他程序'):
                self.store.replace(before, ['example.com'])
        self.assertEqual(b'# edited while staging\n', self.path.read_bytes())
        self.assertEqual([self.original], [p.read_bytes() for p in self.store.backup_dir.iterdir()])
        self.assertEqual([], list(self.root.glob('hosts-local-*.tmp')))

    def test_backup_failure_leaves_original_intact(self):
        with mock.patch.object(self.store, '_backup', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.store.replace(self.store.read(), ['example.com'])
        self.assertEqual(self.original, self.path.read_bytes())

    def test_hardlinks_and_symbolic_links_are_not_edited(self):
        other = self.root / 'other'
        os.link(self.path, other)
        with self.assertRaises(ValueError):
            self.store.read()
        other.unlink()
        if os.name != 'nt':
            other.write_bytes(b'private')
            self.path.unlink()
            self.path.symlink_to(other)
            with self.assertRaises(ValueError):
                self.store.read()
            self.assertEqual(b'private', other.read_bytes())

    def test_redirected_input_cannot_enter_the_settings_writer(self):
        with mock.patch.object(sys, 'stdin', io.StringIO('APPLY\n')), mock.patch.object(hosts, 'system_hosts') as system_path:
            with self.assertRaises(OSError):
                hosts.settings()
            system_path.assert_not_called()

    def test_cli_has_no_automatic_remote_or_startup_mode(self):
        for arguments in ([], ['--apply', 'example.com'], ['--start'], ['--enable'], ['--settings', '--apply']):
            with mock.patch.object(sys, 'argv', ['hosts_rules.py', *arguments]), mock.patch('builtins.print'):
                self.assertEqual(2, hosts.main())

@unittest.skipUnless(os.name == 'nt', 'Windows file replacement and DACL API')
class NativeStorageTest(unittest.TestCase):
    setUp = StorageTest.setUp

    def test_replace_keeps_the_original_native_dacl(self):
        api = c.WinDLL('advapi32', use_last_error=True)
        api.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [c.c_wchar_p, c.c_uint32, c.POINTER(c.c_void_p), c.c_void_p]
        api.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = c.c_int
        api.SetFileSecurityW.argtypes = [c.c_wchar_p, c.c_uint32, c.c_void_p]
        api.SetFileSecurityW.restype = c.c_int
        api.GetFileSecurityW.argtypes = [c.c_wchar_p, c.c_uint32, c.c_void_p, c.c_uint32, c.POINTER(c.c_uint32)]
        api.GetFileSecurityW.restype = c.c_int
        kernel = c.WinDLL('kernel32')
        kernel.LocalFree.argtypes = [c.c_void_p]
        kernel.LocalFree.restype = c.c_void_p
        descriptor = c.c_void_p()
        self.assertTrue(api.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            'D:P(A;;FA;;;OW)(A;;FA;;;SY)(A;;FA;;;BA)', 1, c.byref(descriptor), None), c.get_last_error())
        try:
            self.assertTrue(api.SetFileSecurityW(str(self.path), 0x80000004, descriptor), c.get_last_error())
        finally:
            kernel.LocalFree(descriptor)
        def dacl():
            length = c.c_uint32()
            api.GetFileSecurityW(str(self.path), 4, None, 0, c.byref(length))
            buffer = c.create_string_buffer(length.value)
            self.assertTrue(api.GetFileSecurityW(str(self.path), 4, buffer, len(buffer), c.byref(length)), c.get_last_error())
            return buffer.raw
        original_dacl = dacl()
        self.store.replace(self.store.read(), ['example.com'])
        self.assertEqual(original_dacl, dacl())
        self.store.replace(self.store.read(), [])
        self.assertEqual(original_dacl, dacl())


if __name__ == '__main__':
    unittest.main()

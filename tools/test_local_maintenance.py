"""Local maintenance IO, rollback and timestamp conflict regressions."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SOURCE = Path(__file__).resolve().parents[1] / "windows/local_maintenance.py"
spec = importlib.util.spec_from_file_location("local_maintenance", SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Startup:
    def __init__(self, root, owned=True):
        self.original = ('"%s"' % (root / "LanCmdAgent.exe"), 1)
        self.value = self.original if owned else ('"C:\\other\\LanCmdAgent.exe"', 1)

    def read(self):
        return self.value

    def owned(self, value):
        return value == self.original

    def change(self, expected, replacement):
        if self.value != expected:
            raise ValueError("registration conflict")
        self.value = replacement


class LocalMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lancmd-maintenance-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.root = self.folder / "接收端 & ! folder"
        self.root.mkdir()
        self.session = self.folder / "maintenance"
        self.session.mkdir()
        self.state = self.folder / "state"
        self.state.mkdir()
        self.env = mock.patch.dict(os.environ, APPDATA=str(self.state), USERPROFILE=str(self.folder / "other-user"))
        self.env.start()
        self.addCleanup(self.env.stop)
        self.names = ["Start.exe", "LanCmdAgent.exe", "lan_command_agent.py", "python/python.exe",
                      "python/pythonw.exe", "python/python311.dll", "local_maintenance.py", "python/library.zip"]
        for name in self.names:
            file = self.root / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes((name + "\n").encode())
        self.refresh()
        self.startup = Startup(self.root)

    def refresh(self, mutate=None):
        data = dict(product="LanCmd-Windows", format=1, version="3.16.0", files={
            n: dict(size=(self.root / n).stat().st_size, sha256=m.digest(self.root / n)) for n in self.names})
        if mutate:
            mutate(data)
        (self.root / m.MANIFEST).write_text(json.dumps(data), encoding="utf-8")

    def doc(self, name="report.docx"):
        path = self.folder / name
        path.write_bytes(b"document content")
        os.utime(path, ns=(1600000000000000000, 1600000100000000000))
        return path

    def test_manifest_rejects_tamper_traversal_and_duplicates(self):
        self.assertEqual("LanCmd-Windows", m.manifest(self.root)["product"])
        (self.root / "Start.exe").write_text("changed")
        with self.assertRaises(ValueError):
            m.manifest(self.root)
        self.refresh(lambda d: d["files"].update({"../outside": dict(size=0, sha256="0" * 64)}))
        with self.assertRaises(ValueError):
            m.manifest(self.root)
        self.refresh(lambda d: d["files"].update({"START.EXE": d["files"]["Start.exe"]}))
        with self.assertRaises(ValueError):
            m.manifest(self.root)

    def test_non_object_manifest_rejected(self):
        (self.root / m.MANIFEST).write_text("[]")
        with self.assertRaises(ValueError):
            m.manifest(self.root)

    def test_directory_rename_updates_only_owned_registration_and_keeps_data(self):
        custom = self.root / "my document.txt"
        custom.write_text("keep")
        target = self.folder / "新的安装名称"
        m.migrate(self.root, target, self.session, self.startup, lambda: None)
        self.assertFalse(self.root.exists())
        self.assertEqual("keep", (target / custom.name).read_text())
        self.assertEqual(('"%s"' % (target / "LanCmdAgent.exe"), 1), self.startup.value)
        self.assertEqual("complete", m.load(self.session / "installation.json")["status"])

    def test_registration_failure_rolls_directory_back(self):
        target = self.folder / "renamed"
        with mock.patch.object(self.startup, "change", side_effect=OSError("registry denied")):
            with self.assertRaises(OSError):
                m.migrate(self.root, target, self.session, self.startup, lambda: None)
        self.assertTrue((self.root / "Start.exe").is_file())
        self.assertFalse(target.exists())
        self.assertEqual("rolled_back", m.load(self.session / "installation.json")["status"])

    def test_rename_preserves_other_install_registration(self):
        startup = Startup(self.root, owned=False)
        before = startup.read()
        m.migrate(self.root, self.folder / "other name", self.session, startup, lambda: None)
        self.assertEqual(before, startup.read())

    def test_cannot_overwrite_directory_escape_parent_or_use_device_name(self):
        for name in ("..", "CON", "aux.txt", "A/B", "x\\y", "end.", "space ", ":ads"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                m.new_name(self.root, name)
        existing = self.folder / "existing"
        existing.mkdir()
        with self.assertRaises(ValueError):
            m.migrate(self.root, existing, self.session, self.startup, lambda: None)
        (self.folder / "outside").mkdir()
        with self.assertRaises(ValueError):
            m.migrate(self.root, self.folder / "outside/target", self.session, self.startup, lambda: None)

    def test_uninstall_keeps_unknown_files_configs_recovery_and_backup(self):
        custom = self.root / "upgrade/user-package.zip"
        custom.parent.mkdir()
        custom.write_bytes(b"user data")
        (self.state / "agent.log").write_text("history")
        m.uninstall(self.root, self.session, self.startup, lambda: None)
        self.assertIsNone(self.startup.read())
        self.assertEqual(b"user data", custom.read_bytes())
        self.assertEqual("history", (self.state / "agent.log").read_text())
        self.assertFalse((self.root / "Start.exe").exists())
        self.assertTrue((self.session / "removed-files" / m.MANIFEST).is_file())
        self.assertEqual(set(self.names) | {m.MANIFEST}, set(m.load(self.session / "installation.json")["moved"]))

    def test_uninstall_copy_failure_restores_files_and_startup(self):
        real = shutil.copy2
        def failing(source, destination, *args, **kwargs):
            if Path(source) == self.root / "LanCmdAgent.exe":
                raise OSError("disk full")
            return real(source, destination, *args, **kwargs)
        with mock.patch.object(m.shutil, "copy2", side_effect=failing), self.assertRaises(OSError):
            m.uninstall(self.root, self.session, self.startup, lambda: None)
        m.manifest(self.root)
        self.assertEqual(self.startup.original, self.startup.value)
        self.assertEqual("rolled_back", m.load(self.session / "installation.json")["status"])

    def test_running_or_pending_guard_precedes_any_install_change(self):
        def refuse():
            raise ValueError("receiver running")
        with self.assertRaises(ValueError):
            m.uninstall(self.root, self.session, self.startup, refuse)
        m.manifest(self.root)
        self.assertFalse((self.session / "installation.json").exists())
        for name in ("app-locks.json", "file-locks.json", "usb-disconnects.json", "device-policy.json"):
            file = self.state / name
            file.write_text('{"record":{}}')
            with self.subTest(name=name), self.assertRaises(ValueError):
                m.recovery_check(self.state)
            file.write_text("{}")
        m.recovery_check(self.state)
        upgrade = self.state / "upgrade"
        upgrade.mkdir()
        (upgrade / "upgrade-status.json").write_text('{"status":"prepared"}')
        with self.assertRaises(ValueError):
            m.recovery_check(self.state)
        (upgrade / "upgrade-status.json").write_text('{"status":"completed"}')
        m.recovery_check(self.state)

    def test_uninstall_does_not_remove_other_install_registration(self):
        other = Startup(self.root, owned=False)
        before = other.read()
        m.uninstall(self.root, self.session, other, lambda: None)
        self.assertEqual(before, other.read())

    def test_batch_dates_preserve_contents_access_time_and_restore_original(self):
        paths = [self.doc(), self.doc("other.pdf")]
        before = [p.stat() for p in paths]
        record, result = m.dates(paths, 1700000000000000000, self.session, [self.root, self.state])
        self.assertTrue(all(e["status"] == "applied" for e in result["entries"]), result)
        for path, old in zip(paths, before):
            self.assertEqual(old.st_atime_ns, path.stat().st_atime_ns)
            self.assertEqual(1700000000000000000, path.stat().st_mtime_ns)
            if os.name == "nt":
                self.assertEqual(old.st_ctime_ns, path.stat().st_ctime_ns)
            self.assertEqual(b"document content", path.read_bytes())
        restored = m.restore_dates(record, [self.root, self.state])
        self.assertTrue(all(e["status"] == "restored" for e in restored["entries"]), restored)
        self.assertEqual([s.st_mtime_ns for s in before], [p.stat().st_mtime_ns for p in paths])

    def test_date_restore_skips_newer_edits_and_replaced_file(self):
        first, second = self.doc(), self.doc("other.txt")
        record, _ = m.dates([first, second], 1700000000000000000, self.session, [])
        os.utime(first, ns=(1600000000000000000, 1800000000000000000))
        replacement = self.doc("replacement.txt")
        os.replace(replacement, second)
        result = m.restore_dates(record, [])
        self.assertTrue(all(e.get("error") for e in result["entries"]), result)
        self.assertEqual(1800000000000000000, first.stat().st_mtime_ns)

    def test_date_journal_written_before_change(self):
        path = self.doc()
        before = path.stat().st_mtime_ns
        with mock.patch.object(m, "save", side_effect=OSError("disk full")), self.assertRaises(OSError):
            m.dates([path], 1700000000000000000, self.session, [])
        self.assertEqual(before, path.stat().st_mtime_ns)

    def test_protected_and_executable_files_not_timestamped(self):
        data = self.root / "file.txt"
        data.write_text("protected")
        for path in (data, self.root / "Start.exe", self.doc("script.bat"), self.session):
            with self.subTest(path=path), self.assertRaises(ValueError):
                m.document(path, [self.root, self.session])
        linked = self.folder / "hardlink.txt"
        os.link(self.doc(), linked)
        with self.assertRaises(ValueError):
            m.document(linked, [])

    def test_malformed_restore_record_cannot_partially_change_documents(self):
        path = self.doc()
        record, result = m.dates([path], 1700000000000000000, self.session, [])
        result["entries"].append(None)
        m.save(record, result)
        with self.assertRaises(ValueError):
            m.restore_dates(record, [])
        self.assertEqual(1700000000000000000, path.stat().st_mtime_ns)

    def test_no_unattended_or_redirected_cli(self):
        result = subprocess.run([sys.executable, "-I", str(SOURCE), "--uninstall", str(self.root)],
                                capture_output=True, text=True, encoding="utf-8",
                                env=dict(os.environ, PYTHONIOENCODING="utf-8"), timeout=10)
        self.assertEqual(1, result.returncode)
        self.assertNotIn("Traceback", result.stderr)
        self.assertTrue((self.root / "Start.exe").exists())


if __name__ == "__main__":
    unittest.main()

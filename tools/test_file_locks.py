"""Password, durable recovery journal and real Windows ACL regression."""
import datetime
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'windows'))
from file_locks import FileLocks

class Error(Exception):pass

class Permissions:
    original='D:(A;;FA;;;WD)'
    applied='D:(D;;0x27;;;WD)(A;;FA;;;WD)'
    def __init__(self,journal):self.current=self.original;self.calls=[];self.journal=journal
    def call(self,**q):
        self.calls.append(q)
        if q['action']=='plan':return dict(sid='S-1-1-0',original=self.original,applied=self.applied)
        if q['action']=='inspect':return [dict(path=p,sddl=self.current) for p in q['paths']]
        if q['action']=='apply':
            if not self.journal.exists():raise AssertionError('ACL changed before journal was durable')
            if self.current!=q['expected']:raise ValueError('external change')
            self.current=q['target'];return dict(sddl=self.current)
        raise AssertionError(q)

class FileLockTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.path=self.root/'document.txt';self.path.write_bytes(b'unchanged content')
        self.database=self.root/'state'/'file-locks.json';self.backend=Permissions(self.database)
        self.now=1800000000.0
        self.manager=FileLocks(self.database,backend=self.backend,clock=lambda:self.now,worker=False)
    def request(self,**q):return self.manager.request(q,Error)
    def lock(self,**extra):
        return self.request(action='lock',path=str(self.path),password='test file password!',**extra)['items'][0]
    def test_password_is_salted_redacted_and_required_to_restore_original_acl(self):
        item=self.lock();self.assertEqual('locked',item['status']);self.assertNotIn('salt',item);self.assertNotIn('verifier',item)
        journal=self.database.read_text();self.assertNotIn('test file password!',journal)
        self.assertEqual(b'unchanged content',self.path.read_bytes())
        with self.assertRaisesRegex(Error,'不正确'):self.request(action='unlock',lock_id=item['id'],password='wrong password')
        self.assertEqual(self.backend.applied,self.backend.current)
        q=dict(action='unlock',lock_id=item['id'],password='test file password!')
        result=self.manager.request(q,Error)
        self.assertNotIn('password',q);self.assertTrue(result['unlocked']);self.assertEqual(self.backend.original,self.backend.current)
        self.request(action='forget',lock_id=item['id']);self.assertEqual([],self.request()['items'])
        self.assertTrue(list((self.database.parent/'app-lock-backups').glob('*.json')))
    def test_expiry_uses_windows_clock_and_recovers_after_receiver_restart(self):
        until=datetime.datetime.fromtimestamp(self.now+20).strftime('%Y-%m-%d %H:%M:%S')
        item=self.lock(until_local=until);self.manager.tick();self.assertEqual(self.backend.applied,self.backend.current)
        self.now+=21
        restarted=FileLocks(self.database,backend=self.backend,clock=lambda:self.now,worker=False)
        restarted.tick();self.assertEqual(self.backend.original,self.backend.current)
        self.assertTrue(json.loads(self.database.read_text())[item['id']]['released'])
    def test_indefinite_lock_has_no_automatic_expiry_but_local_recovery_remains_possible(self):
        self.lock();self.now+=86400*365;self.manager.tick();self.assertEqual(self.backend.applied,self.backend.current)
        self.assertTrue(self.manager.recover()[0]['restored']);self.assertEqual(self.backend.original,self.backend.current)
        with self.assertRaises(Error):self.request(action='recover')
    def test_external_acl_change_and_replaced_file_are_never_overwritten(self):
        item=self.lock();self.backend.current='D:(A;;FR;;;WD)'
        with self.assertRaisesRegex(Error,'已变化'):self.request(action='unlock',lock_id=item['id'],password='test file password!')
        self.assertEqual('D:(A;;FR;;;WD)',self.backend.current);self.assertTrue(self.database.exists())
        self.backend.current=self.backend.applied
        self.path.rename(self.root/'old.txt');self.path.write_bytes(b'replacement')
        with self.assertRaises(Error):self.request(action='unlock',lock_id=item['id'],password='test file password!')
        self.assertEqual(b'replacement',self.path.read_bytes());self.assertEqual(self.backend.applied,self.backend.current)
    def test_write_ahead_failure_does_not_change_permissions(self):
        with mock.patch.object(self.manager,'_save',side_effect=OSError('disk full')):
            with self.assertRaisesRegex(Error,'disk full'):self.lock()
        self.assertFalse(any(q['action']=='apply' for q in self.backend.calls))
    def test_bad_times_passwords_protected_paths_and_hardlinks_are_rejected(self):
        for extra in (dict(password='short'),dict(until_local='2020-01-01 00:00:00'),dict(until_local='bad')):
            q=dict(action='lock',path=str(self.path),password='test file password!');q.update(extra)
            with self.assertRaises(Error):self.manager.request(q,Error)
        protected=FileLocks(self.database,protected=(self.root,),backend=self.backend,worker=False)
        with self.assertRaises(Error):protected.request(dict(action='lock',path=str(self.path),password='test file password!'),Error)
        alias=self.root/'alias';os.link(self.path,alias)
        with self.assertRaises(Error):self.lock()
        self.assertFalse(any(q['action']=='apply' for q in self.backend.calls))
    def test_attempt_limit_and_corrupt_backup_never_bypass_the_lock(self):
        item=self.lock()
        for _ in range(5):
            with self.assertRaises(Error):self.request(action='unlock',lock_id=item['id'],password='bad password')
        with self.assertRaisesRegex(Error,'尝试过多'):self.request(action='unlock',lock_id=item['id'],password='test file password!')
        self.now+=61
        self.request(action='unlock',lock_id=item['id'],password='test file password!')
        self.database.write_text('{bad backup',encoding='utf-8')
        with self.assertRaises(Error):self.request()
        self.assertEqual('{bad backup',self.database.read_text())

@unittest.skipUnless(os.name=='nt','Windows NTFS ACL')
class NativeFileLocksTest(unittest.TestCase):
    def test_real_unicode_file_is_unreadable_until_password_or_timed_recovery(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);path=root/'测试 & document.txt';other=root/'other.txt'
            path.write_bytes(b'content intact');other.write_bytes(b'other intact')
            manager=FileLocks(root/'file-locks.json',worker=False)
            try:
                result=manager.request(dict(action='lock',path=str(path),password='local test password'),Error)
                item=result['items'][0];self.assertEqual('locked',item['status'])
                with self.assertRaises(PermissionError):path.read_bytes()
                self.assertEqual(b'other intact',other.read_bytes())
                manager.request(dict(action='unlock',lock_id=item['id'],password='local test password'),Error)
                self.assertEqual(b'content intact',path.read_bytes())
                manager.request(dict(action='forget',lock_id=item['id']),Error)
                clock=[1800000000.0];manager.clock=lambda:clock[0]
                until=datetime.datetime.fromtimestamp(clock[0]+30).strftime('%Y-%m-%d %H:%M:%S')
                locked=manager.request(dict(action='lock',path=str(path),password='local test password',until_local=until),Error)['items'][0]
                with self.assertRaises(PermissionError):path.read_bytes()
                clock[0]+=31;manager.tick();self.assertEqual(b'content intact',path.read_bytes())
                manager.request(dict(action='forget',lock_id=locked['id']),Error)
                manager.request(dict(action='lock',path=str(path),password='local test password'),Error)
                self.assertTrue(manager.recover()[0]['restored']);self.assertEqual(b'content intact',path.read_bytes())
            finally:manager.recover()

if __name__=='__main__':unittest.main()

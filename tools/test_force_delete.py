"""Delete occupied files only after an owner-scoped application confirmation."""
import ctypes
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "windows"))
from file_jobs import FileJobs, file_token
from force_delete import FileInUse, NativeForceDelete


class Error(Exception): pass


def holder(pid=101, created="123"):
    return dict(pid=pid, created=created, application="Document editor", path="C:\\apps\\editor.exe",
                can_close=True, detected=time.time())


class ForceDeleteJobsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.path = self.root / "document.txt"; self.path.write_text("original")
        self.owner = SimpleNamespace(client_id="phone")
        self.backend = mock.Mock()
        self.backend.remove.side_effect = FileInUse([holder()])
        self.jobs = FileJobs(self.root / "jobs.json", allow_non_windows=True, force_backend=self.backend)
        self.addCleanup(self.jobs.close)

    def call(self, **q): return self.jobs.request(q, self.owner, Error)

    def wait(self, predicate):
        deadline = time.monotonic() + 5
        while not predicate():
            if time.monotonic() > deadline: self.fail("File task did not settle")
            time.sleep(.01)

    def start(self, path=None):
        path = path or self.path
        result = self.call(action="create", operation="force_delete",
                           items=[dict(path=str(path), file_id=file_token(path.lstat()))])["job"]
        return self.jobs.jobs[result["id"]]

    def confirm(self, job, **changes):
        q = dict(action="confirm_force_delete", job_id=job["data"]["id"],
                 confirmation_id=job["data"]["confirmation_id"], confirm_data_loss=True)
        q.update(changes)
        return self.call(**q)

    def test_busy_file_is_untouched_until_confirmed_and_approved_processes_come_from_server(self):
        job = self.start()
        self.wait(lambda: job["data"]["status"] == "needs_confirmation")
        self.assertTrue(self.path.exists()); self.assertFalse(job["done"].is_set())
        self.assertEqual([], self.backend.remove.call_args.args[2])
        self.backend.remove.side_effect = lambda path, original, approved, cancel: (
            path.unlink() or dict(deleted=True))
        self.confirm(job, blockers=[holder(999)])  # Untrusted supplied list must not be used.
        self.assertTrue(job["done"].wait(5))
        self.assertFalse(self.path.exists())
        self.assertEqual(101, self.backend.remove.call_args.args[2][0]["pid"])
        self.assertEqual("completed", job["data"]["items"][0]["status"])

    def test_confirmation_is_owner_scoped_and_requires_current_nonce_and_data_loss_flag(self):
        job = self.start(); self.wait(lambda: job["data"]["status"] == "needs_confirmation")
        for params in (dict(confirmation_id="old"), dict(confirm_data_loss=False)):
            with self.assertRaises(Error): self.confirm(job, **params)
        with self.assertRaises(Error):
            self.jobs.request(dict(action="confirm_force_delete", job_id=job["data"]["id"],
                confirmation_id=job["data"]["confirmation_id"], confirm_data_loss=True),
                SimpleNamespace(client_id="other"), Error)
        self.assertEqual(1, self.backend.remove.call_count)

    def test_file_replacement_during_confirmation_is_not_deleted(self):
        job = self.start(); self.wait(lambda: job["data"]["status"] == "needs_confirmation")
        old = self.root / "old"; self.path.rename(old); self.path.write_text("replacement")
        self.confirm(job); self.assertTrue(job["done"].wait(5))
        self.assertEqual("failed", job["data"]["items"][0]["status"])
        self.assertEqual("replacement", self.path.read_text()); self.assertEqual(1, self.backend.remove.call_count)

    def test_new_occupant_requires_another_confirmation_and_cancel_releases_waiting_job(self):
        job = self.start(); self.wait(lambda: job["data"]["status"] == "needs_confirmation")
        first = job["data"]["confirmation_id"]
        self.backend.remove.side_effect = FileInUse([holder(102, "new")])
        self.confirm(job)
        self.wait(lambda: job["data"]["status"] == "needs_confirmation" and job["data"]["confirmation_id"] != first)
        with self.assertRaises(Error): self.confirm(job, confirmation_id=first)
        self.call(action="cancel", job_id=job["data"]["id"])
        self.assertTrue(job["done"].wait(1)); self.assertTrue(self.path.exists())
        self.assertEqual("cancelled", job["data"]["status"])

    def test_protected_occupants_and_directories_are_not_ended(self):
        self.backend.remove.side_effect = FileInUse([dict(holder(), can_close=False)])
        job = self.start(); self.assertTrue(job["done"].wait(5))
        self.assertEqual("failed", job["data"]["items"][0]["status"])
        folder = self.root / "folder"; folder.mkdir()
        before = self.backend.remove.call_count
        job = self.start(folder); self.assertTrue(job["done"].wait(5))
        self.assertEqual(before, self.backend.remove.call_count)


class OccupantRaceTest(unittest.TestCase):
    def test_application_exit_removing_its_own_file_counts_as_removed_without_ending_more_apps(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "temporary"; path.write_text("temporary")
            backend = NativeForceDelete.__new__(NativeForceDelete)
            error = OSError("occupied"); error.winerror = 32
            backend._delete_handle = mock.Mock(side_effect=error)
            owners = [holder(), holder(102)]
            backend.blockers = mock.Mock(return_value=owners)
            backend.end = mock.Mock(side_effect=lambda item: path.unlink())
            result = backend.remove(path, path.lstat(), owners, threading.Event())
            self.assertTrue(result["deleted"])
            self.assertEqual(1, backend.end.call_count)

    def test_new_or_reused_process_is_never_ended_using_old_confirmation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data"; path.write_text("data")
            backend = NativeForceDelete.__new__(NativeForceDelete)
            backend.blockers = mock.Mock(return_value=[holder(created="reused")])
            backend.end = mock.Mock()
            for code in (32, 1224):
                error = OSError("file occupied"); error.winerror = code
                backend._delete_handle = mock.Mock(side_effect=error)
                with self.assertRaises(FileInUse): backend.remove(path, path.lstat(), [holder()], threading.Event())
            backend.end.assert_not_called(); self.assertTrue(path.exists())


@unittest.skipUnless(os.name == "nt", "Requires Windows native file sharing and Restart Manager")
class NativeForceDeleteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.path = self.root / "文件 document.txt"
        self.path.write_text("original")
        self.backend = NativeForceDelete()

    def start_holder(self, share):
        code = """import ctypes, sys
from ctypes import wintypes as w
k=ctypes.WinDLL('kernel32',use_last_error=True)
k.CreateFileW.argtypes=[w.LPCWSTR,w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD,w.DWORD,w.HANDLE]
k.CreateFileW.restype=w.HANDLE
k.CloseHandle.argtypes=[w.HANDLE]
h=k.CreateFileW(sys.argv[1],0x80000000,int(sys.argv[2]),None,3,0,None)
if h==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
print('ready',flush=True)
sys.stdin.readline()
k.CloseHandle(h)
"""
        process = subprocess.Popen([sys.executable, "-c", code, str(self.path), str(share)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=0x08000000)
        def cleanup():
            if process.poll() is None: process.terminate()
            process.wait(10)
            for pipe in (process.stdin, process.stdout, process.stderr): pipe.close()
        self.addCleanup(cleanup)
        self.assertEqual(b"ready", process.stdout.readline().strip())
        return process

    def test_exclusive_file_lists_real_occupant_and_deletes_only_after_confirmation(self):
        original = self.path.lstat(); process = self.start_holder(1)
        with self.assertRaises(FileInUse) as caught:
            self.backend.remove(self.path, original, [], threading.Event())
        owners = caught.exception.blockers
        self.assertIn(process.pid, [p["pid"] for p in owners]); self.assertIsNone(process.poll())
        self.assertTrue(all(p["can_close"] for p in owners), owners)
        result = self.backend.remove(self.path, original, owners, threading.Event())
        self.assertTrue(result["deleted"], result)
        self.assertFalse(self.path.exists()); process.wait(10)

    def test_cooperative_shared_handle_can_survive_posix_directory_entry_deletion(self):
        original = self.path.lstat(); process = self.start_holder(7)
        result = self.backend.remove(self.path, original, [], threading.Event())
        self.assertTrue(result["deleted"], result); self.assertFalse(self.path.exists())
        self.assertIsNone(process.poll(), "A cooperative holder must not be ended")

    def test_protected_file_and_process_identities_are_not_substituted(self):
        original = self.path.lstat()
        self.path.rename(self.root / "old"); self.path.write_text("replacement")
        with self.assertRaisesRegex(ValueError, "身份"):
            self.backend.remove(self.path, original, [], threading.Event())
        self.assertEqual("replacement", self.path.read_text())


if __name__ == "__main__": unittest.main()

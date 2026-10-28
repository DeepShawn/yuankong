"""Allocation/cancellation tests use small files; never reserve hundreds of GB."""
import ctypes
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "windows"))
from placeholder_files import PlaceholderFiles, NativeAllocation, NativeBackend, MAX_BYTES, RESERVE_BYTES


class Error(Exception):
    pass


class Allocation:
    def __init__(self, path, backend):
        self.path, self.backend = path, backend
        self.stream = path.open("xb", buffering=0)
        self.committed = False
    def write(self, data):
        if self.backend.entered is not None:
            self.backend.entered.set()
            self.backend.release.wait(5)
        if self.backend.fail:
            raise OSError("disk failure")
        return self.stream.write(data[:self.backend.short_write] if self.backend.short_write else data)
    def flush(self):
        self.stream.flush()
        os.fsync(self.stream.fileno())
    def inspect(self):
        size = self.path.stat().st_size
        return self.backend.inspection or dict(size=size, allocated=size, attributes=6)
    def commit(self):
        self.committed = True
    def close(self):
        self.stream.close()
        if not self.committed:
            self.path.unlink()


class Backend:
    def __init__(self):
        self.available = MAX_BYTES + RESERVE_BYTES * 2
        self.inspection = None
        self.entered = self.release = None
        self.fail = False
        self.short_write = 0
    def validate(self, path, size):
        pass
    def free(self, path):
        return self.available
    def create(self, path):
        return Allocation(path, self)


class PlaceholderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.backend = Backend()
        self.manager = PlaceholderFiles(self.backend)
        self.addCleanup(self.manager.close)
        self.owner = SimpleNamespace(client_id="phone")
        self.path = Path(self.temp.name) / "reserve.dat"
    def call(self, **q):
        return self.manager.request(q, self.owner, Error)
    def tearDown(self):
        self.manager.close()
        for job in self.manager.jobs.values():
            self.assertTrue(job["done"].wait(8), "allocation worker did not finish cleanup")
    def wait(self, job):
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(8))
        return self.call()["items"][0]
    def test_zero_and_small_files_allocate_exact_bytes_and_commit(self):
        for size in (0, 1025):
            with self.subTest(size=size):
                self.backend.short_write = 19
                job = self.call(action="create", path=str(self.path), size=size)["job"]
                result = self.wait(job)
                self.assertEqual("completed", result["status"], result)
                self.assertEqual(size, self.path.stat().st_size)
                self.assertEqual(size, result["allocated"])
                self.assertEqual(b"\0" * size, self.path.read_bytes())
                self.path.unlink()
    def test_500gb_upper_bound_is_validated_without_large_allocation(self):
        with mock.patch("placeholder_files.threading.Thread.start"):
            job = self.call(action="create", path=str(self.path), size=500_000_000_000)["job"]
            self.assertEqual(MAX_BYTES, job["size"])
            self.assertFalse(self.path.exists())
            self.manager.jobs[job["id"]]["done"].set()
        for size in (-1, MAX_BYTES + 1, True, 1.5, "1"):
            with self.subTest(size=size), self.assertRaises(Error):
                self.call(action="create", path=str(self.path), size=size)
    def test_existing_file_and_links_are_not_overwritten(self):
        self.path.write_bytes(b"original")
        result=self.wait(self.call(action="create", path=str(self.path), size=10)["job"])
        self.assertEqual("failed",result["status"])
        self.assertEqual(b"original", self.path.read_bytes())
        if os.name != "nt":
            alias = Path(self.temp.name) / "alias"
            alias.symlink_to(self.temp.name, target_is_directory=True)
            result=self.wait(self.call(action="create", path=str(alias / "other.dat"), size=10)["job"])
            self.assertEqual("failed",result["status"])
    def test_relative_paths_and_windows_device_aliases_are_rejected(self):
        for path in ("relative.dat", *[str(Path(self.temp.name) / name) for name in ("NUL.txt", "CON .txt", "COM¹.dat", "CONOUT$", "file.")]):
            with self.subTest(path=path), self.assertRaises(Error):
                self.call(action="create", path=path, size=0)
    def test_free_space_preflight_and_runtime_reserve_cleanup(self):
        self.backend.available = RESERVE_BYTES + 9
        result=self.wait(self.call(action="create", path=str(self.path), size=10)["job"])
        self.assertEqual("failed",result["status"])
        self.assertIn("空间不足",result["error"])
        self.assertFalse(self.path.exists())
        self.backend.available = RESERVE_BYTES + 100
        original = self.backend.create
        def create(path):
            result = original(path)
            self.backend.available = RESERVE_BYTES
            return result
        with mock.patch.object(self.backend, "create", side_effect=create):
            result = self.wait(self.call(action="create", path=str(self.path), size=10)["job"])
        self.assertEqual("failed", result["status"])
        self.assertFalse(self.path.exists())
    def test_cancel_and_close_clean_partial_files_and_enforce_owner(self):
        self.backend.entered, self.backend.release = threading.Event(), threading.Event()
        job = self.call(action="create", path=str(self.path), size=1024)["job"]
        self.assertTrue(self.backend.entered.wait(3))
        other = SimpleNamespace(client_id="other")
        self.assertEqual([], self.manager.request({}, other, Error)["items"])
        with self.assertRaises(Error):
            self.manager.request(dict(action="cancel", job_id=job["id"]), other, Error)
        with self.assertRaises(Error):
            self.call(action="create", path=str(self.path.with_name("two.dat")), size=0)
        self.call(id=71, action="cancel", job_id=job["id"])
        self.backend.release.set()
        self.assertEqual("cancelled", self.wait(job)["status"])
        self.assertFalse(self.path.exists())
        self.backend.entered.clear(); self.backend.release.clear()
        job = self.call(action="create", path=str(self.path), size=1024)["job"]
        self.assertTrue(self.backend.entered.wait(3))
        self.manager.close(); self.backend.release.set()
        self.assertEqual("cancelled", self.wait(job)["status"])
        self.assertFalse(self.path.exists())
    def test_write_failure_and_unconfirmed_allocation_do_not_leave_files(self):
        self.backend.fail = True
        result = self.wait(self.call(action="create", path=str(self.path), size=32)["job"])
        self.assertEqual("failed", result["status"])
        self.assertFalse(self.path.exists())
        self.backend.fail = False
        for attributes, allocated in ((6 | 0x200, 32), (6 | 0x800, 32), (2, 32), (6, 0)):
            self.backend.inspection = dict(size=32, allocated=allocated, attributes=attributes)
            result = self.wait(self.call(action="create", path=str(self.path), size=32)["job"])
            self.assertEqual("failed", result["status"])
            self.assertFalse(self.path.exists())
    def test_device_disconnect_waits_for_only_matching_placeholder(self):
        pending = dict(done=threading.Event(), cancel=threading.Event(), data=dict(path="E:\\data\\reserve.dat"))
        self.manager.jobs["pending"] = pending
        self.manager.stop_for_device(dict(paths=["F:\\"]))
        self.assertFalse(pending["cancel"].is_set())
        with self.assertRaises(ValueError):
            self.manager.stop_for_device(dict(paths=[]))
        def finish():
            pending["cancel"].wait(2)
            pending["done"].set()
        worker = threading.Thread(target=finish); worker.start()
        self.manager.stop_for_device(dict(paths=["e:\\"]))
        worker.join(3)
        self.assertTrue(pending["cancel"].is_set())

    def test_slow_usb_preflight_does_not_block_submission_status_or_cancellation(self):
        entered,release=threading.Event(),threading.Event()
        def validate(path,size):entered.set();release.wait(5)
        with ThreadPoolExecutor(max_workers=1) as pool, mock.patch.object(self.backend,"validate",side_effect=validate):
            try:
                job=pool.submit(self.call,action="create",path=str(self.path),size=1024).result(1)["job"]
                self.assertTrue(entered.wait(1))
                result=pool.submit(self.call).result(1)
                self.assertEqual("checking",result["items"][0]["status"])
                self.assertTrue(result["items"][0]["can_cancel"])
                result=pool.submit(self.call,action="cancel",job_id=job["id"]).result(1)
                self.assertTrue(result["cancel_requested"])
                self.assertEqual("cancelling",self.call()["items"][0]["status"])
            finally:release.set()
        self.assertEqual("cancelled",self.wait(job)["status"])
        self.assertFalse(self.path.exists())

    def test_slow_usb_commit_does_not_hold_registry_lock_and_has_explicit_boundary(self):
        entered,release=threading.Event(),threading.Event()
        original=self.backend.create
        def create(path):
            allocation=original(path);commit=allocation.commit
            def blocked():entered.set();release.wait(5);commit()
            allocation.commit=blocked
            return allocation
        with ThreadPoolExecutor(max_workers=1) as pool, mock.patch.object(self.backend,"create",side_effect=create):
            job=self.call(action="create",path=str(self.path),size=128)["job"]
            try:
                self.assertTrue(entered.wait(1))
                item=pool.submit(self.call).result(1)["items"][0]
                self.assertEqual("committing",item["status"])
                self.assertFalse(item["can_cancel"])
                self.assertFalse(pool.submit(self.call,action="cancel",job_id=job["id"]).result(1)["cancel_requested"])
            finally:release.set()
        self.assertEqual("completed",self.wait(job)["status"])

    def test_cancellation_is_not_reported_finished_until_slow_cleanup_returns(self):
        self.backend.entered,self.backend.release=threading.Event(),threading.Event()
        entered,release=threading.Event(),threading.Event()
        original=self.backend.create
        def create(path):
            allocation=original(path);close=allocation.close
            def blocked():entered.set();release.wait(5);close()
            allocation.close=blocked
            return allocation
        with mock.patch.object(self.backend,"create",side_effect=create):
            job=self.call(action="create",path=str(self.path),size=128)["job"]
            try:
                self.assertTrue(self.backend.entered.wait(1))
                self.call(action="cancel",job_id=job["id"]);self.backend.release.set()
                self.assertTrue(entered.wait(1))
                self.assertEqual("cancelling",self.call()["items"][0]["status"])
                self.assertFalse(self.manager.jobs[job["id"]]["done"].is_set())
            finally:release.set();self.backend.release.set()
        self.assertEqual("cancelled",self.wait(job)["status"])
        self.assertFalse(self.path.exists())

    def test_fallback_flushes_bounded_batches_before_the_final_verification(self):
        original=self.backend.create;flushes=[]
        def create(path):
            allocation=original(path);flush=allocation.flush
            def record():flushes.append(path.stat().st_size);flush()
            allocation.flush=record
            return allocation
        with mock.patch.object(self.backend,"create",side_effect=create), \
                mock.patch("placeholder_files.CHUNK_BYTES",1024), mock.patch("placeholder_files.FLUSH_BYTES",4096):
            result=self.wait(self.call(action="create",path=str(self.path),size=10000)["job"])
        self.assertEqual("completed",result["status"])
        self.assertEqual([4096,8192,10000],flushes)


@unittest.skipUnless(os.name == "nt", "Windows allocation APIs")
class NativePlaceholderTest(unittest.TestCase):
    def test_real_allocation_hidden_system_attributes_and_visible_ls(self):
        import lan_command_agent as agent
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "中文占位.dat"
            manager = PlaceholderFiles()
            try:
                owner = SimpleNamespace(client_id="native")
                for size in (0, 2 * 1024 * 1024 + 7):
                    job = manager.request(dict(action="create", path=str(path), size=size), owner, Error)["job"]
                    self.assertTrue(manager.jobs[job["id"]]["done"].wait(20))
                    item = manager.request({}, owner, Error)["items"][0]
                    self.assertEqual("completed", item["status"], item)
                    self.assertGreaterEqual(item["allocated"], size)
                    self.assertEqual(size, path.stat().st_size)
                    listing = next(i for i in agent.cmd_ls(str(path.parent))["items"] if i["name"] == path.name)
                    self.assertTrue(listing["hidden"]); self.assertTrue(listing["system"])
                    with path.open("rb") as stream:
                        self.assertEqual(b"\0" * min(size, 32), stream.read(32))
                    path.unlink()
            finally:
                manager.close()
    def test_uncommitted_file_is_deleted_on_close_and_on_process_termination(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "partial.dat"
            allocation = NativeAllocation(path)
            allocation.write(b"partial"); allocation.close()
            self.assertFalse(path.exists())
            source = ("import sys,time; from pathlib import Path; from placeholder_files import NativeAllocation; "
                      "a=NativeAllocation(Path(sys.argv[1])); a.write(b'partial'); print('ready',flush=True); time.sleep(60)")
            env = dict(os.environ, PYTHONPATH=str(ROOT / "windows"))
            child = subprocess.Popen([sys.executable, "-u", "-c", source, str(path)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                self.assertEqual(b"ready\n", child.stdout.readline().replace(b"\r\n", b"\n"))
                child.kill(); child.wait(timeout=5)
                self.assertFalse(path.exists())
            finally:
                if child.poll() is None:
                    child.kill(); child.wait(timeout=5)
                child.stdout.close(); child.stderr.close()


if __name__ == "__main__":
    unittest.main()

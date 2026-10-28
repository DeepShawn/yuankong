"""Application discovery and suspension tests; never freeze arbitrary CI applications."""
import io
import os
from pathlib import Path
import queue
import sys
import tempfile
import threading
import subprocess
import time
from types import SimpleNamespace
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from app_catalog import AppCatalog,executable
from app_freeze import FreezeCore,serve_worker,NativeSuspension


class Error(Exception):pass


class CatalogTest(unittest.TestCase):
    def test_registered_exe_is_merged_with_running_process_and_lock_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"app.exe";path.write_bytes(b"exe")
            process=dict(pid=10,created="123",path=str(path),name="app",can_terminate=True)
            backend=SimpleNamespace(records=lambda:[dict(name="My App",path=str(path)+",0",publisher="Publisher"),dict(name="My App",path=str(path),version="1.0")])
            calls=[]
            catalog=AppCatalog(lambda value:calls.append(value),SimpleNamespace(list=lambda:[process]),backend)
            result=catalog.request({},Error)
            self.assertEqual(1,len(result["items"]))
            app=result["items"][0]
            self.assertEqual(str(path.resolve()),app["path"]);self.assertTrue(app["can_lock"])
            self.assertEqual(1,app["running"]);self.assertEqual("1.0",app["version"])
            self.assertEqual(10,app["processes"][0]["pid"])
            self.assertIs(result,catalog.request({},Error));self.assertEqual(1,len(calls))

    def test_non_executable_icons_and_protected_apps_are_not_offered_as_lockable(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"system.exe";path.write_bytes(b"exe")
            def deny(value):raise ValueError("受保护")
            source=SimpleNamespace(records=lambda:[dict(name="Runtime",path="icon.dll"),dict(name="Protected",path=str(path))])
            result=AppCatalog(deny,SimpleNamespace(list=lambda:[]),source).request({},Error)
            self.assertTrue(all(not item["can_lock"] for item in result["items"]))
            self.assertIsNone(executable("relative.exe"));self.assertIsNone(executable("http://example.test/app.exe"))


class FreezeTest(unittest.TestCase):
    def core(self):
        processes=[dict(pid=10,created="100",path="app.exe",can_terminate=True),dict(pid=20,created="200",path="app.exe",can_terminate=False)]
        resumed=[]
        backend=SimpleNamespace(list=lambda:processes,suspend=lambda item,path:(item["pid"],item["created"]),resume=resumed.append)
        return FreezeCore(backend,lambda path:("app.exe",[1,2])),resumed

    def test_freeze_is_owner_scoped_and_skips_protected_processes(self):
        core,resumed=self.core()
        result=core.request(dict(action="freeze",owner="phone",path="app.exe"))
        self.assertTrue(result["partial"])
        self.assertEqual([10],[p["pid"] for p in result["items"][0]["processes"]])
        self.assertEqual([],core.request(dict(owner="other"))["items"])
        with self.assertRaises(ValueError):core.request(dict(action="resume",owner="other",freeze_id=result["freeze_id"]))
        core.request(dict(action="resume",owner="phone",freeze_id=result["freeze_id"]))
        self.assertEqual([(10,"100")],resumed);self.assertEqual({},core.jobs)

    def test_a_second_freeze_cannot_accumulate_suspend_counts(self):
        core,resumed=self.core();core.request(dict(action="freeze",owner="phone",path="app.exe"))
        with self.assertRaises(ValueError):core.request(dict(action="freeze",owner="phone",path="app.exe"))
        self.assertEqual([],core.close());self.assertEqual([(10,"100")],resumed)

    def test_pipe_eof_and_missing_heartbeat_restore_held_process_handles(self):
        class Pipe:
            def __init__(self):self.q=queue.Queue()
            def __iter__(self):return self
            def __next__(self):
                item=self.q.get()
                if item is None:raise StopIteration
                return item
        for eof in (True,False):
            core,resumed=self.core();core.request(dict(action="freeze",owner="phone",path="app.exe"))
            source=Pipe();destination=io.StringIO()
            worker=threading.Thread(target=serve_worker,args=(core,source,destination,.3),daemon=True);worker.start()
            if eof:source.q.put(None)
            worker.join(2)
            self.assertFalse(worker.is_alive());self.assertEqual([(10,"100")],resumed)
            source.q.put(None)


@unittest.skipUnless(os.name=="nt","Windows process/display APIs")
class NativeWorkspaceTest(unittest.TestCase):
    def test_suspend_and_resume_only_this_tests_own_child(self):
        with tempfile.TemporaryDirectory() as folder:
            counter=Path(folder)/"counter"
            source="import pathlib,sys,time;p=pathlib.Path(sys.argv[1]);i=0\nwhile True:\n p.write_text(str(i));i+=1;time.sleep(.02)"
            process=subprocess.Popen([sys.executable,"-u","-c",source,str(counter)],creationflags=0x08000000)
            native=NativeSuspension((Path(__file__).resolve().parents[1]/"windows",));handle=None
            try:
                deadline=time.monotonic()+5
                while not counter.exists() and time.monotonic()<deadline:time.sleep(.02)
                self.assertTrue(counter.exists())
                item=native.processes.info(process.pid)
                self.assertTrue(item["can_terminate"],item)
                handle=native.suspend(item,item["path"])
                time.sleep(.05);before=counter.read_bytes();time.sleep(.15)
                self.assertEqual(before,counter.read_bytes())
                native.resume(handle);handle=None
                deadline=time.monotonic()+3
                while counter.read_bytes()==before and time.monotonic()<deadline:time.sleep(.02)
                self.assertNotEqual(before,counter.read_bytes())
            finally:
                if handle is not None:native.resume(handle)
                process.terminate();process.wait(5)


if __name__=="__main__":unittest.main()

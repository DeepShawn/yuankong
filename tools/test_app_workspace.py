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


class RuntimeCatalogTest(unittest.TestCase):
    def test_runtime_scan_classifies_windows_keeps_office_and_attaches_verified_desktop_windows(self):
        from unittest import mock
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);system=root/'Windows';system.mkdir();apps=root/'Apps';apps.mkdir()
            system_exe=system/'system.exe';office=apps/'office.exe';system_exe.write_bytes(b'exe');office.write_bytes(b'exe')
            processes=[dict(pid=11,created='1',path=str(system_exe),name='system',can_terminate=False),
                       dict(pid=22,created='2',path=str(office),name='Office',can_terminate=True)]
            windows=[dict(handle='12',pid=22,title='Document',path=str(office),minimized=False),
                     dict(handle='13',pid=22,title='Stale PID',path=str(system_exe.resolve()),minimized=False)]
            backend=SimpleNamespace(records=lambda:(_ for _ in ()).throw(AssertionError('runtime must not rescan registry')))
            labels=SimpleNamespace(lock=threading.RLock(),cached=dict(items=[dict(path=str(office.resolve()),name='Office Writer',publisher='Microsoft Corporation',version='1.0')]))
            catalog=AppCatalog(lambda value:None,SimpleNamespace(list=lambda:processes),backend,runtime=True,
                               windows=SimpleNamespace(windows=lambda:windows),labels=labels)
            with mock.patch.dict(os.environ,{'SystemRoot':str(system)}):
                result=catalog.request({},Error)
            by_name={item['name']:item for item in result['items']}
            self.assertEqual('windows',by_name['system']['kind'])
            self.assertEqual('third_party',by_name['Office Writer']['kind'])
            self.assertEqual('Microsoft Corporation',by_name['Office Writer']['publisher'])
            self.assertEqual(['Document'],[w['title'] for w in by_name['Office Writer']['windows']])
            self.assertEqual(1,by_name['Office Writer']['running'])
            processes.clear();catalog.updated=0
            result=catalog.request({'refresh':True},Error)
            self.assertEqual([],result['items'])

    def test_unverified_catalog_entries_are_not_misreported_as_third_party(self):
        result=AppCatalog(lambda value:None,SimpleNamespace(list=lambda:[]),
                          SimpleNamespace(records=lambda:[dict(name='Unresolved metadata',path='')])).request({},Error)
        self.assertEqual('unknown',result['items'][0]['kind'])


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


    def test_close_rejects_stale_identity_and_ends_only_this_tests_own_child(self):
        from unittest import mock
        child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"],creationflags=0x08000000)
        native=NativeSuspension((Path(__file__).resolve().parents[1]/"windows",))
        try:
            item=native.processes.info(child.pid)
            self.assertTrue(item["can_terminate"],item)
            core=FreezeCore(native,lambda path:(path,[]))
            stale=dict(item,created=str(int(item["created"])+1))
            with mock.patch.object(native,"list_matching",return_value=[stale]),self.assertRaises(ValueError):
                core.request(dict(action="close",owner="phone",path=item["path"]))
            self.assertIsNone(child.poll())
            with mock.patch.object(native,"list_matching",return_value=[item]):
                result=core.request(dict(action="close",owner="phone",path=item["path"]))
            self.assertEqual(1,result["closed"]);child.wait(5)
        finally:
            if child.poll() is None:child.terminate();child.wait(5)


class ResponsiveOperationsTest(unittest.TestCase):
    def test_slow_catalog_build_is_single_flight_and_returns_loading_without_holding_cache_lock(self):
        release=threading.Event();entered=threading.Event();calls=[]
        def records():calls.append(1);entered.set();release.wait(3);return []
        catalog=AppCatalog(lambda path:None,SimpleNamespace(list=lambda:[]),SimpleNamespace(records=records))
        try:
            start=time.monotonic();result=catalog.request({'background':True},Error)
            self.assertLess(time.monotonic()-start,.5);self.assertTrue(result['loading'])
            self.assertTrue(entered.wait(1))
            for _ in range(20):catalog.request({'background':True,'refresh':True},Error)
            self.assertEqual(1,len(calls))
        finally:release.set()
        self.assertTrue(catalog.done.wait(1));self.assertFalse(catalog.request({'background':True},Error)['loading'])

    def test_freeze_job_status_and_state_remain_available_during_a_slow_native_operation(self):
        from app_freeze import AppFreeze
        from unittest import mock
        control=AppFreeze.__new__(AppFreeze)
        control.jobs_lock=threading.RLock();control.jobs={};control.snapshots={};control.closed=False
        entered=threading.Event();release=threading.Event()
        def rpc(q):entered.set();release.wait(3);return dict(items=[dict(id='held',path=q.get('path'))])
        control._rpc=mock.Mock(side_effect=rpc)
        owner=SimpleNamespace(client_id='phone')
        try:
            result=control.request(dict(action='submit',operation='freeze',path='app.exe',owner='spoofed'),owner,Error)
            self.assertTrue(entered.wait(1));job_id=result['job']['id']
            start=time.monotonic()
            self.assertEqual('running',control.request(dict(action='job',job_id=job_id),owner,Error)['job']['status'])
            self.assertEqual([],control.request(dict(action='state'),owner,Error)['items'])
            self.assertLess(time.monotonic()-start,.5)
            with self.assertRaises(Error):control.request(dict(action='job',job_id=job_id),SimpleNamespace(client_id='other'),Error)
            with self.assertRaises(Error):control.request(dict(action='submit',operation='close',path='other.exe'),owner,Error)
        finally:release.set()
        end=time.monotonic()+1
        while control.jobs[job_id]['status']=='running' and time.monotonic()<end:time.sleep(.01)
        self.assertEqual('completed',control.request(dict(action='job',job_id=job_id),owner,Error)['job']['status'])
        self.assertEqual('held',control.request(dict(action='state'),owner,Error)['items'][0]['id'])
        self.assertEqual('phone',control._rpc.call_args.args[0]['owner'])

    def test_failed_background_operation_is_reported_without_a_success_result(self):
        from app_freeze import AppFreeze
        from unittest import mock
        control=AppFreeze.__new__(AppFreeze)
        control.jobs_lock=threading.RLock();control.jobs={};control.snapshots={};control.closed=False
        control._rpc=mock.Mock(side_effect=OSError("access denied"))
        owner=SimpleNamespace(client_id="phone")
        job=control.request(dict(action="submit",operation="freeze",path="app.exe"),owner,Error)["job"]
        end=time.monotonic()+1
        while control.jobs[job["id"]]["status"] in ("queued","running") and time.monotonic()<end:time.sleep(.01)
        result=control.request(dict(action="job",job_id=job["id"]),owner,Error)["job"]
        self.assertEqual("failed",result["status"]);self.assertIn("access denied",result["error"])
        self.assertNotIn("result",result)

    def test_application_close_uses_verified_versions_skips_protected_and_releases_own_frozen_handles(self):
        core,resumed=FreezeTest().core();ended=[]
        core.backend.terminate=lambda pid,created:ended.append((pid,created))
        core.request(dict(action='freeze',owner='phone',path='app.exe'))
        result=core.request(dict(action='close',owner='phone',path='app.exe'))
        self.assertEqual([(10,'100')],ended);self.assertEqual(1,result['closed'])
        self.assertTrue(result['partial']);self.assertTrue(result['errors'])
        self.assertFalse(result['items']);self.assertEqual([(10,'100')],resumed)

if __name__=="__main__":unittest.main()

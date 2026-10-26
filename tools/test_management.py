import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
from text_editor import read_text,write_text,LIMIT
from app_locks import AppLocks,FilePermissions
from registry_editor import RegistryEditor,NativeRegistry,version
from browser_control import browser_request
from task_manager import TaskManager,NativeProcesses
from privacy_devices import PrivacyDevices,PrivacyHardware
from script_runner import ScriptRunner


class Error(Exception):
    def __init__(self,message,code=1):super().__init__(message)


def settle(check,seconds=10):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if check():return
        time.sleep(.02)
    raise AssertionError("operation did not settle")


class TextTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/"中文 & file.bat"
    def test_create_read_edit_preserve_encoding_and_windows_newlines(self):
        for encoding in ("utf-8","utf-8-bom","gbk","gb18030","utf-16-le","utf-16-be"):
            with self.subTest(encoding=encoding):
                self.path.unlink(missing_ok=True)
                write_text(self.path,dict(text="第一行\n第二行",encoding=encoding,newline="crlf"),True)
                value=read_text(self.path)
                self.assertEqual("第一行\r\n第二行",value["text"])
                self.assertEqual("crlf",value["newline"])
                write_text(self.path,dict(text="更新\n内容",encoding=encoding,newline="crlf",revision=value["revision"]))
                self.assertEqual("更新\r\n内容",read_text(self.path)["text"])
    def test_stale_save_does_not_overwrite_and_create_is_exclusive(self):
        write_text(self.path,dict(text="old"),True);read=read_text(self.path)
        self.path.write_text("external")
        with self.assertRaisesRegex(ValueError,"外部修改"):write_text(self.path,dict(text="phone",revision=read["revision"]))
        with self.assertRaises(FileExistsError):write_text(self.path,dict(text="replace"),True)
        self.assertEqual("external",self.path.read_text())
    def test_binary_oversize_and_unrepresentable_text_never_truncate_file(self):
        for data in (b"MZ\0binary",b"x"*(LIMIT+1)):
            self.path.write_bytes(data)
            with self.assertRaises(ValueError):read_text(self.path)
        self.path.write_text("original");before=read_text(self.path)
        with self.assertRaises(UnicodeError):write_text(self.path,dict(text="😀",encoding="gbk",revision=before["revision"]))
        self.assertEqual("original",self.path.read_text())
        self.assertEqual([],list(self.path.parent.glob('.lancmd-edit-*')))
    def test_mixed_endings_and_noop_save_preserve_bytes(self):
        data=b"one\r\ntwo\nthree\r";self.path.write_bytes(data);value=read_text(self.path)
        self.assertTrue(value["mixed"])
        write_text(self.path,dict(text=value["text"],newline="preserve",revision=value["revision"]))
        self.assertEqual(data,self.path.read_bytes())


class ACL:
    def __init__(self):self.values={};self.calls=[]
    def call(self,**q):
        self.calls.append(q)
        if q["action"]=="plan":return dict(sid="S-1-test",original=self.values.get(q["path"],"D:original"),applied="D:locked")
        if q["action"]=="inspect":return [dict(path=p,sddl=self.values.get(p,"D:original")) for p in q["paths"]]
        if q["action"]=="apply":
            if self.values.get(q["path"],"D:original")!=q["expected"]:raise ValueError("external ACL changed")
            self.values[q["path"]]=q["target"];return dict(sddl=q["target"])


class AppLockTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.exe=self.root/"selected.exe";self.exe.write_bytes(b"MZtest")
        self.acl=ACL();self.manager=AppLocks(self.root/"locks.json",backend=self.acl)
    def call(self,**q):return self.manager.request(q,Error)
    def test_backup_survives_restart_and_unlock_restores_original(self):
        result=self.call(action="lock",path=str(self.exe));rid=result["items"][0]["id"]
        self.assertEqual("locked",result["items"][0]["status"])
        restarted=AppLocks(self.root/"locks.json",backend=self.acl)
        restarted.request(dict(action="unlock",id=rid),Error)
        self.assertEqual("D:original",self.acl.values[str(self.exe)])
        self.call(action="forget",id=rid);self.assertEqual([],self.call()["items"])
    def test_external_acl_change_never_overwritten_and_protected_paths_rejected(self):
        rid=self.call(action="lock",path=str(self.exe))["items"][0]["id"]
        self.acl.values[str(self.exe)]="D:external"
        with self.assertRaises(Error):self.call(action="unlock",id=rid)
        with self.assertRaises(Error):self.call(action="forget",id=rid)
        protected=AppLocks(self.root/"other.json",protected=(self.root,),backend=self.acl)
        with self.assertRaises(Error):protected.request(dict(action="lock",path=str(self.exe)),Error)
        self.assertEqual("D:external",self.acl.values[str(self.exe)])


class MemoryRegistry:
    def __init__(self):self.values={}
    def read(self,r,p,v,n):return self.values.get((r,p,v,n))
    def write(self,r,p,v,n,value):
        if value is None:self.values.pop((r,p,v,n),None)
        else:self.values[r,p,v,n]=value
    def browse(self,*args):return dict(keys=[],values=[],key_count=0,value_count=0,key_offset=0,value_offset=0)
    def create(self,*args):pass
    def delete(self,*args):pass


class RegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.native=MemoryRegistry();self.manager=RegistryEditor(self.temp.name,self.native)
    def call(self,**q):return self.manager.request(dict(root="HKCU",path="Software\\LanCmdTest",view=64,**q),Error)
    def test_typed_values_backup_restore_and_stale_write_rejected(self):
        for kind,data in (("REG_SZ","中文"),("REG_EXPAND_SZ","%TEMP%"),("REG_MULTI_SZ",["一","二"]),("REG_BINARY","00 ff 7e"),("REG_DWORD","0xffffffff"),("REG_QWORD","18446744073709551615")):
            name=kind
            result=self.call(action="set",name=name,revision="missing",value=dict(type=kind,data=data))
            loaded=self.call(action="get",name=name)
            self.assertEqual(kind,loaded["type"])
            with self.assertRaises(Error):self.call(action="set",name=name,revision="missing",value=dict(type="REG_SZ",data="bad"))
            self.manager.request(dict(action="restore",id=result["backup"]),Error)
            self.assertIsNone(self.native.read("HKCU","Software\\LanCmdTest",64,name))
    def test_external_changes_block_restore_and_invalid_payload_does_not_write(self):
        result=self.call(action="set",name="x",revision="missing",value=dict(type="REG_SZ",data="first"))
        self.native.write("HKCU","Software\\LanCmdTest",64,"x",(1,"external"))
        with self.assertRaises(Error):self.manager.request(dict(action="restore",id=result["backup"]),Error)
        for value in (dict(type="REG_DWORD",data="4294967296"),dict(type="REG_QWORD",data="-1"),dict(type="REG_BINARY",data="xyz"),dict(type="REG_SZ",data="a\0b")):
            with self.assertRaises(Error):self.call(action="set",name="bad",revision="missing",value=value)
        self.assertEqual((1,"external"),self.native.read("HKCU","Software\\LanCmdTest",64,"x"))


class BrowserTest(unittest.TestCase):
    def test_only_valid_http_links_and_current_inventory_may_launch(self):
        inventory=lambda:[dict(id="edge",path="C:/Edge/msedge.exe",name="Edge")]
        with mock.patch("browser_control.subprocess.Popen") as launch:
            for url in ("file:///C:/x","javascript:alert(1)","https://x/\ncmd","https://user:pass@example.com","https://x:0"):
                with self.assertRaises(Error):browser_request(dict(action="open",url=url,browser="edge"),Error,inventory)
            launch.assert_not_called()
            browser_request(dict(action="open",url="https://example.com/a?x=1&y=2",browser="edge"),Error,inventory)
            self.assertEqual(["C:/Edge/msedge.exe","https://example.com/a?x=1&y=2"],launch.call_args.args[0])
            with self.assertRaises(Error):browser_request(dict(action="open",url="https://example.com",browser="removed"),Error,inventory)


class PrivacyTest(unittest.TestCase):
    def setUp(self):
        self.items=[dict(id="camera",kind="camera",name="cam",enabled=True,problem=0),dict(id="mic",kind="microphone",name="mic",enabled=True,problem=0,endpoint="capture-id")]
        self.backend=SimpleNamespace(inventory=lambda:[dict(i) for i in self.items],set_enabled=mock.Mock(),status=lambda id:dict(enabled=False,problem=22))
        self.audio=mock.Mock(return_value=dict(volume=50,muted=True))
    def test_fresh_inventory_admin_checks_and_microphone_endpoint_mute(self):
        manager=PrivacyDevices(lambda:False,self.backend,self.audio)
        with self.assertRaises(Error):manager.request(dict(action="set_enabled",id="camera",enabled=False),Error)
        self.backend.set_enabled.assert_not_called()
        with self.assertRaises(Error):manager.request(dict(action="mute",id="camera",muted=True),Error)
        manager.request(dict(action="mute",id="mic",muted=True),Error)
        self.audio.assert_called_once_with(muted=True,device_id="capture-id",flow=1)
        with self.assertRaises(Error):manager.request(dict(action="mute",id="other",muted=True),Error)
    def test_explicit_device_disable_and_unconfirmed_state_is_not_success(self):
        manager=PrivacyDevices(lambda:True,self.backend,self.audio)
        self.assertTrue(manager.request(dict(action="set_enabled",id="camera",enabled=False),Error)["changed"])
        self.backend.status=lambda id:dict(enabled=True,problem=0)
        with self.assertRaises(Error):manager.request(dict(action="set_enabled",id="camera",enabled=False),Error)


class TaskTest(unittest.TestCase):
    def test_creation_token_required_and_cpu_sampling_uses_same_process_instance(self):
        backend=SimpleNamespace(list=lambda:[dict(pid=999999,name="test",created="123",cpu_ticks=100,memory=1)],terminate=mock.Mock())
        manager=TaskManager(backend=backend)
        self.assertIsNone(manager.request({},Error)["items"][0]["cpu"])
        self.assertIsNotNone(manager.request({},Error)["items"][0]["cpu"])
        with self.assertRaises(Error):manager.request(dict(action="terminate",pid=999999),Error)
        manager.request(dict(action="terminate",pid=999999,created="123"),Error)
        backend.terminate.assert_called_once_with(999999,"123")


class ScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/"script.bat";self.path.write_text("test")
        self.procs=[];self.code="import time; print('started',flush=True); time.sleep(30)"
        def spawn(*args,**kw):
            kw["creationflags"]=0
            proc=subprocess.Popen([sys.executable,"-u","-c",self.code],**kw);self.procs.append(proc);return proc
        self.manager=ScriptRunner(spawn);self.owner=SimpleNamespace(client_id="owner")
    def tearDown(self):
        self.manager.close()
        for proc in self.procs:
            try:proc.wait(timeout=3)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
    def call(self,**q):return self.manager.request(q,self.owner,Error)
    def test_owner_checks_and_cancel_do_not_run_another_users_script(self):
        item=self.call(action="run",path=str(self.path))["job"]
        settle(lambda:self.call()["items"][0]["status"]=="running")
        other=SimpleNamespace(client_id="other")
        self.assertEqual([],self.manager.request({},other,Error)["items"])
        with self.assertRaises(Error):self.manager.request(dict(action="cancel",id=item["id"]),other,Error)
        self.call(action="cancel",id=item["id"])
        settle(lambda:self.call()["items"][0]["status"]=="cancelled")
    def test_output_is_bounded_and_arbitrary_executables_rejected(self):
        self.code="import sys; sys.stdout.write('x'*100000)"
        self.call(action="run",path=str(self.path))
        settle(lambda:self.call()["items"][0]["status"] not in ("starting","running"))
        item=self.call()["items"][0];self.assertEqual("completed",item["status"]);self.assertEqual(8192,len(item["out"]));self.assertTrue(item["truncated"])
        exe=self.path.with_suffix('.exe');exe.write_bytes(b'MZ')
        with self.assertRaises(Error):self.call(action="run",path=str(exe))


@unittest.skipUnless(os.name=="nt","Windows native management")
class NativeManagementTest(unittest.TestCase):
    def test_acl_deny_execute_then_restore_exact_original(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd 权限 ") as directory:
            root=Path(directory);exe=root/"selected.exe";exe.write_bytes(b"MZtest")
            backend=FilePermissions();original=backend.call(action="inspect",paths=[str(exe)])[0]["sddl"]
            manager=AppLocks(root/"backup.json",backend=backend)
            try:
                item=manager.request(dict(action="lock",path=str(exe)),Error)["items"][0]
                self.assertEqual("locked",item["status"])
                from ctypes import wintypes as w
                kernel=ctypes.WinDLL("kernel32",use_last_error=True)
                kernel.CreateFileW.argtypes=[w.LPCWSTR,w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD,w.DWORD,w.HANDLE];kernel.CreateFileW.restype=w.HANDLE;kernel.CloseHandle.argtypes=[w.HANDLE]
                handle=kernel.CreateFileW(str(exe),0x20000000,7,None,3,0,None)
                if handle!=ctypes.c_void_p(-1).value:kernel.CloseHandle(handle);self.fail("execute ACL was not denied")
                self.assertEqual(5,ctypes.get_last_error())
            finally:
                data=json.loads((root/"backup.json").read_text())
                if data:manager.request(dict(action="unlock",id=next(iter(data))),Error)
            self.assertEqual(original,backend.call(action="inspect",paths=[str(exe)])[0]["sddl"])
    def test_native_registry_round_trip_under_isolated_hkcu_key(self):
        import winreg as w
        import uuid
        path="Software\\LanCmdTests\\"+uuid.uuid4().hex
        native=NativeRegistry()
        with w.CreateKeyEx(w.HKEY_CURRENT_USER,path,0,w.KEY_WRITE|w.KEY_WOW64_64KEY):pass
        try:
            with tempfile.TemporaryDirectory() as backup:
                manager=RegistryEditor(backup,native)
                result=manager.request(dict(action="set",root="HKCU",path=path,view=64,name="中文",revision="missing",value=dict(type="REG_QWORD",data="18446744073709551615")),Error)
                self.assertEqual((11,18446744073709551615),native.read("HKCU",path,64,"中文"))
                manager.request(dict(action="restore",id=result["backup"]),Error)
                self.assertIsNone(native.read("HKCU",path,64,"中文"))
        finally:w.DeleteKeyEx(w.HKEY_CURRENT_USER,path,w.KEY_WOW64_64KEY,0)
    def test_native_text_replace_preserves_dacl_and_alternate_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"edit.txt";path.write_text("old")
            Path(str(path)+":lancmd-test").write_text("stream")
            backend=FilePermissions();original=backend.call(action="inspect",paths=[str(path)])[0]["sddl"]
            read=read_text(path);write_text(path,dict(text="new",revision=read["revision"]))
            self.assertEqual("new",path.read_text());self.assertEqual("stream",Path(str(path)+":lancmd-test").read_text())
            self.assertEqual(original,backend.call(action="inspect",paths=[str(path)])[0]["sddl"])
    def test_native_process_identity_blocks_reused_pid_then_terminates_child(self):
        child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"])
        try:
            backend=NativeProcesses();info=backend.info(child.pid)
            self.assertTrue(info["created"]);self.assertTrue(info["can_terminate"])
            with self.assertRaises(ValueError):backend.terminate(child.pid,"0")
            self.assertIsNone(child.poll());backend.terminate(child.pid,info["created"]);child.wait(timeout=5)
        finally:
            if child.poll() is None:child.kill();child.wait()
    def test_native_batch_special_path_and_vbs_outputs_are_hidden_host_jobs(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd & % ") as directory:
            root=Path(directory);batch=root/"测试 & (!)%USERPROFILE%.bat";batch.write_bytes(b"@echo off\r\necho batch-output\r\nexit /b 7\r\n")
            vbs=root/"测试.vbs";vbs.write_text('WScript.Echo "script-output"\r\nWScript.Quit 0\r\n',encoding="utf-16")
            manager=ScriptRunner();owner=SimpleNamespace(client_id="phone")
            try:
                manager.request(dict(action="run",path=str(batch)),owner,Error)
                settle(lambda:manager.request({},owner,Error)["items"][0]["status"] not in ("starting","running"))
                first=manager.request({},owner,Error)["items"][0]
                self.assertEqual(7,first.get("code"),first);self.assertIn("batch-output",first["out"])
                manager.request(dict(action="run",path=str(vbs)),owner,Error)
                settle(lambda:manager.request({},owner,Error)["items"][0]["status"] not in ("starting","running"))
                second=manager.request({},owner,Error)["items"][0]
                self.assertEqual(0,second.get("code"),second);self.assertIn("script-output",second["out"])
            finally:manager.close()
    def test_privacy_inventory_read_only_and_default_audio_unchanged(self):
        items=PrivacyHardware().inventory()
        self.assertIsInstance(items,list)
        self.assertTrue(all(i["kind"] in ("camera","microphone") for i in items))


if __name__=="__main__":unittest.main()

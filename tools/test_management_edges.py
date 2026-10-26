"""Regression coverage for management races, native limits and launch validation."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
from browser_control import browser_request
from registry_editor import NativeRegistry,RegistryEditor,unpack,version
from script_runner import ScriptRunner,attach_process_job,script_command,check_vbscript
from task_manager import TaskManager


class Error(Exception):
    pass


class ScriptEdgeTest(unittest.TestCase):
    def test_quick_exit_job_assignment_failure_preserves_result(self):
        finished=SimpleNamespace(poll=lambda:7)
        with mock.patch("script_runner.ProcessJob",side_effect=OSError("process exited")):
            self.assertIsNone(attach_process_job(finished))
            with self.assertRaises(OSError):attach_process_job(SimpleNamespace(poll=lambda:None))

    def test_vbs_console_host_is_noninteractive_and_selects_engine(self):
        command,env=script_command(Path("script.vbs"))
        self.assertEqual("cscript.exe",Path(command[0]).name.lower())
        self.assertIn("//B",command)
        self.assertNotIn("//I",command)
        self.assertIn("//E:VBScript",command)
        self.assertIn("//U",command)
        self.assertIn("//T:300",command)

    def test_vbs_probe_rejects_silent_zero_exit_and_does_not_modify_policy(self):
        def silent(command,**kwargs):
            self.assertIn("//E:VBScript",command)
            self.assertIn("//B",command)
            self.assertIn("//T:5",command)
            self.assertEqual(10,kwargs["timeout"])
            self.assertIn(b"WScript.Quit 37",Path(command[-1]).read_bytes())
            return SimpleNamespace(returncode=0,stdout=b"",stderr=b"")
        with self.assertRaisesRegex(ValueError,"用户脚本未启动"):
            check_vbscript(run=silent)
        for code,output in ((37,b""),(0,"LanCmd-VBScript-check".encode("utf-16-le"))):
            with self.assertRaises(ValueError):
                check_vbscript(run=lambda *a,**kw:SimpleNamespace(returncode=code,stdout=output,stderr=b""))
        check_vbscript(run=lambda *a,**kw:SimpleNamespace(returncode=37,stdout="LanCmd-VBScript-check\r\n".encode("utf-16-le"),stderr=b""))

    def test_unavailable_vbs_host_never_starts_user_script_or_claims_completion(self):
        launch=mock.Mock();manager=ScriptRunner(launch)
        job={"cancel":threading.Event(),"data":{"status":"starting","out":"","err":""}}
        with mock.patch("script_runner.check_vbscript",side_effect=ValueError("VBScript 运行组件未通过执行检查；用户脚本未启动")):
            manager._run(job,Path("user.vbs"))
        launch.assert_not_called()
        self.assertEqual("failed",job["data"]["status"])
        self.assertIn("用户脚本未启动",job["data"]["err"])
        self.assertNotIn("code",job["data"])
        self.assertIn("finished",job["data"])
        manager.close()

    def test_cancelled_processes_still_finishing_consume_running_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"test.bat";path.write_text("echo test")
            launch=mock.Mock(side_effect=AssertionError("should not spawn"))
            manager=ScriptRunner(launch)
            for index in range(4):
                manager.jobs[str(index)]={"owner":"owner","cancel":threading.Event(),"data":{"status":"cancelled"}}
            with self.assertRaisesRegex(Error,"同时运行"):
                manager.request(dict(action="run",path=str(path)),SimpleNamespace(client_id="owner"),Error)
            launch.assert_not_called()
            manager.close()


class ProcessEdgeTest(unittest.TestCase):
    def test_pid_must_fit_windows_dword_before_native_call(self):
        native=SimpleNamespace(info=mock.Mock(),terminate=mock.Mock())
        manager=TaskManager(backend=native)
        for pid in (False,0,-1,2**32,2**32+123):
            with self.subTest(pid=pid):
                with self.assertRaisesRegex(Error,"编号无效"):
                    manager.request(dict(action="terminate",pid=pid,created="123"),Error)
        native.info.assert_not_called();native.terminate.assert_not_called()


class RegistryMemory:
    def __init__(self,value=None):self.value=value;self.writes=[]
    def read(self,*args):return self.value
    def write(self,*args):self.value=args[-1];self.writes.append(args[-1])


class RegistryEdgeTest(unittest.TestCase):
    def test_multi_string_rejects_empty_elements_that_windows_would_truncate(self):
        self.assertEqual((7,[]),unpack(dict(type="REG_MULTI_SZ",data=[])))
        self.assertEqual((7,["first","second"]),unpack(dict(type="REG_MULTI_SZ",data=["first","second"])))
        for data in ([""],["first","","second"]):
            with self.assertRaisesRegex(ValueError,"空项"):unpack(dict(type="REG_MULTI_SZ",data=data))

    def test_value_bounds_follow_utf16_storage_not_json_escape_length(self):
        self.assertEqual((1,"中"*32767),unpack(dict(type="REG_SZ",data="中"*32767)))
        self.assertEqual((3,b"\xff"*65536),unpack(dict(type="REG_BINARY",data="ff"*65536)))
        with self.assertRaisesRegex(ValueError,"过大"):unpack(dict(type="REG_SZ",data="x"*32768))
        with self.assertRaisesRegex(ValueError,"过大"):unpack(dict(type="REG_BINARY",data="ff"*65537))

    def test_restore_rechecks_external_change_while_journal_reaches_disk(self):
        with tempfile.TemporaryDirectory() as directory:
            native=RegistryMemory();manager=RegistryEditor(directory,native)
            result=manager.request(dict(action="set",root="HKCU",path="Software\\Test",name="value",revision="missing",value=dict(type="REG_SZ",data="after")),Error)
            journal=manager._journal
            def external_change(*args):
                record=journal(*args);native.value=(1,"external");return record
            with mock.patch.object(manager,"_journal",side_effect=external_change):
                with self.assertRaisesRegex(Error,"备份期间"):
                    manager.request(dict(action="restore",backup_id=result["backup"]),Error)
            self.assertEqual((1,"external"),native.value)
            self.assertEqual([(1,"after")],native.writes)

    def test_oversized_prior_value_cannot_be_modified_without_restorable_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            native=RegistryMemory((1,"x"*32768));manager=RegistryEditor(directory,native)
            with self.assertRaisesRegex(Error,"过大"):
                manager.request(dict(action="delete_value",root="HKCU",path="Software\\Test",name="value",revision=version(native.value)),Error)
            self.assertFalse(native.writes);self.assertEqual([],list(Path(directory).glob("*.json")))

    def test_subkey_create_explicitly_keeps_selected_32bit_view(self):
        class Key:
            def __enter__(self):return self
            def __exit__(self,*args):pass
        class Winreg:
            KEY_READ=0x20019;KEY_WRITE=0x20006;KEY_SET_VALUE=2;KEY_CREATE_SUB_KEY=4;KEY_ENUMERATE_SUB_KEYS=8
            KEY_WOW64_32KEY=0x200;KEY_WOW64_64KEY=0x100
            def __init__(self):self.opens=[];self.creates=[]
            def OpenKey(self,parent,path,reserved,access):
                self.opens.append(access)
                if isinstance(parent,Key):raise FileNotFoundError()
                return Key()
            def CreateKeyEx(self,parent,path,reserved,access):self.creates.append(access);return Key()
        registry=NativeRegistry.__new__(NativeRegistry);registry.w=Winreg();registry.roots={"HKLM":"HKLM"}
        registry.create("HKLM","Software",32,"Child")
        self.assertTrue(all(access&0x200 and not access&0x100 for access in registry.w.opens+registry.w.creates))


class BrowserEdgeTest(unittest.TestCase):
    def test_ambiguous_urls_never_reach_a_browser(self):
        inventory=lambda:[dict(id="edge",path="C:/Edge/msedge.exe")]
        with mock.patch("browser_control.subprocess.Popen") as launch:
            for url in ("https://@example.com","https://:@example.com",'https://example.com/"x"',"https://example.com\\path","https://example.com/\x7f","https://example.com/\u00a0"):
                with self.subTest(url=url),self.assertRaises(Error):
                    browser_request(dict(action="open",url=url,browser="edge"),Error,inventory)
            launch.assert_not_called()
            browser_request(dict(action="open",url="https://example.com/%22x%22?x=1&y=2",browser="edge"),Error,inventory)
            self.assertEqual(["C:/Edge/msedge.exe","https://example.com/%22x%22?x=1&y=2"],launch.call_args.args[0])


if __name__=="__main__":unittest.main()

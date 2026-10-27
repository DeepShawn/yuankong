"""Delayed file ownership, cancellation, revision conflicts, and Windows defaults."""
import base64
import ctypes
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from file_schedule import FileSchedule
from file_actions import file_request
from file_jobs import file_token
import lan_command_agent as agent
from remote_features import WindowsBackend
from privacy_devices import PrivacyHardware


class Error(Exception):pass


class FileScheduleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.owner=SimpleNamespace(client_id="phone")
        self.manager=FileSchedule(lambda cmd,q,st: file_request(q,Error))
        self.addCleanup(self.manager.close)
    def tearDown(self):
        self.manager.close()
        for job in self.manager.jobs.values():self.assertTrue(job["done"].wait(3))
    def add(self,delay=0,**params):
        return self.manager.request(dict(action="add",command="files",params=params,delay=delay),self.owner,Error)["job"]
    def get(self,job):return self.manager.request(dict(action="get",job_id=job["id"]),self.owner,Error)["job"]
    def test_delayed_write_uses_original_owner_and_completes_without_phone_state(self):
        path=self.root/"new.txt"
        job=self.add(1,action="text_create",path=str(path),text="delayed",encoding="utf-8",newline="lf")
        self.assertFalse(path.exists());self.assertEqual("waiting",self.get(job)["status"])
        self.owner=SimpleNamespace(client_id="different phone")
        with self.assertRaises(Error):self.get(job)
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(3))
        self.assertEqual("delayed",path.read_text())
        self.owner=SimpleNamespace(client_id="phone");self.assertEqual("completed",self.get(job)["status"])
    def test_cancel_waiting_plan_does_not_modify_file(self):
        path=self.root/"new.txt";job=self.add(86400,action="text_create",path=str(path),text="never",encoding="utf-8")
        self.manager.request(dict(action="cancel",job_id=job["id"]),self.owner,Error)
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(3));self.assertEqual("cancelled",self.get(job)["status"])
        self.assertFalse(path.exists())
    def test_disallowed_actions_bad_delays_and_recursive_scheduling_are_rejected(self):
        for cmd,delay,params in [("exec",0,{}),("file_schedule",0,{}),("files",-1,{}),("files",86401,{}),("files",True,{}),("file_jobs",0,{"action":"cancel"})]:
            with self.subTest(cmd=cmd,delay=delay),self.assertRaises(Error):
                self.manager.request(dict(action="add",command=cmd,params=params,delay=delay),self.owner,Error)
    def test_modified_time_preserves_access_time_and_rejects_stale_selection(self):
        path=self.root/"document.docx";path.write_bytes(b"document")
        original=path.stat();token=file_token(original)
        result=file_request(dict(action="mtime",path=str(path),file_id=token,modified=1609459200),Error)
        self.assertEqual(1609459200,path.stat().st_mtime)
        self.assertEqual(original.st_atime_ns,path.stat().st_atime_ns)
        with self.assertRaises(Error):file_request(dict(action="mtime",path=str(path),file_id=token,modified=1700000000),Error)
        for value in (-1,float('nan'),float('inf'),True,4102444801):
            with self.assertRaises(Error):file_request(dict(action="mtime",path=str(path),modified=value),Error)
    def test_usb_disconnect_cancels_waiting_plan_before_it_can_access_volume(self):
        path=self.root/"new.txt";job=self.add(86400,action="text_create",path=str(path),text="never",encoding="utf-8")
        self.manager.stop_for_device({"paths":[str(self.root)]})
        self.assertEqual("cancelled",self.get(job)["status"]);self.assertFalse(path.exists())
    def test_failed_operation_is_reported_and_list_does_not_include_preview_payload(self):
        path=self.root/"missing";job=self.add(action="preview",path=str(path))
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(3));self.assertEqual("failed",self.get(job)["status"])
        self.assertIn("error",self.get(job));self.assertNotIn("params",self.get(job))
    def test_revision_upload_never_overwrites_file_changed_during_transfer(self):
        path=self.root/"edited.docx";path.write_bytes(b"original")
        state=agent.ClientState(None,("127.0.0.1",1));self.addCleanup(state.close_transfers)
        sid=agent.cmd_upload(dict(path=str(path),size=6,file_id=file_token(path.stat())),state)["sid"]
        agent.cmd_upload(dict(action="chunk",sid=sid,data=base64.b64encode(b"edited").decode()),state)
        path.write_bytes(b"new version on computer")
        with self.assertRaisesRegex(agent.RpcError,"回传期间被修改"):agent.cmd_upload(dict(action="end",sid=sid),state)
        self.assertEqual(b"new version on computer",path.read_bytes());self.assertEqual([],list(self.root.glob("*.lancmd-part")))
        token=file_token(path.stat())
        sid=agent.cmd_upload(dict(path=str(path),size=6,file_id=token),state)["sid"]
        agent.cmd_upload(dict(action="chunk",sid=sid,data=base64.b64encode(b"edited").decode()),state)
        result=agent.cmd_upload(dict(action="end",sid=sid),state)
        self.assertEqual("edited",path.read_text());self.assertEqual(file_token(path.stat()),result["file_id"])


@unittest.skipUnless(os.name=="nt","Windows native APIs")
class NativeDefaultsTest(unittest.TestCase):
    def test_cmd_default_directory_is_system32_without_new_console(self):
        result=agent.run_exec("cd",timeout=10)
        expected=os.path.join(os.environ["SystemRoot"],"System32")
        self.assertEqual(0,result["code"]);self.assertEqual(expected.casefold(),result["out"].strip().casefold())
    def test_message_title_has_no_application_prefix(self):
        event=threading.Event();seen=[]
        def display(hwnd,text,title,flags):seen.append((title,text));event.set();return 1
        with mock.patch.object(ctypes.windll.user32,"MessageBoxW",side_effect=display):
            WindowsBackend("").message("标题","内容");self.assertTrue(event.wait(3))
        self.assertEqual([("标题","内容")],seen)
    def test_background_document_uses_no_activation_and_only_repositions_its_new_window(self):
        from document_open import open_document
        with tempfile.TemporaryDirectory() as folder:
            document=Path(folder)/"report & 中文.docx";document.write_bytes(b"office")
            apps=mock.Mock();shell=mock.Mock();apps.user.GetForegroundWindow.side_effect=[10,20]
            apps.user.IsWindow.side_effect=[True,False]
            apps.kernel.GetProcessId.return_value=100
            apps.windows.side_effect=[[],[{"handle":"20","pid":100},{"handle":"30","pid":200}]]
            captured=[]
            def execute(pointer):
                info=pointer._obj;captured.append((info.lpFile,info.lpParameters,info.nShow,info.fMask));info.hProcess=42;return True
            shell.ShellExecuteExW.side_effect=execute
            def thread(**kwargs):
                result=mock.Mock();result.start.side_effect=kwargs["target"];return result
            with mock.patch("document_open.wps_executable",return_value=Path(folder)/"wps.exe"),mock.patch("desktop_apps.DesktopApps",return_value=apps),mock.patch("document_open.ctypes.WinDLL",return_value=shell),mock.patch("document_open.threading.Thread",side_effect=thread),mock.patch("document_open.time.sleep"):
                result=open_document(document,"wps",True)
            self.assertTrue(result["background_requested"])
            self.assertEqual(4,captured[0][2]);self.assertIn(str(document),captured[0][1])
            apps.user.SetWindowPos.assert_called_once_with(20,10,0,0,0,0,0x13)
            apps.user.SetForegroundWindow.assert_called_once_with(10)
            apps.kernel.CloseHandle.assert_called_once_with(42)
    def test_privacy_veto_is_distinguished_from_permission_denial(self):
        hardware=PrivacyHardware();hardware.node=mock.Mock(return_value=1)
        hardware.property_change=mock.Mock(side_effect=OSError("driver refused"))
        with mock.patch.object(hardware.cm,"CM_Disable_DevNode",return_value=0x17):
            with self.assertRaisesRegex(RuntimeError,"CR_REMOVE_VETOED"):hardware.set_enabled("test",False)
        with mock.patch.object(hardware.cm,"CM_Disable_DevNode",return_value=0x33):
            with self.assertRaisesRegex(RuntimeError,"权限不足"):hardware.set_enabled("test",False)


if __name__=="__main__":unittest.main()

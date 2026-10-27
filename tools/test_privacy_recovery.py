"""Selected-device property changes and capture-release ordering; no CI device mutations."""
import ctypes
import logging
import sys
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock
import uuid
from contextlib import contextmanager

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from privacy_devices import PrivacyHardware,PrivacyDevices
from remote_features import RemoteFeatures
from recordings import Recordings


class Error(Exception):pass


class PropertyChangeTest(unittest.TestCase):
    def hardware(self,device_class="ca3e7ab9-b4c3-4ae6-8251-579ef933890f",restart=False):
        hardware=PrivacyHardware.__new__(PrivacyHardware);api=mock.Mock()
        api.SetupDiCreateDeviceInfoList.return_value=7
        def open_info(handle,instance,parent,flags,pointer):
            value=uuid.UUID(device_class).bytes_le
            ctypes.memmove(ctypes.addressof(pointer._obj)+type(pointer._obj).ClassGuid.offset,value,16)
            return True
        api.SetupDiOpenDeviceInfoW.side_effect=open_info
        def get_params(handle,info,pointer):
            pointer._obj.Flags|=0x100 if restart else 0
            return True
        api.SetupDiGetDeviceInstallParamsW.side_effect=get_params
        return hardware,api
    def test_setupapi_changes_only_selected_camera_with_quiet_global_property_change(self):
        hardware,api=self.hardware();seen=[]
        def params(handle,info,pointer,size):
            p=pointer._obj;seen.append((p.ClassInstallHeader.InstallFunction,p.StateChange,p.Scope,p.HwProfile));return True
        api.SetupDiSetClassInstallParamsW.side_effect=params
        with mock.patch("privacy_devices.ctypes.WinDLL",return_value=api,create=True):
            result=hardware.property_change("USB\\SELECTED_CAMERA",False)
        self.assertEqual("setupapi",result["method"]);self.assertFalse(result["reboot_required"])
        self.assertEqual([(0x12,2,1,0)],seen)
        install=api.SetupDiSetDeviceInstallParamsW.call_args.args[2]._obj
        self.assertTrue(install.Flags&0x800000);self.assertFalse(install.hwndParent)
        api.SetupDiOpenDeviceInfoW.assert_called_once();self.assertEqual("USB\\SELECTED_CAMERA",api.SetupDiOpenDeviceInfoW.call_args.args[1])
        api.SetupDiDestroyDeviceInfoList.assert_called_once_with(7)
    def test_parent_controller_and_render_endpoint_are_never_changed(self):
        for guid,instance in [("36fc9e60-c465-11cf-8056-444553540000","USB\\HUB"),("c166523c-fe0c-4a94-a586-f1a80cfbbf3e",r"SWD\MMDEVAPI\{0.0.0.00000000}.{12345678-1234-1234-1234-123456789abc}")]:
            hardware,api=self.hardware(guid)
            with mock.patch("privacy_devices.ctypes.WinDLL",return_value=api,create=True),self.assertRaises(ValueError):hardware.property_change(instance,False)
            api.SetupDiCallClassInstaller.assert_not_called();api.SetupDiDestroyDeviceInfoList.assert_called_once_with(7)
    def test_restart_requirement_is_reported_without_restarting_windows(self):
        hardware,api=self.hardware(restart=True)
        with mock.patch("privacy_devices.ctypes.WinDLL",return_value=api,create=True):result=hardware.property_change("USB\\CAMERA",True)
        self.assertTrue(result["reboot_required"])
        change=api.SetupDiSetClassInstallParamsW.call_args.args[2]._obj
        self.assertEqual(1,change.StateChange)
    def test_veto_uses_property_change_but_access_denied_does_not_retry(self):
        hardware=PrivacyHardware.__new__(PrivacyHardware);hardware.node=mock.Mock(return_value=9)
        hardware.cm=mock.Mock();hardware.cm.CM_Disable_DevNode.return_value=0x17
        hardware.property_change=mock.Mock(return_value={"method":"setupapi"})
        self.assertEqual("setupapi",hardware.set_enabled("selected",False)["method"])
        hardware.property_change.assert_called_once_with("selected",False)
        hardware.property_change.reset_mock();hardware.cm.CM_Disable_DevNode.return_value=0x33
        with self.assertRaisesRegex(RuntimeError,"权限不足"):hardware.set_enabled("selected",False)
        hardware.property_change.assert_not_called()


class DeviceOrderingTest(unittest.TestCase):
    def manager(self,guard=None):
        backend=mock.Mock();backend.inventory.return_value=[dict(id="camera",kind="camera",name="Camera",enabled=True,problem=0)]
        backend.status.return_value=dict(enabled=False,problem=22);backend.set_enabled.return_value=dict(method="setupapi")
        return PrivacyDevices(lambda:True,backend=backend,device_guard=guard),backend
    def test_capture_guard_runs_before_hardware_and_status_is_verified_before_release(self):
        events=[]
        @contextmanager
        def guard(item):
            events.append("release captures");yield {"recordings":1};events.append("unblock")
        manager,backend=self.manager(guard)
        backend.set_enabled.side_effect=lambda *args:events.append("disable") or {"method":"setupapi"}
        backend.status.side_effect=lambda *args:events.append("verify") or dict(enabled=False,problem=22)
        result=manager.request(dict(action="set_enabled",device_id="camera",enabled=False),Error)
        self.assertEqual(["release captures","disable","verify","unblock"],events)
        self.assertTrue(result["changed"]);self.assertEqual(1,result["released"]["recordings"])
    def test_capture_release_failure_does_not_disable_device(self):
        @contextmanager
        def guard(item):raise ValueError("仍被本应用占用");yield
        manager,backend=self.manager(guard)
        with self.assertRaisesRegex(Error,"占用"):manager.request(dict(action="set_enabled",device_id="camera",enabled=False),Error)
        backend.set_enabled.assert_not_called()
    def test_pending_reboot_does_not_claim_device_has_been_disabled(self):
        manager,backend=self.manager();backend.set_enabled.return_value=dict(reboot_required=True)
        backend.status.return_value=dict(enabled=True,problem=0)
        with mock.patch("privacy_devices.time.monotonic",side_effect=[0,4]):
            result=manager.request(dict(action="set_enabled",device_id="camera",enabled=False),Error)
        self.assertFalse(result["changed"]);self.assertTrue(result["pending_reboot"]);self.assertTrue(result["enabled"])
    def test_camera_guard_waits_for_capture_and_rejects_old_lease(self):
        backend=mock.Mock();entered=threading.Event();release=threading.Event();disabled=threading.Event()
        def capture(*args):entered.set();release.wait(3);return b"jpeg"
        backend.capture.side_effect=capture
        remote=RemoteFeatures(Error,logging.getLogger("privacy-test"),"",backend=backend)
        state=SimpleNamespace(client_id="phone",peer="127.0.0.1");session=remote.session(dict(scope="camera"),state)
        errors=[]
        def frame():
            try:remote.frame(dict(sid=session["sid"]),state,"camera")
            except Exception as exc:errors.append(exc)
        worker=threading.Thread(target=frame);worker.start();self.assertTrue(entered.wait(1))
        def disable():
            with remote.camera_guard():disabled.set()
        changer=threading.Thread(target=disable);changer.start();self.assertFalse(disabled.wait(.02))
        release.set();worker.join(3);changer.join(3);self.assertTrue(disabled.is_set())
        with self.assertRaises(Error):remote.frame(dict(sid=session["sid"]),state,"camera")
    def test_recording_guard_blocks_new_capture_and_restores_admission_on_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            manager=Recordings(folder,"missing.exe",popen=mock.Mock())
            with manager.device_guard("camera"):
                with self.assertRaisesRegex(ValueError,"启停"):manager._start(dict(mode="camera",audio=False),"phone")
            self.assertNotIn("camera",manager.blocked_resources)
            process=mock.Mock();process.wait.side_effect=__import__('subprocess').TimeoutExpired("capture",18)
            manager.jobs={"active":dict(data=dict(mode="camera",status="recording"),process=process)};manager._stop=mock.Mock()
            with self.assertRaisesRegex(ValueError,"未停用"):
                with manager.device_guard("camera"):self.fail("must wait")
            self.assertNotIn("camera",manager.blocked_resources)


if __name__=="__main__":unittest.main()

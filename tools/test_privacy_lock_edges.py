import ctypes
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
from app_locks import AppLocks,_FileSecurityApi,same_dacl,snapshot_file_dacl,restore_file_dacl
from privacy_devices import PrivacyDevices,PrivacyHardware,capture_endpoint


class Error(Exception):
    pass


class Permissions:
    def __init__(self):self.current={};self.apply=[];self.on_plan=None;self.on_apply=None
    def call(self,**q):
        if q["action"]=="plan":
            if self.on_plan:self.on_plan()
            return dict(sid="S-1-test",original="D:original",applied="D:deny-execute")
        if q["action"]=="inspect":return [dict(path=p,sddl=self.current.get(p,"D:original")) for p in q["paths"]]
        if self.on_apply:self.on_apply(q)
        self.apply.append(q);self.current[q["path"]]=q["target"]
        return dict(sddl=q["target"])


class AppLockEdges(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.exe=self.root/"selected.exe";self.exe.write_bytes(b"selected")
        self.permissions=Permissions();self.manager=AppLocks(self.root/"app-locks.json",backend=self.permissions)
    def call(self,**q):return self.manager.request(q,Error)

    def test_file_replaced_while_planning_is_not_modified(self):
        replacement=self.root/"replacement.exe";replacement.write_bytes(b"replacement")
        self.permissions.on_plan=lambda:os.replace(replacement,self.exe)
        with self.assertRaisesRegex(Error,"替换|移动"):
            self.call(action="lock",path=str(self.exe))
        self.assertEqual([],self.permissions.apply)

    def test_recovery_journal_is_durable_before_acl_apply(self):
        def check(q):
            entries=json.loads(self.manager.path.read_text(encoding="utf-8"))
            self.assertEqual(1,len(entries));entry=next(iter(entries.values()))
            self.assertEqual("D:original",entry["original"])
            self.assertEqual(entry["identity"],q["identity"])
        self.permissions.on_apply=check
        self.call(action="lock",path=str(self.exe))

    def test_inspection_permission_error_cannot_discard_recovery_record(self):
        rid=self.call(action="lock",path=str(self.exe))["items"][0]["id"]
        original=Path.stat
        def info(path,*args,**kwargs):
            if path==self.exe:raise PermissionError("denied")
            return original(path,*args,**kwargs)
        with mock.patch.object(Path,"stat",autospec=True,side_effect=info):
            self.assertEqual("changed",self.call()["items"][0]["status"])
            with self.assertRaises(Error):self.call(action="forget",id=rid)
        self.assertIn(rid,json.loads(self.manager.path.read_text()))

    def test_moved_file_keeps_its_original_acl_in_archived_backup(self):
        rid=self.call(action="lock",path=str(self.exe))["items"][0]["id"]
        entry=json.loads(self.manager.path.read_text())[rid]
        self.exe.rename(self.root/"moved.exe")
        result=self.call(action="forget",id=rid)
        archived=json.loads(Path(result["archive"]).read_text(encoding="utf-8"))
        self.assertEqual(entry,archived["entry"])
        self.assertEqual([],self.call()["items"])

    def test_hardlink_alias_cannot_lock_a_protected_binary(self):
        protected=self.root/"agent.exe";protected.write_bytes(b"agent")
        alias=self.root/"alias.exe";os.link(protected,alias)
        manager=AppLocks(self.root/"other.json",protected=(protected,),backend=self.permissions)
        with self.assertRaisesRegex(Error,"硬链接"):
            manager.request(dict(action="lock",path=str(alias)),Error)
        self.assertEqual([],self.permissions.apply)


class PrivacyEdges(unittest.TestCase):
    def manager(self,item,**kwargs):
        self.backend=SimpleNamespace(inventory=lambda:[dict(item)],set_enabled=mock.Mock(),status=mock.Mock(return_value=dict(enabled=False,problem=22)))
        self.audio=mock.Mock(return_value=dict(muted=False,volume=50))
        return PrivacyDevices(lambda:True,self.backend,self.audio)

    def test_unknown_device_state_is_not_reported_as_already_disabled(self):
        manager=self.manager(dict(id="camera",kind="camera",enabled=False,problem=-1,error="read failure"))
        with self.assertRaisesRegex(Error,"状态异常|未知"):
            manager.request(dict(action="set_enabled",id="camera",enabled=False),Error)
        self.backend.set_enabled.assert_not_called()

    def test_missing_endpoint_never_falls_back_to_default_microphone(self):
        manager=self.manager(dict(id="mic",kind="microphone",enabled=True,problem=0,endpoint=None))
        listed=manager.request({},Error)["items"][0]
        self.assertIn("audio_error",listed)
        with self.assertRaisesRegex(Error,"端点编号"):
            manager.request(dict(action="mute",id="mic",muted=True),Error)
        self.audio.assert_not_called()

    def test_mute_result_must_match_requested_state(self):
        manager=self.manager(dict(id="mic",kind="microphone",enabled=True,problem=0,endpoint="capture-id"))
        with self.assertRaisesRegex(Error,"未确认"):
            manager.request(dict(action="mute",id="mic",muted=True),Error)
        self.audio.assert_called_once_with(muted=True,device_id="capture-id",flow=1)

    def test_native_inventory_excludes_render_and_malformed_capture_endpoints(self):
        guid="{12345678-1234-1234-1234-123456789abc}"
        capture="SWD\\MMDEVAPI\\{0.0.1.00000000}."+guid
        render="SWD\\MMDEVAPI\\{0.0.0.00000000}."+guid
        self.assertEqual("{0.0.1.00000000}."+guid,capture_endpoint(capture))
        self.assertIsNone(capture_endpoint(render));self.assertIsNone(capture_endpoint("SWD\\MMDEVAPI\\{0.0.1.fake}"))
        hardware=PrivacyHardware.__new__(PrivacyHardware)
        hardware.status=mock.Mock(return_value=dict(enabled=True,problem=0))
        raw=[dict(id=capture,kind="microphone",endpoint="wrong",name="mic"),dict(id=render,kind="microphone",name="speaker"),dict(id="controller",kind="speaker")]
        response=SimpleNamespace(returncode=0,stdout=json.dumps(raw).encode(),stderr=b"")
        with mock.patch("privacy_devices.subprocess.run",return_value=response):items=hardware.inventory()
        self.assertEqual([capture],[i["id"] for i in items]);self.assertEqual("{0.0.1.00000000}."+guid,items[0]["endpoint"])


class DaclComparison(unittest.TestCase):
    def test_only_auto_inherited_bookkeeping_is_ignored(self):
        base="D:(A;ID;FA;;;SY)(A;ID;FR;;;BA)"
        self.assertTrue(same_dacl(base,base.replace("D:","D:AI",1)))
        self.assertFalse(same_dacl(base,base.replace("D:","D:P",1)))
        self.assertFalse(same_dacl(base,base.replace("D:","D:AR",1)))
        self.assertFalse(same_dacl(base,base.replace("A;ID;FA","A;;FA",1)))
        self.assertFalse(same_dacl(base,base.replace("FA;;;SY","FR;;;SY",1)))
        self.assertFalse(same_dacl(base,"D:(A;ID;FR;;;BA)(A;ID;FA;;;SY)"))


@unittest.skipUnless(os.name=="nt","native file-handle sharing")
class NativeAclEdges(unittest.TestCase):
    def test_bound_acl_handle_prevents_file_replacement(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd 身份 ") as directory:
            root=Path(directory).resolve();path=root/"selected.exe";replacement=root/"replacement.exe"
            path.write_bytes(b"selected");replacement.write_bytes(b"replacement")
            info=path.stat();api=_FileSecurityApi()
            _,fd=api.open(path,True,[info.st_dev,info.st_ino])
            try:
                with self.assertRaises(PermissionError):os.replace(replacement,path)
                self.assertEqual(b"selected",path.read_bytes())
            finally:os.close(fd)
            os.replace(replacement,path);self.assertEqual(b"replacement",path.read_bytes())

    def test_restore_after_replace_keeps_only_original_inherited_aces(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd 继承 ") as directory:
            root=Path(directory).resolve();path=root/"document.txt";replacement=root/"replacement.tmp"
            path.write_text("original",encoding="utf-8");replacement.write_text("updated",encoding="utf-8")
            before=snapshot_file_dacl(path,require_write=True)
            from ctypes import wintypes as w
            kernel=ctypes.WinDLL("kernel32",use_last_error=True)
            kernel.ReplaceFileW.argtypes=[w.LPCWSTR,w.LPCWSTR,w.LPCWSTR,w.DWORD,ctypes.c_void_p,ctypes.c_void_p]
            kernel.ReplaceFileW.restype=w.BOOL
            if not kernel.ReplaceFileW(str(path),str(replacement),None,0,None,None):
                raise ctypes.WinError(ctypes.get_last_error())
            changed=snapshot_file_dacl(path)
            restore_file_dacl(path,before["sddl"],changed["identity"])
            after=snapshot_file_dacl(path)
            self.assertTrue(same_dacl(before["sddl"],after["sddl"]),(before,after))
            self.assertEqual("updated",path.read_text(encoding="utf-8"))

    def test_protected_acl_is_restored_without_inheriting_parent_entries(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd 保护 ") as directory:
            root=Path(directory).resolve();path=root/"selected.exe";path.write_bytes(b"selected")
            before=snapshot_file_dacl(path,require_write=True);api=_FileSecurityApi()
            sid=api.plan(path)["sid"]
            protected="D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;"+sid+")"
            descriptor=ctypes.c_void_p()
            if not api.advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(protected,1,ctypes.byref(descriptor),None):
                raise ctypes.WinError(ctypes.get_last_error())
            try:protected=api.sddl(descriptor)
            finally:api.kernel.LocalFree(descriptor)
            try:
                restore_file_dacl(path,protected,before["identity"])
                self.assertTrue(same_dacl(protected,snapshot_file_dacl(path)["sddl"]))
                manager=AppLocks(root/"app-locks.json")
                item=manager.request(dict(action="lock",path=str(path)),Error)["items"][0]
                self.assertEqual("locked",item["status"])
                manager.request(dict(action="unlock",id=item["id"]),Error)
                self.assertTrue(same_dacl(protected,snapshot_file_dacl(path)["sddl"]))
            finally:restore_file_dacl(path,before["sddl"],before["identity"])


if __name__=="__main__":unittest.main()

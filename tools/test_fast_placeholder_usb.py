"""Bounded reservations and selected USB recovery; no real device is disabled."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
import test_placeholder_files as fixtures
import test_device_management as device_fixtures
import test_privacy_recovery as property_fixtures
from placeholder_files import PlaceholderFiles,NativeAllocation,NativeBackend,FastAllocationUnsupported,FastAllocationCancelled,RESERVE_BYTES,ALLOCATION_MAX
from device_management import UsbHardware
from device_property_change import change_state,USB_DISK_CLASSES


class QuickAllocation(fixtures.Allocation):
    def reserve(self,size,progress,cancelled):
        if self.backend.entered:
            self.backend.entered.set();self.backend.release.wait(3)
        if self.backend.mode=="unsupported":
            self.stream.truncate(size//2);progress(size//2)
            raise FastAllocationUnsupported()
        if self.backend.mode=="failure":raise OSError("allocation failed")
        self.stream.truncate(size);progress(size)
        if cancelled():raise FastAllocationCancelled()
    def write(self,data):raise AssertionError("fast path must not stream payload")


class QuickBackend(fixtures.Backend):
    def __init__(self):super().__init__();self.mode="fast";self.creates=0
    def create(self,path):
        self.creates+=1
        return QuickAllocation(path,self) if self.creates==1 else fixtures.Allocation(path,self)


class FastStrategyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/"reserve.dat";self.backend=QuickBackend();self.manager=PlaceholderFiles(self.backend)
        self.owner=__import__('types').SimpleNamespace(client_id="phone");self.addCleanup(self.manager.close)
    def tearDown(self):
        self.manager.close()
        for job in self.manager.jobs.values():self.assertTrue(job["done"].wait(5))
    def start(self,size=4097):
        return self.manager.request(dict(action="create",path=str(self.path),size=size),self.owner,fixtures.Error)["job"]
    def wait(self,job):
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(5))
        return self.manager.request({},self.owner,fixtures.Error)["items"][0]
    def test_fast_path_completes_without_streaming_the_file(self):
        result=self.wait(self.start());self.assertEqual("completed",result["status"],result)
        self.assertEqual("preallocate",result["method"]);self.assertEqual(4097,result["written"])
        self.assertEqual(b"\0"*4097,self.path.read_bytes())
    def test_partial_unsupported_reservation_is_removed_before_full_stream_fallback(self):
        self.backend.mode="unsupported";result=self.wait(self.start())
        self.assertEqual("completed",result["status"],result);self.assertEqual("stream",result["method"])
        self.assertEqual(2,self.backend.creates);self.assertEqual(4097,self.path.stat().st_size)
        self.assertEqual(b"\0"*4097,self.path.read_bytes())
    def test_allocation_failure_never_falls_back_or_keeps_a_partial_file(self):
        self.backend.mode="failure";result=self.wait(self.start())
        self.assertEqual("failed",result["status"]);self.assertEqual(1,self.backend.creates);self.assertFalse(self.path.exists())
    def test_cancel_during_allocation_removes_the_uncommitted_file(self):
        self.backend.entered=threading.Event();self.backend.release=threading.Event();job=self.start()
        self.assertTrue(self.backend.entered.wait(1));self.manager.request(dict(action="cancel",job_id=job["id"]),self.owner,fixtures.Error)
        self.backend.release.set();result=self.wait(job)
        self.assertEqual("cancelled",result["status"]);self.assertFalse(self.path.exists())
    def test_native_strategy_uses_bounded_allocations_and_eof_without_valid_data_bypass(self):
        allocation=NativeAllocation.__new__(NativeAllocation);allocation.kernel=mock.Mock();allocation.handle=7
        current=[0];calls=[]
        def apply(handle,kind,value,size):
            amount=value._obj.value;calls.append((kind,amount))
            if kind==5:current[0]=amount
            return True
        allocation.kernel.SetFileInformationByHandle.side_effect=apply
        allocation.inspect=lambda:dict(allocated=current[0]);progress=[]
        allocation.reserve(500_000_000_000,progress.append)
        self.assertEqual((6,500_000_000_000),calls[-1])
        # Every EOF update is bounded too; never defer it to one huge final call.
        lengths=[amount for kind,amount in calls if kind==6]
        self.assertTrue(all(0<b-a<=ALLOCATION_MAX for a,b in zip([0]+lengths,lengths)))
        self.assertEqual(len(lengths),len([1 for kind,_ in calls if kind==5]))
        self.assertEqual(500_000_000_000,progress[-1]);allocation.kernel.SetFileValidData.assert_not_called()
    def test_native_reservation_can_cancel_between_segments(self):
        allocation=NativeAllocation.__new__(NativeAllocation);allocation.kernel=mock.Mock();allocation.handle=7
        allocation.inspect=lambda:dict(allocated=16*1024**3);progress=[]
        with self.assertRaises(FastAllocationCancelled):allocation.reserve(32*1024**3,progress.append,lambda:bool(progress))
        self.assertEqual(2,allocation.kernel.SetFileInformationByHandle.call_count)

    def test_slow_allocation_reduces_batches_and_fast_storage_grows_again(self):
        allocation=NativeAllocation.__new__(NativeAllocation);allocation.kernel=mock.Mock();allocation.handle=7
        targets=[]
        def apply(handle,kind,value,size):
            if kind==5:targets.append(value._obj.value)
            return True
        allocation.kernel.SetFileInformationByHandle.side_effect=apply
        allocation.inspect=lambda:dict(allocated=targets[-1])
        clock=[0.0];calls=[0]
        def tick():
            if calls[0]%2:clock[0]+=2 if calls[0]<4 else .001
            calls[0]+=1
            return clock[0]
        with mock.patch("placeholder_files.time.monotonic",side_effect=tick):allocation.reserve(64*1024**2)
        deltas=[b-a for a,b in zip([0]+targets,targets)]
        self.assertLess(deltas[1],deltas[0]);self.assertLess(deltas[2],deltas[1])
        self.assertGreater(deltas[3],deltas[2])

    def test_cancel_after_space_allocation_skips_potentially_slow_eof_update(self):
        allocation=NativeAllocation.__new__(NativeAllocation);allocation.kernel=mock.Mock();allocation.handle=7
        cancelled=[False]
        def apply(*args):cancelled[0]=True;return True
        allocation.kernel.SetFileInformationByHandle.side_effect=apply
        with self.assertRaises(FastAllocationCancelled):allocation.reserve(16*1024**2,cancelled=lambda:cancelled[0])
        self.assertEqual([5],[call.args[1] for call in allocation.kernel.SetFileInformationByHandle.call_args_list])


class UsbFallbackTest(unittest.TestCase):
    def setUp(self):
        self.fixture=device_fixtures.DeviceTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.backend=self.fixture.usb_backend();self.instance=self.fixture.items[0]["id"]
    def force(self):return self.fixture.request(action="force_disconnect",device_id=self.instance,confirm_data_loss=True)
    def restore(self):return self.fixture.request(action="restore_connection",device_id=self.instance)
    def test_veto_targets_only_usb_disk_class_in_current_profile(self):
        backend=UsbHardware.__new__(UsbHardware);backend._usb_node=mock.Mock(return_value=7);backend.cm=mock.Mock();backend.cm.CM_Disable_DevNode.return_value=0x17
        with mock.patch("device_management.change_state",return_value=dict(method="setupapi")) as change:
            self.assertEqual("setupapi",backend.set_enabled(self.instance,False)["method"])
        change.assert_called_once_with(self.instance,False,USB_DISK_CLASSES,scope=2)
        backend.cm.CM_Get_Parent.assert_not_called()
    def test_property_change_disk_class_and_scope_are_revalidated(self):
        _,api=property_fixtures.PropertyChangeTest().hardware("4d36e967-e325-11ce-bfc1-08002be10318")
        with mock.patch("device_property_change.ctypes.WinDLL",return_value=api,create=True):change_state(self.instance,False,USB_DISK_CLASSES,scope=2)
        params=api.SetupDiSetClassInstallParamsW.call_args.args[2]._obj
        self.assertEqual(2,params.Scope);self.assertEqual(2,params.StateChange)
        api.SetupDiOpenDeviceInfoW.assert_called_once();self.assertEqual(self.instance,api.SetupDiOpenDeviceInfoW.call_args.args[1])
    def test_pending_disable_is_recoverable_even_while_device_is_still_enabled(self):
        self.backend.set_enabled.side_effect=lambda *args:dict(method="setupapi",reboot_required=True)
        with mock.patch("device_management.time.monotonic",side_effect=[0,3]),self.assertRaisesRegex(Exception,"要求重启"):self.force()
        record=json.loads(self.fixture.manager.disconnect_path.read_text())[self.instance];self.assertTrue(record["config_restore"])
        self.backend.restore_config=mock.Mock(return_value=dict(reboot_required=False))
        self.restore();self.backend.restore_config.assert_called_once_with(self.instance)
        self.assertEqual({},json.loads(self.fixture.manager.disconnect_path.read_text()))
    def test_restore_reboot_requirement_retains_recovery_record(self):
        self.backend.set_enabled.side_effect=lambda *args:dict(method="setupapi",reboot_required=True)
        with mock.patch("device_management.time.monotonic",side_effect=[0,3]),self.assertRaises(Exception):self.force()
        self.backend.restore_config=mock.Mock(return_value=dict(reboot_required=True))
        result=self.restore();self.assertIn("要求重启",result["notice"])
        self.assertIn(self.instance,json.loads(self.fixture.manager.disconnect_path.read_text()))


@unittest.skipUnless(os.name=="nt","Windows allocation APIs")
class NativeQuickAllocationTest(unittest.TestCase):
    def test_real_preallocation_reserves_clusters_and_reads_zero_at_start_middle_and_end(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/"fast.dat";manager=PlaceholderFiles(NativeBackend())
            try:
                with mock.patch.object(NativeAllocation,"write",side_effect=AssertionError("must not stream")):
                    job=manager.request(dict(action="create",path=str(path),size=8*1024*1024+17),__import__('types').SimpleNamespace(client_id="phone"),fixtures.Error)["job"]
                    self.assertTrue(manager.jobs[job["id"]]["done"].wait(20))
                result=manager.jobs[job["id"]]["data"];self.assertEqual("completed",result["status"],result)
                self.assertEqual("preallocate",result["method"]);self.assertGreaterEqual(result["allocated"],result["size"])
                with path.open("rb") as stream:
                    for position in (0,result["size"]//2,result["size"]-64):stream.seek(position);self.assertEqual(b"\0"*64,stream.read(64))
                self.assertEqual(result["size"],path.stat().st_size);self.assertEqual(6,path.stat().st_file_attributes&6)
                self.assertFalse(path.stat().st_file_attributes&(0x200|0x800))
            finally:manager.close()
    def test_real_fallback_writes_and_cleans_the_first_reservation(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/"fallback.dat";manager=PlaceholderFiles(NativeBackend())
            try:
                with mock.patch.object(NativeAllocation,"reserve",side_effect=FastAllocationUnsupported()):
                    job=manager.request(dict(action="create",path=str(path),size=2_000_000),__import__('types').SimpleNamespace(client_id="phone"),fixtures.Error)["job"]
                    self.assertTrue(manager.jobs[job["id"]]["done"].wait(20))
                result=manager.jobs[job["id"]]["data"];self.assertEqual("completed",result["status"],result);self.assertEqual("stream",result["method"])
                self.assertEqual(2_000_000,path.stat().st_size)
            finally:manager.close()
    def test_process_exit_deletes_uncommitted_preallocated_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory).resolve()/"uncommitted.dat"
            source="import sys,time;from pathlib import Path;from placeholder_files import NativeAllocation;a=NativeAllocation(Path(sys.argv[1]));a.reserve(2000000);print('ready',flush=True);time.sleep(60)"
            child=subprocess.Popen([sys.executable,"-u","-c",source,str(path)],env=dict(os.environ,PYTHONPATH=str(ROOT/"windows")),stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=0x08000000)
            try:
                self.assertEqual(b"ready\n",child.stdout.readline().replace(b"\r\n",b"\n"));child.kill();child.wait(5);self.assertFalse(path.exists())
            finally:
                if child.poll() is None:child.kill();child.wait(5)
                child.stdout.close();child.stderr.close()


if __name__=="__main__":unittest.main()

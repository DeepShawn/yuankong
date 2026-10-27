"""Policy rollback and ejection checks never modify the CI host's device policy."""
import json
import os
from pathlib import Path
import sys
import tempfile
import ctypes
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
import device_management as dm
import lan_command_agent as agent


class MemoryRegistry:
    def __init__(self):
        self.values = {}
        self.writes = []
        self.fail_at = None

    def read(self, path, name, user=False):
        return self.values.get((path, name, user))

    def write(self, path, name, value):
        self.writes.append((path, name, value))
        if len(self.writes) == self.fail_at:
            raise PermissionError("registry access denied")
        if value is None:
            self.values.pop((path, name, False), None)
        else:
            self.values[path, name, False] = value


class DeviceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.registry = MemoryRegistry()
        self.admin = mock.Mock(return_value=True)
        self.items = [{"id": "USBSTOR\\DISK\\A", "name": "USB", "ejectable": True, "protected": False}]
        self.enumerate = mock.Mock(side_effect=lambda: list(self.items))
        self.eject = mock.Mock()
        self.manager = dm.DeviceManager(Path(self.temp.name) / "device-policy.json", self.admin,
                                        self.registry, self.enumerate, self.eject)

    def request(self, **params):
        with mock.patch.object(dm.os, "name", "nt"):
            return self.manager.request(params, agent.RpcError)

    def test_list_reports_privilege_and_actual_policy_without_writing(self):
        self.admin.return_value = False
        response = self.request()
        self.assertFalse(response["admin"])
        self.assertEqual(self.items, response["devices"])
        self.assertEqual("allow", response["policies"]["storage"]["mode"])
        self.assertEqual([], self.registry.writes)

    def test_lock_read_only_and_restore_survive_manager_restart(self):
        self.request(action="policy", scope="storage", mode="blocked")
        self.assertEqual("blocked", self.manager.policies()["storage"]["mode"])
        self.request(action="policy", scope="storage", mode="read_only")
        self.assertEqual("read_only", self.manager.policies()["storage"]["mode"])
        self.manager = dm.DeviceManager(self.manager.path, self.admin, self.registry)
        self.request(action="policy", scope="storage", mode="allow")
        self.assertEqual({}, self.registry.values)
        self.assertFalse(self.manager.policies()["storage"]["managed"])

    def test_restore_preserves_original_restrictions_instead_of_clearing_them(self):
        entry = self.manager._entries("storage")[0]
        self.registry.values[entry + (False,)] = 1
        self.request(action="policy", scope="storage", mode="blocked")
        self.request(action="policy", scope="storage", mode="allow")
        self.assertEqual({entry + (False,): 1}, self.registry.values)
        writes = len(self.registry.writes)
        self.request(action="policy", scope="storage", mode="allow")
        self.assertEqual(writes, len(self.registry.writes))

    def test_portable_policy_updates_both_classes_without_touching_storage(self):
        self.request(action="policy", scope="portable", mode="blocked")
        self.assertEqual(4, len(self.registry.values))
        self.assertEqual("allow", self.manager.policies()["storage"]["mode"])
        self.request(action="policy", scope="portable", mode="allow")
        self.assertEqual({}, self.registry.values)

    def test_registry_failure_rolls_back_and_removes_new_ownership(self):
        self.registry.fail_at = 2
        with self.assertRaises(agent.RpcError):
            self.request(action="policy", scope="portable", mode="blocked")
        self.assertEqual({}, self.registry.values)
        self.assertEqual({}, json.loads(self.manager.path.read_text()))

    def test_first_write_failure_does_not_leave_false_ownership(self):
        self.registry.fail_at = 1
        with self.assertRaises(agent.RpcError):
            self.request(action="policy", scope="storage", mode="blocked")
        self.assertEqual({}, json.loads(self.manager.path.read_text()))
        self.assertEqual(1, len(self.registry.writes))

    def test_state_save_failure_makes_no_registry_changes(self):
        with mock.patch.object(self.manager, "_save", side_effect=OSError("disk full")):
            with self.assertRaises(agent.RpcError):
                self.request(action="policy", scope="storage", mode="blocked")
        self.assertEqual([], self.registry.writes)

    def test_external_policy_change_is_not_overwritten(self):
        self.request(action="policy", scope="storage", mode="blocked")
        entry = self.manager._entries("storage")[0]
        self.registry.values[entry + (False,)] = 0
        writes = len(self.registry.writes)
        self.assertTrue(self.manager.policies()["storage"]["conflict"])
        with self.assertRaisesRegex(agent.RpcError, "其他程序"):
            self.request(action="policy", scope="storage", mode="allow")
        self.assertEqual(writes, len(self.registry.writes))

    def test_corrupted_recovery_record_does_not_clear_originals(self):
        self.manager.path.write_text('{"storage":{"original":[],"applied":[]}}')
        with self.assertRaisesRegex(agent.RpcError, "恢复记录损坏"):
            self.request(action="policy", scope="storage", mode="blocked")
        self.assertEqual([], self.registry.writes)

    def test_standard_user_cannot_change_policies_or_eject(self):
        self.admin.return_value = False
        for action in (dict(action="policy", scope="storage", mode="blocked"),
                       dict(action="eject", device_id=self.items[0]["id"])):
            with self.assertRaisesRegex(agent.RpcError, "管理员权限"):
                self.request(**action)
        self.assertEqual([], self.registry.writes)
        self.eject.assert_not_called()

    def test_invalid_parameters_never_mutate_devices(self):
        for params in (dict(action="disable"), dict(action="policy", scope="keyboard", mode="blocked"),
                       dict(action="policy", scope=[], mode="blocked"),
                       dict(action="policy", scope="portable", mode="read_only"),
                       dict(action="policy", scope="storage", mode="bad"),
                       dict(action="eject", device_id="x\x00y"), dict(action="eject", device_id=123)):
            with self.subTest(params=params), self.assertRaises(agent.RpcError):
                self.request(**params)
        self.assertEqual([], self.registry.writes)
        self.eject.assert_not_called()

    def test_eject_only_accepts_fresh_unprotected_inventory_entries(self):
        self.request(action="eject", device_id=self.items[0]["id"])
        self.eject.assert_called_once_with(self.items[0]["id"])
        self.eject.reset_mock()
        for change in ({"protected": True}, {"protected": False, "ejectable": False}):
            self.items[0].update(change)
            with self.assertRaises(agent.RpcError):
                self.request(action="eject", device_id=self.items[0]["id"])
        self.items.clear()
        with self.assertRaises(agent.RpcError):
            self.request(action="eject", device_id="USBSTOR\\DISK\\A")
        with self.assertRaises(agent.RpcError):
            self.request(action="eject", device_id="PCI\\SYSTEM")
        self.eject.assert_not_called()

    def test_ejection_veto_is_reported_without_forced_retry(self):
        self.eject.side_effect = RuntimeError("设备忙")
        with self.assertRaisesRegex(agent.RpcError, "设备忙"):
            self.request(action="eject", device_id=self.items[0]["id"])
        self.eject.assert_called_once()

    def test_user_and_global_policies_are_visible(self):
        entry = self.manager._entries("storage")[1]
        self.registry.values[entry + (True,)] = 1
        self.registry.values[dm.ROOT, "Deny_All", True] = 1
        response = self.request()
        self.assertEqual("read_only", response["policies"]["storage"]["mode"])
        self.assertTrue(response["policies"]["deny_all"])

    def test_rpc_and_capability_are_registered(self):
        with mock.patch.object(agent, "IS_WIN", True):
            self.assertIn("devices", agent.remote_capabilities()["features"])
        self.assertIn("devices", agent.HANDLERS)

    def usb_backend(self):
        self.items[0].update(kind="storage", paths=["E:\\"])
        self.usb_state = {"enabled": True, "problem": 0}
        backend = mock.Mock();backend.restore_config=None
        backend.status.side_effect = lambda instance: dict(self.usb_state)
        backend.set_enabled.side_effect = lambda instance, enabled: self.usb_state.update(enabled=enabled, problem=0 if enabled else 22)
        self.manager.connection = backend
        return backend

    def test_force_disconnect_requires_explicit_risk_confirmation_and_admin(self):
        backend = self.usb_backend()
        with self.assertRaisesRegex(agent.RpcError, "单独确认"):
            self.request(action="force_disconnect", device_id=self.items[0]["id"])
        self.admin.return_value = False
        with self.assertRaisesRegex(agent.RpcError, "管理员权限"):
            self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        backend.set_enabled.assert_not_called()
        self.assertFalse(self.manager.disconnect_path.exists())

    def test_force_disconnect_journals_before_mutation_and_restores_after_restart(self):
        backend = self.usb_backend()
        change = backend.set_enabled.side_effect
        def checked(instance, enabled):
            records = json.loads(self.manager.disconnect_path.read_text(encoding="utf-8"))
            self.assertIn(instance, records)
            change(instance, enabled)
        backend.set_enabled.side_effect = checked
        self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        self.assertEqual(22, self.request()["disconnected"][0]["problem"])
        self.manager = dm.DeviceManager(self.manager.path, self.admin, self.registry, self.enumerate, self.eject, backend)
        self.request(action="restore_connection", device_id=self.items[0]["id"])
        self.assertTrue(self.usb_state["enabled"])
        self.assertEqual({}, json.loads(self.manager.disconnect_path.read_text(encoding="utf-8")))
        self.eject.assert_not_called()

    def test_force_disconnect_never_targets_system_portable_or_stale_devices(self):
        backend = self.usb_backend()
        for change in ({"protected": True}, {"protected": False, "kind": "portable"}, {"kind": "storage", "ejectable": False}):
            self.items[0].update(change)
            with self.assertRaises(agent.RpcError):
                self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        self.items.clear()
        with self.assertRaises(agent.RpcError):
            self.request(action="force_disconnect", device_id="USBSTOR\\DISK\\A", confirm_data_loss=True)
        backend.set_enabled.assert_not_called()

    def test_preparation_and_journal_failure_do_not_disable_any_device(self):
        backend = self.usb_backend()
        self.manager.before_disconnect = mock.Mock(side_effect=ValueError("正在清理"))
        with self.assertRaisesRegex(agent.RpcError, "正在清理"):
            self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        backend.set_enabled.assert_not_called()
        self.manager.before_disconnect = lambda device: None
        with mock.patch.object(self.manager, "_save_disconnected", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(agent.RpcError, "disk full"):
                self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        backend.set_enabled.assert_not_called()

    def test_external_device_change_after_preparation_is_not_overwritten(self):
        backend = self.usb_backend()
        self.manager.before_disconnect = lambda device: self.items[0].update(protected=True)
        with self.assertRaises(agent.RpcError):
            self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        backend.set_enabled.assert_not_called()

    def test_unconfirmed_state_never_claims_force_success_and_recovery_record_remains(self):
        backend = self.usb_backend()
        backend.set_enabled.side_effect = None
        with mock.patch.object(dm.time, "monotonic", side_effect=[0, 3]):
            with self.assertRaisesRegex(agent.RpcError, "未确认设备状态"):
                self.request(action="force_disconnect", device_id=self.items[0]["id"], confirm_data_loss=True)
        self.assertIn(self.items[0]["id"], json.loads(self.manager.disconnect_path.read_text(encoding="utf-8")))
        self.request(action="restore_connection", device_id=self.items[0]["id"])
        with self.assertRaises(agent.RpcError):
            self.request(action="restore_connection", device_id="USBSTOR\\UNRECORDED")
        self.assertEqual(2, backend.set_enabled.call_count)

    def test_receiver_and_recovery_drive_cannot_be_disconnected(self):
        with mock.patch.object(agent, "_capture_dir", "E:\\LanCmd"), mock.patch.object(agent, "APP_DIR", "C:\\LanCmd"):
            with self.assertRaisesRegex(ValueError, "接收端或恢复记录"):
                agent.prepare_usb_disconnect({"paths": ["e:\\"]})

    def test_native_usb_control_validates_disk_class_and_never_disables_parent_hub(self):
        from ctypes import wintypes as w
        backend = dm.UsbHardware.__new__(dm.UsbHardware)
        backend.w = w
        backend.node = mock.Mock(return_value=w.DWORD(17))
        backend.cm = mock.Mock()
        def property_(node, prop, kind, buffer, length, flags):
            self.assertEqual(9, prop)
            buffer.value = "{4d36e967-e325-11ce-bfc1-08002be10318}"
            return 0
        def identifier(node, buffer, length, flags):
            buffer.value = "USBSTOR\\DISK\\A"
            return 0
        backend.cm.CM_Get_DevNode_Registry_PropertyW.side_effect = property_
        backend.cm.CM_Get_Device_IDW.side_effect = identifier
        backend.cm.CM_Disable_DevNode.return_value = 0
        backend.cm.CM_Enable_DevNode.return_value = 0
        backend.set_enabled("USBSTOR\\DISK\\A", False)
        call = backend.cm.CM_Disable_DevNode.call_args.args
        self.assertEqual(17, call[0].value);self.assertEqual(4, call[1])
        backend.cm.CM_Get_Parent.assert_not_called()
        backend.set_enabled("USBSTOR\\DISK\\A", True)
        self.assertEqual(0, backend.cm.CM_Enable_DevNode.call_args.args[1])
        backend.cm.CM_Get_DevNode_Registry_PropertyW.side_effect = lambda *a: 1
        with self.assertRaisesRegex(RuntimeError, "磁盘设备"):
            backend.set_enabled("USBSTOR\\DISK\\A", False)
        self.assertEqual(1, backend.cm.CM_Disable_DevNode.call_count)


@unittest.skipUnless(os.name == "nt", "Windows native API")
class NativeReadTest(unittest.TestCase):
    def test_inventory_and_policy_reads_on_native_windows(self):
        with tempfile.TemporaryDirectory() as directory:
            manager = dm.DeviceManager(Path(directory) / "unused.json", agent.is_admin)
            result = manager.request({}, agent.RpcError)
            self.assertIsInstance(result["devices"], list)
            self.assertIn("storage", result["policies"])
            self.assertFalse(manager.path.exists())

    def test_eject_unknown_node_reports_error_without_dialog(self):
        with self.assertRaisesRegex(RuntimeError, "无法定位"):
            dm.eject_device("LANCMD_NONEXISTENT_DEVICE\\TEST")


if __name__ == "__main__":
    unittest.main()

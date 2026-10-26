"""Audio protocol validation and input-pause recovery without blocking real input."""
import ctypes
import logging
import os
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
from system_controls import audio_request, endpoint_audio
from input_guard import InputGuard, NativeInputGuard, escape_pressed, INPUT_TAG
from remote_features import RemoteFeatures, KEY_CODES


class RpcError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)


class AudioTest(unittest.TestCase):
    def test_volume_and_mute_are_independent_and_return_authoritative_state(self):
        backend = mock.Mock(return_value={"volume": 25, "muted": True})
        self.assertEqual({"volume": 25, "muted": True}, audio_request({"action": "set", "volume": 25}, RpcError, backend))
        backend.assert_called_once_with(25, None)
        audio_request({"action": "set", "muted": False}, RpcError, backend)
        backend.assert_called_with(None, False)
        audio_request({}, RpcError, backend)
        backend.assert_called_with()

    def test_invalid_audio_requests_cannot_reach_device(self):
        backend = mock.Mock()
        invalid = [{"action": "exec"}, {"action": "set"}]
        invalid += [{"action": "set", "volume": v} for v in (-1, 101, True, "50", 0.5)]
        invalid += [{"action": "set", "muted": v} for v in (1, 0, "false")]
        for q in invalid:
            with self.subTest(q=q), self.assertRaises(RpcError):
                audio_request(q, RpcError, backend)
        backend.assert_not_called()

    def test_virtual_keyboard_codes_cover_oem_keys_and_modifiers(self):
        for key in ("CTRL", "ALT", "SHIFT", "WIN", "F12", "OEM_1", "OEM_MINUS", "OEM_PLUS", "OEM_7"):
            self.assertIn(key, KEY_CODES)


class Native:
    def __init__(self):
        self.escape = threading.Event()
        self.events = []
        self.fail = False

    def show(self, seconds):
        self.events.append(("show", seconds))
        return 123

    def hide(self, window): self.events.append(("hide", window))
    def update(self, window, seconds): pass
    def block(self, enabled):
        self.events.append(("block", enabled))
        return not self.fail


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.native = Native()
        self.now = 100.0
        self.allowed = True
        self.guard = InputGuard(self.native, lambda: self.now)
        self.addCleanup(self.guard.close)

    def pause(self, seconds=0):
        return self.guard.pause("phone", seconds, lambda: self.allowed)

    def wait_released(self):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if not self.guard.status("phone")["paused"]:
                return
            time.sleep(0.01)
        self.fail("Input did not recover")

    def test_unlimited_pause_is_not_time_limited_and_owner_release_works(self):
        self.assertIsNone(self.pause()["remaining"])
        self.now += 86400
        self.assertTrue(self.guard.status("phone")["paused"])
        self.assertFalse(self.guard.release("other")["paused"])
        self.assertTrue(self.guard.status("phone")["paused"])
        self.guard.release("phone")
        self.assertEqual(("block", False), self.native.events[-2])
        self.assertEqual(("hide", 123), self.native.events[-1])

    def test_finite_pause_expires(self):
        self.pause(30)
        self.now += 31
        self.wait_released()

    def test_disconnect_or_watchdog_releases_indefinite_pause(self):
        self.pause()
        self.allowed = False
        self.wait_released()

    def test_local_escape_releases_indefinite_pause(self):
        self.pause()
        self.native.escape.set()
        self.wait_released()

    def test_repeated_pause_cannot_extend_or_replace_owner(self):
        self.pause(30)
        with self.assertRaises(RuntimeError): self.pause(0)
        with self.assertRaises(RuntimeError): self.guard.pause("other", 0, lambda: True)
        self.now += 31
        self.wait_released()

    def test_install_failure_closes_notice_and_does_not_claim_pause(self):
        self.native.fail = True
        with self.assertRaises(RuntimeError): self.pause()
        self.assertFalse(self.guard.status("phone")["paused"])
        self.assertEqual(("hide", 123), self.native.events[-1])

    def test_worker_preserves_remote_input_calls_while_paused(self):
        self.pause()
        self.assertEqual(self.guard.thread.ident, self.guard.invoke(threading.get_ident))
        self.assertEqual(42, self.guard.invoke(lambda: self.guard.invoke(lambda: 42)))

    def test_exact_physical_escape_combination(self):
        keys = set(map(ord, "ARTEX")) | {0xA3, 0x2E}
        self.assertTrue(escape_pressed(keys))
        for key in keys:
            self.assertFalse(escape_pressed(keys - {key}))


class SessionTest(unittest.TestCase):
    def test_pause_requires_own_input_session_and_stale_heartbeat_revokes_it(self):
        backend = SimpleNamespace(local_input=mock.Mock(return_value={"paused": True}),
                                  release_local_input=mock.Mock())
        remote = RemoteFeatures(RpcError, logging.getLogger("guard-test"), "unused", backend)
        state = SimpleNamespace(peer="local", client_id="phone")
        sid = remote.session({"scope": "input"}, state)["sid"]
        remote.local_input({"sid": sid, "action": "pause", "seconds": 0}, state)
        allowed = backend.local_input.call_args.args[3]
        self.assertTrue(allowed())
        state.remote_sessions[sid].last_active -= 13
        self.assertFalse(allowed())
        for q in ({"sid": "wrong"}, {"sid": sid, "seconds": -1}, {"sid": sid, "seconds": True}, {"sid": sid, "seconds": 31}):
            with self.assertRaises(RpcError): remote.local_input(q, state)
        remote.close(state)
        backend.release_local_input.assert_called_with(sid)


@unittest.skipUnless(os.name == "nt", "Windows ABI")
class WindowsNativeTest(unittest.TestCase):
    def test_native_notice_and_hooks_install_then_owner_release(self):
        guard = InputGuard()
        try:
            self.assertTrue(guard.pause("ci-own-session", 30, lambda: True)["paused"])
            self.assertTrue(guard.status("ci-own-session")["paused"])
            self.assertFalse(guard.release("ci-own-session")["paused"])
        finally:
            guard.close()

    def test_audio_device_read_or_specific_missing_endpoint_error(self):
        try:
            result = endpoint_audio()
        except RuntimeError as exc:
            self.assertIn("音频设备", str(exc))
        else:
            self.assertIsInstance(result["volume"], int)
            self.assertTrue(0 <= result["volume"] <= 100)
            self.assertIsInstance(result["muted"], bool)

    def test_physical_escape_and_tagged_remote_input_are_distinguished(self):
        from ctypes import wintypes as w
        native = NativeInputGuard()  # No hooks installed; invoke callbacks with synthetic structs.
        class Key(ctypes.Structure):
            _fields_ = [("vk", w.DWORD), ("scan", w.DWORD), ("flags", w.DWORD), ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        for key in list(map(ord, "ARTEX")) + [0xA2, 0x2E]:
            event = Key(key, 0, 0x10, 0, INPUT_TAG)
            native.keyboard_proc(0, 0x100, ctypes.addressof(event))
        self.assertFalse(native.escape.is_set(), "Remote synthetic chords must not unlock local hooks")
        for key in list(map(ord, "ARTEX")) + [0xA2, 0x2E]:
            event = Key(key, 0, 0, 0, 0)
            self.assertEqual(1, native.keyboard_proc(0, 0x100, ctypes.addressof(event)))
        self.assertTrue(native.escape.is_set())
        native.escape.clear(); native.pressed.clear()
        for key in (0xA2, 0xA4):
            event = Key(key, 0, 0, 0, 0)
            native.keyboard_proc(0, 0x100, ctypes.addressof(event))
        self.assertTrue(native.escape.is_set(), "Secure Delete may never reach the ordinary desktop hook")


if __name__ == "__main__":
    unittest.main()

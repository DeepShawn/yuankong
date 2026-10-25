"""Pointer validation, drag ownership and cleanup; native calls are mocked."""
import ctypes
import logging
import os
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
from remote_features import RemoteFeatures, WindowsBackend


class RpcError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


class Backend:
    def __init__(self):
        self.events = []
        self.released = []
        self.fail = False

    def pointer(self, action, x, y, button, delta, allowed):
        assert allowed()
        self.events.append((action, x, y, button, delta))
        if self.fail:
            raise RuntimeError("Input failed")

    def release_mouse(self, button):
        self.released.append(button)


class PointerTest(unittest.TestCase):
    def setUp(self):
        self.backend = Backend()
        self.remote = RemoteFeatures(RpcError, logging.getLogger("pointer-test"), "unused", self.backend)
        self.state = SimpleNamespace(client_id="phone", peer="127.0.0.1")
        self.sid = self.remote.session({"scope": "input"}, self.state)["sid"]
        self.addCleanup(self.remote.close, self.state)

    def call(self, action="click", **params):
        q = {"sid": self.sid, "action": action, "x": 0.25, "y": 0.75}
        q.update(params)
        return self.remote.pointer(q, self.state)

    def test_normalized_click_and_scroll_reach_backend(self):
        self.assertTrue(self.call()["sent"])
        self.call("scroll", delta=-3, x=1, y=0)
        self.assertEqual(("click", 0.25, 0.75, "left", 0), self.backend.events[0])
        self.assertEqual(("scroll", 1.0, 0.0, "left", -3), self.backend.events[1])

    def test_black_bar_or_invalid_coordinates_are_rejected(self):
        for value in (-0.01, 1.01, float("nan"), float("inf"), True, "0.5", None, 10 ** 1000):
            with self.subTest(value=repr(value)):
                with self.assertRaises(RpcError): self.call(x=value)
                with self.assertRaises(RpcError): self.call(y=value)
        self.assertFalse(self.backend.events)

    def test_invalid_actions_buttons_and_scroll_are_rejected(self):
        for q in ({"action": "inject"}, {"button": "extra"},
                  *({"action": "scroll", "delta": v} for v in (0, 11, -11, 0.5, True, "1"))):
            with self.assertRaises(RpcError): self.call(**q)
        self.assertFalse(self.backend.events)

    def test_requires_matching_input_session(self):
        screen = self.remote.session({"scope": "screen"}, self.state)["sid"]
        for sid in ("missing", screen):
            with self.assertRaises(RpcError): self.call(sid=sid)
        other = SimpleNamespace(client_id="phone", peer="127.0.0.1")
        with self.assertRaises(RpcError): self.remote.pointer({"sid": self.sid, "x": 0.5, "y": 0.5}, other)

    def test_drag_up_and_duplicate_up_are_safe(self):
        self.call("down"); self.call("move"); self.call("up"); self.call("up")
        self.assertEqual(["down", "move", "up"], [e[0] for e in self.backend.events])
        self.assertFalse(self.remote.pointer_owners)

    def test_stop_and_disconnect_release_pressed_buttons(self):
        self.call("down")
        self.remote.session({"action": "end", "sid": self.sid}, self.state)
        self.assertEqual(["left"], self.backend.released)
        self.sid = self.remote.session({"scope": "input"}, self.state)["sid"]
        self.call("down", button="right")
        self.remote.close(self.state)
        self.assertEqual(["left", "right"], self.backend.released)
        self.assertFalse(self.remote.pointer_owners)

    def test_direct_session_close_releases_drag_only_once(self):
        self.call("down")
        self.state.remote_sessions[self.sid].close()
        self.state.remote_sessions[self.sid].close()
        self.assertEqual(["left"], self.backend.released)
        with self.assertRaises(RpcError): self.call("move")

    def test_other_connection_cannot_move_or_release_owned_drag(self):
        self.call("down")
        other = SimpleNamespace(client_id="other", peer="127.0.0.1")
        sid = self.remote.session({"scope": "input"}, other)["sid"]
        for action in ("move", "up", "click", "down", "scroll"):
            with self.assertRaises(RpcError):
                self.remote.pointer({"sid": sid, "x": 0.5, "y": 0.5, "action": action, "delta": 1}, other)
        self.remote.close(other)
        self.assertFalse(self.backend.released)
        self.assertEqual({"left": self.sid}, self.remote.pointer_owners)

    def test_native_error_clears_owned_drag(self):
        self.backend.fail = True
        with self.assertRaises(RuntimeError): self.call("down")
        self.assertEqual(["left"], self.backend.released)
        self.assertFalse(self.remote.pointer_owners)

    def test_click_during_drag_is_rejected(self):
        self.call("down")
        with self.assertRaises(RpcError): self.call("double_click")

    def test_identity_change_during_handshake_releases_previous_drag(self):
        import lan_command_agent as agent
        state = agent.ClientState(None, ("127.0.0.1", 12345))
        state.client_id = "before"
        sid = self.remote.session({"scope": "input"}, state)["sid"]
        self.remote.pointer({"sid": sid, "action": "down", "x": 0.5, "y": 0.5}, state)
        with mock.patch.object(agent, "load_pairs", return_value={}), mock.patch.object(agent, "save_pairs"):
            agent._handle_hello(state, {"client_id": "after"}, {"name": "test"})
        self.assertEqual("after", state.client_id)
        self.assertFalse(state.remote_sessions)
        self.assertEqual(["left"], self.backend.released)
        self.assertFalse(self.remote.pointer_owners)


@unittest.skipUnless(os.name == "nt", "Windows ABI only; no actual cursor input")
class NativePointerTest(unittest.TestCase):
    def test_input_layout_absolute_virtual_desktop_and_signed_wheel(self):
        captured = []
        def send(count, pointer, size):
            self.assertEqual(1, count)
            self.assertEqual(40 if struct.calcsize("P") == 8 else 28, size)
            raw = ctypes.string_at(pointer, size)
            self.assertEqual(0, struct.unpack_from("<I", raw)[0])
            captured.append(struct.unpack_from("<iiII", raw, 8 if size == 40 else 4))
            return 1
        user = SimpleNamespace(SendInput=mock.Mock(side_effect=send))
        backend = WindowsBackend("unused")
        with mock.patch.object(ctypes, "WinDLL", return_value=user):
            backend.pointer("double_click", 0, 1, "right", 0, lambda: True)
            backend.pointer("scroll", 0.5, 0.5, "left", -3, lambda: True)
        self.assertEqual((0, 65535, 0, 0xc001), captured[0])
        self.assertEqual([0x8, 0x10, 0x8, 0x10], [row[3] for row in captured[1:5]])
        self.assertEqual((0, 0, (-360) & 0xffffffff, 0x800), captured[-1])


if __name__ == "__main__":
    unittest.main()

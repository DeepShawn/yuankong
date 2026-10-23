"""Native Windows smoke tests; run on an interactive test desktop, not a user's session."""
import ctypes
import os
import sys
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows"))
from remote_features import ConsentSession, WindowsBackend, show_session_window


@unittest.skipUnless(os.name == "nt", "Requires a Windows desktop")
class WindowsFeaturesTest(unittest.TestCase):
    def setUp(self):
        from ctypes import wintypes as w
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.user.FindWindowW.argtypes = [w.LPCWSTR, w.LPCWSTR]
        self.user.FindWindowW.restype = w.HWND
        self.user.SendMessageW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
        self.user.SendMessageW.restype = ctypes.c_ssize_t
        self.user.ShowWindow.argtypes = [w.HWND, ctypes.c_int]
        self.user.IsWindowVisible.argtypes = [w.HWND]
        self.session = None
        self.window = None
        self.worker = None

    def tearDown(self):
        if self.session:
            self.session.close()
        if self.window:
            self.user.SendMessageW(self.window, 0x0010, 0, 0)
        if self.worker:
            self.worker.join(3)
            self.assertFalse(self.worker.is_alive(), "Native session window must exit")

    def open_window(self):
        self.session = ConsentSession("screen", "127.0.0.1 · test")
        self.worker = threading.Thread(target=show_session_window, args=(self.session,), daemon=True)
        self.worker.start()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            self.window = self.user.FindWindowW("LanCmdSession_" + self.session.sid, None)
            if self.window and self.user.IsWindowVisible(self.window):
                return
            time.sleep(0.02)
        self.fail("Native consent window did not appear")

    def test_visible_permission_and_stop_buttons(self):
        self.open_window()
        self.assertFalse(self.session.approved.is_set())
        self.user.SendMessageW(self.window, 0x0111, 101, 0)
        self.assertTrue(self.session.alive())
        self.assertTrue(self.user.IsWindowVisible(self.window))
        self.user.SendMessageW(self.window, 0x0111, 102, 0)
        self.assertFalse(self.session.alive())
        self.assertTrue(self.session.stopped.is_set())

    def test_closing_pending_window_denies(self):
        self.open_window()
        self.user.SendMessageW(self.window, 0x0010, 0, 0)
        self.assertTrue(self.session.stopped.is_set())
        self.assertFalse(self.session.approved.is_set())

    def test_hiding_active_indicator_revokes(self):
        self.open_window()
        self.user.SendMessageW(self.window, 0x0111, 101, 0)
        self.user.ShowWindow(self.window, 0)
        self.assertTrue(self.session.stopped.wait(2))
        self.assertFalse(self.session.alive())

    def test_native_capture_jpeg_and_camera_enumeration(self):
        helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows", "LanCmdCapture.exe")
        self.assertTrue(os.path.isfile(helper), "Build capture.cpp before running native tests")
        backend = WindowsBackend(helper)
        raw = backend.capture("screen")
        self.assertTrue(raw.startswith(b"\xff\xd8") and raw.endswith(b"\xff\xd9"))
        self.assertGreater(len(raw), 1000)
        self.assertIsInstance(backend.cameras(), list)

    def test_sendinput_layout_unicode_and_key_release(self):
        events = []
        def send(count, pointer, size):
            item = pointer._obj
            key = item.payload.keyboard
            events.append((item.type, key.vk, key.scan, key.flags, size))
            return count
        with patch("remote_features.ctypes.WinDLL", return_value=SimpleNamespace(SendInput=send)):
            backend = WindowsBackend("unused.exe")
            backend.keys(["CTRL", "C"], "")
            self.assertEqual([(0x11, 0), (0x43, 0), (0x43, 2), (0x11, 2)], [(e[1], e[3]) for e in events])
            self.assertTrue(all(e[0] == 1 and e[4] == 40 for e in events))
            events.clear()
            backend.keys([], "你😀")
            self.assertEqual([0x4F60, 0x4F60, 0xD83D, 0xD83D, 0xDE00, 0xDE00], [e[2] for e in events])
            self.assertEqual([4, 6, 4, 6, 4, 6], [e[3] for e in events])

    def test_revocation_releases_already_pressed_modifier(self):
        events = []
        def send(count, pointer, size):
            key = pointer._obj.payload.keyboard
            events.append((key.vk, key.flags))
            return count
        allowed = iter([True, False])
        with patch("remote_features.ctypes.WinDLL", return_value=SimpleNamespace(SendInput=send)):
            with self.assertRaises(RuntimeError):
                WindowsBackend("unused.exe").keys(["CTRL", "V"], "", allowed=lambda: next(allowed))
        self.assertEqual([(0x11, 0), (0x11, 2)], events)


if __name__ == "__main__":
    unittest.main()

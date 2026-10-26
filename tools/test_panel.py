"""Native panel regression; elevation approval is simulated, never automated."""
import ctypes
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt", "Native Windows panel helpers")
class PanelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "windows/build-win/StartPanelTest.dll"
        if not path.is_file():
            raise RuntimeError("Build StartPanelTest.dll before running this test")
        cls.panel = ctypes.CDLL(str(path), use_last_error=True)
        cls.panel.panel_elevated.argtypes = [ctypes.c_int]
        cls.panel.panel_stop.argtypes = [ctypes.c_int]
        cls.panel.panel_appdata.argtypes = [ctypes.c_wchar_p]
        cls.panel.panel_request_admin.argtypes = [ctypes.c_int]

    def setUp(self):
        self.previous = os.environ.get("APPDATA", "")
        self.directory = tempfile.TemporaryDirectory(prefix="LanCmd panel 中文 ")
        self.addCleanup(self.directory.cleanup)
        self.panel.panel_appdata(self.directory.name)
        self.addCleanup(self.panel.panel_appdata, self.previous)
        self.pidfile = Path(self.directory.name) / "LanCmd/agent.pid"
        self.pidfile.parent.mkdir()

    def child(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        def cleanup():
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=10)
        self.addCleanup(cleanup)
        return child

    def test_receiver_token_matches_current_windows_token(self):
        expected = int(bool(ctypes.windll.shell32.IsUserAnAdmin()))
        self.assertEqual(expected, self.panel.panel_elevated(os.getpid()))
        self.assertEqual(-1, self.panel.panel_elevated(0x7fffffff))

    def test_successful_stop_waits_for_exit_and_removes_its_pid_file(self):
        child = self.child()
        self.pidfile.write_text(str(child.pid))
        self.assertEqual(1, self.panel.panel_stop(child.pid))
        child.wait(timeout=2)
        self.assertFalse(self.pidfile.exists())

    def test_failed_stop_preserves_running_state_file(self):
        self.pidfile.write_text(str(os.getpid()))
        self.assertEqual(0, self.panel.panel_stop(os.getpid()))
        self.assertEqual(str(os.getpid()), self.pidfile.read_text())

    def test_stopping_old_pid_does_not_remove_replacement_state_file(self):
        child = self.child()
        self.pidfile.write_text("1234567")
        self.assertEqual(1, self.panel.panel_stop(child.pid))
        self.assertEqual("1234567", self.pidfile.read_text())

    def test_uac_cancel_and_approval_leave_old_receiver_until_replacement_starts(self):
        child = self.child()
        self.pidfile.write_text(str(child.pid))
        # Both cases use a fake ShellExecuteEx: no actual elevation dialog opens.
        for approved in (0, 1):
            self.assertEqual(1, self.panel.panel_request_admin(approved))
            self.assertIsNone(child.poll())
            self.assertEqual(str(child.pid), self.pidfile.read_text())


if __name__ == "__main__":
    unittest.main()

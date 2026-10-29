"""Run the shipped ZIP on Windows, without substituting the installed Python.

Usage: python tools/check_windows_package.py dist/LanCmd-Windows-<version>.zip
"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import uuid
import zipfile


class PackagedStartupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.name != "nt":
            raise RuntimeError("The actual portable package must be tested on Windows")
        cls.temp = tempfile.TemporaryDirectory(prefix="lancmd-shipped-")
        cls.addClassCleanup(cls.cleanup_package)
        cls.folder = Path(cls.temp.name)
        # Include characters that must survive CRT and CMD argument processing.
        destination = cls.folder / "便携接收端 & space ! %folder%"
        with zipfile.ZipFile(PACKAGE) as archive:
            archive.extractall(destination)
        cls.package = destination / "LanCmd-Windows"
        cls.version = json.loads((cls.package / "release-manifest.json").read_text(
            encoding="utf-8"))["version"]
        spec = importlib.util.spec_from_file_location("shipped_upgrade", cls.package / "local_upgrade.py")
        cls.upgrade = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.upgrade)

        # Fail before starting a GUI launcher that could display an error dialog.
        # python.exe here is the archive's isolated embeddable runtime, not CI's.
        state = cls.folder / "preflight"
        state.mkdir()
        result = subprocess.run([str(cls.package / "python/python.exe"),
                                 str(cls.package / "lan_command_agent.py"), "--status"],
                                cwd=cls.folder, env=dict(os.environ, APPDATA=str(state),
                                                        PYTHONIOENCODING="utf-8"),
                                capture_output=True, timeout=20)
        output = (result.stdout + result.stderr).decode("utf-8", "replace")
        if result.returncode != 1 or "LanCmd Agent v" + cls.version not in output or "Traceback" in output:
            raise AssertionError("Shipped Python could not load receiver:\n" + output)

    @classmethod
    def cleanup_package(cls):
        # A read-only firewall probe can briefly outlive a receiver terminated by
        # the test and retain its inherited working directory. Do not hide a
        # permanent leak, but wait for the real Windows handle to be released.
        deadline = time.monotonic() + 20
        while True:
            try:
                cls.temp.cleanup()
                return
            except PermissionError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.2)

    def setUp(self):
        self.state = self.folder / uuid.uuid4().hex
        self.state.mkdir()
        config = self.state / "LanCmd"
        config.mkdir()
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.port = reservation.getsockname()[1]
        (config / "agent.json").write_text(json.dumps(dict(bind="127.0.0.1", port=self.port,
                                                          discovery_port=0)), encoding="utf-8")
        self.env = dict(os.environ, APPDATA=str(self.state))

    def assert_connected(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as connection:
            with connection.makefile("rwb") as stream:
                def call(cmd, **fields):
                    stream.write(json.dumps(dict(id=1, cmd=cmd, **fields)).encode() + b"\n")
                    stream.flush()
                    response = json.loads(stream.readline())
                    self.assertTrue(response["ok"], response)
                    return response
                call("hello", client_id="shipped-startup", client="Package regression")
                capabilities = call("capabilities")
                self.assertEqual(self.version, capabilities["version"])
                for feature in ("storage", "local_upgrade", "app_freeze", "file_search", "force_delete", "startup_network"):
                    self.assertIn(feature, capabilities["features"])

    def test_console_launcher_runs_shipped_python_and_all_feature_imports(self):
        result = subprocess.run([str(self.package / "LanCmdCtl.exe"), "--status"],
                                cwd=self.folder, env=self.env, capture_output=True, timeout=20)
        self.assertEqual(1, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertIn(("LanCmd Agent v" + self.version).encode(), result.stdout)
        self.assertNotIn(b"Traceback", result.stderr)

    def test_gui_launcher_starts_real_receiver_and_accepts_phone_connection(self):
        ready = self.state / "healthy.json"
        nonce = uuid.uuid4().hex
        env = dict(self.env, LANCMD_UPGRADE_READY=str(ready), LANCMD_UPGRADE_NONCE=nonce)
        launcher = subprocess.Popen([str(self.package / "LanCmdAgent.exe")],
                                    cwd=self.folder, env=env)
        receiver = None
        try:
            deadline = time.monotonic() + 20
            while not ready.exists():
                if time.monotonic() >= deadline:
                    self.fail("Shipped GUI receiver did not become ready")
                time.sleep(.1)
            health = json.loads(ready.read_text(encoding="utf-8"))
            self.assertEqual(nonce, health["nonce"])
            self.assertEqual(self.version, health["version"])
            receiver = self.upgrade.WindowsProcess(health["pid"], image=self.package / "python/pythonw.exe")
            self.assert_connected()
            self.assertEqual(0, launcher.wait(10))
            self.assertTrue(receiver.alive())
        finally:
            if receiver is not None:
                receiver.stop()
                receiver.close()
            if launcher.poll() is None:
                launcher.terminate()
            launcher.wait(10)

    def test_upgrade_batch_starts_real_receiver_and_closes_its_cmd(self):
        with mock.patch.dict(os.environ, self.env):
            startup = self.upgrade.ReceiverStart(self.package, self.state, self.version)
        try:
            self.assertFalse(startup.legacy)
            startup.wait_ready(30)
            self.assertEqual(0, startup.command.poll(), "Upgrade CMD must exit automatically")
            self.assertTrue(startup.receiver.alive())
            self.assert_connected()
        except Exception as exc:
            logs = "\n".join(path.name + ":\n" + path.read_text(encoding="utf-8", errors="replace")
                             for path in self.state.glob("*.log"))
            raise AssertionError(str(exc) + "\n" + logs) from exc
        finally:
            startup.stop()
            startup.close()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_windows_package.py <Windows ZIP>")
    PACKAGE = Path(sys.argv.pop()).resolve(strict=True)
    unittest.main(verbosity=2)

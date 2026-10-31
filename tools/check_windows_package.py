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
from cursor_window_fixture import CursorWindow


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
        manifest = json.loads((cls.package / "release-manifest.json").read_text(encoding="utf-8"))
        cls.version = manifest['version']
        cls.display_version = manifest.get('display_version', cls.version)
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
        if result.returncode != 1 or "LanCmd Agent v" + cls.display_version not in output or "Traceback" in output:
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

    def test_packaged_recorder_encodes_and_decodes_panorama_and_stereo_sources(self):
        media=self.folder/'encoded panorama'
        media.mkdir(exist_ok=True)
        result=subprocess.run([str(self.package/'LanCmdRecord.exe'),'self-test',str(media)],cwd=self.folder,
                              env=self.env,capture_output=True,timeout=45)
        self.assertEqual(0,result.returncode,(result.stdout+result.stderr).decode('utf-8','replace'))
        report=json.loads(result.stdout)
        self.assertTrue(report['panorama_decode']);self.assertTrue(report['stereo_mix'])
        self.assertTrue((media/'panorama.mp4').is_file())

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
                request_id = 100
                def call(cmd, _expect_ok=True, **fields):
                    nonlocal request_id
                    request_id += 1
                    stream.write(json.dumps(dict(id=request_id, cmd=cmd, **fields)).encode() + b"\n")
                    stream.flush()
                    response = json.loads(stream.readline())
                    self.assertEqual(_expect_ok,response["ok"],response)
                    self.assertEqual(request_id,response['id'],'Worker IPC IDs must never replace phone RPC IDs')
                    return response
                call("hello", client_id="shipped-startup", client="Package regression")
                capabilities = call("capabilities")
                self.assertEqual(self.display_version, capabilities["version"])
                self.assertEqual(self.version, capabilities['build_version'])
                for feature in ("storage", "local_upgrade", "app_freeze", "file_search", "force_delete", "startup_network", "network_management", "network_device_state", "hotspot_client_access", "hotspot_adapter_selection", "network_password", "network_batch", "hotspot_timer", "panorama_recording", "system_audio_recording", "app_runtime", "file_locks", "file_resize", "file_sources", "announcements", "global_delays", "cursor_visibility_verified", "cursor_visibility_input_queue", "native_touch", "native_touch_hold"):
                    self.assertIn(feature, capabilities["features"])
                gate=call('network',action='auth_status')
                self.assertTrue(gate['requires_password']);self.assertIsInstance(gate['admin'],bool)
                locked=call('network',_expect_ok=False,action='refresh',area='adapters')
                self.assertIn('NETWORK_',locked['error'])
                denied=call('network',_expect_ok=False,action='unlock',password='invalid-package-test-password')
                self.assertNotIn('invalid-package-test-password',denied['error'])
                self.assertNotIn('network_token',denied)
                # Exercise the same helper using the archive's isolated Python,
                # without embedding a real user password in a public CI test.
                probe=subprocess.run([str(self.package/'python/python.exe'),'-X','utf8','-c',
                    "import sys,json;sys.path.insert(0,sys.argv[1]);from network_management import WindowsNetwork;print(json.dumps(WindowsNetwork().read('adapters')))",str(self.package)],
                    cwd=self.folder,env=self.env,capture_output=True,timeout=60)
                self.assertEqual(0,probe.returncode,probe.stderr.decode('utf-8','replace'))
                self.assertIsInstance(json.loads(probe.stdout)['adapters'],list)
                with CursorWindow() as window:
                    self.assertGreaterEqual(window.initial['display_count'], 0)
                    lease = call("session", scope="input")["sid"]
                    try:
                        cursor = call("cursor", sid=lease, hidden=True)
                        self.assertTrue(cursor["hidden"])
                        self.assertTrue(cursor["verified"], "Packaged receiver must verify native cursor hiding")
                        for _ in range(3):
                            call('pointer', sid=lease, action='click', x=window.initial['x'], y=window.initial['y'])
                            current = window.report('probe')
                            self.assertEqual(current['custom'], current['selected'])
                            self.assertLess(current['display_count'], 0, current)
                            self.assertFalse(current['visible'], current)
                    finally:
                        call("session", action="end", sid=lease)
                    self.assertEqual(window.initial['display_count'], window.report('probe')['display_count'])
                with CursorWindow(touch=True) as window:
                    lease = call('session', scope='input')['sid']
                    ready = call('touch', sid=lease, action='start')
                    self.assertEqual('InjectTouchInput', ready['api'])
                    x, y = window.initial['x'], window.initial['y']
                    def contact(state, x):return dict(id=0,state=state,x=x,y=y)
                    call('touch', sid=lease, action='frame', contacts=[contact('down',x)])
                    call('touch', sid=lease, action='frame', contacts=[contact('move',x+.02)])
                    call('session', action='end', sid=lease)  # Release an in-flight touch.
                    deadline = time.monotonic() + 3
                    while True:
                        events = window.report('probe')['touch_events']
                        if any(p['flags'] & 4 for p in events):break
                        if time.monotonic() >= deadline:self.fail('The shipped receiver did not release its native touch: '+str(events))
                        time.sleep(.02)
                    self.assertTrue(any(p['flags'] & 2 for p in events))
                    self.assertEqual(window.initial['display_count'],window.report('probe')['display_count'])

    def test_console_launcher_runs_shipped_python_and_all_feature_imports(self):
        result = subprocess.run([str(self.package / "LanCmdCtl.exe"), "--status"],
                                cwd=self.folder, env=self.env, capture_output=True, timeout=20)
        self.assertEqual(1, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertIn(("LanCmd Agent v" + self.display_version).encode(), result.stdout)
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

"""Start the real entry point with the module isolation used by portable Python."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]


class IsolatedStartupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lancmd-isolated-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, APPDATA=str(self.root), XDG_CONFIG_HOME=str(self.root))
        # -I ignores PYTHONUTF8; mirror the native launcher's UTF-8 setting
        # explicitly so redirected Chinese status output works on English Windows.
        self.command = [sys.executable, "-I", "-X", "utf8", str(ROOT / "windows/lan_command_agent.py")]

    def test_status_imports_all_features_without_script_directory_on_python_path(self):
        result = subprocess.run(self.command + ["--status"], cwd=self.root, env=self.env,
                                capture_output=True, timeout=20)
        output = result.stdout.decode("utf-8", "replace")
        self.assertEqual(1, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertIn("LanCmd Agent v", output)
        self.assertNotIn(b"Traceback", result.stderr)

    def test_receiver_starts_and_serves_with_isolated_python(self):
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        config = self.root / ("LanCmd" if os.name == "nt" else "lancmd")
        config.mkdir()
        (config / "agent.json").write_text(json.dumps(dict(bind="127.0.0.1", port=port,
                                                          discovery_port=0)), encoding="utf-8")
        with (self.root / "output.log").open("wb") as output:
            process = subprocess.Popen(self.command, cwd=ROOT.parent, env=self.env,
                                       stdin=subprocess.DEVNULL, stdout=output, stderr=output)
            try:
                deadline = time.monotonic() + 20
                while True:
                    self.assertIsNone(process.poll(), (self.root / "output.log").read_text(errors="replace"))
                    try:
                        connection = socket.create_connection(("127.0.0.1", port), timeout=1)
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            self.fail("Isolated receiver did not listen: " +
                                      (self.root / "output.log").read_text(errors="replace"))
                        time.sleep(.1)
                with connection, connection.makefile("rwb") as stream:
                    def call(cmd, **fields):
                        stream.write(json.dumps(dict(id=1, cmd=cmd, **fields)).encode() + b"\n")
                        stream.flush()
                        return json.loads(stream.readline())
                    self.assertTrue(call("hello", client_id="isolated-startup")["ok"])
                    capabilities = call("capabilities")
                    self.assertTrue(capabilities["ok"])
                    self.assertIn("storage", capabilities["features"])
            finally:
                if process.poll() is None:
                    process.terminate()
                process.wait(10)


if __name__ == "__main__":
    unittest.main()

"""Run the native Windows launcher with a real Python runtime.

Build windows/LanCmdCtl.exe first, then run this module on Windows. The tests
exercise CreateProcess and Python's actual argv parsing, including paths with
spaces and Chinese characters. They never start the receiving service.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == "nt", "Requires the native Windows launcher")
class WindowsLauncherTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.built_launcher = ROOT / "windows" / "LanCmdCtl.exe"
        if not cls.built_launcher.is_file():
            raise RuntimeError("Build windows/LanCmdCtl.exe before running launcher tests")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lancmd-launcher-")
        self.addCleanup(self.temp.cleanup)
        self.package = Path(self.temp.name) / "LanCmd 中文 with spaces"
        self.package.mkdir()
        self.launcher = self.package / "LanCmdCtl.exe"
        shutil.copy2(self.built_launcher, self.launcher)
        self.runtime = self.package / "python"
        # A directory junction preserves Python's DLL/Lib layout and needs no
        # developer mode or symlink privilege. Copying python.exe alone cannot.
        junction = subprocess.run(
            ["cmd", "/c", "mklink", "/J", str(self.runtime), sys.base_prefix],
            capture_output=True,
            timeout=15,
        )
        self.assertEqual(0, junction.returncode, junction.stderr.decode(errors="replace"))
        self.addCleanup(self.runtime.rmdir)
        self.script = self.package / "lan_command_agent.py"
        self.script.write_text(
            "import json, sys\nprint(json.dumps(sys.argv[1:], ensure_ascii=False))\n",
            encoding="utf-8",
        )
        self.appdata = Path(self.temp.name) / "isolated appdata"
        self.env = dict(os.environ, APPDATA=str(self.appdata))

    def run_launcher(self, args):
        return subprocess.run(
            [str(self.launcher), *args],
            cwd=self.temp.name,
            env=self.env,
            capture_output=True,
            timeout=20,
        )

    def assert_roundtrip(self, args):
        result = self.run_launcher(args)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertEqual(args, json.loads(result.stdout.decode("utf-8")))

    def test_python_runs_script_instead_of_interpreter_binary(self):
        self.assert_roundtrip([])

    def test_empty_unicode_quotes_and_backslashes_roundtrip(self):
        args = ["", "plain", "two words", "tab\tvalue", "中文目录", '"quoted"']
        for count in range(1, 5):
            args.extend([
                "before" + "\\" * count + '"after',
                "space and trailing " + "\\" * count,
            ])
        self.assert_roundtrip(args)

    def test_overflow_fails_without_executing_truncated_command(self):
        # The incoming command fits the OS limit; adding CRT quotes to these
        # individual arguments exceeds the launcher's 32768-character buffer.
        result = self.run_launcher(["x"] * 9000)
        self.assertEqual(2, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertIn(b"LanCmd", result.stderr)

    def test_status_uses_real_agent_without_starting_service(self):
        for name in ("lan_command_agent.py", "remote_features.py", "system_controls.py", "input_guard.py", "device_management.py", "touch_guard.py", "file_actions.py", "desktop_apps.py", "command_queue.py"):
            shutil.copy2(ROOT / "windows" / name, self.package / name)
        result = self.run_launcher(["--status"])
        # An isolated APPDATA has no running receiver; --status returns 1.
        self.assertEqual(1, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertIn(b"LanCmd Agent v", result.stdout, result.stderr.decode("utf-8", "replace"))
        self.assertTrue((self.appdata / "LanCmd").is_dir())
        self.assertFalse((self.appdata / "LanCmd" / "agent.pid").exists())


if __name__ == "__main__":
    unittest.main()

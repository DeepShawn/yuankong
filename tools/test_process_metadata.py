"""Process responses report actual administrator status without granting it."""
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
import lan_command_agent as agent


class ProcessMetadataTest(unittest.TestCase):
    def test_windows_reports_standard_and_administrator_state(self):
        for elevated in (False, True):
            with self.subTest(elevated=elevated), mock.patch.object(agent, "IS_WIN", True), \
                 mock.patch.object(agent, "is_admin", return_value=elevated), \
                 mock.patch.object(agent, "run_exec", return_value={"out": '"editor.exe","123","Console","1","1,234 K"\n'}):
                result = agent.cmd_ps()
                self.assertIs(elevated, result["admin"])
                self.assertEqual(123, result["items"][0]["pid"])
                self.assertEqual(1234 * 1024, result["items"][0]["mem_bytes"])

    def test_debug_receiver_retains_process_fields_and_privilege_metadata(self):
        with mock.patch.object(agent, "IS_WIN", False), mock.patch.object(agent, "is_admin", return_value=False), \
             mock.patch.object(agent, "run_exec", return_value={"out": "PID RSS COMMAND\n123 1024 editor\n"}):
            result = agent.cmd_ps("editor")
            self.assertFalse(result["admin"])
            self.assertEqual(1, result["count"])
            self.assertEqual(1024 * 1024, result["items"][0]["mem_bytes"])


if __name__ == "__main__":
    unittest.main()

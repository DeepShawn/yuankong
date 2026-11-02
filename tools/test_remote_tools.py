import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))

import app_control
import self_manage
import tunnel
from dates_batch import BatchDates, collect, format_time, parse_time


def boom(message, code=1):
    raise ValueError(message)


class DatesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        self.files = self.root / "docs"
        self.files.mkdir()
        for name in ("a.txt", "b.md", "c.log"):
            (self.files / name).write_text("x" * 10, encoding="utf-8")
        self.dates = BatchDates(self.state, str(self.root / "install"), boom)

    def test_parse_and_format_round_trip(self):
        value = parse_time("2021-03-04 05:06:07")
        self.assertEqual("2021-03-04 05:06:07", format_time(value))
        self.assertEqual(parse_time("2021-03-04"), parse_time("2021-03-04 00:00:00"))
        with self.assertRaisesRegex(ValueError, "格式"):
            parse_time("2021/03/04 05:06:07")
        with self.assertRaises(ValueError):
            parse_time("1969-12-31 23:59:59")

    def test_collect_respects_mask_and_links(self):
        nested = self.files / "sub"
        nested.mkdir()
        (nested / "d.txt").write_text("y", encoding="utf-8")
        found = collect([str(self.files)], True, "*.txt", False, "")
        self.assertEqual([self.files / "a.txt", nested / "d.txt"], found)
        self.assertEqual(1, len(collect([str(self.files)], False, "*.txt", False, "")))
        if hasattr(os, "symlink"):
            link = self.files / "link.txt"
            try:
                link.symlink_to(self.files / "a.txt")
            except (OSError, NotImplementedError):
                link = None
            if link is not None:
                self.assertNotIn(link, collect([str(self.files)], True, "*.txt", False, ""))

    def test_apply_and_restore_round_trip(self):
        target = "2020-05-06 07:08:09"
        wanted = parse_time(target)
        before = {p.name: p.stat().st_mtime_ns for p in self.files.iterdir()}
        result = self.dates.request(dict(action="apply", paths=[str(self.files)], mask="*",
                                         time=target, recursive=False), None)
        self.assertEqual(3, result["applied"])
        for path in self.files.iterdir():
            self.assertEqual(wanted, path.stat().st_mtime_ns)
        records = self.dates.request(dict(action="records"), None)["items"]
        self.assertEqual(1, len(records))
        restored = self.dates.request(dict(action="restore", id=records[0]["id"]), None)
        self.assertEqual(3, restored["restored"])
        for path in self.files.iterdir():
            self.assertEqual(before[path.name], path.stat().st_mtime_ns)

    def test_range_is_spread_inside_window(self):
        result = self.dates.request(dict(action="apply", paths=[str(self.files)], mask="*.txt",
                                         start="2020-01-01 00:00:00",
                                         end="2020-01-01 00:10:00", recursive=False), None)
        self.assertEqual(1, result["applied"])
        value = (self.files / "a.txt").stat().st_mtime_ns
        self.assertGreaterEqual(value, parse_time("2020-01-01 00:00:00"))
        self.assertLessEqual(value, parse_time("2020-01-01 00:10:00"))

    def test_changed_file_is_not_overwritten_on_restore(self):
        target = "2020-05-06 07:08:09"
        self.dates.request(dict(action="apply", paths=[str(self.files / "a.txt")], mask="*",
                                time=target, recursive=False), None)
        record = self.dates.request(dict(action="records"), None)["items"][0]
        (self.files / "a.txt").write_text("changed", encoding="utf-8")
        result = self.dates.request(dict(action="restore", id=record["id"]), None)
        self.assertEqual(0, result["restored"])
        self.assertEqual(1, result["skipped"])

    def test_preview_and_invalid_target(self):
        preview = self.dates.request(dict(action="preview", paths=[str(self.files)], mask="*.md",
                                          recursive=False), None)
        self.assertEqual(1, preview["count"])
        with self.assertRaisesRegex(ValueError, "time"):
            self.dates.request(dict(action="apply", paths=[str(self.files)], mask="*"), None)

    def test_include_self_covers_install_dir(self):
        install = self.root / "install"
        install.mkdir()
        (install / "lan_command_agent.py").write_text("#", encoding="utf-8")
        result = self.dates.request(dict(action="apply", paths=[], include_self=True,
                                         time="2019-01-02 03:04:05", recursive=True), None)
        self.assertEqual(1, result["applied"])
        self.assertEqual(parse_time("2019-01-02 03:04:05"),
                         (install / "lan_command_agent.py").stat().st_mtime_ns)


class SelfManageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "LanCmd-Windows"
        self.root.mkdir()
        self.patch = mock.patch.dict(os.environ, {"LOCALAPPDATA": str(Path(self.temp.name) / "local")})
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.manager = self_manage.SelfManage(self.root, Path(self.temp.name) / "config", 4242, boom)

    def test_helper_source_compiles(self):
        compile(self_manage.HELPER, "helper", "exec")

    def test_name_and_root_checks(self):
        target = self_manage.check_name(self.root, "新名字")
        self.assertEqual(self.root.parent / "新名字", target)
        for bad in ("", " a", "a/b", "con", "x" * 81, "."):
            with self.assertRaises(ValueError):
                self_manage.check_name(self.root, bad)
        (self.root.parent / "占用").mkdir()
        with self.assertRaisesRegex(ValueError, "已存在"):
            self_manage.check_name(self.root, "占用")
        with self.assertRaises(ValueError):
            self_manage.check_root(Path("/"))

    def test_confirm_string_is_required(self):
        with self.assertRaisesRegex(ValueError, "RENAME"):
            self.manager.request(dict(action="rename", name="abc"), None)
        with self.assertRaisesRegex(ValueError, "DELETE"):
            self.manager.request(dict(action="delete"), None)

    def test_plan_is_written_before_anything_runs(self):
        plan = self.manager.plan_rename("改名后")
        self.assertEqual("rename", plan["operation"])
        self.assertEqual(str(self.root.parent / "改名后"), plan["target"])
        path = Path(plan["plan"])
        self.assertTrue(path.is_file())
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual("LanCmdAgent", data["run_value"])
        self.assertTrue(data["journal"].endswith(".jsonl"))
        status = self.manager.status()
        self.assertEqual(str(self.root), status["root"])
        pending = self.manager.pending()
        self.assertEqual(1, len(pending))
        self.assertEqual("rename", pending[0]["operation"])

    def test_finished_journal_is_not_pending(self):
        plan = self.manager.plan_delete({})
        journal = Path(plan["journal"])
        journal.parent.mkdir(parents=True, exist_ok=True)
        journal.write_text(json.dumps(dict(event="deleted")) + "\n", encoding="utf-8")
        self.assertEqual([], self.manager.pending())


class TunnelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tunnel = tunnel.Tunnel(self.root / "tunnel.json", self.root, boom, local_port=47823)

    def base(self, **fields):
        data = dict(tunnel.DEFAULT)
        data.update(server_addr="node.example.com", server_port=7000, token="secret",
                    remote_port=23456, local_port=47823)
        data.update(fields)
        return self.tunnel.validate(data)

    def test_render_ini_and_toml(self):
        data = self.base()
        ini = self.tunnel.render(data, False)
        self.assertIn("[common]", ini)
        self.assertIn("remote_port = 23456", ini)
        self.assertIn("tls_enable = true", ini)
        toml = self.tunnel.render(data, True)
        self.assertIn('serverAddr = "node.example.com"', toml)
        self.assertIn("[[proxies]]", toml)
        self.assertIn("remotePort = 23456", toml)
        self.assertIn('auth.token = "secret"', toml)

    def test_pasted_config_wins(self):
        data = self.base(config_text="[common]\nserver_addr = x\n")
        self.assertEqual("[common]\nserver_addr = x\n", self.tunnel.render(data, True))

    def test_validation(self):
        with self.assertRaises(ValueError):
            self.tunnel.validate(dict(tunnel.DEFAULT))
        with self.assertRaises(ValueError):
            self.base(remote_port=0)
        with self.assertRaises(ValueError):
            self.base(proxy="坏 名字")
        with self.assertRaises(ValueError):
            self.base(server_addr="")

    def test_merged_parses_form_values(self):
        data = self.tunnel.merged(dict(server_addr="a.b", server_port="7000", remote_port="23456",
                                       token="t", provider="custom", enabled=True))
        self.assertEqual(7000, data["server_port"])
        self.assertEqual(23456, data["remote_port"])
        self.assertEqual(47823, data["local_port"])
        self.assertTrue(data["enabled"])
        self.assertEqual("a.b:23456", self.tunnel.endpoint(data))

    def test_merged_keeps_stored_token_when_blank_or_masked(self):
        self.tunnel.cfg = self.base()
        for value in ("", "********"):
            self.assertEqual("secret", self.tunnel.merged(dict(token=value))["token"])
        self.assertEqual("new", self.tunnel.merged(dict(token="new"))["token"])

    def test_status_hides_token(self):
        self.tunnel.cfg = self.base()
        status = self.tunnel.status()
        self.assertEqual("********", status["config"]["token"])
        self.assertFalse(status["running"])
        self.assertEqual("node.example.com:23456", status["endpoint"])

    def test_helpers(self):
        self.assertEqual((0, 61, 0), tunnel.version_tuple("frpc version 0.61.0"))
        self.assertEqual("hello", tunnel.clean("\x1b[32mhello\x1b[0m\r"))


class AppControlTest(unittest.TestCase):
    def test_profiles_cover_labels(self):
        for key, profile in app_control.PROFILES.items():
            self.assertIn(key, ("seewo", "wps", "qq"))
            self.assertTrue(profile["match"])
            for action in profile["keys"]:
                self.assertIn(action, app_control.ACTION_LABELS)

    def test_profile_match(self):
        self.assertEqual("seewo", app_control.profile_of("EasiNote5.exe"))
        self.assertEqual("wps", app_control.profile_of("wpp.exe"))
        self.assertEqual("qq", app_control.profile_of("QQ.exe"))
        self.assertEqual("", app_control.profile_of("notepad.exe"))

    def test_parse_keys(self):
        self.assertEqual(["CTRL", "S"], app_control.parse_keys("ctrl+s"))
        self.assertEqual(["PAGEDOWN"], app_control.parse_keys(["pagedown"]))
        self.assertEqual([], app_control.parse_keys(""))
        with self.assertRaisesRegex(ValueError, "不认识"):
            app_control.parse_keys("HYPER")

    def test_override_merges_and_persists(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        control = app_control.AppControl(temp.name, boom)
        self.assertEqual("P", control.table("seewo")["pen"])
        control.request(dict(action="bind", profile="seewo", name="pen", keys="CTRL+P"), None)
        self.assertEqual("CTRL P", control.table("seewo")["pen"])
        again = app_control.AppControl(temp.name, boom)
        self.assertEqual("CTRL P", again.table("seewo")["pen"])

    def test_hidden_records_survive_restart_and_recover(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        control = app_control.AppControl(temp.name, boom)
        with control.lock:
            control.hidden["1234"] = dict(handle="1234", pid=99, title="窗口", name="app.exe",
                                          path="", profile="", hidden_at=0, client="c")
        control.save_hidden()
        again = app_control.AppControl(temp.name, boom)
        self.assertEqual(["1234"], list(again.hidden))
        result = again.recover()
        self.assertEqual(0, result["restored"])
        self.assertEqual({}, again.hidden)
        self.assertEqual("[]", again.hidden_path.read_text(encoding="utf-8"))

    def test_unknown_action_and_profile(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        control = app_control.AppControl(temp.name, boom)
        with self.assertRaisesRegex(ValueError, "未知的动作"):
            control.request(dict(action="bind", profile="seewo", name="dance", keys="A"), None)
        with self.assertRaisesRegex(ValueError, "未知的应用类型"):
            control.request(dict(action="bind", profile="other", name="pen", keys="A"), None)


class HelperRunTest(unittest.TestCase):
    """Run the detached helper itself: rename, delete, journal, self-cleanup."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "LanCmd-Windows"
        self.root.mkdir()
        (self.root / "lan_command_agent.py").write_text("# agent\n", encoding="utf-8")
        (self.root / "python").mkdir()
        self.journal = Path(self.temp.name) / "journal.jsonl"

    def run_helper(self, **plan):
        payload = dict(operation="rename", root=str(self.root), target="", restart=False,
                       pid=0, max_wait=20, run_key=self_manage.RUN_KEY,
                       run_value=self_manage.RUN_VALUE, journal=str(self.journal))
        payload.update(plan)
        script = Path(self.temp.name) / "helper.py"
        script.write_text(self_manage.HELPER, encoding="utf-8")
        data = Path(self.temp.name) / "plan.json"
        data.write_text(json.dumps(payload), encoding="utf-8")
        result = subprocess.run([sys.executable, str(script), str(data)],
                                capture_output=True, timeout=120)
        return result, script, data

    def events(self):
        return [json.loads(line) for line in self.journal.read_text(encoding="utf-8").splitlines()
                if line.strip()]

    def test_rename_moves_folder_and_cleans_up(self):
        target = str(Path(self.temp.name) / "改名后")
        result, script, data = self.run_helper(target=target)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertTrue(Path(target).is_dir())
        self.assertFalse(self.root.exists())
        events = [item["event"] for item in self.events()]
        self.assertIn("renamed", events)
        self.assertNotIn("failed", events)
        self.assertNotIn("abort", events)
        self.assertFalse(script.exists())
        self.assertFalse(data.exists())

    def test_delete_removes_folder_and_config(self):
        config = Path(self.temp.name) / "config"
        config.mkdir()
        (config / "agent.json").write_text("{}", encoding="utf-8")
        result, script, _ = self.run_helper(operation="delete", target="",
                                            config=str(config), keep_config=False)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertFalse(self.root.exists())
        self.assertFalse(config.exists())
        self.assertFalse(script.exists())
        events = [item["event"] for item in self.events()]
        self.assertIn("deleted", events)
        self.assertNotIn("failed", events)

    def test_keep_config_keeps_the_folder(self):
        config = Path(self.temp.name) / "config"
        config.mkdir()
        result, _, _ = self.run_helper(operation="delete", target="",
                                       config=str(config), keep_config=True)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        self.assertFalse(self.root.exists())
        self.assertTrue(config.is_dir())


if __name__ == "__main__":
    unittest.main()

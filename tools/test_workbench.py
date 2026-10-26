import ctypes
import io
import json
import os
from pathlib import Path
import queue
import stat
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
import lan_command_agent as agent
from file_actions import file_request, extract_zip, preview
from desktop_apps import apps_request, DesktopApps
from command_queue import CommandQueue
from touch_guard import recovery_worker, TouchDevices
from input_guard import NativeInputGuard


class FilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, entries):
        path = self.root / "test.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries: archive.writestr(name, data)
        return path

    def test_text_image_and_archive_preview_are_bounded(self):
        text = self.root / "text.txt"; text.write_text("你好" * 100000, encoding="utf-8")
        result = preview(text)
        self.assertEqual("text", result["kind"]); self.assertTrue(result["truncated"])
        self.assertTrue(result["text"].startswith("你好"))
        image = self.root / "tiny.png"; image.write_bytes(b"\x89PNG\r\n\x1a\n" + b"data")
        self.assertEqual("image", preview(image)["kind"])
        archive = self.archive([("one/a.txt", "test")])
        self.assertEqual("one/a.txt", preview(archive)["items"][0]["name"])

    def test_utf16_and_binary_detection(self):
        text = self.root / "text.txt"; text.write_bytes("Windows 文本".encode("utf-16"))
        self.assertEqual("Windows 文本", preview(text)["text"])
        text.write_bytes(b"MZ\0\x01\x02")
        with self.assertRaisesRegex(ValueError, "不支持"): preview(text)

    def test_extract_new_folder_and_never_overwrite_existing(self):
        source = self.archive([("docs/", ""), ("docs/a.txt", "abc")])
        dest = self.root / "result"
        result = extract_zip(source, dest)
        self.assertEqual("abc", (dest / "docs/a.txt").read_text())
        self.assertEqual(3, result["bytes"])
        with self.assertRaisesRegex(ValueError, "目标已存在"): extract_zip(source, dest)

    def test_traversal_symlinks_and_windows_collisions_rejected_before_writing(self):
        link = zipfile.ZipInfo("link"); link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for entries in ([('../escape', 'x')], [('..\\escape', 'x')], [('C:/x','x')], [('CON.txt','x')],
                        [('a.txt','one'),('A.TXT','two')], [('a','file'),('a/x','x')], [(link,'../escape')]):
            with self.subTest(entries=entries):
                source = self.archive(entries); dest = self.root / "new"
                with self.assertRaises(ValueError): extract_zip(source, dest)
                self.assertFalse(dest.exists())
                self.assertFalse(any(self.root.glob('.lancmd-extract-*')))

    def test_open_is_explicit_and_uses_exact_path_without_shell_interpolation(self):
        path = self.root / "test & echo.exe"; path.write_bytes(b"x")
        with mock.patch("file_actions.checked_path", return_value=path), mock.patch("file_actions.os.name", "nt"), \
             mock.patch("file_actions.os.startfile", create=True) as launch:
            result = file_request({"action":"open", "path":str(path)}, agent.RpcError)
            launch.assert_called_once_with(str(path), "open")
            self.assertEqual(str(path), result["opened"])


class AppTest(unittest.TestCase):
    def test_window_actions_require_current_handle_and_pid(self):
        backend = SimpleNamespace(windows=mock.Mock(return_value=[{"handle":"12", "pid":44}]), act=mock.Mock())
        with mock.patch("desktop_apps.os.name", "nt"):
            apps_request({"action":"activate","handle":"12","pid":44}, agent.RpcError, backend)
            backend.act.assert_called_once_with(12, "activate")
            for q in ({"action":"close","handle":"12","pid":45}, {"action":"close","handle":"13","pid":44},
                      {"action":"kill","handle":"12","pid":44}):
                with self.assertRaises(agent.RpcError): apps_request(q, agent.RpcError, backend)
            self.assertEqual(1, backend.act.call_count)


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.execute = mock.Mock(return_value={"code":0,"out":"ok"})
        self.queue = CommandQueue(self.execute); self.addCleanup(self.queue.close)
        self.owner = SimpleNamespace(client_id="phone")

    def request(self, **q): return self.queue.request(q, self.owner, agent.RpcError)
    def wait(self, check):
        end = time.monotonic() + 4
        while time.monotonic() < end:
            if check(): return
            time.sleep(.01)
        self.fail("Queue did not settle")

    def test_list_runs_in_order_and_delays_after_previous_completion(self):
        done = []; gate = threading.Event()
        def execute(line, shell):
            if line == "first": gate.wait(2)
            done.append((line,time.monotonic())); return {"code":0}
        self.queue.execute = execute
        result = self.request(action="add", items=[{"line":"first"}, {"line":"second","delay":1}])
        time.sleep(.1); self.assertEqual([], done)
        gate.set(); self.wait(lambda: len(done) == 2)
        self.assertGreaterEqual(done[1][1] - done[0][1], .95)
        self.wait(lambda: "finished" in self.request()["batches"][0])
        self.assertEqual(["completed","completed"], [i["status"] for i in self.request()["batches"][0]["items"]])
        self.assertTrue(result["batch"]["id"])

    def test_cancel_waiting_keeps_commands_from_starting(self):
        batch = self.request(action="add", items=[{"line":"later","delay":3}])["batch"]
        self.request(action="cancel", batch_id=batch["id"])
        self.wait(lambda: "finished" in self.request()["batches"][0])
        self.execute.assert_not_called()
        self.assertEqual("cancelled", self.request()["batches"][0]["items"][0]["status"])

    def test_cancel_running_does_not_claim_to_undo_started_command(self):
        gate = threading.Event(); started = threading.Event(); calls = []
        def execute(line, shell):
            calls.append(line); started.set(); gate.wait(2); return {"code":0}
        self.queue.execute = execute
        batch = self.request(action="add",items=[{"line":"running"},{"line":"never"}])["batch"]
        self.assertTrue(started.wait(1))
        self.request(action="cancel",batch_id=batch["id"]); gate.set()
        self.wait(lambda: "finished" in self.request()["batches"][0])
        self.assertEqual(["running"],calls)

    def test_ownership_and_validation(self):
        batch=self.request(action="add",items=[{"line":"later","delay":3}])["batch"]
        other=SimpleNamespace(client_id="other")
        self.assertEqual([],self.queue.request({},other,agent.RpcError)["batches"])
        with self.assertRaises(agent.RpcError): self.queue.request({"action":"cancel","batch_id":batch["id"]},other,agent.RpcError)
        for items in ([],[{"line":"x","delay":True}],[{"line":"x","delay":-1}],[{"line":"x","shell":"bash"}],[{"line":""}]):
            with self.subTest(items=items),self.assertRaises(agent.RpcError): self.request(action="add",items=items)


class Pipe:
    def __init__(self): self.lines=queue.Queue()
    def __iter__(self): return self
    def __next__(self):
        value=self.lines.get()
        if value is None: raise StopIteration()
        return value


class TouchRecoveryTest(unittest.TestCase):
    def test_driver_wait_precedes_hook_install_and_hooks_release_before_driver_restore(self):
        events=[]
        native=NativeInputGuard.__new__(NativeInputGuard)
        native.escape=threading.Event(); native.hooks=[]; native.pressed=set()
        native.keyboard_proc=object(); native.mouse_proc=object()
        native.touch=SimpleNamespace(start=lambda:events.append("touch-start"),stop=lambda:events.append("touch-stop"))
        native.kernel=SimpleNamespace(GetModuleHandleW=lambda name:1)
        native.user=SimpleNamespace(GetAsyncKeyState=lambda key:0,
            SetWindowsHookExW=lambda kind,*args:events.append("hook-%d"%kind) or kind,
            UnhookWindowsHookEx=lambda hook:events.append("unhook-%d"%hook) or True)
        self.assertTrue(native.block(True)); self.assertTrue(native.block(False))
        self.assertEqual(["touch-start","hook-13","hook-14","unhook-13","unhook-14","touch-stop"],events)

    def run_worker(self, failure=False, watchdog=.25):
        stream=Pipe(); output=io.StringIO(); restored=threading.Event()
        devices=SimpleNamespace(pause=mock.Mock(side_effect=RuntimeError("partial failure") if failure else lambda stop:1),
                                restore=mock.Mock(side_effect=lambda: restored.set() or []))
        thread=threading.Thread(target=recovery_worker,args=(devices,stream,output,watchdog),daemon=True)
        thread.start()
        end=time.monotonic()+2
        while not output.getvalue() and time.monotonic()<end: time.sleep(.01)
        return stream,output,restored,thread,devices

    def test_parent_pipe_close_restores_touch(self):
        stream,out,restored,thread,devices=self.run_worker()
        self.assertTrue(json.loads(out.getvalue().splitlines()[0])["ok"])
        stream.lines.put(None); self.assertTrue(restored.wait(2)); thread.join(2)
        devices.restore.assert_called_once()

    def test_missing_heartbeat_restores_even_if_parent_remains_alive(self):
        stream,out,restored,thread,devices=self.run_worker()
        self.assertTrue(restored.wait(2)); thread.join(2); stream.lines.put(None)

    def test_partial_disable_failure_always_restores(self):
        stream,out,restored,thread,devices=self.run_worker(failure=True)
        self.assertFalse(json.loads(out.getvalue().splitlines()[0])["ok"])
        self.assertTrue(restored.wait(2)); thread.join(2); stream.lines.put(None)

    def test_device_disable_is_temporary_no_ui_and_preserves_pre_disabled_devices(self):
        devices=TouchDevices.__new__(TouchDevices)
        devices.w=SimpleNamespace(DWORD=ctypes.c_ulong,ULONG=ctypes.c_ulong)
        devices.disabled=[]; devices.inventory=lambda:["active","already-disabled"]
        devices.node=lambda instance:1 if instance=="active" else 2
        def status(status,problem,node,flags): status._obj.value=8 if node==1 else 0; return 0
        devices.cm=SimpleNamespace(CM_Get_DevNode_Status=status,CM_Disable_DevNode=mock.Mock(return_value=0),CM_Enable_DevNode=mock.Mock(return_value=0))
        self.assertEqual(1,devices.pause(threading.Event()))
        devices.cm.CM_Disable_DevNode.assert_called_once_with(1,4)
        self.assertEqual([],devices.restore()); devices.cm.CM_Enable_DevNode.assert_called_once_with(1,0)


@unittest.skipUnless(os.name=="nt","Windows API")
class NativeAppsTest(unittest.TestCase):
    def test_desktop_windows_can_be_read_without_changing_them(self):
        self.assertIsInstance(DesktopApps().windows(),list)


if __name__=="__main__": unittest.main()

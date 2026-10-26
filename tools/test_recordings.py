import json
import logging
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import wave

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "windows"))
from recordings import Recordings
from gestures import Gestures, validate_events
from remote_features import RemoteFeatures


class Error(Exception):
    def __init__(self, message, code=1): super().__init__(message)


def settle(condition, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition(): return
        time.sleep(.01)
    raise AssertionError("worker did not settle")


class RecordingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.argv = []; self.procs = []
        self.manager = Recordings(self.temp.name, "unused.exe", self.spawn)
        self.addCleanup(self.manager.close)
        self.owner = SimpleNamespace(client_id="phone")

    def spawn(self, args, **kw):
        self.argv.append(args)
        script = "import sys,json,wave; p=sys.argv[1]; print(json.dumps({'ready':True}),flush=True); sys.stdin.read(); w=wave.open(p,'wb'); w.setparams((1,2,48000,0,'NONE','')); w.writeframes(b'\\0'*960); w.close(); print(json.dumps({'finished':True,'reason':'stopped'}),flush=True)"
        proc = subprocess.Popen([sys.executable, "-u", "-c", script, args[3]], **kw)
        self.procs.append(proc)
        return proc

    def call(self, **q): return self.manager.request(q, self.owner, Error)
    def tearDown(self):
        self.manager.close()
        for p in self.procs:
            try: p.wait(timeout=3)
            except subprocess.TimeoutExpired: p.kill(); p.wait()
        settle(lambda: all(j["data"]["status"] not in ("starting", "recording", "stopping") for j in self.manager.jobs.values()))

    def test_explicit_start_stop_finalizes_file_and_keeps_arguments_separate(self):
        item = self.call(action="start", mode="audio", seconds=30)["recording"]
        self.assertEqual("recording", item["status"])
        self.assertEqual(["record", "audio", item["path"], "-1", "0", "30", "1"], self.argv[0][1:])
        self.call(action="stop", id=item["id"])
        settle(lambda: self.call()["recordings"][0]["status"] == "completed")
        with wave.open(item["path"]) as wav: self.assertEqual(48000, wav.getframerate())
        self.assertNotIn("owner", self.call()["recordings"][0])
        self.call(action="delete", id=item["id"])
        self.assertFalse(Path(item["path"]).exists())

    def test_mic_conflicts_and_ownership_never_stop_other_phone(self):
        item = self.call(action="start", mode="audio")["recording"]
        with self.assertRaises(Error): self.call(action="start", mode="screen", audio=True)
        with self.assertRaises(Error): self.call(action="delete", id=item["id"])
        other = SimpleNamespace(client_id="other")
        self.assertEqual([], self.manager.request({}, other, Error)["recordings"])
        with self.assertRaises(Error): self.manager.request({"action":"stop","id":item["id"]}, other, Error)

    def test_invalid_parameters_do_not_launch_any_process(self):
        for q in ({"mode":"bad"}, {"mode":"audio","seconds":True}, {"mode":"screen","seconds":3601},
                  {"mode":"audio","audio":False}, {"mode":"camera","camera":-1}, {"mode":"audio","microphone":False}):
            with self.subTest(q=q), self.assertRaises(Error): self.call(action="start", **q)
        self.assertEqual([], self.argv)

    def test_restart_recovers_interrupted_metadata_without_trusting_saved_paths(self):
        self.manager._load()
        rid = "a" * 32
        (Path(self.temp.name) / (rid + ".json")).write_text(json.dumps(dict(id=rid,mode="audio",path="C:/outside.wav",status="recording",owner="phone",created=1)))
        recovered = Recordings(self.temp.name, "unused.exe", self.spawn)
        item = recovered.request({}, self.owner, Error)["recordings"][0]
        self.assertEqual("interrupted", item["status"])
        self.assertEqual(str(Path(self.temp.name) / (rid + ".wav")), item["path"])

    def test_receiver_close_finalizes_active_recording(self):
        self.call(action="start", mode="audio")
        self.manager.close()
        settle(lambda: self.call()["recordings"][0]["status"] == "completed")

    def test_device_failure_is_reported_and_never_recorded_as_success(self):
        def denied(args, **kw):
            proc = subprocess.Popen([sys.executable,"-u","-c","import json; print(json.dumps({'error':'privacy permission denied'})); raise SystemExit(1)"], **kw)
            self.procs.append(proc)
            return proc
        self.manager.popen = denied
        with self.assertRaisesRegex(Error, "privacy permission denied"):
            self.call(action="start", mode="audio")
        self.assertEqual("failed",self.call()["recordings"][0]["status"])


class PointerBackend:
    def __init__(self): self.events = []; self.released = []
    def pointer(self, action, x, y, button, delta, allowed):
        if not allowed(): raise RuntimeError("cancelled")
        self.events.append((action, time.monotonic(), x, y))
    def release_mouse(self, button): self.released.append(button)


class GestureTest(unittest.TestCase):
    def setUp(self):
        self.backend = PointerBackend(); self.remote = RemoteFeatures(Error, logging.getLogger("test"), "", self.backend)
        self.state = SimpleNamespace(client_id="phone", peer="127.0.0.1")
        self.sid = self.remote.session({"scope":"input"}, self.state)["sid"]
        self.gestures = Gestures(self.remote)
        self.addCleanup(self.remote.close, self.state)
    def call(self, **q): return self.gestures.request(dict(q, sid=self.sid), self.state, Error)
    def test_invalid_events_and_unbalanced_drag_are_rejected(self):
        for events in ([],[{"ms":True,"action":"move","x":0,"y":0}], [{"ms":0,"action":"move","x":float("nan"),"y":0}],
                       [{"ms":0,"action":"down","x":0,"y":0}], [{"ms":0,"action":"up","x":0,"y":0}]):
            with self.assertRaises(ValueError): validate_events(events)
    def test_timing_reservation_and_normal_release(self):
        events = [{"ms":0,"action":"down","x":.2,"y":.3}, {"ms":150,"action":"move","x":.5,"y":.5}, {"ms":250,"action":"up","x":.8,"y":.7}]
        self.call(action="play", events=events)
        with self.assertRaises(Error): self.remote.pointer(dict(action="move",x=0,y=0,sid=self.sid),self.state)
        settle(lambda: self.call()["job"]["status"] == "completed")
        self.assertEqual(["down","move","up"], [e[0] for e in self.backend.events])
        self.assertGreaterEqual(self.backend.events[-1][1] - self.backend.events[0][1], .23)
        self.assertFalse(self.remote.pointer_owners); self.assertIsNone(self.remote.playback_owner)
    def test_delay_cancel_and_disconnect_release_held_button(self):
        self.call(action="play", delay=1, events=[{"ms":0,"action":"move","x":0,"y":0}])
        self.call(action="cancel")
        settle(lambda: self.call()["job"]["status"] == "cancelled")
        self.assertEqual([], self.backend.events)
        self.call(action="play", events=[{"ms":0,"action":"down","x":0,"y":0},{"ms":2000,"action":"up","x":1,"y":1}])
        settle(lambda: bool(self.backend.events))
        self.remote.close(self.state)
        settle(lambda: self.remote.playback_owner is None)
        self.assertIn("left", self.backend.released)
        self.assertEqual(["down"], [e[0] for e in self.backend.events])

    def test_replayed_events_cannot_renew_the_phone_heartbeat(self):
        session = self.state.remote_sessions[self.sid]
        before = session.last_active = time.monotonic() - 1
        self.remote.playback_owner = self.sid
        self.remote.pointer(dict(action="move",x=.5,y=.5,sid=self.sid),self.state,playback=True)
        self.assertEqual(before,session.last_active)
        self.remote.playback_owner = None

    def test_initial_delay_is_timed_on_the_receiver(self):
        started = time.monotonic()
        self.call(action="play", delay=1, events=[{"ms":0,"action":"move","x":.25,"y":.75}])
        time.sleep(.05)
        self.assertEqual([],self.backend.events)
        settle(lambda:self.call()["job"]["status"] == "completed")
        self.assertGreaterEqual(self.backend.events[0][1]-started,.95)


@unittest.skipUnless(os.name == "nt", "native Media Foundation")
class NativeEncoderTest(unittest.TestCase):
    def test_mp4_audio_video_encode_decode_and_wav_header(self):
        with tempfile.TemporaryDirectory(prefix="LanCmd 录制 ") as path:
            result = subprocess.run([str(ROOT / "windows" / "LanCmdRecord.exe"), "self-test", path], capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace"))
            self.assertTrue(json.loads(result.stdout)["self_test"])
            with wave.open(str(Path(path) / "test.wav")) as wav:
                self.assertEqual((1,2,48000,96000), (wav.getnchannels(),wav.getsampwidth(),wav.getframerate(),wav.getnframes()))
            data = (Path(path) / "test.mp4").read_bytes()
            self.assertIn(b"ftyp", data[:64]); self.assertIn(b"moov", data); self.assertIn(b"avc1", data); self.assertIn(b"mp4a", data)
    def test_device_enumeration_does_not_start_capture(self):
        result = subprocess.run([str(ROOT / "windows" / "LanCmdRecord.exe"), "devices"], capture_output=True, timeout=15)
        self.assertEqual(0,result.returncode,result.stdout.decode("utf-8","replace"))
        devices = json.loads(result.stdout)
        self.assertIsInstance(devices["microphones"],list); self.assertIsInstance(devices["cameras"],list)


if __name__ == "__main__": unittest.main()

"""Explicit native recordings with bounded duration, local files and owner checks.

The helper never changes Windows privacy permissions or device indicators.
Closing its stdin asks it to finalize, including when this receiver exits.
"""
import atexit
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import uuid

ACTIVE = {"starting", "recording", "stopping"}


class Recordings:
    def __init__(self, root, executable, popen=None):
        self.root = Path(root)
        self.executable = str(executable)
        self.popen = popen or subprocess.Popen
        self.lock = threading.RLock()
        self.jobs = {}
        self.loaded = False
        atexit.register(self.close)

    def _load(self):
        if self.loaded:
            return
        self.root.mkdir(parents=True, exist_ok=True)
        for path in sorted(self.root.glob("*.json"))[-1000:]:
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                rid = data["id"]
                if uuid.UUID(rid).hex != rid or path.stem != rid or data["mode"] not in ("audio", "screen", "camera"):
                    continue
                # Never trust a path persisted in JSON for download/delete.
                data["path"] = str(self.root / (rid + (".wav" if data["mode"] == "audio" else ".mp4")))
                if data["status"] in ACTIVE:
                    data.update(status="interrupted", error="接收端曾退出；录制已停止，请检查文件是否完整")
                self.jobs[rid] = {"data": data, "process": None}
            except (OSError, ValueError, KeyError, TypeError):
                continue
        self.loaded = True

    def _save(self, job):
        data = job["data"]
        path = self.root / (data["id"] + ".json")
        temp = path.with_suffix(".json.tmp")
        temp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, path)

    def _public(self, job):
        data = dict(job["data"])
        data.pop("owner", None)
        try:
            data["size"] = Path(data["path"]).stat().st_size
        except OSError:
            data["size"] = 0
        return data

    def request(self, q, state, error):
        try:
            if os.name != "nt" and self.popen is subprocess.Popen:
                raise ValueError("录制仅支持 Windows 接收端")
            if not state.client_id:
                raise ValueError("连接身份无效")
            action = q.get("action", "list")
            if action == "devices":
                result = subprocess.run([self.executable, "devices"], stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, creationflags=0x08000000)
                if result.returncode:
                    raise ValueError("无法读取录制设备，请检查 Windows 隐私权限及 Media Feature Pack")
                return json.loads(result.stdout.decode("utf-8"))
            with self.lock:
                self._load()
                if action == "list":
                    jobs = sorted((j for j in self.jobs.values() if j["data"]["owner"] == state.client_id),
                                  key=lambda j: j["data"]["created"], reverse=True)
                    return {"recordings": [self._public(j) for j in jobs[:200]], "directory": str(self.root)}
                if action == "start":
                    job = self._start(q, state.client_id)
                elif action in ("stop", "delete"):
                    job = self.jobs.get(str(q.get("id", "")))
                    if not job or job["data"]["owner"] != state.client_id:
                        raise ValueError("录制不存在或属于其他手机")
                    if action == "delete":
                        if job["data"]["status"] in ACTIVE:
                            raise ValueError("请先停止录制并等待文件封装完成")
                        Path(job["data"]["path"]).unlink(missing_ok=True)
                        (self.root / (job["data"]["id"] + ".json")).unlink(missing_ok=True)
                        self.jobs.pop(job["data"]["id"])
                        return {"deleted": True}
                    self._stop(job)
                    return {"recording": self._public(job)}
                else:
                    raise ValueError("无效的录制操作")
            # Opening a camera/encoder can take time; never hold the manager lock.
            if not job["ready"].wait(18):
                with self.lock:
                    self._stop(job)
                raise ValueError("录制设备启动超时，已请求停止；请刷新检查结果")
            with self.lock:
                if job["data"]["status"] != "recording":
                    raise ValueError(job["data"].get("error", "录制未能开始"))
                return {"recording": self._public(job)}
        except (ValueError, OSError, subprocess.SubprocessError) as e:
            raise error(str(e))

    def _start(self, q, owner):
        mode = q.get("mode")
        seconds, microphone, camera = q.get("seconds", 300), q.get("microphone", -1), q.get("camera", 0)
        audio = q.get("audio", mode == "audio")
        if mode not in ("audio", "screen", "camera") or not isinstance(audio, bool) or (mode == "audio" and not audio):
            raise ValueError("请选择录音、屏幕或摄像头模式")
        for name, value, low, high in (("时长", seconds, 1, 3600), ("麦克风", microphone, -1, 1023), ("摄像头", camera, 0, 31)):
            if type(value) is not int or not low <= value <= high:
                raise ValueError(name + "参数无效；录制时长为 1–3600 秒")
        active = [j["data"] for j in self.jobs.values() if j["data"]["status"] in ACTIVE]
        if len(active) >= 3 or any(d["mode"] == mode or (d["audio"] and audio) for d in active):
            raise ValueError("录制资源正在使用，请先停止使用相同画面或麦克风的录制")
        if sum(d["owner"] == owner for d in (j["data"] for j in self.jobs.values())) >= 200:
            raise ValueError("最多保留 200 条录制，请先删除旧录制")
        if shutil.disk_usage(self.root).free < 200 * 1024 * 1024:
            raise ValueError("录制磁盘至少需要 200 MB 可用空间")
        rid = uuid.uuid4().hex
        path = self.root / (rid + (".wav" if mode == "audio" else ".mp4"))
        data = dict(id=rid, mode=mode, audio=audio, microphone=microphone, camera=camera, seconds=seconds,
                    path=str(path), owner=owner, created=time.time(), status="starting")
        job = {"data": data, "ready": threading.Event(), "process": None, "stop_requested": False}
        self._save(job)  # A recording cannot start if its recovery metadata cannot be saved.
        try:
            job["process"] = self.popen([self.executable, "record", mode, str(path),
                str(microphone if audio else -2), str(camera), str(seconds), "1"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                creationflags=0x08000000 if os.name == "nt" else 0, close_fds=True)
        except OSError:
            (self.root / (rid + ".json")).unlink(missing_ok=True)
            raise
        self.jobs[rid] = job
        threading.Thread(target=self._watch, args=(job,), daemon=True, name="recording-monitor").start()
        threading.Thread(target=self._deadline, args=(job,), daemon=True, name="recording-deadline").start()
        return job

    def _watch(self, job):
        proc, data = job["process"], job["data"]
        try:
            for line in proc.stdout:
                if len(line) > 4096:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                with self.lock:
                    if event.get("ready") and not job["stop_requested"]:
                        data.update(status="recording", started=time.time())
                        self._save(job)
                        job["ready"].set()
                    if event.get("error"):
                        data["error"] = str(event["error"])[:1000]
                    if event.get("finished"):
                        data["reason"] = str(event.get("reason", "stopped"))
                        job["finished_event"] = True
            code = proc.wait()
            with self.lock:
                data["status"] = "completed" if code == 0 and job.get("finished_event") and Path(data["path"]).is_file() else "failed"
                if data["status"] == "failed":
                    data.setdefault("error", "录制未正常完成，文件可能无法播放；请检查设备、隐私权限和系统媒体组件")
                data["finished"] = time.time()
                self._save(job)
        except Exception as e:
            with self.lock:
                data.update(status="failed", error=str(e)[:1000], finished=time.time())
                self._stop(job)
        finally:
            job["ready"].set()
            if proc.stdout:
                proc.stdout.close()
            if proc.stdin and not proc.stdin.closed:
                proc.stdin.close()

    def _deadline(self, job):
        proc = job["process"]
        # Also bounds blocked drivers and broken encoders, not just normal loops.
        try:
            proc.wait(timeout=job["data"]["seconds"] + 25)
        except subprocess.TimeoutExpired:
            with self.lock:
                self._stop(job)

    def _stop(self, job):
        proc = job["process"]
        if not proc or proc.poll() is not None or job.get("stop_requested"):
            return
        job["stop_requested"] = True
        if job["data"]["status"] in ACTIVE:
            job["data"]["status"] = "stopping"
        try:
            proc.stdin.close()
        except (OSError, ValueError):
            pass
        def force_if_blocked():
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                # Do not claim a forcibly interrupted MP4 is playable.
                with self.lock:
                    job["data"]["error"] = "设备未及时停止，已终止采集；文件可能不完整"
                proc.kill()
        threading.Thread(target=force_if_blocked, daemon=True).start()

    def close(self):
        with self.lock:
            for job in self.jobs.values():
                self._stop(job)

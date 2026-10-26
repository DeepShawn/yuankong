"""Timed pointer replay on the existing revocable input session."""
import math
import threading
import time
import uuid


def validate_events(events):
    if not isinstance(events, list) or not 1 <= len(events) <= 6000:
        raise ValueError("手势需包含 1–6000 个事件")
    result, previous, down = [], -1, False
    for event in events:
        if not isinstance(event, dict):
            raise ValueError("手势事件格式无效")
        ms, action, x, y = (event.get(k) for k in ("ms", "action", "x", "y"))
        if type(ms) is not int or not previous <= ms <= 300000 or ms < 0:
            raise ValueError("手势时间需递增且不超过 300 秒")
        if action not in ("move", "down", "up") or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in (x, y)):
            raise ValueError("手势坐标或动作无效")
        if action == "down":
            if down:
                raise ValueError("手势包含重复按下")
            down = True
        if action == "up":
            if not down:
                raise ValueError("手势包含未按下的释放")
            down = False
        result.append(dict(ms=ms, action=action, x=float(x), y=float(y), button="left"))
        previous = ms
    if down:
        raise ValueError("手势缺少抬起事件，请先结束记录")
    return result


class Gestures:
    def __init__(self, remote):
        self.remote = remote
        self.lock = threading.RLock()
        self.job = None

    def request(self, q, state, error):
        if q.get("action") == "dimensions":
            self.remote._check(state)
            size = getattr(self.remote.backend, "desktop_size", lambda: (16, 9))()
            return {"width": size[0], "height": size[1]}
        session = self.remote.active(q, state, "input")
        action = q.get("action", "status")
        with self.lock:
            job = self.job
            visible = job and job["session"] is session
            if action == "status":
                size = getattr(self.remote.backend, "desktop_size", lambda: (16, 9))()
                return {"job": dict(job["data"]) if visible else None, "width": size[0], "height": size[1]}
            if action == "cancel":
                if visible:
                    job["cancel"].set()
                return {"cancelled": bool(visible)}
            if action != "play":
                raise error("无效的手势操作")
            try:
                events = validate_events(q.get("events"))
                delay = q.get("delay", 0)
                if type(delay) is not int or not 0 <= delay <= 86400:
                    raise ValueError("延迟秒数需为 0–86400")
            except ValueError as e:
                raise error(str(e))
            with self.remote.pointer_lock:
                if self.remote.playback_owner is not None or self.remote.pointer_owners:
                    raise error("鼠标正在使用，请先结束拖动或手势回放")
                self.remote.playback_owner = session.sid
            job = {"session": session, "cancel": threading.Event(), "data": dict(id=uuid.uuid4().hex,
                status="waiting", due=time.time() + delay, count=len(events), index=0)}
            self.job = job
            threading.Thread(target=self._play, args=(job, events, delay, state), daemon=True, name="gesture-playback").start()
            return {"job": dict(job["data"])}

    def _play(self, job, events, delay, state):
        session = job["session"]
        def alive():
            return session.alive() and time.monotonic() - session.last_active < 12 and not job["cancel"].is_set()
        def wait_until(deadline):
            while alive():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return True
                job["cancel"].wait(min(.05, remaining))
            return False
        try:
            if not wait_until(time.monotonic() + delay):
                return
            job["data"]["status"] = "playing"
            start = time.monotonic()
            for index, event in enumerate(events):
                if not wait_until(start + event["ms"] / 1000):
                    return
                self.remote.pointer(dict(event, sid=session.sid), state, playback=True, allowed=alive)
                job["data"]["index"] = index + 1
            job["data"]["status"] = "completed"
        except Exception as e:
            job["data"].update(status="failed", error=str(e)[:500])
        finally:
            with self.remote.pointer_lock:
                self.remote._release_pointer(session)
                if self.remote.playback_owner == session.sid:
                    self.remote.playback_owner = None
            if job["data"]["status"] in ("waiting", "playing"):
                job["data"]["status"] = "cancelled"
            job["data"]["finished"] = time.time()

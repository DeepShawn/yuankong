"""Local keyboard/mouse pause with a physical escape chord and disconnect watchdog.

Low-level hooks let the physical escape chord remain observable while suppressing
normal input. They never alter drivers, the secure desktop or Ctrl+Alt+Del.
"""

import ctypes
import queue
import threading
from concurrent.futures import Future

INPUT_TAG = 0x4C434D44


class InputGuard:
    def __init__(self, native=None):
        self.native = native
        self.queue = queue.Queue()
        self.owner = None
        self.ready = Future()
        self.thread = threading.Thread(target=self._run, name="lancmd-local-input", daemon=True)
        self.thread.start()
        self.ready.result(timeout=5)

    def invoke(self, callback):
        if threading.current_thread() is self.thread:
            return callback()
        if not self.thread.is_alive():
            raise RuntimeError("本机输入控制线程已结束")
        future = Future()
        self.queue.put((callback, future))
        return future.result(timeout=10)

    def _release(self):
        if self.owner is not None:
            self.native.block(False)
        self.owner = None

    def pause(self, owner, allowed):
        def start():
            if not allowed():
                raise RuntimeError("控制会话已结束")
            if self.owner is not None:
                raise RuntimeError("本机输入已暂停，请先手动解除")
            if not self.native.block(True):
                raise RuntimeError("Windows 拒绝暂停本机输入，请在电脑端以管理员身份启动接收端")
            self.owner = owner
            self.allowed = allowed
            return {"paused": True, "remaining": None}
        return self.invoke(start)

    def release(self, owner):
        def stop():
            if self.owner == owner:
                self._release()
            return {"paused": self.owner == owner, "remaining": 0}
        return self.invoke(stop)

    def status(self, owner):
        return self.invoke(lambda: {"paused": self.owner == owner,
            "remaining": None if self.owner == owner else 0})

    def _run(self):
        try:
            self.native = self.native or NativeInputGuard()
        except Exception as exc:
            self.ready.set_exception(exc)
            return
        self.ready.set_result(None)
        try:
            while True:
                if self.owner is not None:
                    if not self.allowed() or self.native.escape.is_set():
                        self._release()
                    else:
                        # Low-level hooks need a message pump even without a UI.
                        self.native.pump()
                try:
                    callback, future = self.queue.get(timeout=0.05)
                except queue.Empty:
                    continue
                if callback is None:
                    self._release(); future.set_result(None); return
                try:
                    result = callback()
                except Exception as exc:
                    future.set_exception(exc)
                else:
                    future.set_result(result)
        finally:
            self._release()

    def close(self):
        future = Future()
        self.queue.put((None, future))
        future.result(timeout=10)
        self.thread.join(timeout=2)


class NativeInputGuard:
    def __init__(self):
        from ctypes import wintypes as w
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.GetModuleHandleW.argtypes = [w.LPCWSTR]
        self.kernel.GetModuleHandleW.restype = w.HMODULE
        self.user.PeekMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT, w.UINT]
        self.user.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
        self.user.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
        self.user.DispatchMessageW.restype = ctypes.c_ssize_t
        self.user.GetAsyncKeyState.argtypes = [ctypes.c_int]
        self.user.GetAsyncKeyState.restype = ctypes.c_short
        self.MSG = w.MSG
        self.escape = threading.Event()
        self.pressed = set()
        self.hooks = []
        self.proc_type = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, w.WPARAM, w.LPARAM)
        self.user.SetWindowsHookExW.argtypes = [ctypes.c_int, self.proc_type, w.HINSTANCE, w.DWORD]
        self.user.SetWindowsHookExW.restype = w.HHOOK
        self.user.UnhookWindowsHookEx.argtypes = [w.HHOOK]
        self.user.CallNextHookEx.argtypes = [w.HHOOK, ctypes.c_int, w.WPARAM, w.LPARAM]
        self.user.CallNextHookEx.restype = ctypes.c_ssize_t

        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", w.DWORD), ("scan", w.DWORD), ("flags", w.DWORD),
                        ("time", w.DWORD), ("extra", ctypes.c_size_t)]
        class Mouse(ctypes.Structure):
            _fields_ = [("point", w.POINT), ("data", w.DWORD), ("flags", w.DWORD),
                        ("time", w.DWORD), ("extra", ctypes.c_size_t)]

        def keyboard(code, message, address):
            if code < 0:
                return self.user.CallNextHookEx(None, code, message, address)
            event = ctypes.cast(address, ctypes.POINTER(Keyboard)).contents
            injected = bool(event.flags & 0x10)
            if injected and event.extra == INPUT_TAG:
                return self.user.CallNextHookEx(None, code, message, address)
            if not injected:
                if message in (0x100, 0x104):
                    self.pressed.add(event.vk)
                elif message in (0x101, 0x105):
                    self.pressed.discard(event.vk)
                if escape_pressed(self.pressed):
                    self.escape.set()
                # Begin release on the Ctrl+Alt prefix: secure attention can move
                # Delete to Winlogon before an ordinary desktop hook sees it.
                if self.pressed.intersection((0x11, 0xA2, 0xA3)) and self.pressed.intersection((0x12, 0xA4, 0xA5)):
                    self.escape.set()
            return 1

        def mouse(code, message, address):
            if code < 0:
                return self.user.CallNextHookEx(None, code, message, address)
            event = ctypes.cast(address, ctypes.POINTER(Mouse)).contents
            if event.flags & 1 and event.extra == INPUT_TAG:
                return self.user.CallNextHookEx(None, code, message, address)
            return 1

        self.keyboard_proc = self.proc_type(keyboard)
        self.mouse_proc = self.proc_type(mouse)

    def block(self, enabled):
        if not enabled:
            for hook in self.hooks:
                if not self.user.UnhookWindowsHookEx(hook):
                    raise RuntimeError("卸载本机输入钩子失败，控制线程将退出并恢复输入")
            self.hooks.clear()
            self.pressed.clear()
            return True
        self.escape.clear()
        self.pressed = {key for key in list(map(ord, "ARTEX")) + [0x2E, 0x11, 0xA2, 0xA3, 0x12, 0xA4, 0xA5]
                        if self.user.GetAsyncKeyState(key) & 0x8000}
        for kind, proc in ((13, self.keyboard_proc), (14, self.mouse_proc)):
            hook = self.user.SetWindowsHookExW(kind, proc, self.kernel.GetModuleHandleW(None), 0)
            if not hook:
                self.block(False)
                return False
            self.hooks.append(hook)
        return True

    def pump(self):
        msg = self.MSG()
        while self.user.PeekMessageW(ctypes.byref(msg), None, 0, 0, 1):
            self.user.TranslateMessage(ctypes.byref(msg))
            self.user.DispatchMessageW(ctypes.byref(msg))

def escape_pressed(pressed):
    return set(map(ord, "ARTEX")).issubset(pressed) and 0x2E in pressed and bool(pressed.intersection((0x11, 0xA2, 0xA3)))

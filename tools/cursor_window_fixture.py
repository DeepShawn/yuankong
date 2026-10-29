"""Interactive Windows-only cursor fixture. Never shipped in the receiver."""
import ctypes
from ctypes import wintypes as w
import json
import os
import queue
import sys
import threading
import subprocess

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'windows'))
from cursor_visibility import NativeCursors


class CursorWindow:
    def __init__(self, left=100):
        self.process = subprocess.Popen([sys.executable, os.path.abspath(__file__), str(left)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8')
        self.lines = queue.Queue()
        def read():
            try:
                for line in self.process.stdout:self.lines.put(line)
            finally:self.lines.put(None)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        try:self.initial = self.report()
        except Exception:
            self.close()
            raise

    def report(self, command=None):
        if command:
            self.process.stdin.write(command + '\n');self.process.stdin.flush()
        try:line = self.lines.get(timeout=5)
        except queue.Empty:raise AssertionError('Cursor fixture did not answer within 5 seconds')
        if line is None:raise AssertionError(self.process.stderr.read())
        return json.loads(line)

    def close(self):
        if not self.process.stdin.closed:self.process.stdin.close()
        try:self.process.wait(8)
        except subprocess.TimeoutExpired:
            self.process.kill();self.process.wait(5)
            raise AssertionError('The cursor test window stopped responding')
        finally:
            self.reader.join(1)
            self.process.stdout.close();self.process.stderr.close()

    def __enter__(self):return self
    def __exit__(self, *args):self.close()


def main():
    native = NativeCursors()
    user = native.user
    user.SetProcessDPIAware()
    user.GetCursor.restype = w.HANDLE
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetModuleHandleW.argtypes = [w.LPCWSTR]
    kernel.GetModuleHandleW.restype = w.HMODULE
    proc_type = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    class WindowClass(ctypes.Structure):
        _fields_ = [('style', w.UINT), ('proc', proc_type), ('class_extra', ctypes.c_int),
                   ('window_extra', ctypes.c_int), ('instance', w.HINSTANCE), ('icon', w.HANDLE),
                   ('cursor', w.HANDLE), ('brush', w.HANDLE), ('menu', w.LPCWSTR), ('name', w.LPCWSTR)]
    user.DefWindowProcW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
    user.DefWindowProcW.restype = ctypes.c_ssize_t
    user.RegisterClassW.argtypes = [ctypes.POINTER(WindowClass)]
    user.CreateWindowExW.argtypes = [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int,
                                    ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p]
    user.CreateWindowExW.restype = w.HWND
    user.ShowWindow.argtypes = [w.HWND, ctypes.c_int]
    user.SetForegroundWindow.argtypes = [w.HWND]
    user.GetForegroundWindow.restype = w.HWND
    user.PostMessageW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
    user.DestroyWindow.argtypes = [w.HWND]
    user.GetMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT]
    user.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
    user.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
    user.DispatchMessageW.restype = ctypes.c_ssize_t
    user.ShowCursor.argtypes = [w.BOOL]
    user.ShowCursor.restype = ctypes.c_int
    zero = (ctypes.c_ubyte * 128)()
    white = (ctypes.c_ubyte * 128)(*([255] * 128))
    custom = user.CreateCursor(None, 0, 0, 32, 32, zero, white)
    if not custom:raise ctypes.WinError(ctypes.get_last_error())
    requests = queue.SimpleQueue()
    moves = [0]
    left = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    def report():
        # Query the owning queue without ever increasing its existing count.
        # Headless runners may suppress hardware display even when this count
        # would show a cursor on a physical desktop.
        user.ShowCursor(False)
        display_count = user.ShowCursor(True)
        current = native._current()
        return {'visible': bool(current.flags & 1), 'flags': current.flags,
                'foreground': user.GetForegroundWindow() == window,
                'moves': moves[0], 'cursor': current.cursor, 'custom': custom,
                'selected': user.GetCursor(), 'display_count': display_count,
                'x': (left + 75 - user.GetSystemMetrics(76)) / user.GetSystemMetrics(78),
                'y': (175 - user.GetSystemMetrics(77)) / user.GetSystemMetrics(79)}
    def proc(hwnd, message, word, long):
        if message in (0x20, 0x200):  # Real WM_SETCURSOR / WM_MOUSEMOVE path.
            user.SetCursor(custom)
            if message == 0x200:moves[0] += 1
            return 1 if message == 0x20 else 0
        if message == 0x8001:
            command = requests.get()
            if command == 'focus':
                user.SetForegroundWindow(hwnd)
                user.SetCursorPos(left + 70, 170)
            # An app-owned cursor must stay hidden even when its UI sets it.
            user.SetCursor(custom)
            print(json.dumps(report()), flush=True)
            return 0
        if message == 2:
            user.PostQuitMessage(0)
            return 0
        return user.DefWindowProcW(hwnd, message, word, long)
    callback = proc_type(proc)
    instance = kernel.GetModuleHandleW(None)
    name = 'LanCmdCursorFixture' + str(os.getpid())
    klass = WindowClass(0, callback, 0, 0, instance, None, custom, None, None, name)
    if not user.RegisterClassW(ctypes.byref(klass)):raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, name, 'Cursor regression fixture', 0x00CF0000,
                                  left, 100, 300, 300, None, None, instance, None)
    if not window:raise ctypes.WinError(ctypes.get_last_error())
    shown = 0
    try:
        user.ShowWindow(window, 5)
        user.SetForegroundWindow(window)
        # Establish an actually visible baseline, not a conditional assertion
        # that silently passes on an already-hidden CI desktop.
        for _ in range(32):
            shown += 1
            if user.ShowCursor(True) >= 0:break
        user.SetCursorPos(left + 70, 170)
        user.SetCursor(custom)
        print(json.dumps(dict(report(), pid=os.getpid())), flush=True)
        def read():
            try:
                for line in sys.stdin:
                    command = line.strip()
                    if command == 'close':break
                    requests.put(command)
                    user.PostMessageW(window, 0x8001, 0, 0)
            finally:user.PostMessageW(window, 0x10, 0, 0)
        threading.Thread(target=read, daemon=True).start()
        message = w.MSG()
        while user.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
            user.TranslateMessage(ctypes.byref(message));user.DispatchMessageW(ctypes.byref(message))
    finally:
        for _ in range(shown):user.ShowCursor(False)
        user.SetCursor(native._load(32512))
        user.DestroyCursor(custom)


if __name__ == '__main__':main()

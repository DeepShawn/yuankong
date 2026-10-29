"""Interactive Windows-only cursor fixture. Never shipped in the receiver."""
import ctypes
from ctypes import wintypes as w
import json
import os
import queue
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'windows'))
from cursor_visibility import NativeCursors


def main():
    native = NativeCursors()
    user = native.user
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
    def report():
        current = native._current()
        return {'visible': bool(current.flags & 1), 'flags': current.flags,
                'foreground': user.GetForegroundWindow() == window,
                'moves': moves[0], 'cursor': current.cursor, 'custom': custom}
    def proc(hwnd, message, word, long):
        if message in (0x20, 0x200):  # Real WM_SETCURSOR / WM_MOUSEMOVE path.
            user.SetCursor(custom)
            if message == 0x200:moves[0] += 1
            return 1 if message == 0x20 else 0
        if message == 0x8001:
            command = requests.get()
            if command == 'focus':
                user.SetForegroundWindow(hwnd)
                user.SetCursorPos(170, 170)
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
                                  100, 100, 400, 300, None, None, instance, None)
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
        user.SetCursorPos(170, 170)
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

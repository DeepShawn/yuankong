"""Visible top-level application windows; actions revalidate the handle and PID."""
import ctypes
import os


class DesktopApps:
    def __init__(self):
        from ctypes import wintypes as w
        self.w = w
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.callback = ctypes.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)
        signatures = {
            "EnumWindows": ([self.callback, w.LPARAM], w.BOOL),
            "IsWindowVisible": ([w.HWND], w.BOOL), "IsIconic": ([w.HWND], w.BOOL),
            "GetWindowTextLengthW": ([w.HWND], ctypes.c_int),
            "GetWindowTextW": ([w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int),
            "GetWindowThreadProcessId": ([w.HWND, ctypes.POINTER(w.DWORD)], w.DWORD),
            "ShowWindow": ([w.HWND, ctypes.c_int], w.BOOL),
            "SetForegroundWindow": ([w.HWND], w.BOOL),
            "PostMessageW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], w.BOOL),
            "GetShellWindow": ([], w.HWND), "GetDesktopWindow": ([], w.HWND),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.user, name); function.argtypes = args; function.restype = result
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.kernel.OpenProcess.restype = w.HANDLE
        self.kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
        self.kernel.CloseHandle.argtypes = [w.HANDLE]

    def windows(self):
        items = []
        excluded = {self.user.GetShellWindow(), self.user.GetDesktopWindow()}
        def collect(handle, unused):
            if handle in excluded or not self.user.IsWindowVisible(handle):
                return True
            length = self.user.GetWindowTextLengthW(handle)
            if length <= 0:
                return True
            title = ctypes.create_unicode_buffer(min(length + 1, 1024))
            self.user.GetWindowTextW(handle, title, len(title))
            pid = self.w.DWORD()
            self.user.GetWindowThreadProcessId(handle, ctypes.byref(pid))
            if pid.value == os.getpid():
                return True
            name = ""
            process = self.kernel.OpenProcess(0x1000, False, pid.value)
            if process:
                try:
                    image = ctypes.create_unicode_buffer(32768); size = self.w.DWORD(len(image))
                    if self.kernel.QueryFullProcessImageNameW(process, 0, image, ctypes.byref(size)):
                        name = os.path.basename(image.value)
                finally:
                    self.kernel.CloseHandle(process)
            items.append({"handle": str(handle), "pid": pid.value, "title": title.value,
                          "name": name, "minimized": bool(self.user.IsIconic(handle))})
            return len(items) < 500
        self.user.EnumWindows(self.callback(collect), 0)
        return items

    def act(self, handle, action):
        if action == "activate":
            self.user.ShowWindow(handle, 9)
            if not self.user.SetForegroundWindow(handle):
                raise RuntimeError("窗口已还原，但 Windows 限制了前台切换，请在画面中点击窗口")
        elif action == "minimize":
            self.user.ShowWindow(handle, 6)
        elif not self.user.PostMessageW(handle, 0x10, 0, 0):
            raise RuntimeError("无法发送关闭请求，目标窗口可能需要更高权限")


def apps_request(q, error, backend=None):
    if os.name != "nt":
        raise error("运行程序列表仅支持 Windows")
    action = q.get("action", "list")
    if action not in ("list", "activate", "minimize", "close"):
        raise error("无效的程序窗口操作")
    backend = backend or DesktopApps()
    items = backend.windows()
    if action == "list":
        return {"items": items}
    handle, pid = q.get("handle"), q.get("pid")
    if (not isinstance(handle, str) or not handle.isdecimal() or len(handle) > 20 or
            type(pid) is not int or pid <= 0 or not any(i["handle"] == handle and i["pid"] == pid for i in items)):
        raise error("窗口已关闭或发生变化，请刷新列表")
    try:
        backend.act(int(handle), action)
        return {"requested": action, "handle": handle}
    except (OSError, RuntimeError) as exc:
        raise error(str(exc))

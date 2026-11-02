"""One-tap control for classroom/office apps, plus window invisibility.

Phone-side actions are intentionally small and reversible: flip a slide, pick a
tool, paste a message into the focused chat, or hide a window from the desktop
while keeping it alive and controllable.  Nothing here starts or stops a
program; every action targets a window the phone can see in the list.

Keys are sent in two modes: ``message`` posts WM_KEYDOWN/UP to the window (the
target keeps its focus, works for background apps), ``input`` activates the
window and uses SendInput (needed by apps that read the keyboard queue, e.g.
pasting into QQ).  The default table can be overridden in
``%APPDATA%\\LanCmd\\app-control.json``.
"""
import ctypes
import json
import os
from pathlib import Path
import threading
import time

from remote_features import KEY_CODES

SW_HIDE = 0
SW_SHOW = 5
SW_RESTORE = 9
WM_KEYDOWN = 0x0100
WM_KEYUP = 0x0101
WM_CHAR = 0x0102
CF_UNICODETEXT = 13
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E, 0x5B}

# Shortcut presets.  They are a starting point, not a promise: every name can be
# rebound in app-control.json without touching this file.
PROFILES = {
    "seewo": dict(
        label="希沃白板",
        match=("easinote", "easynote", "seewo"),
        keys={"page_next": "PAGEDOWN", "page_prev": "PAGEUP", "first": "HOME", "last": "END",
              "pen": "P", "eraser": "E", "marker": "H", "select": "V", "blackboard": "B",
              "slideshow": "F5", "exit": "ESC"},
    ),
    "wps": dict(
        label="WPS Office",
        match=("wps", "wpp", "et", "wpsoffice"),
        keys={"page_next": "PAGEDOWN", "page_prev": "PAGEUP", "first": "HOME", "last": "END",
              "save": "CTRL+S", "undo": "CTRL+Z", "bold": "CTRL+B", "find": "CTRL+F",
              "slideshow": "F5", "exit": "ESC"},
    ),
    "qq": dict(
        label="QQ",
        match=("qq",),
        keys={"page_next": "PAGEDOWN", "page_prev": "PAGEUP"},
    ),
}

ACTION_LABELS = {
    "page_next": "下一页", "page_prev": "上一页", "first": "第一页", "last": "最后一页",
    "pen": "画笔", "eraser": "橡皮", "marker": "荧光笔", "select": "选择", "blackboard": "黑板",
    "save": "保存", "undo": "撤销", "bold": "加粗", "find": "查找",
    "slideshow": "开始放映", "exit": "退出／关闭", "send": "发送消息",
}

MAX_HIDDEN = 64


def parse_keys(spec):
    """'CTRL+S' -> ['CTRL', 'S']; a list is accepted as is."""
    if isinstance(spec, str):
        parts = [part.strip().upper() for part in spec.replace("+", " ").split() if part.strip()]
    elif isinstance(spec, list):
        parts = [str(part).strip().upper() for part in spec if str(part).strip()]
    else:
        parts = []
    unknown = [part for part in parts if part not in KEY_CODES]
    if unknown:
        raise ValueError("不认识的按键：%s" % "、".join(unknown[:5]))
    return parts


class NativeWindows:
    """Thin user32 wrapper; every handle is re-validated before it is used."""

    def __init__(self):
        from ctypes import wintypes as wt
        self.w = wt
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        callback = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
        self.callback = callback
        signatures = {
            "EnumWindows": ([callback, wt.LPARAM], wt.BOOL),
            "IsWindow": ([wt.HWND], wt.BOOL),
            "IsWindowVisible": ([wt.HWND], wt.BOOL),
            "IsIconic": ([wt.HWND], wt.BOOL),
            "GetWindowTextLengthW": ([wt.HWND], ctypes.c_int),
            "GetWindowTextW": ([wt.HWND, wt.LPWSTR, ctypes.c_int], ctypes.c_int),
            "GetClassNameW": ([wt.HWND, wt.LPWSTR, ctypes.c_int], ctypes.c_int),
            "GetWindowThreadProcessId": ([wt.HWND, ctypes.POINTER(wt.DWORD)], wt.DWORD),
            "GetForegroundWindow": ([], wt.HWND),
            "SetForegroundWindow": ([wt.HWND], wt.BOOL),
            "ShowWindow": ([wt.HWND, ctypes.c_int], wt.BOOL),
            "IsWindowEnabled": ([wt.HWND], wt.BOOL),
            "PostMessageW": ([wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM], wt.BOOL),
            "GetShellWindow": ([], wt.HWND),
            "GetDesktopWindow": ([], wt.HWND),
            "GetWindow": ([wt.HWND, wt.UINT], wt.HWND),
            "AttachThreadInput": ([wt.DWORD, wt.DWORD, wt.BOOL], wt.BOOL),
            "OpenClipboard": ([wt.HWND], wt.BOOL),
            "CloseClipboard": ([], wt.BOOL),
            "EmptyClipboard": ([], wt.BOOL),
            "GetClipboardData": ([wt.UINT], wt.HANDLE),
            "SetClipboardData": ([wt.UINT, wt.HANDLE], wt.HANDLE),
            "IsClipboardFormatAvailable": ([wt.UINT], wt.BOOL),
            "MapVirtualKeyW": ([wt.UINT, wt.UINT], wt.UINT),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.user, name)
            function.argtypes = args
            function.restype = result
        self.kernel.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
        self.kernel.OpenProcess.restype = wt.HANDLE
        self.kernel.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR,
                                                          ctypes.POINTER(wt.DWORD)]
        self.kernel.CloseHandle.argtypes = [wt.HANDLE]
        self.kernel.GetCurrentThreadId.restype = wt.DWORD
        self.kernel.GlobalAlloc.argtypes = [wt.UINT, ctypes.c_size_t]
        self.kernel.GlobalAlloc.restype = wt.HANDLE
        self.kernel.GlobalLock.argtypes = [wt.HANDLE]
        self.kernel.GlobalLock.restype = ctypes.c_void_p
        self.kernel.GlobalUnlock.argtypes = [wt.HANDLE]
        self.kernel.GlobalFree.argtypes = [wt.HANDLE]
        self.dwm = ctypes.WinDLL("dwmapi", use_last_error=True)
        self.dwm.DwmGetWindowAttribute.argtypes = [wt.HWND, wt.DWORD, ctypes.c_void_p, wt.DWORD]

    def process(self, pid):
        handle = self.kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return "", ""
        try:
            image = ctypes.create_unicode_buffer(32768)
            size = self.w.DWORD(len(image))
            if not self.kernel.QueryFullProcessImageNameW(handle, 0, image, ctypes.byref(size)):
                return "", ""
            return os.path.basename(image.value), image.value
        finally:
            self.kernel.CloseHandle(handle)

    def windows(self, include_hidden=False):
        items = []
        excluded = {self.user.GetShellWindow(), self.user.GetDesktopWindow()}
        own = os.getpid()

        def collect(handle, unused):
            if handle in excluded:
                return True
            cloaked = self.w.DWORD()
            if self.dwm.DwmGetWindowAttribute(handle, 14, ctypes.byref(cloaked),
                                              ctypes.sizeof(cloaked)) == 0 and cloaked.value:
                return True
            visible = bool(self.user.IsWindowVisible(handle))
            if not visible and not include_hidden:
                return True
            length = self.user.GetWindowTextLengthW(handle)
            if length <= 0:
                return True
            title = ctypes.create_unicode_buffer(min(length + 1, 1024))
            self.user.GetWindowTextW(handle, title, len(title))
            pid = self.w.DWORD()
            self.user.GetWindowThreadProcessId(handle, ctypes.byref(pid))
            if pid.value == own:
                return True
            name, path = self.process(pid.value)
            items.append({"handle": str(handle), "pid": pid.value, "title": title.value,
                          "name": name, "path": path, "visible": visible,
                          "minimized": bool(self.user.IsIconic(handle)),
                          "available": bool(self.user.IsWindowEnabled(handle))})
            return len(items) < 800

        self.user.EnumWindows(self.callback(collect), 0)
        return items


def profile_of(name):
    name = (name or "").casefold()
    for key, profile in PROFILES.items():
        if any(name.startswith(prefix) for prefix in profile["match"]):
            return key
    return ""


class SendKeys:
    """WM_KEYDOWN or SendInput, with the same VK table the remote keyboard uses."""

    def __init__(self, native):
        self.native = native
        self.lock = threading.RLock()
        from ctypes import wintypes as wt

        class Keyboard(ctypes.Structure):
            _fields_ = [("vk", wt.WORD), ("scan", wt.WORD), ("flags", wt.DWORD),
                        ("time", wt.DWORD), ("extra", ctypes.c_size_t)]

        class Mouse(ctypes.Structure):
            _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("data", wt.DWORD),
                        ("flags", wt.DWORD), ("time", wt.DWORD), ("extra", ctypes.c_size_t)]

        class Payload(ctypes.Union):
            _fields_ = [("keyboard", Keyboard), ("mouse", Mouse)]

        class Input(ctypes.Structure):
            _fields_ = [("type", wt.DWORD), ("payload", Payload)]

        self.Input = Input
        self.Payload = Payload
        self.Keyboard = Keyboard
        self.user = native.user
        self.user.SendInput.argtypes = [wt.UINT, ctypes.POINTER(Input), ctypes.c_int]

    # -------------------------------------------------------------- SendInput
    def input(self, keys, text=""):
        Input, Payload, Keyboard = self.Input, self.Payload, self.Keyboard

        def send(vk=0, scan=0, flags=0):
            item = Input(1, Payload(keyboard=Keyboard(vk, scan, flags, 0, 0x4C434D44)))
            if self.user.SendInput(1, ctypes.byref(item), ctypes.sizeof(Input)) != 1:
                raise RuntimeError("系统拒绝输入；请确认电脑已解锁、目标窗口未被提权保护")

        with self.lock:
            if text:
                raw = text.encode("utf-16-le")
                for index in range(0, len(raw), 2):
                    code = int.from_bytes(raw[index:index + 2], "little")
                    send(scan=code, flags=4)
                    send(scan=code, flags=6)
                return
            pressed = []
            try:
                for name in keys:
                    vk = KEY_CODES[name]
                    send(vk, flags=1 if vk in EXTENDED else 0)
                    pressed.append(vk)
                    time.sleep(0.02)
            finally:
                for vk in reversed(pressed):
                    send(vk, flags=3 if vk in EXTENDED else 2)

    # ------------------------------------------------------------ PostMessage
    def message(self, handle, keys):
        """Press the whole combination, then release it in reverse order."""
        with self.lock:
            pressed = []
            try:
                for name in keys:
                    vk = KEY_CODES[name]
                    scan = self.user.MapVirtualKeyW(vk, 0)
                    extra = 1 << 24 if vk in EXTENDED else 0
                    down = 1 | (scan << 16) | extra
                    up = down | (1 << 30) | (1 << 31)
                    if not self.user.PostMessageW(handle, WM_KEYDOWN, vk, down):
                        raise RuntimeError("目标窗口拒绝按键，请改用前台输入")
                    pressed.append((vk, up))
                    time.sleep(0.01)
            finally:
                for vk, up in reversed(pressed):
                    self.user.PostMessageW(handle, WM_KEYUP, vk, up)
                    time.sleep(0.01)

    def text_message(self, handle, text):
        with self.lock:
            raw = text.encode("utf-16-le")
            for index in range(0, len(raw), 2):
                code = int.from_bytes(raw[index:index + 2], "little")
                self.user.PostMessageW(handle, WM_CHAR, code, 1)


class AppControl:
    def __init__(self, config_dir, error, logger=None):
        self.folder = Path(config_dir)
        self.error = error
        self.log = logger
        self.lock = threading.RLock()
        self.hidden = {}
        self.supported = os.name == "nt"
        self.native = None
        self.send = None
        self.overrides = self.load_overrides()
        self.load_hidden()

    # ----------------------------------------------------------- configuration
    @property
    def path(self):
        return self.folder / "app-control.json"

    def load_overrides(self):
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"keys": {}}
        if not isinstance(data, dict) or not isinstance(data.get("keys", {}), dict):
            return {"keys": {}}
        return {"keys": data.get("keys") or {}}

    def save_overrides(self):
        try:
            self.folder.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.overrides, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError:
            pass

    @property
    def hidden_path(self):
        return self.folder / "app-control-hidden.json"

    def load_hidden(self):
        """Restore records survive a crash so no window is left invisible."""
        try:
            data = json.loads(self.hidden_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        if not isinstance(data, list):
            return
        for item in data:
            if (not isinstance(item, dict) or not isinstance(item.get("handle"), str)
                    or type(item.get("pid")) is not int):
                continue
            self.hidden[item["handle"]] = dict(item)

    def save_hidden(self):
        try:
            self.folder.mkdir(parents=True, exist_ok=True)
            temporary = self.hidden_path.with_name(self.hidden_path.name + ".tmp")
            with self.lock:
                data = list(self.hidden.values())
            temporary.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            os.replace(temporary, self.hidden_path)
        except OSError:
            pass

    def table(self, profile):
        merged = dict(PROFILES[profile]["keys"])
        for name, value in (self.overrides.get("keys", {}).get(profile) or {}).items():
            if name in ACTION_LABELS:
                merged[name] = value
        return merged

    def _native(self):
        if not self.supported:
            raise self.error("应用控制仅支持 Windows")
        with self.lock:
            if self.native is None:
                self.native = NativeWindows()
                self.send = SendKeys(self.native)
            return self.native

    # ------------------------------------------------------------------ listing
    def profiles(self):
        items = []
        for key, profile in PROFILES.items():
            actions = [dict(id=name, label=ACTION_LABELS[name], keys=self.table(key).get(name, ""))
                       for name in ACTION_LABELS if self.table(key).get(name)]
            items.append(dict(id=key, label=profile["label"], actions=actions))
        return items

    def listing(self):
        native = self._native()
        supported, others = [], []
        for item in native.windows(include_hidden=False):
            profile = profile_of(item["name"])
            entry = dict(item, profile=profile)
            if profile:
                supported.append(entry)
            elif len(others) < 40:
                others.append(entry)
        with self.lock:
            hidden = [dict(item) for item in self.hidden.values()]
        return dict(windows=supported + others, profiles=self.profiles(), hidden=hidden,
                    note="隐形窗口在本机桌面和任务栏不可见，进程继续运行；恢复后回到前台")

    # ------------------------------------------------------------------ actions
    def request(self, q, state=None):
        action = q.get("action", "list")
        if action == "list":
            return self.listing()
        if action == "profiles":
            return dict(profiles=self.profiles())
        if action in ("act", "send"):
            return self.perform(q)
        if action == "hide":
            return self.hide(q, state)
        if action == "show":
            return self.show(q.get("handle"))
        if action == "hidden":
            with self.lock:
                return dict(hidden=[dict(item) for item in self.hidden.values()])
        if action == "restore_all":
            return self.restore_all()
        if action == "bind":
            return self.bind(q)
        raise self.error("无效的应用控制操作")

    def bind(self, q):
        profile = q.get("profile")
        name = q.get("name")
        if profile not in PROFILES:
            raise self.error("未知的应用类型")
        if name not in ACTION_LABELS:
            raise self.error("未知的动作")
        keys = parse_keys(q.get("keys", ""))
        if not keys:
            raise self.error("按键不能为空")
        self.overrides.setdefault("keys", {}).setdefault(profile, {})[name] = " ".join(keys)
        self.save_overrides()
        return dict(profile=profile, name=name, keys=" ".join(keys), actions=self.profiles())

    def _window(self, q):
        native = self._native()
        handle = q.get("handle")
        pid = q.get("pid")
        if not isinstance(handle, str) or not handle.isdecimal() or len(handle) > 20:
            raise self.error("窗口句柄无效")
        for item in native.windows(include_hidden=True):
            if item["handle"] == handle and item["pid"] == pid:
                return item
        raise self.error("窗口已关闭或已变化，请刷新列表")

    def perform(self, q):
        item = self._window(q)
        handle = int(item["handle"])
        if q.get("action") == "focus":
            self._focus(handle)
            return dict(handle=item["handle"], pid=item["pid"], name=item["name"],
                        action="focus", mode="input", keys="", text="")
        name = q.get("name") or q.get("action_name") or ""
        mode = q.get("mode") or ("input" if q.get("action") == "send" else "message")
        keys, text = [], ""
        if q.get("action") == "send":
            text = q.get("text") or ""
            if not isinstance(text, str) or not text.strip() or len(text) > 2000:
                raise self.error("消息须为 1–2000 个字符")
        elif q.get("keys"):
            # Free-form combo from the phone: no preset needed.
            keys = parse_keys(q.get("keys"))
            name = name or "keys"
        else:
            profile = item.get("profile") or profile_of(item["name"])
            if not profile:
                raise self.error("该窗口没有预设动作；请发送自定义按键")
            if name not in ACTION_LABELS:
                raise self.error("未知的动作")
            spec = self.table(profile).get(name)
            if not spec:
                raise self.error("该应用没有这个动作的默认按键，可在电脑端 app-control.json 里绑定")
            keys = parse_keys(spec)
        if mode not in ("message", "input"):
            raise self.error("无效的输入方式")
        if mode == "input":
            self._focus(handle)
        if text:
            self._paste(handle, text, bool(q.get("enter", True)), mode)
        elif keys:
            if mode == "input":
                self.send.input(keys)
            else:
                self.send.message(handle, keys)
        if self.log:
            self.log.info("应用控制 %s %s (%s)", item["name"], name or "send", mode)
        return dict(handle=item["handle"], pid=item["pid"], name=item["name"],
                    action=name or "send", mode=mode, keys=" ".join(keys), text=text[:200])

    def _focus(self, handle):
        native = self._native()
        if native.user.IsIconic(handle):
            native.user.ShowWindow(handle, SW_RESTORE)
        front = native.user.GetForegroundWindow()
        current = native.kernel.GetCurrentThreadId()
        target = native.user.GetWindowThreadProcessId(front, None) if front else 0
        attached = False
        try:
            if target and target != current:
                attached = bool(native.user.AttachThreadInput(current, target, True))
            native.user.ShowWindow(handle, SW_SHOW)
            if not native.user.SetForegroundWindow(handle) and native.user.GetForegroundWindow() != handle:
                raise self.error("系统拒绝切换前台；请先在远程画面里点一下目标窗口")
        finally:
            if attached:
                native.user.AttachThreadInput(current, target, False)
        time.sleep(0.15)

    # ---------------------------------------------------------------- clipboard
    def _clipboard_text(self):
        native = self._native()
        if not native.user.IsClipboardFormatAvailable(CF_UNICODETEXT):
            return ""
        if not native.user.OpenClipboard(None):
            return ""
        try:
            handle = native.user.GetClipboardData(CF_UNICODETEXT)
            if not handle:
                return ""
            pointer = native.kernel.GlobalLock(handle)
            if not pointer:
                return ""
            try:
                return ctypes.wstring_at(pointer)
            finally:
                native.kernel.GlobalUnlock(handle)
        finally:
            native.user.CloseClipboard()

    def _set_clipboard(self, text):
        native = self._native()
        raw = (text + "\0").encode("utf-16-le")
        handle = native.kernel.GlobalAlloc(0x0042, len(raw))
        if not handle:
            raise self.error("无法分配剪贴板内存")
        pointer = native.kernel.GlobalLock(handle)
        if not pointer:
            native.kernel.GlobalFree(handle)
            raise self.error("无法锁定剪贴板内存")
        ctypes.memmove(pointer, raw, len(raw))
        native.kernel.GlobalUnlock(handle)
        if not native.user.OpenClipboard(None):
            native.kernel.GlobalFree(handle)
            raise self.error("剪贴板被其他程序占用，请稍后重试")
        try:
            native.user.EmptyClipboard()
            if not native.user.SetClipboardData(CF_UNICODETEXT, handle):
                raise self.error("无法写入剪贴板")
        finally:
            native.user.CloseClipboard()

    def _paste(self, handle, text, enter, mode):
        if mode == "message":
            # Background mode never touches the clipboard: post WM_CHAR directly.
            self.send.text_message(handle, text)
            if enter:
                self.send.message(handle, ["ENTER"])
            return
        previous = self._clipboard_text()
        self._set_clipboard(text)
        time.sleep(0.1)
        try:
            self.send.input(["CTRL", "V"])
            time.sleep(0.15)
            if enter:
                self.send.input(["ENTER"])
        finally:
            # Do not leave the phone's message in the user's clipboard.
            try:
                self._set_clipboard(previous or " ")
            except Exception:
                pass

    # ------------------------------------------------------------------- hiding
    def hide(self, q, state=None):
        item = self._window(q)
        native = self._native()
        with self.lock:
            if len(self.hidden) >= MAX_HIDDEN:
                raise self.error("最多同时隐藏 %d 个窗口" % MAX_HIDDEN)
            if item["handle"] in self.hidden:
                raise self.error("该窗口已经是隐形状态")
            if not native.user.ShowWindow(int(item["handle"]), SW_HIDE):
                raise self.error("Windows 拒绝隐藏该窗口（可能已关闭）")
            self.hidden[item["handle"]] = dict(handle=item["handle"], pid=item["pid"],
                                               title=item["title"], name=item["name"],
                                               path=item["path"], profile=item.get("profile", ""),
                                               hidden_at=int(time.time()),
                                               client=getattr(state, "client_id", "") or "")
            self.save_hidden()
        if self.log:
            self.log.info("窗口隐形 %s (%s)", item["title"], item["name"])
        return dict(handle=item["handle"], hidden=len(self.hidden), title=item["title"])

    def show(self, handle):
        client = handle if isinstance(handle, str) else str(handle or "")
        native = self._native()
        with self.lock:
            record = self.hidden.pop(client, None)
        if record is None:
            raise self.error("没有对应的隐形窗口记录")
        if not native.user.IsWindow(int(record["handle"])):
            raise self.error("窗口所在的程序已经退出")
        native.user.ShowWindow(int(record["handle"]), SW_SHOW)
        native.user.ShowWindow(int(record["handle"]), SW_RESTORE)
        native.user.SetForegroundWindow(int(record["handle"]))
        self.save_hidden()
        if self.log:
            self.log.info("窗口恢复 %s", record["title"])
        return dict(handle=record["handle"], title=record["title"], restored=True,
                    remaining=len(self.hidden))

    def restore_all(self):
        restored, errors = [], []
        with self.lock:
            records = list(self.hidden.values())
            self.hidden.clear()
        native = self._native() if self.supported else None
        for record in records:
            try:
                if not native.user.IsWindow(int(record["handle"])):
                    raise ValueError("程序已退出")
                native.user.ShowWindow(int(record["handle"]), SW_SHOW)
                native.user.ShowWindow(int(record["handle"]), SW_RESTORE)
                restored.append(record["title"])
            except Exception as exc:
                errors.append("%s：%s" % (record["title"], exc))
        self.save_hidden()
        return dict(restored=restored, errors=errors)

    def recover(self):
        """Called at startup: a previous run may have died with windows hidden."""
        with self.lock:
            stale = list(self.hidden.values())
        if not stale or not self.supported:
            with self.lock:
                self.hidden.clear()
            self.save_hidden()
            return dict(restored=0, titles=[])
        native = self._native()
        windows = {(item["handle"], item["pid"]): item for item in native.windows(include_hidden=True)}
        titles = []
        for record in stale:
            item = windows.get((record["handle"], record["pid"]))
            if item is None:
                continue  # the program exited or the handle was reused: nothing to restore
            try:
                native.user.ShowWindow(int(record["handle"]), SW_SHOW)
                native.user.ShowWindow(int(record["handle"]), SW_RESTORE)
                titles.append(item["title"])
            except Exception:
                continue
        with self.lock:
            self.hidden.clear()
        self.save_hidden()
        if titles and self.log:
            self.log.info("恢复上次退出时隐形的窗口：%s", "、".join(titles[:5]))
        return dict(restored=len(titles), titles=titles)

    def close(self):
        """Bring every hidden window back when the receiver stops."""
        if not self.hidden or not self.supported:
            with self.lock:
                self.hidden.clear()
            self.save_hidden()
            return
        try:
            self.restore_all()
        except Exception:
            pass

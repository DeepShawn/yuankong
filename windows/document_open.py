"""Open documents with their association or installed WPS, optionally without activation."""
import ctypes
import os
from pathlib import Path
import threading
import time

OFFICE = {".doc", ".docx", ".docm", ".dot", ".dotx", ".rtf", ".ppt", ".pptx", ".pptm", ".pps", ".ppsx", ".xls", ".xlsx", ".xlsm", ".xlsb", ".csv", ".et", ".dps", ".wps", ".pdf", ".odt", ".ods", ".odp"}
_guard_lock=threading.Lock()
_active_guard=None


def _new_guard():
    global _active_guard
    with _guard_lock:
        if _active_guard is not None:_active_guard.set()
        _active_guard=threading.Event()
        return _active_guard


def _window_pid(user,handle):
    from ctypes import wintypes as w
    pid=w.DWORD()
    user.GetWindowThreadProcessId(handle,ctypes.byref(pid))
    return pid.value


def _process_image(apps,pid):
    from ctypes import wintypes as w
    process=apps.kernel.OpenProcess(0x1000,False,pid)
    if not process:return None
    try:
        image=ctypes.create_unicode_buffer(32768);size=w.DWORD(len(image))
        if apps.kernel.QueryFullProcessImageNameW(process,0,image,ctypes.byref(size)):return image.value
    finally:apps.kernel.CloseHandle(process)
    return None


def _last_input(user):
    from ctypes import wintypes as w
    class LastInput(ctypes.Structure):
        _fields_=[("cbSize",w.UINT),("dwTime",w.DWORD)]
    info=LastInput();info.cbSize=ctypes.sizeof(info)
    return info.dwTime if user.GetLastInputInfo(ctypes.byref(info)) else None


def _associated_executable(path):
    if path.suffix.lower()==".exe":return str(path.resolve())
    if not path.suffix:return None
    from ctypes import wintypes as w
    api=ctypes.WinDLL("shlwapi",use_last_error=True)
    api.AssocQueryStringW.argtypes=[w.DWORD,ctypes.c_int,w.LPCWSTR,w.LPCWSTR,w.LPWSTR,ctypes.POINTER(w.DWORD)]
    api.AssocQueryStringW.restype=ctypes.c_long
    size=w.DWORD()
    api.AssocQueryStringW(0,2,path.suffix,"open",None,ctypes.byref(size))
    if not 1<size.value<=32768:return None
    value=ctypes.create_unicode_buffer(size.value)
    if api.AssocQueryStringW(0,2,path.suffix,"open",value,ctypes.byref(size))!=0:return None
    executable=Path(value.value.strip('"'))
    return str(executable.resolve()) if executable.is_file() else None


def _guard_background(apps,anchor,anchor_pid,pid,before,expected,input_stamp,stop,duration=30):
    """Bounded startup protection. Stop for an intentional focus change or closed anchor."""
    user=apps.user;deadline=time.monotonic()+duration;next_scan=0;previous=None
    tracked=set();images={}
    expected=os.path.normcase(os.path.normpath(expected)).casefold() if expected else None
    while time.monotonic()<deadline and not stop.wait(.1):
        if not user.IsWindow(anchor) or user.IsIconic(anchor):return
        if anchor_pid and _window_pid(user,anchor)!=anchor_pid:return
        foreground=user.GetForegroundWindow()
        stamp=_last_input(user)
        user_input=stamp is not None and input_stamp is not None and stamp!=input_stamp
        if stamp is not None:input_stamp=stamp
        now=time.monotonic()
        if foreground==previous and now<next_scan:continue
        previous=foreground;next_scan=now+1
        windows=apps.windows();targets=[]
        live_pids={item["pid"] for item in windows}
        images={key:value for key,value in images.items() if key in live_pids}
        for item in windows:
            handle=int(item["handle"]);identity=(handle,item["pid"])
            if handle==anchor or item.get("minimized"):continue
            # Existing singleton windows are relevant only if activated by this
            # launch, or already positively identified. Leave other documents alone.
            if identity in before and handle!=foreground and identity not in tracked:continue
            matches=bool(pid and item["pid"]==pid)
            if not matches and expected:
                if item["pid"] not in images:images[item["pid"]]=_process_image(apps,item["pid"])
                image=images[item["pid"]]
                matches=bool(image and os.path.normcase(os.path.normpath(image)).casefold()==expected)
            if matches:targets.append(identity)
        handles={handle for handle,_ in targets}
        if foreground not in (None,0,anchor) and foreground not in handles:return
        if foreground in handles and user_input:return  # Do not fight a deliberate switch.
        for handle,window_pid in targets:
            if not user.IsWindow(handle) or _window_pid(user,handle)!=window_pid:continue
            tracked.add((handle,window_pid))
            if user.GetWindow(anchor,2)!=handle:  # GW_HWNDNEXT
                # Demote an app-created topmost splash/window before placing it;
                # preserve geometry, focus and the owner's existing Z order.
                user.SetWindowPos(handle,-2,0,0,0,0,0x213)  # HWND_NOTOPMOST
                user.SetWindowPos(handle,anchor,0,0,0,0,0x213)
        if user.GetForegroundWindow() in handles:
            # Re-check immediately before restoration so another app selection
            # during enumeration cannot be overwritten.
            latest_input=_last_input(user)
            if latest_input is not None and input_stamp is not None and latest_input!=input_stamp:return
            if (user.IsWindow(anchor) and not user.IsIconic(anchor) and
                    (not anchor_pid or _window_pid(user,anchor)==anchor_pid)):
                user.SetForegroundWindow(anchor)


def wps_executable(path):
    import winreg
    suffix = path.suffix.lower()
    exe = "wpp.exe" if suffix in {".ppt", ".pptx", ".pptm", ".pps", ".ppsx", ".dps", ".odp"} else "et.exe" if suffix in {".xls", ".xlsx", ".xlsm", ".xlsb", ".csv", ".et", ".ods"} else "wps.exe"
    for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        for view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
            try:
                with winreg.OpenKey(hive, "Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\"+exe, 0, winreg.KEY_READ | view) as key:
                    value, _ = winreg.QueryValueEx(key, None)
                candidate = Path(os.path.expandvars(value.strip('"')))
                if candidate.is_file() and candidate.name.lower() == exe:return candidate
            except (OSError, ValueError):pass
    raise ValueError("未检测到电脑 WPS，请安装并启动一次 WPS，或选择默认应用打开")


def open_document(path, application="default", background=False):
    if os.name != "nt":raise ValueError("在电脑打开仅支持 Windows")
    if application not in ("default", "wps") or not isinstance(background, bool):raise ValueError("打开方式无效")
    if not path.exists():raise ValueError("目标不存在，请刷新目录")
    stop_guard=_new_guard()  # A later explicit open supersedes any earlier maintenance.
    if application=="default" and not background:
        os.startfile(str(path),"open")
        return {"opened":str(path),"application":application,"background_requested":False,"notice":"已向电脑发送打开请求"}
    target = wps_executable(path) if application == "wps" else path
    if application == "wps" and path.suffix.lower() not in OFFICE:raise ValueError("请选择 Office、WPS 或 PDF 文档")
    from ctypes import wintypes as w
    import subprocess
    from desktop_apps import DesktopApps
    class ShellInfo(ctypes.Structure):
        _fields_ = [("cbSize", w.DWORD), ("fMask", w.ULONG), ("hwnd", w.HWND), ("lpVerb", w.LPCWSTR),
            ("lpFile", w.LPCWSTR), ("lpParameters", w.LPCWSTR), ("lpDirectory", w.LPCWSTR), ("nShow", ctypes.c_int),
            ("hInstApp", w.HINSTANCE), ("lpIDList", ctypes.c_void_p), ("lpClass", w.LPCWSTR), ("hkeyClass", w.HKEY),
            ("dwHotKey", w.DWORD), ("hIcon", w.HANDLE), ("hProcess", w.HANDLE)]
    apps = DesktopApps()
    user, kernel = apps.user, apps.kernel
    user.GetForegroundWindow.restype = w.HWND
    user.IsWindow.argtypes=[w.HWND];user.IsWindow.restype=w.BOOL
    user.SetWindowPos.argtypes=[w.HWND,w.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,w.UINT]
    user.SetWindowPos.restype=w.BOOL
    user.GetWindow.argtypes=[w.HWND,w.UINT];user.GetWindow.restype=w.HWND
    user.GetLastInputInfo.argtypes=[ctypes.c_void_p];user.GetLastInputInfo.restype=w.BOOL
    kernel.GetProcessId.argtypes=[w.HANDLE];kernel.GetProcessId.restype=w.DWORD
    foreground = user.GetForegroundWindow()
    anchor_pid=_window_pid(user,foreground) if background and foreground else 0
    input_stamp=_last_input(user) if background else None
    before = {(int(i["handle"]),i["pid"]) for i in apps.windows()} if background else set()
    expected=str(target.resolve()) if application=="wps" else _associated_executable(path) if background else None
    info=ShellInfo();info.cbSize=ctypes.sizeof(info);info.fMask=0x40|0x400
    info.lpVerb="open";info.lpFile=str(target);info.lpParameters=subprocess.list2cmdline([str(path)]) if application=="wps" else None
    info.lpDirectory=str(path.parent);info.nShow=4 if background else 1
    shell=ctypes.WinDLL("shell32",use_last_error=True);shell.ShellExecuteExW.argtypes=[ctypes.POINTER(ShellInfo)];shell.ShellExecuteExW.restype=w.BOOL
    if not shell.ShellExecuteExW(ctypes.byref(info)):raise ctypes.WinError(ctypes.get_last_error())
    pid=kernel.GetProcessId(info.hProcess) if info.hProcess else 0
    if background and foreground:
        def place_below():
            try:_guard_background(apps,foreground,anchor_pid,pid,before,expected,input_stamp,stop_guard)
            except (OSError,RuntimeError):pass
            finally:
                if info.hProcess:kernel.CloseHandle(info.hProcess)
        try:threading.Thread(target=place_below,daemon=True,name="document-z-order").start()
        except Exception:
            if info.hProcess:kernel.CloseHandle(info.hProcess)
            raise RuntimeError("文件已发送打开，但无法启动下层窗口维护") from None
    elif info.hProcess:kernel.CloseHandle(info.hProcess)
    return {"opened":str(path),"application":application,"background_requested":background,
        "notice":"已请求在当前窗口下方打开，并维护启动期间的层级；主动切换会结束维护，Windows 或应用限制仍可能影响结果" if background else "已向电脑发送打开请求"}

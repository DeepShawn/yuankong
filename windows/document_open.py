"""Open documents with their association or installed WPS, optionally without activation."""
import ctypes
import os
from pathlib import Path
import threading
import time

OFFICE = {".doc", ".docx", ".docm", ".dot", ".dotx", ".rtf", ".ppt", ".pptx", ".pptm", ".pps", ".ppsx", ".xls", ".xlsx", ".xlsm", ".xlsb", ".csv", ".et", ".dps", ".wps", ".pdf", ".odt", ".ods", ".odp"}


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
    kernel.GetProcessId.argtypes=[w.HANDLE];kernel.GetProcessId.restype=w.DWORD
    foreground = user.GetForegroundWindow()
    before = {i["handle"] for i in apps.windows()} if background else set()
    info=ShellInfo();info.cbSize=ctypes.sizeof(info);info.fMask=0x40|0x400
    info.lpVerb="open";info.lpFile=str(target);info.lpParameters=subprocess.list2cmdline([str(path)]) if application=="wps" else None
    info.lpDirectory=str(path.parent);info.nShow=4 if background else 1
    shell=ctypes.WinDLL("shell32",use_last_error=True);shell.ShellExecuteExW.argtypes=[ctypes.POINTER(ShellInfo)];shell.ShellExecuteExW.restype=w.BOOL
    if not shell.ShellExecuteExW(ctypes.byref(info)):raise ctypes.WinError(ctypes.get_last_error())
    pid=kernel.GetProcessId(info.hProcess) if info.hProcess else 0
    if info.hProcess:kernel.CloseHandle(info.hProcess)
    if background and foreground:
        def place_below():
            for _ in range(30):
                time.sleep(.1)
                if not user.IsWindow(foreground):return
                for window in apps.windows():
                    if window["handle"] in before:continue
                    # Never reposition unrelated apps. Existing single-instance windows stay untouched.
                    if not pid or window["pid"]!=pid:continue
                    handle=int(window["handle"])
                    user.SetWindowPos(handle,foreground,0,0,0,0,0x13)
                    if user.GetForegroundWindow()==handle:user.SetForegroundWindow(foreground)
        threading.Thread(target=place_below,daemon=True,name="document-z-order").start()
    return {"opened":str(path),"application":application,"background_requested":background,
        "notice":"已请求在当前窗口下方打开；复用已有窗口或自行激活的应用可能不遵守此方式" if background else "已向电脑发送打开请求"}

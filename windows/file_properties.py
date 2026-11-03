"""Inspect ordinary file attributes and edit only the read-only flag.

Hidden/system flags are informational. No concealment, recursion, DACL or
timestamp changes. A held native handle prevents rename/replacement races.
"""
import ctypes
import ntpath
import os
from pathlib import Path
import stat

from file_jobs import checked_path, file_token, snapshot


class NativeProperties:
    def __init__(self, path, expected, write=False):
        from ctypes import wintypes as w
        if os.name != "nt":
            raise ValueError("文件属性仅支持 Windows")
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.w = w
        class Information(ctypes.Structure):
            _fields_ = [("attributes", w.DWORD), ("created", w.FILETIME), ("accessed", w.FILETIME),
                        ("modified", w.FILETIME), ("volume", w.DWORD), ("size_high", w.DWORD),
                        ("size_low", w.DWORD), ("links", w.DWORD), ("index_high", w.DWORD), ("index_low", w.DWORD)]
        class Basic(ctypes.Structure):
            _fields_ = [("created", ctypes.c_longlong), ("accessed", ctypes.c_longlong),
                        ("modified", ctypes.c_longlong), ("changed", ctypes.c_longlong), ("attributes", w.DWORD)]
        self.Information, self.Basic = Information, Basic
        k = self.kernel
        k.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.HANDLE]
        k.CreateFileW.restype = w.HANDLE
        k.CloseHandle.argtypes = [w.HANDLE]; k.CloseHandle.restype = w.BOOL
        k.GetFileInformationByHandle.argtypes = [w.HANDLE, ctypes.POINTER(Information)]
        k.GetFileInformationByHandle.restype = w.BOOL
        k.SetFileInformationByHandle.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        k.SetFileInformationByHandle.restype = w.BOOL
        # Share readers/writers, never deletion; support ordinary directories too.
        self.handle = k.CreateFileW(str(path), 0x80 | (0x100 if write else 0), 3, None, 3, 0x02200000, None)
        if self.handle == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            info = self.info()
            identity = [info.volume, info.index_high << 32 | info.index_low]
            if (identity != [expected.st_dev, expected.st_ino] or info.attributes & 0x400 or
                    bool(info.attributes & 0x10) != stat.S_ISDIR(expected.st_mode) or
                    (not info.attributes & 0x10 and info.links != 1)):
                raise ValueError("文件身份已变化或包含链接，请刷新后重试")
        except Exception:
            self.close()
            raise

    def info(self):
        info = self.Information()
        if not self.kernel.GetFileInformationByHandle(self.handle, ctypes.byref(info)):
            raise ctypes.WinError(ctypes.get_last_error())
        return info

    def attributes(self):
        return self.info().attributes

    def readonly(self, value, expected):
        current = self.attributes()
        if current != expected:
            raise ValueError("属性已被其他程序更改，请重新读取后操作")
        desired = current | 1 if value else current & ~1
        if desired != 0x80:
            desired &= ~0x80  # NORMAL is exclusive; keep every other existing bit.
        change = self.Basic()
        change.attributes = desired or 0x80
        if not self.kernel.SetFileInformationByHandle(self.handle, 0, ctypes.byref(change), ctypes.sizeof(change)):
            raise ctypes.WinError(ctypes.get_last_error())
        after = self.attributes()
        if bool(after & 1) != value or (after & 6) != (current & 6):
            raise ValueError("Windows 未确认只读属性，未报告成功")
        return after

    def close(self):
        self.kernel.CloseHandle(self.handle)


def protected_roots():
    roots = [Path(__file__).absolute().parent]
    for key in ("SystemRoot", "WINDIR", "ProgramFiles", "ProgramFiles(x86)", "ProgramData"):
        if os.environ.get(key):
            roots.append(Path(os.environ[key]).absolute())
    if os.environ.get("APPDATA"):
        roots.append(Path(os.environ["APPDATA"]) / "LanCmd")
    if os.environ.get("LOCALAPPDATA"):
        roots.append(Path(os.environ["LOCALAPPDATA"]) / "LanCmdMaintenance")
    return roots


def properties(q, backend=NativeProperties, protected=None):
    action = q.get("action")
    if action not in ("properties", "readonly"):
        raise ValueError("无效的属性操作")
    if any(key in q for key in ("hidden", "system", "attributes", "mask")):
        raise ValueError("隐藏和系统属性仅可查看，不能通过此接口修改")
    value=q.get("path")
    if isinstance(value,str) and (":" in ntpath.splitdrive(value)[1] or value.startswith(("\\\\","\\??\\"))):
        raise ValueError("请选择普通本地文件或文件夹，不支持数据流、网络或设备路径")
    path = checked_path(value)
    before = snapshot(path)
    if before.st_nlink > 1 and not stat.S_ISDIR(before.st_mode):
        raise ValueError("不处理具有多个硬链接的文件")
    token = file_token(before)
    if q.get("file_id") is not None and q["file_id"] != token:
        raise ValueError("所选文件已变化，请重新读取属性")
    roots = protected_roots() if protected is None else [Path(p).absolute() for p in protected]
    writable = path.parent != path and not any(path == root or root in path.parents for root in roots)
    if action == "readonly":
        if not writable:
            raise ValueError("不修改系统、接收端或恢复记录的属性")
        if q.get("file_id") != token or type(q.get("readonly")) is not bool or type(q.get("expected_attributes")) is not int:
            raise ValueError("请先读取属性并明确选择只读状态")
    native = backend(path, before, action == "readonly")
    try:
        flags = native.attributes()
        if action == "readonly":
            if flags & 4:
                raise ValueError("系统属性文件仅可查看")
            flags = native.readonly(q["readonly"], q["expected_attributes"])
        directory = stat.S_ISDIR(before.st_mode)
        return dict(path=str(path), file_id=token, attributes=flags, readonly=bool(flags & 1),
                    hidden=bool(flags & 2), system=bool(flags & 4), dir=directory,
                    size=0 if directory else before.st_size, mtime=int(before.st_mtime),
                    editable=writable and not bool(flags & 4),
                    notice="文件夹只读属性不限制子项读写，不等于文件夹权限锁" if directory else "只修改只读标记，不更改内容、时间或权限")
    finally:
        native.close()

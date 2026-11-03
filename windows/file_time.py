"""Shared ordinary-file timestamp handle; no executable maintenance entry point."""
import ctypes
import os
from pathlib import Path
import stat


class DocumentHandle:
    def __init__(self, path):
        self.path = Path(path)
        self.fd = None

    def __enter__(self):
        if os.name == "nt":
            import msvcrt
            from ctypes import wintypes as w
            self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            self.kernel.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.HANDLE]
            self.kernel.CreateFileW.restype = w.HANDLE
            self.kernel.CloseHandle.argtypes = [w.HANDLE]
            self.kernel.CloseHandle.restype = w.BOOL
            self.kernel.SetFileTime.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(w.FILETIME)]
            self.kernel.SetFileTime.restype = w.BOOL
            self.handle = self.kernel.CreateFileW(str(self.path), 0x180, 1, None, 3, 0x200000, None)
            if self.handle == ctypes.c_void_p(-1).value:
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                self.fd = msvcrt.open_osfhandle(int(self.handle), os.O_RDONLY)
            except Exception:
                self.kernel.CloseHandle(self.handle)
                raise
        else:
            self.fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        info = os.fstat(self.fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or
                getattr(info, "st_file_attributes", 0) & 0x400 or not info.st_ino):
            os.close(self.fd)
            self.fd = None
            raise ValueError("文档身份无效或已被替换")
        return self

    def snapshot(self):
        info = os.fstat(self.fd)
        return dict(identity=[info.st_dev, info.st_ino], size=info.st_size, mtime_ns=info.st_mtime_ns)

    def set(self, modified):
        if os.name == "nt":
            from ctypes import wintypes as w
            value = modified // 100 + 116444736000000000
            stamp = w.FILETIME(value & 0xffffffff, value >> 32)
            if not self.kernel.SetFileTime(self.handle, None, None, ctypes.byref(stamp)):
                raise ctypes.WinError(ctypes.get_last_error())
        else:
            os.utime(self.fd, ns=(os.fstat(self.fd).st_atime_ns, modified))

    def __exit__(self, *args):
        if self.fd is not None:
            os.close(self.fd)


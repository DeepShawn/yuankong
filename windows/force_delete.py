"""Handle-bound deletion and explicit, verified closure of file-using applications.

No foreign handle closure, reboot scheduling, ACL changes or fabricated errors.
"""
import ctypes
from ctypes import wintypes as w
import os
from pathlib import Path
import stat
import time


def identity(info):
    return info.st_dev, info.st_ino


class FileInUse(Exception):
    def __init__(self, blockers):
        self.blockers = blockers
        super().__init__("文件仍被应用占用，需要确认后结束所列应用")


class RestartManager:
    """Read occupancy only. Never use RmShutdown/RmRestart or close a service."""
    def __init__(self):
        self.api = r = ctypes.WinDLL("rstrtmgr", use_last_error=True)
        class UniqueProcess(ctypes.Structure):
            _fields_ = [("pid", w.DWORD), ("created", w.FILETIME)]
        class ProcessInfo(ctypes.Structure):
            _fields_ = [("process", UniqueProcess), ("name", w.WCHAR * 256),
                        ("service", w.WCHAR * 64), ("kind", ctypes.c_int),
                        ("status", w.ULONG), ("session", w.DWORD), ("restartable", w.BOOL)]
        self.ProcessInfo = ProcessInfo
        r.RmStartSession.argtypes = [ctypes.POINTER(w.DWORD), w.DWORD, w.LPWSTR]
        r.RmEndSession.argtypes = [w.DWORD]
        r.RmRegisterResources.argtypes = [w.DWORD, w.UINT, ctypes.POINTER(w.LPCWSTR),
                                         w.UINT, ctypes.POINTER(UniqueProcess), w.UINT,
                                         ctypes.POINTER(w.LPCWSTR)]
        r.RmGetList.argtypes = [w.DWORD, ctypes.POINTER(w.UINT), ctypes.POINTER(w.UINT),
                               ctypes.POINTER(ProcessInfo), ctypes.POINTER(w.DWORD)]

    def list(self, path):
        session = w.DWORD()
        key = ctypes.create_unicode_buffer(33)
        code = self.api.RmStartSession(ctypes.byref(session), 0, key)
        if code:
            raise OSError("无法查询占用应用 (Windows %d)" % code)
        try:
            files = (w.LPCWSTR * 1)(str(path))
            code = self.api.RmRegisterResources(session, 1, files, 0, None, 0, None)
            if code:
                raise OSError("无法登记所选文件的占用查询 (Windows %d)" % code)
            capacity = 0
            for _ in range(4):
                needed, count, reason = w.UINT(), w.UINT(capacity), w.DWORD()
                records = (self.ProcessInfo * capacity)() if capacity else None
                code = self.api.RmGetList(session, ctypes.byref(needed), ctypes.byref(count),
                                          records, ctypes.byref(reason))
                if not code:
                    return [dict(pid=p.process.pid, created=str(
                        (p.process.created.dwHighDateTime << 32) | p.process.created.dwLowDateTime),
                        application=p.name, kind=p.kind, session=p.session)
                        for p in (records or ())[:count.value]]
                if code != 234 or needed.value > 256:
                    raise OSError("无法取得稳定的文件占用列表 (Windows %d)" % code)
                capacity = max(needed.value, capacity + 1)
            raise OSError("占用应用持续变化，请重试")
        finally:
            self.api.RmEndSession(session)


class NativeForceDelete:
    def __init__(self, protected=()):
        from task_manager import NativeProcesses
        system = os.environ.get("SystemRoot", r"C:\Windows")
        self.processes = NativeProcesses((*protected, system))
        self.occupancy = RestartManager()
        self.kernel = k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p,
                                  w.DWORD, w.DWORD, w.HANDLE]
        k.CreateFileW.restype = w.HANDLE
        k.CloseHandle.argtypes = [w.HANDLE]
        k.GetFileInformationByHandle.argtypes = [w.HANDLE, ctypes.c_void_p]
        k.SetFileInformationByHandle.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        k.OpenProcess.restype = w.HANDLE
        k.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
        k.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        k.ProcessIdToSessionId.argtypes = [w.DWORD, ctypes.POINTER(w.DWORD)]
        self.session = w.DWORD()
        if not k.ProcessIdToSessionId(os.getpid(), ctypes.byref(self.session)):
            raise ctypes.WinError(ctypes.get_last_error())

    def blockers(self, path):
        result = []
        for entry in self.occupancy.list(path):
            current = self.processes.info(entry["pid"])
            # Restart Manager's PID alone is insufficient: it can be reused
            # while a phone confirmation is open or enumeration is in flight.
            same = current["created"] == entry["created"]
            can_close = bool(same and current["can_terminate"] and
                             entry["kind"] not in (3, 4, 1000) and
                             entry["session"] == self.session.value)
            result.append(dict(pid=entry["pid"], created=entry["created"],
                               application=entry["application"] or current["name"],
                               path=current["path"] if same else "", can_close=can_close,
                               detected=time.time()))
        return result

    def _delete_handle(self, path, original):
        k = self.kernel
        # DELETE + FILE_READ_ATTRIBUTES. OPEN_REPARSE_POINT prevents following a
        # last-component link substituted after the directory listing.
        handle = k.CreateFileW(str(path), 0x10080, 7, None, 3, 0x00200000, None)
        if handle == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        legacy = False
        try:
            class Info(ctypes.Structure):
                _fields_ = [("attributes", w.DWORD), ("created", w.FILETIME),
                            ("accessed", w.FILETIME), ("modified", w.FILETIME),
                            ("volume", w.DWORD), ("size_high", w.DWORD), ("size_low", w.DWORD),
                            ("links", w.DWORD), ("index_high", w.DWORD), ("index_low", w.DWORD)]
            info = Info()
            if not k.GetFileInformationByHandle(handle, ctypes.byref(info)):
                raise ctypes.WinError(ctypes.get_last_error())
            if info.attributes & (0x400 | 0x10) or (info.volume,
                    (info.index_high << 32) | info.index_low) != identity(original):
                raise ValueError("文件身份已变化或已变成链接，未删除替换后的对象")
            # POSIX semantics removes the directory entry while cooperative
            # handles remain open; space is released when the last handle closes.
            for flags in (0x13, 0x3):  # DELETE | POSIX | (IGNORE_READONLY)
                disposition = w.DWORD(flags)
                if k.SetFileInformationByHandle(handle, 21, ctypes.byref(disposition), ctypes.sizeof(disposition)):
                    return dict(deleted=True, method="posix",
                                notice="已删除原文件目录项；仍被持有的数据在最后一个句柄关闭后释放")
                code = ctypes.get_last_error()
                if code not in (1, 50, 87):
                    raise ctypes.WinError(code)
            disposition = ctypes.c_ubyte(1)
            if not k.SetFileInformationByHandle(handle, 4, ctypes.byref(disposition), 1):
                raise ctypes.WinError(ctypes.get_last_error())
            legacy = True
        finally:
            k.CloseHandle(handle)
        if legacy:
            try:
                remaining = path.lstat()
            except FileNotFoundError:
                return dict(deleted=True, method="legacy")
            except PermissionError:
                remaining = original
            if identity(remaining) != identity(original):
                return dict(deleted=True, method="legacy", notice="原文件已删除；程序创建了另一个同名文件")
            return dict(deleted=False, pending_release=True,
                        notice="Windows 已标记删除，仍需占用句柄关闭；尚未确认立即删除")

    def end(self, item):
        handle = self.kernel.OpenProcess(0x101001, False, item["pid"])
        if not handle:
            if ctypes.get_last_error() == 87:  # Process already exited.
                return
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            current = self.processes.info(item["pid"], handle=handle)
            session = w.DWORD()
            if (not current["can_terminate"] or current["created"] != item["created"] or
                    os.path.normcase(current["path"]) != os.path.normcase(item["path"]) or
                    not self.kernel.ProcessIdToSessionId(item["pid"], ctypes.byref(session)) or
                    session.value != self.session.value):
                raise ValueError("占用进程身份、路径或会话已变化，未结束其他程序")
            if not self.kernel.TerminateProcess(handle, 1):
                raise ctypes.WinError(ctypes.get_last_error())
            if self.kernel.WaitForSingleObject(handle, 5000):
                raise ValueError("已请求结束程序，但句柄尚未释放，请重试")
        finally:
            self.kernel.CloseHandle(handle)

    def remove(self, path, original, approved, cancel):
        def check():
            if cancel.is_set():
                from file_jobs import Cancelled
                raise Cancelled()
            current = path.lstat()
            if not stat.S_ISREG(current.st_mode) or identity(current) != identity(original):
                raise ValueError("文件身份已变化，未删除替换后的对象")
        check()
        try:
            return self._delete_handle(path, original)
        except OSError as exc:
            if exc.winerror not in (5, 32, 33):
                raise
            original_error = exc
        blockers = self.blockers(path)
        if not blockers:
            raise OSError("Windows 拒绝删除，未找到可验证的占用应用；可能是权限或驱动限制：%s" % original_error)
        keys = lambda items: {(p["pid"], p["created"], os.path.normcase(p["path"])) for p in items}
        if not approved or not keys(blockers).issubset(keys(approved)):
            raise FileInUse(blockers)
        if any(not p["can_close"] for p in blockers):
            raise ValueError("文件被系统服务、受保护进程或其他登录会话占用，未结束这些进程")
        for item in blockers:
            check()
            self.end(item)
        for attempt in range(10):
            check()
            try:
                result = self._delete_handle(path, original)
                result["ended"] = [dict(p) for p in blockers]
                return result
            except OSError as exc:
                if exc.winerror not in (5, 32, 33) or attempt == 9:
                    raise
                if cancel.wait(.1):
                    check()

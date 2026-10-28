"""Explicit, bounded disk reservations using ordinary hidden/system files.

No sparse allocation, valid-data shortcuts, ACL concealment or device policies.
An unfinished native file is delete-pending: closing its handle, even following
receiver termination, removes it. Successful files remain visible to LanCmd ls.
"""
import atexit
import ctypes
import os
from pathlib import Path
import shutil
import stat
import threading
import time
import uuid
from contextlib import contextmanager, nullcontext

MAX_BYTES = 500_000_000_000
RESERVE_BYTES = 1_000_000_000
CHUNK_BYTES = 4 * 1024 * 1024
ALLOCATION_MIN = 1024 * 1024
ALLOCATION_MAX = 256 * 1024 * 1024
FLUSH_BYTES = 64 * 1024 * 1024


class FastAllocationUnsupported(Exception):
    pass


class FastAllocationCancelled(Exception):
    pass


def target_path(value, check_disk=True):
    if not isinstance(value, str) or not value.strip() or len(value) > 32700 or "\0" in value:
        raise ValueError("请输入占位文件的完整路径")
    expanded = os.path.expanduser(value)
    if not os.path.isabs(expanded):
        raise ValueError("请输入占位文件的绝对路径")
    raw = Path(expanded)
    # GetFullPathNameW (used by ntpath.abspath) can erase a trailing dot before
    # validation. Validate the caller's basename before Win32 normalization.
    name = raw.name
    if (not name or name.endswith((".", " ")) or any(ord(c) < 32 or c in ':*?"<>|' for c in name) or
            name.split(".")[0].rstrip(" ").upper() in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
                *["COM%s" % n for n in "123456789¹²³"], *["LPT%s" % n for n in "123456789¹²³"]}):
        raise ValueError("文件名不兼容 Windows")
    if any(part.endswith((".", " ")) and part not in (".", "..") for part in raw.parts[1:-1]):
        raise ValueError("目录名含有 Windows 会改写的尾随点或空格")
    path = Path(os.path.abspath(expanded))
    if not check_disk:
        return path
    for parent in (path.parent, *path.parent.parents):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("请选择普通目录，不支持链接或重解析点")
    if os.path.lexists(path):
        raise ValueError("目标已存在，请使用新文件名；不会覆盖已有文件")
    return path


class NativeAllocation:
    def __init__(self, path):
        import msvcrt
        from ctypes import wintypes as w
        self.path = path
        self.kernel = k = ctypes.WinDLL("kernel32", use_last_error=True)
        k.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.HANDLE]
        k.CreateFileW.restype = w.HANDLE
        k.CloseHandle.argtypes = [w.HANDLE]
        k.GetFileInformationByHandleEx.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        k.SetFileInformationByHandle.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        k.GetFinalPathNameByHandleW.argtypes = [w.HANDLE, w.LPWSTR, w.DWORD, w.DWORD]
        k.GetFinalPathNameByHandleW.restype = w.DWORD
        k.DeviceIoControl.argtypes = [w.HANDLE, w.DWORD, ctypes.c_void_p, w.DWORD, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p]
        self.handle = self.directory = None
        self.stream = None
        self.committed = False
        self.pending = False
        self.identity = None
        try:
            # Hold the parent without delete sharing while resolving the new file.
            self.directory = k.CreateFileW(str(path.parent), 0x80, 3, None, 3, 0x02200000, None)
            if self.directory == ctypes.c_void_p(-1).value:
                self.directory = None
                raise ctypes.WinError(ctypes.get_last_error())
            if self.basic(self.directory) & 0x400:
                raise ValueError("父目录已变为重解析点，未创建")
            self.expected = self.final_path(self.directory).rstrip("\\") + "\\" + path.name
            # CREATE_NEW + DELETE access; no overwrite and no delete/rename sharing.
            self.handle = k.CreateFileW(str(path), 0xC0010000, 1, None, 1, 0x08000006, None)
            if self.handle == ctypes.c_void_p(-1).value:
                self.handle = None
                raise ctypes.WinError(ctypes.get_last_error())
            fd = msvcrt.open_osfhandle(int(self.handle), os.O_RDWR | os.O_BINARY)
            self.identity = os.fstat(fd)
            try:
                self.stream = os.fdopen(fd, "wb", buffering=0)
            except Exception:
                os.close(fd)
                self.handle = None
                raise
            self.disposition(True)
            self.pending = True
            if self.final_path(self.handle).casefold() != self.expected.casefold():
                raise ValueError("目录在创建期间已变化，已取消")
            if self.basic(self.handle) & 0x800:
                compression = w.USHORT(0)
                returned = w.DWORD()
                if not k.DeviceIoControl(self.handle, 0x9C040, ctypes.byref(compression), 2, None, 0, ctypes.byref(returned), None):
                    raise ctypes.WinError(ctypes.get_last_error())
            if self.basic(self.handle) & (0x200 | 0x800):
                raise ValueError("无法创建非稀疏、非压缩的占位文件")
        except Exception:
            self.close()
            raise

    def basic(self, handle):
        from ctypes import wintypes as w
        class Basic(ctypes.Structure):
            _fields_ = [(n, ctypes.c_longlong) for n in ("creation", "access", "write", "change")] + [("attributes", w.DWORD)]
        value = Basic()
        if not self.kernel.GetFileInformationByHandleEx(handle, 0, ctypes.byref(value), ctypes.sizeof(value)):
            raise ctypes.WinError(ctypes.get_last_error())
        return value.attributes

    def final_path(self, handle):
        value = ctypes.create_unicode_buffer(32768)
        count = self.kernel.GetFinalPathNameByHandleW(handle, value, len(value), 0)
        if not count or count >= len(value):
            raise ctypes.WinError(ctypes.get_last_error())
        return value.value

    def disposition(self, delete):
        value = ctypes.c_ubyte(bool(delete))  # FILE_DISPOSITION_INFO.DeleteFile is BOOLEAN
        if not self.kernel.SetFileInformationByHandle(self.handle, 4, ctypes.byref(value), ctypes.sizeof(value)):
            raise ctypes.WinError(ctypes.get_last_error())

    def write(self, data):
        return self.stream.write(data)

    def reserve(self,size,progress=lambda amount:None,cancelled=lambda:False):
        """Reserve real clusters in bounded steps; keep Windows zero-read semantics."""
        if type(size) is not int or not 0<=size<=MAX_BYTES:raise ValueError("占位大小无效")
        if size==0:return
        def set_info(info_class,value):
            amount=ctypes.c_longlong(value)
            if not self.kernel.SetFileInformationByHandle(self.handle,info_class,ctypes.byref(amount),ctypes.sizeof(amount)):
                code=ctypes.get_last_error()
                if code in (1,50,87,120):raise FastAllocationUnsupported("文件系统不支持快速实际分配")
                raise ctypes.WinError(code)
        # No SetFileValidData and no sparse marking. Windows returns zeros for
        # bytes beyond valid-data length, while ordinary clusters are reserved.
        # A single 16 GiB allocation or one final huge EOF update can monopolize
        # a USB controller. Bound BOTH operations and adapt to measured latency.
        step=8*1024**2
        start=0
        while start<size:
            if cancelled():raise FastAllocationCancelled()
            started=time.monotonic()
            target=min(size,start+step)
            set_info(5,max(4096,target))  # FileAllocationInfo
            if cancelled():raise FastAllocationCancelled()
            set_info(6,target)  # FileEndOfFileInfo; never one final whole-drive jump.
            actual=self.inspect()["allocated"]
            if actual<target:raise FastAllocationUnsupported("文件系统未确认实际空间预分配")
            progress(target)
            elapsed=max(.001,time.monotonic()-started)
            step=max(ALLOCATION_MIN,min(ALLOCATION_MAX,step*2,max(step//2,int(step*.25/elapsed))))
            start=target
        if cancelled():raise FastAllocationCancelled()

    def flush(self):
        self.stream.flush()
        os.fsync(self.stream.fileno())

    def inspect(self):
        from ctypes import wintypes as w
        class Standard(ctypes.Structure):
            _fields_ = [("allocation", ctypes.c_longlong), ("size", ctypes.c_longlong), ("links", w.DWORD),
                        ("pending", ctypes.c_ubyte), ("directory", ctypes.c_ubyte)]
        value = Standard()
        if not self.kernel.GetFileInformationByHandleEx(self.handle, 1, ctypes.byref(value), ctypes.sizeof(value)):
            raise ctypes.WinError(ctypes.get_last_error())
        return dict(size=value.size, allocated=value.allocation, attributes=self.basic(self.handle))

    def commit(self):
        if self.final_path(self.handle).casefold() != self.expected.casefold():
            raise ValueError("文件所在目录已变化，已取消创建")
        self.disposition(False)
        self.pending = False
        self.committed = True

    def close(self):
        if self.stream is not None:
            self.stream.close()
            self.stream = None
            self.handle = None
        if self.handle is not None:
            self.kernel.CloseHandle(self.handle)
            self.handle = None
        if self.directory is not None:
            self.kernel.CloseHandle(self.directory)
            self.directory = None
        if not self.committed and not self.pending and self.identity is not None:
            try:
                current = self.path.lstat()
                if (current.st_dev, current.st_ino) == (self.identity.st_dev, self.identity.st_ino):
                    self.path.unlink()
            except FileNotFoundError:
                pass


class NativeBackend:
    @contextmanager
    def background(self):
        # Give interactive foreground I/O precedence without imposing a transfer
        # rate limit when the drive is idle. Best effort; no elevation or UI.
        kernel=handle=None
        applied=False
        try:
            from ctypes import wintypes as w
            kernel=ctypes.WinDLL("kernel32",use_last_error=True)
            kernel.GetCurrentThread.restype=w.HANDLE
            kernel.SetThreadPriority.argtypes=[w.HANDLE,ctypes.c_int]
            handle=kernel.GetCurrentThread()
            applied=bool(kernel.SetThreadPriority(handle,0x10000))
        except (OSError,AttributeError):
            pass
        try:
            yield
        finally:
            if applied:kernel.SetThreadPriority(handle,0x20000)

    def validate(self, path, size):
        from ctypes import wintypes as w
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetDriveTypeW.argtypes = [w.LPCWSTR]
        # Network shares and virtual namespaces do not promise local allocation.
        if not path.drive or path.drive.startswith("\\") or kernel.GetDriveTypeW(path.anchor) not in (2, 3):
            raise ValueError("请选择本地磁盘或 U 盘中的普通目录")
        kernel.GetVolumePathNameW.argtypes = [w.LPCWSTR, w.LPWSTR, w.DWORD]
        kernel.GetVolumeInformationW.argtypes = [w.LPCWSTR, w.LPWSTR, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.POINTER(w.DWORD), ctypes.POINTER(w.DWORD), w.LPWSTR, w.DWORD]
        volume = ctypes.create_unicode_buffer(32768)
        fs = ctypes.create_unicode_buffer(64)
        if not kernel.GetVolumePathNameW(str(path.parent), volume, len(volume)) or not kernel.GetVolumeInformationW(volume, None, 0, None, None, None, fs, len(fs)):
            raise ctypes.WinError(ctypes.get_last_error())
        if fs.value.upper() in ("FAT", "FAT32") and size > 0xFFFFFFFF:
            raise ValueError("FAT/FAT32 单文件不能超过 4 GB，请改用 NTFS/exFAT 或减小文件")

    def free(self, path):
        return shutil.disk_usage(path.parent).free

    def create(self, path):
        return NativeAllocation(path)


class PlaceholderFiles:
    def __init__(self, backend=None):
        self.backend = backend
        self.lock = threading.RLock()
        self.jobs = {}
        self.stopped = False
        atexit.register(self.close)

    def _public(self, job):
        data=dict(job["data"])
        data["cancel_requested"]=job["cancel"].is_set()
        data["can_cancel"]=not job["done"].is_set() and not job.get("committing",False) and not job["cancel"].is_set()
        data["idle_seconds"]=max(0,int(time.time()-data.get("updated",data["created"])))
        if not job["done"].is_set():
            if job["cancel"].is_set() and not job.get("committing"):
                data["status"]="cancelling"
            elif data["status"] in ("cancelled","failed","completed"):
                data["status"]="cleaning"
        return data

    def request(self, q, state, error):
        try:
            if not state.client_id:
                raise ValueError("连接身份无效")
            if self.backend is None:
                if os.name != "nt":
                    raise ValueError("占位文件仅支持 Windows")
                self.backend = NativeBackend()
            with self.lock:
                action = q.get("action", "list")
                if action == "list":
                    return dict(items=[self._public(j) for j in reversed(list(self.jobs.values())) if j["owner"] == state.client_id],
                                max_bytes=MAX_BYTES, reserve_bytes=RESERVE_BYTES)
                if action == "cancel":
                    job = self.jobs.get(q.get("job_id"))
                    if not job or job["owner"] != state.client_id:
                        raise ValueError("任务不存在或属于其他手机")
                    cancellable=not job["done"].is_set() and not job.get("committing")
                    if cancellable:
                        job["cancel"].set()
                    return {"cancel_requested": cancellable}
                if action != "create":
                    raise ValueError("无效的占位文件操作")
                size = q.get("size")
                if type(size) is not int or not 0 <= size <= MAX_BYTES:
                    raise ValueError("占位大小须为 0–500 GB 的整数字节数（1 GB = 1,000,000,000 字节）")
                if self.stopped or any(not j["done"].is_set() for j in self.jobs.values()):
                    raise ValueError("当前有占位文件正在创建或接收端正在退出，请等待或取消")
                # Admission only performs lexical validation. All volume I/O is
                # asynchronous so a stalled USB cannot hold the job registry lock.
                path = target_path(q.get("path"), check_disk=False)
                completed = [rid for rid, j in self.jobs.items() if j["done"].is_set()]
                while len(self.jobs) >= 100 and completed:
                    self.jobs.pop(completed.pop(0))
                rid = uuid.uuid4().hex
                job = dict(owner=state.client_id, cancel=threading.Event(), done=threading.Event(),
                           data=dict(id=rid, path=str(path), size=size, written=0, allocated=0, status="queued", created=time.time(), updated=time.time()))
                self.jobs[rid] = job
                worker = threading.Thread(target=self._run, args=(job, path), name="placeholder-file", daemon=True)
                job["thread"] = worker
                worker.start()
                return {"job": self._public(job)}
        except (OSError, ValueError, TypeError) as exc:
            raise error(str(exc))

    def _run(self, job, path):
        with getattr(self.backend,"background",nullcontext)():
            self._execute(job,path)

    def _execute(self, job, path):
        allocation = None
        data = job["data"]
        try:
            if job["cancel"].is_set():
                data["status"] = "cancelled"
                return
            data["status"]="checking"
            path=target_path(str(path))
            self.backend.validate(path,data["size"])
            if data["size"] and self.backend.free(path)-data["size"]<RESERVE_BYTES:
                raise ValueError("磁盘空间不足：创建后须至少保留 1 GB 可用空间")
            if job["cancel"].is_set():
                data["status"]="cancelled"
                return
            allocation = self.backend.create(path)
            reserve=getattr(allocation,"reserve",None)
            data.update(status="allocating" if callable(reserve) else "writing",method="preallocate" if callable(reserve) else "stream")
            if callable(reserve):
                try:
                    if data["size"] and self.backend.free(path)-data["size"]<RESERVE_BYTES:
                        raise ValueError("空间已变化，快速分配后无法保留 1 GB，已取消")
                    def progress(amount):
                        data["written"]=min(amount,data["size"])
                        data["updated"]=time.time()
                        if data["size"] and self.backend.free(path)<RESERVE_BYTES:
                            raise ValueError("分配后剩余空间不足 1 GB，已取消并清理")
                    reserve(data["size"],progress,job["cancel"].is_set)
                    info=allocation.inspect()
                    if info["size"]!=data["size"] or info["allocated"]<data["size"]:
                        raise FastAllocationUnsupported("未确认快速实际分配，改用逐块写入")
                    data["written"]=data["size"]
                except FastAllocationUnsupported:
                    # Delete-pending cleanup releases any partial reservation before
                    # starting the conservative writer with a fresh exclusive file.
                    data["status"]="cleaning"
                    allocation.close();allocation=None
                    if job["cancel"].is_set():data["status"]="cancelled";return
                    if data["size"] and self.backend.free(path)-data["size"]<RESERVE_BYTES:
                        raise ValueError("空间不足，无法回退写入；未完成文件已清理")
                    allocation=self.backend.create(path)
                    data.update(status="writing",method="stream",written=0)
            block = bytes(CHUNK_BYTES) if data["written"]<data["size"] else b""
            unflushed=0
            while data["written"] < data["size"]:
                if job["cancel"].is_set():
                    data["status"] = "cancelled"
                    return
                chunk = block[:min(CHUNK_BYTES, data["size"] - data["written"])]
                if self.backend.free(path) - len(chunk) < RESERVE_BYTES:
                    raise ValueError("剩余空间已接近 1 GB，已停止并清理未完成文件")
                count = allocation.write(chunk)
                if not count:
                    raise OSError("写入未取得进展")
                data["written"] += count
                data["updated"]=time.time()
                unflushed+=count
                if unflushed>=FLUSH_BYTES:
                    if job["cancel"].is_set():
                        data["status"]="cancelled"
                        return
                    data["status"]="flushing"
                    allocation.flush()
                    unflushed=0
                    data.update(status="writing",updated=time.time())
            if job["cancel"].is_set():
                data["status"]="cancelled"
                return
            data["status"] = "verifying"
            allocation.flush()
            info = allocation.inspect()
            if (info["size"] != data["size"] or info["allocated"] < data["size"] or
                    info["attributes"] & 6 != 6 or info["attributes"] & (0x200 | 0x800)):
                raise ValueError("未确认实际空间分配或隐藏/系统属性，已清理未完成文件")
            if data["size"] and self.backend.free(path) < RESERVE_BYTES:
                raise ValueError("剩余空间不足 1 GB，已清理未完成文件")
            with self.lock:
                if job["cancel"].is_set():
                    data["status"] = "cancelled"
                    return
                # Claim the non-cancellable commit boundary under the lock, but
                # never execute a potentially blocking device call while holding it.
                job["committing"]=True
                data["status"]="committing"
            allocation.commit()
            data.update(allocated=info["allocated"], status="completed")
        except FastAllocationCancelled:
            data["status"]="cancelled"
        except Exception as exc:
            data.update(status="failed", error=str(exc)[:1600])
        finally:
            if allocation is not None:
                try:
                    allocation.close()
                except OSError as exc:
                    data.update(status="failed", error="关闭文件失败，请检查目标路径：" + str(exc)[:1000])
            data["finished"] = time.time()
            job["done"].set()

    def close(self):
        with self.lock:
            self.stopped = True
            for job in self.jobs.values():
                if not job["done"].is_set():
                    job["cancel"].set()

    def stop_for_device(self, device):
        paths = [str(p).replace("/", "\\").rstrip("\\").casefold() + "\\" for p in device.get("paths", []) if p]
        with self.lock:
            active = [j for j in self.jobs.values() if not j["done"].is_set()]
            if active and not paths:
                raise ValueError("设备路径未知，请先取消占位文件任务并等待清理，再强制断开")
            matches = [j for j in active if any(j["data"]["path"].replace("/", "\\").casefold().startswith(p) for p in paths)]
            for job in matches:
                job["cancel"].set()
        deadline = time.monotonic() + 5
        for job in matches:
            if not job["done"].wait(max(0, deadline - time.monotonic())):
                raise ValueError("占位文件仍在停止和清理，请稍后重试；设备未被禁用")

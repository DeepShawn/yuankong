"""Visible, computer-local maintenance. No network, RPC or unattended action mode.

The helper runs outside the installation. It stops only the PID whose executable
belongs to that installation, waits for recovery workers, and asks again locally
before changing anything. Logs, recovery journals and unknown files are retained.
"""
import ctypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid


MANIFEST = "release-manifest.json"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
DOCUMENTS = {".txt", ".md", ".csv", ".tsv", ".rtf", ".pdf", ".doc", ".docx",
             ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp"}


def ordinary(path, directory=False):
    path = Path(path)
    for parent in (*reversed(path.parents), path):
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("路径不能包含链接、目录联接或重解析点：" + str(parent))
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError("请选择普通文件或目录：" + str(path))
    if not directory and (info.st_nlink != 1 or not info.st_ino):
        raise ValueError("文件必须具有唯一身份且不能包含硬链接：" + str(path))
    return info


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def copy_exclusive(source, target):
    """Rollback must never overwrite a file created after maintenance started."""
    ordinary(Path(target).parent, directory=True)
    with Path(source).open("rb") as reader, Path(target).open("xb") as writer:
        shutil.copyfileobj(reader, writer)
        writer.flush()
        os.fsync(writer.fileno())
    shutil.copystat(source, target, follow_symlinks=False)


def save(path, data):
    """Write the recovery/audit record before changing its corresponding file."""
    path = Path(path)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def load(path, limit=1024 * 1024):
    if ordinary(path).st_size > limit:
        raise ValueError("记录过大，请保留后检查：" + str(path))
    return json.loads(Path(path).read_text(encoding="utf-8"))


def audit(session, event, **fields):
    with (Path(session) / "maintenance.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(dict(time=datetime.datetime.now().isoformat(), event=event,
                                     **fields), ensure_ascii=False) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def within(path, root):
    return path == root or root in path.parents


def manifest(root):
    root = Path(root).absolute()
    ordinary(root, directory=True)
    if root.parent == root:
        raise ValueError("安装目录不能位于磁盘根目录")
    for key in ("SystemRoot", "WINDIR", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "ProgramData", "ProgramFiles", "ProgramFiles(x86)"):
        if os.environ.get(key):
            special = Path(os.environ[key]).absolute()
            if within(special, root) or (key in ("SystemRoot", "WINDIR") and within(root, special)):
                raise ValueError("不能维护系统、用户主目录或配置数据目录")
    if os.environ.get("APPDATA") and within(root, Path(os.environ["APPDATA"]) / "LanCmd"):
        raise ValueError("不能将配置／恢复目录作为安装目录维护")
    data = load(root / MANIFEST)
    files = data.get("files") if isinstance(data, dict) else None
    required = {"Start.exe", "LanCmdAgent.exe", "lan_command_agent.py", "python/python.exe",
                "python/pythonw.exe", "python/python311.dll", "local_maintenance.py"}
    if (not isinstance(data, dict) or data.get("product") != "LanCmd-Windows" or data.get("format") != 1 or
            not isinstance(files, dict) or not required.issubset(files) or len(files) > 2000):
        raise ValueError("需要包含有效发布清单的完整 Windows 包")
    seen = set()
    for name, expected in files.items():
        if (not isinstance(name, str) or not name or "\\" in name or ":" in name or
                any(p in ("", ".", "..") or p.endswith((".", " ")) or
                    re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", p) for p in name.split("/")) or
                name.casefold() in seen or name == MANIFEST):
            raise ValueError("发布清单包含无效路径")
        seen.add(name.casefold())
        if (not isinstance(expected, dict) or type(expected.get("size")) is not int or
                not isinstance(expected.get("sha256"), str)):
            raise ValueError("发布清单格式错误")
        path = root / name
        info = ordinary(path)
        if info.st_size != expected["size"] or digest(path) != expected["sha256"]:
            raise ValueError("安装文件已变化，未执行维护：" + name)
    return data


def recovery_check(state):
    """Never strand persisted restrictions by removing the code to undo them."""
    state = Path(state)
    for name in ("app-locks.json", "file-locks.json", "device-policy.json", "usb-disconnects.json", "file-jobs.json"):
        path = state / name
        if not path.exists():
            continue
        data = load(path)
        if not isinstance(data, dict):
            raise ValueError("恢复记录损坏，请保留并检查：" + str(path))
        if data:
            raise ValueError("请先恢复并清理锁定、设备策略或文件任务暂存记录，再维护：" + str(path))
    pending = state / "upgrade/upgrade-status.json"
    if pending.exists():
        data = load(pending)
        if not isinstance(data, dict) or data.get("status") not in ("completed", "failed", "rolled_back", "cancelled"):
            raise ValueError("本地升级尚未结束，请先完成或恢复升级：" + str(pending))


def new_name(root, text):
    if (not isinstance(text, str) or not text.strip() or text != text.strip() or len(text) > 80 or
            re.search(r'[<>:"/\\|?*\x00-\x1f]', text) or text.endswith((".", " ")) or
            text in (".", "..") or re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", text)):
        raise ValueError("请输入 1–80 字符的普通文件夹名，不能包含路径或 Windows 保留名称")
    target = Path(root).parent / text
    if target.exists() or target.is_symlink():
        raise ValueError("目标名称已存在，请选择其他名称")
    return target


class StartupEntry:
    """Only touch the exact Run value owned by this installation."""
    def __init__(self, root):
        import winreg
        self.registry = winreg
        self.expected = '"%s"' % (Path(root) / "LanCmdAgent.exe")

    def read(self):
        r = self.registry
        try:
            with r.OpenKey(r.HKEY_CURRENT_USER, RUN_KEY) as key:
                return r.QueryValueEx(key, "LanCmdAgent")
        except FileNotFoundError:
            return None

    def change(self, expected, replacement):
        r = self.registry
        with r.CreateKeyEx(r.HKEY_CURRENT_USER, RUN_KEY, 0, r.KEY_QUERY_VALUE | r.KEY_SET_VALUE) as key:
            if self.read() != expected:
                raise ValueError("自启动项已被其他程序更改，未覆盖")
            if replacement is None:
                r.DeleteValue(key, "LanCmdAgent")
            else:
                r.SetValueEx(key, "LanCmdAgent", 0, *replacement[::-1])

    def owned(self, value):
        return bool(value and value[1] == self.registry.REG_SZ and
                    value[0].casefold() == self.expected.casefold())


def migrate(root, target, session, startup, check):
    root, target, session = Path(root), Path(target), Path(session)
    manifest(root)
    if target != new_name(root, target.name):
        raise ValueError("只能更改安装目录名称，不能移动到其他位置")
    check()
    before = startup.read()
    after = ('"%s"' % (target / "LanCmdAgent.exe"), before[1]) if startup.owned(before) else before
    plan = dict(operation="rename", root=str(root), destination=str(target), startup_before=before,
                startup_after=after, status="prepared")
    save(session / "installation.json", plan)
    renamed = False
    registered = False
    try:
        root.rename(target)
        renamed = True
        if after != before:
            startup.change(before, after)
            registered = True
        plan["status"] = "complete"
        save(session / "installation.json", plan)
        audit(session, "renamed", source=str(root), destination=str(target))
    except Exception:
        if renamed:
            try:
                if root.exists():
                    raise ValueError("原位置已被占用，未覆盖；保留迁移记录以便本机恢复")
                target.rename(root)
                if registered:
                    startup.change(after, before)
                plan["status"] = "rolled_back"
                save(session / "installation.json", plan)
            except Exception as failure:
                audit(session, "rollback_failed", error=str(failure))
        raise


def uninstall(root, session, startup, check):
    """Remove only verified release-owned files; retain a recoverable copy."""
    root, session = Path(root), Path(session)
    data = manifest(root)
    check()
    before = startup.read()
    plan = dict(operation="uninstall", root=str(root), startup_before=before,
                files=list(data["files"]) + [MANIFEST], status="prepared", moved=[])
    save(session / "installation.json", plan)
    backup = session / "removed-files"
    backup.mkdir()
    registered = False
    try:
        if startup.owned(before):
            startup.change(before, None)
            registered = True
        for name in plan["files"]:
            source, target = root / name, backup / name
            ordinary(source)
            if name != MANIFEST:
                expected = data["files"][name]
                if source.stat().st_size != expected["size"] or digest(source) != expected["sha256"]:
                    raise ValueError("文件在卸载期间发生变化，未删除：" + name)
            target.parent.mkdir(parents=True, exist_ok=True)
            # Cross-volume copy completes and is verified before the source is removed.
            copy_exclusive(source, target)
            if digest(source) != digest(target):
                raise ValueError("备份校验失败，保留原文件：" + name)
            plan["moved"].append(name)
            save(session / "installation.json", plan)
            source.unlink()
        plan["status"] = "complete"
        save(session / "installation.json", plan)
        for path in sorted({(root / name).parent for name in plan["files"]}, key=lambda p: len(p.parts), reverse=True):
            try:
                path.rmdir()
            except OSError:
                pass  # User files and directories are intentionally retained.
        audit(session, "uninstalled", root=str(root), backup=str(backup))
    except Exception:
        conflicts = []
        for name in reversed(plan["moved"]):
            source, target = backup / name, root / name
            if os.path.lexists(target):
                try:
                    ordinary(target)
                    if digest(target) != digest(source):
                        conflicts.append(str(target))
                except (OSError, ValueError):
                    conflicts.append(str(target))
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            copy_exclusive(source, target)
        if registered:
            startup.change(None, before)
        plan.update(status="rollback_conflict" if conflicts else "rolled_back", conflicts=conflicts)
        save(session / "installation.json", plan)
        raise


def document(path, protected):
    path = Path(path).absolute()
    if (path.suffix.casefold() not in DOCUMENTS or str(path).startswith("\\\\") or
            (os.name == "nt" and ":" in os.path.splitdrive(str(path))[1]) or
            any(within(path, Path(root).absolute()) for root in protected)):
        raise ValueError("仅支持普通本地文档；不能修改系统、应用、日志或恢复记录：" + str(path))
    ordinary(path)
    return path


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


def dates(paths, modified, session, protected):
    if type(modified) is not int or not 0 <= modified < 4102444800 * 10**9 or not 1 <= len(paths) <= 100:
        raise ValueError("日期须在 1970–2099 年之间，每批 1–100 个文档")
    selected = list(dict.fromkeys(document(path, protected) for path in paths))
    record = Path(session) / ("dates-" + uuid.uuid4().hex + ".json")
    data = dict(format=1, operation="document_dates", modified=modified, entries=[])
    for path in selected:
        with DocumentHandle(path) as handle:
            data["entries"].append(dict(path=str(path), **handle.snapshot(), status="pending"))
    save(record, data)
    for entry in data["entries"]:
        try:
            document(entry["path"], protected)
            with DocumentHandle(entry["path"]) as handle:
                if handle.snapshot() != {k: entry[k] for k in ("identity", "size", "mtime_ns")}:
                    raise ValueError("文档在选择后已变化，未修改")
                handle.set(modified)
                if handle.snapshot()["mtime_ns"] != modified:
                    raise ValueError("文件系统未保存精确日期，请检查恢复记录")
                entry["status"] = "applied"
        except (OSError, ValueError) as exc:
            entry.update(status="failed", error=str(exc))
        save(record, data)
    return record, data


def restore_dates(record, protected):
    data = load(record)
    if (not isinstance(data, dict) or data.get("format") != 1 or data.get("operation") != "document_dates" or
            type(data.get("modified")) is not int or not 0 <= data["modified"] < 4102444800 * 10**9 or
            not isinstance(data.get("entries"), list) or
            not 1 <= len(data["entries"]) <= 100):
        raise ValueError("不是有效的文档日期恢复记录")
    if any(not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or
           not isinstance(entry.get("identity"), list) or len(entry["identity"]) != 2 or
           any(type(v) is not int for v in entry["identity"]) or type(entry.get("size")) is not int or
           type(entry.get("mtime_ns")) is not int for entry in data["entries"]):
        raise ValueError("日期恢复条目损坏，未修改任何文件")
    for entry in data["entries"]:
        if entry.get("status") == "restored":
            continue
        try:
            path = document(entry["path"], protected)
            if type(entry.get("mtime_ns")) is not int or not 0 <= entry["mtime_ns"] < 4102444800 * 10**9:
                raise ValueError("原日期记录无效")
            with DocumentHandle(path) as handle:
                current = handle.snapshot()
                if current["identity"] != entry["identity"] or current["size"] != entry["size"]:
                    raise ValueError("文档身份或内容大小已变化，未覆盖")
                if current["mtime_ns"] == entry["mtime_ns"]:
                    entry["status"] = "restored"
                elif current["mtime_ns"] == data["modified"]:
                    handle.set(entry["mtime_ns"])
                    if handle.snapshot()["mtime_ns"] != entry["mtime_ns"]:
                        raise ValueError("未确认原日期已恢复")
                    entry["status"] = "restored"
                else:
                    raise ValueError("文档后来已修改，未覆盖当前日期")
            entry.pop("error", None)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            entry["error"] = str(exc)
        save(record, data)
    return data


class WindowsProcesses:
    def __init__(self):
        from ctypes import wintypes as w
        self.w = w
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        self.ps = ctypes.WinDLL("psapi", use_last_error=True)
        self.ps.EnumProcesses.argtypes = [ctypes.POINTER(w.DWORD), w.DWORD, ctypes.POINTER(w.DWORD)]
        self.ps.EnumProcesses.restype = w.BOOL
        self.k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.k.OpenProcess.restype = w.HANDLE
        self.k.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
        self.k.QueryFullProcessImageNameW.restype = w.BOOL
        self.k.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
        self.k.TerminateProcess.restype = w.BOOL
        self.k.CloseHandle.argtypes = [w.HANDLE]
        self.k.CloseHandle.restype = w.BOOL
        self.k.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        self.k.WaitForSingleObject.restype = w.DWORD

    def image(self, handle):
        size = self.w.DWORD(32768)
        text = ctypes.create_unicode_buffer(size.value)
        if not self.k.QueryFullProcessImageNameW(handle, 0, text, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        return Path(text.value)

    def running(self, root):
        ids = (self.w.DWORD * 65536)()
        used = self.w.DWORD()
        if not self.ps.EnumProcesses(ids, ctypes.sizeof(ids), ctypes.byref(used)):
            raise ctypes.WinError(ctypes.get_last_error())
        result = []
        for pid in ids[:used.value // ctypes.sizeof(self.w.DWORD)]:
            if pid == os.getpid():
                continue
            handle = self.k.OpenProcess(0x100000 | 0x1000, False, pid)
            if not handle:
                continue
            try:
                if self.k.WaitForSingleObject(handle, 0) == 258 and within(self.image(handle), Path(root)):
                    result.append(pid)
            except OSError:
                pass  # Protected unrelated processes cannot be inspected.
            finally:
                self.k.CloseHandle(handle)
        return result

    def stop_receiver(self, root, state):
        path = Path(state) / "agent.pid"
        if not path.exists():
            return
        if ordinary(path).st_size > 32:
            raise ValueError("接收端 PID 记录异常，请在电脑本机停止程序")
        pid = int(path.read_text().strip())
        handle = self.k.OpenProcess(0x100000 | 0x1000 | 1, False, pid)
        if not handle:
            if ctypes.get_last_error() == 87:
                return  # Stale PID; never terminate a different executable.
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if self.k.WaitForSingleObject(handle, 0) != 258:
                return
            if self.image(handle) not in (Path(root) / "python/python.exe", Path(root) / "python/pythonw.exe"):
                raise ValueError("PID 不属于当前安装，未结束任何进程；请从本机启动面板停止接收端")
            if not self.k.TerminateProcess(handle, 0):
                raise ctypes.WinError(ctypes.get_last_error())
            if self.k.WaitForSingleObject(handle, 10000) != 0:
                raise ValueError("接收端尚未退出，未执行维护")
        finally:
            self.k.CloseHandle(handle)


def confirm(message):
    user = ctypes.WinDLL("user32", use_last_error=True)
    user.MessageBoxW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint]
    user.MessageBoxW.restype = ctypes.c_int
    return user.MessageBoxW(None, message, "LanCmd · 本机维护确认", 0x10000 | 0x100 | 0x30 | 4) == 6


def choose_files(records=False):
    """Windows' local picker; it is never driven by an Android/RPC request."""
    from ctypes import wintypes as w
    class OpenFileName(ctypes.Structure):
        _fields_ = [("size", w.DWORD), ("owner", w.HWND), ("instance", w.HINSTANCE),
                    ("filter", w.LPCWSTR), ("custom", w.LPWSTR), ("custom_max", w.DWORD),
                    ("filter_index", w.DWORD), ("file", w.LPWSTR), ("file_max", w.DWORD),
                    ("title_file", w.LPWSTR), ("title_file_max", w.DWORD), ("directory", w.LPCWSTR),
                    ("title", w.LPCWSTR), ("flags", w.DWORD), ("offset", w.WORD), ("extension", w.WORD),
                    ("default_extension", w.LPCWSTR), ("data", ctypes.c_ssize_t), ("hook", ctypes.c_void_p),
                    ("template", w.LPCWSTR), ("reserved", ctypes.c_void_p), ("reserved_size", w.DWORD),
                    ("flags_ex", w.DWORD)]
    buffer = ctypes.create_unicode_buffer(65536)
    query = OpenFileName()
    query.size = ctypes.sizeof(query)
    query.filter = "日期恢复记录\0dates-*.json\0\0" if records else "普通文档\0" + ";".join("*" + v for v in sorted(DOCUMENTS)) + "\0\0"
    query.file = ctypes.cast(buffer, w.LPWSTR)
    query.file_max = len(buffer)
    query.title = "选择本机日期恢复记录" if records else "选择文档（最多 100 个）"
    query.flags = 0x80000 | 0x1000 | 0x800 | 0x8 | (0 if records else 0x200)
    dialog = ctypes.WinDLL("comdlg32", use_last_error=True)
    dialog.GetOpenFileNameW.argtypes = [ctypes.POINTER(OpenFileName)]
    dialog.GetOpenFileNameW.restype = w.BOOL
    dialog.CommDlgExtendedError.argtypes = []
    dialog.CommDlgExtendedError.restype = w.DWORD
    if not dialog.GetOpenFileNameW(ctypes.byref(query)):
        code = dialog.CommDlgExtendedError()
        if code:
            raise OSError("打开文件选择器失败：0x%X" % code)
        return []
    values = buffer[:].split("\0\0", 1)[0].split("\0")
    return [Path(values[0])] if len(values) == 1 else [Path(values[0]) / name for name in values[1:]]


def stage(root):
    data = manifest(root)
    base = Path(os.environ["LOCALAPPDATA"]) / "LanCmdMaintenance"
    base.mkdir(exist_ok=True)
    ordinary(base, directory=True)
    if within(base, root):
        raise ValueError("维护记录必须保存在安装目录之外")
    session = Path(tempfile.mkdtemp(prefix="session-", dir=base))
    for name in ["local_maintenance.py"] + [p for p in data["files"] if p.startswith("python/")]:
        target = session / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, target)
        if digest(target) != data["files"][name]["sha256"]:
            raise ValueError("维护运行时复制校验失败")
    audit(session, "prepared", root=str(root), version=data["version"])
    subprocess.Popen([str(session / "python/python.exe"), "-I", "-B", "-u",
                      str(session / "local_maintenance.py"), "--local-session", str(root)],
                     cwd=session, creationflags=subprocess.CREATE_NEW_CONSOLE, close_fds=True)
    print("已打开独立的本机维护窗口。记录和回退文件：" + str(session))


def interactive(root, session):
    root, session = Path(root), Path(session)
    if within(session, root):
        raise ValueError("维护工具必须运行在安装目录之外，请双击原目录的本机维护.cmd")
    manifest(root)
    state = Path(os.environ["APPDATA"]) / "LanCmd"
    process = WindowsProcesses()
    print("LanCmd 本机维护\n安装目录：%s\n恢复记录：%s\n" % (root, session))
    print("维护期间会断开手机连接。日志、配置、上传文件和恢复记录会保留。")
    print("设备启停、网络／防火墙和壁纸设置请先在 Windows 本机恢复；工具不会重置其他软件的设置。")
    if not confirm("准备进入本机维护。\n\n将停止当前安装的接收端并断开手机连接，等待输入／冻结恢复。\n不会在此步骤删除或移动文件。\n\n继续？\n" + str(root)):
        return
    # The staging launcher must have exited before this list can be empty.
    time.sleep(1)
    process.stop_receiver(root, state)
    deadline = time.monotonic() + 15
    while process.running(root) and time.monotonic() < deadline:
        time.sleep(0.5)
    def stopped():
        active = process.running(root)
        if active:
            raise ValueError("请先关闭启动面板和录制等程序，再运行维护。仍占用安装目录的 PID：" + str(active))
    stopped()
    audit(session, "receiver_stopped", root=str(root))
    protected = [root, session.parent, state]
    protected.extend(Path(os.environ[key]) for key in ("SystemRoot", "WINDIR", "ProgramFiles", "ProgramFiles(x86)",
                     "ProgramData", "APPDATA", "LOCALAPPDATA") if os.environ.get(key))
    while True:
        print("\n1 文件夹改名\n2 卸载（保留回退文件）\n3 普通文档批量修改最后修改日期\n4 恢复文档原日期\n0 退出（接收端保持停止）")
        action = input("选择：").strip()
        if action == "0":
            return
        try:
            stopped()
            if action in ("1", "2"):
                recovery_check(state)
                entry = StartupEntry(root)
                if action == "1":
                    target = new_name(root, input("新文件夹名称："))
                    if not confirm("接收端已停止。将更改文件夹名称，并仅更新属于此安装的当前用户自启动项。\n\n%s\n→ %s\n\n确认改名？" % (root, target)):
                        continue
                    migrate(root, target, session, entry, lambda: (stopped(), recovery_check(state)))
                    print("改名完成：" + str(target))
                    if confirm("是否在电脑上打开改名后的可见启动面板？\n" + str(target / "Start.exe")):
                        os.startfile(str(target / "Start.exe"))
                    return
                if confirm("接收端已停止。将卸载发布清单中的已验证文件，移除属于此安装的当前用户自启动项。\n\n保留用户文件、配置、日志及恢复记录。回退副本：\n%s\n\n确认卸载？\n%s" % (session, root)):
                    uninstall(root, session, entry, lambda: (stopped(), recovery_check(state)))
                    print("卸载完成。用户文件和配置保留；回退副本：" + str(session / "removed-files"))
                    return
            elif action == "3":
                selected = choose_files()
                if not selected:
                    continue
                for path in selected:
                    document(path, protected)
                text = input("本机当地时间 YYYY-MM-DD HH:MM:SS（留空取消）：").strip()
                if not text:
                    continue
                stamp = int(datetime.datetime.strptime(text, "%Y-%m-%d %H:%M:%S").timestamp()) * 10**9
                print("所选文档：\n" + "\n".join(map(str, selected)))
                preview = "\n".join(map(str, selected[:6]))
                if len(selected) > 6:
                    preview += "\n其余 %d 个文档的完整列表见控制台。" % (len(selected) - 6)
                if not confirm("将修改 %d 个普通文档的最后修改日期：%s\n\n%s\n\n原日期保存在本机维护记录中。确认？" % (len(selected), text, preview)):
                    continue
                stopped()
                record, result = dates(selected, stamp, session, protected)
                audit(session, "document_dates", record=str(record))
                print("成功 %d / %d。记录：%s" % (sum(e["status"] == "applied" for e in result["entries"]), len(result["entries"]), record))
                for item in result["entries"]:
                    if item.get("error"):
                        print(item["path"] + "：" + item["error"])
            elif action == "4":
                selected = choose_files(records=True)
                if not selected:
                    continue
                record = selected[0].absolute()
                if not within(record, session.parent) or not re.fullmatch(r"dates-[0-9a-f]{32}\.json", record.name):
                    raise ValueError("请选择本机维护目录中的原始日期记录")
                if confirm("按原始记录恢复文档日期。文件身份、大小或当前日期有冲突时跳过。\n\n" + str(record)):
                    stopped()
                    result = restore_dates(record, protected)
                    audit(session, "dates_restored", record=str(record))
                    print("已恢复 %d / %d；具体结果保存在：%s" % (sum(e["status"] == "restored" for e in result["entries"]), len(result["entries"]), record))
            else:
                print("请输入菜单中的编号。")
        except (OSError, ValueError, KeyError, TypeError) as exc:
            audit(session, "failed", action=action, error=str(exc))
            print("未完成：" + str(exc))


def main():
    if os.name != "nt" or not sys.stdin.isatty() or not sys.stdout.isatty():
        print("Local maintenance requires a visible interactive Windows console. No remote/unattended mode.")
        return 1
    try:
        shell = ctypes.WinDLL("shell32", use_last_error=True)
        shell.IsUserAnAdmin.argtypes = []
        shell.IsUserAnAdmin.restype = ctypes.c_int
        if not shell.IsUserAnAdmin():
            raise ValueError("请在 Windows 本机右键本机维护.cmd，选择以管理员身份运行；工具不会自动提权")
        if len(sys.argv) == 1:
            stage(Path(__file__).absolute().parent)
        elif len(sys.argv) == 3 and sys.argv[1] == "--local-session":
            interactive(Path(sys.argv[2]).absolute(), Path(__file__).absolute().parent)
            input("\n按 Enter 关闭本机维护窗口。")
        else:
            raise ValueError("请双击本机维护.cmd；不接受操作、自动确认或远程参数")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("维护未完成：" + str(exc))
        input("按 Enter 关闭；接收端可能已停止，可从原 Start.exe 重新启动。")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

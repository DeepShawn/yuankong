"""Owner-scoped copy/move/delete jobs; no overwrite and cancellable small writes."""
import atexit
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import threading
import time
import uuid

MAX_ITEMS = 100
MAX_ENTRIES = 20000
RESERVE = 1_000_000_000


def file_token(info):
    values = (info.st_dev, info.st_ino, info.st_mtime_ns, info.st_size, stat.S_IFMT(info.st_mode))
    return hashlib.sha256(repr(values).encode("ascii")).hexdigest()


def snapshot(path):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("批量操作不跟随链接或重解析点，请使用普通文件和目录")
    if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
        raise ValueError("不支持此类文件")
    return info


def checked_path(value, directory=False):
    if not isinstance(value, str) or not value or len(value) > 32700 or "\0" in value or not os.path.isabs(value):
        raise ValueError("请输入有效的绝对路径")
    raw = Path(value)
    if any(p.endswith((".", " ")) and p not in (".", "..") for p in raw.parts[1:]):
        raise ValueError("路径含 Windows 会改写的尾随点或空格")
    path = Path(os.path.abspath(value))
    for parent in (path.parent, *path.parent.parents):
        info = snapshot(parent)
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError("父路径不是目录")
    info = snapshot(path)
    if directory and not stat.S_ISDIR(info.st_mode):
        raise ValueError("目标必须是已有文件夹")
    return path


def publish_new(source, target):
    """Atomic rename without replacing an existing file OR empty directory."""
    if os.name == "nt":
        from ctypes import wintypes as w
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.MoveFileExW.argtypes = [w.LPCWSTR, w.LPCWSTR, w.DWORD]
        if not kernel.MoveFileExW(str(source), str(target), 0):
            raise ctypes.WinError(ctypes.get_last_error())
    else:
        # Linux implementation used by regression tests; never plain rename-overwrite.
        libc = ctypes.CDLL(None, use_errno=True)
        function = getattr(libc, "renameat2", None)
        if function is None:
            raise OSError("此系统不支持安全的无覆盖重命名")
        function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        if function(-100, os.fsencode(source), -100, os.fsencode(target), 1):
            number = ctypes.get_errno()
            raise OSError(number, os.strerror(number))


class Cancelled(Exception):
    pass


class FileJobs:
    def __init__(self, journal, protected=(), allow_non_windows=False):
        self.journal = Path(journal)
        self.protected = [Path(p).absolute() for p in protected if p]
        self.allow_non_windows = allow_non_windows
        self.lock = threading.RLock()
        self.jobs = {}
        self.stages = {}
        self.loaded = False
        self.warning = ""
        self.closed = False
        atexit.register(self.close)

    def _save_stages(self):
        self.journal.parent.mkdir(parents=True, exist_ok=True)
        temp = self.journal.with_suffix(".tmp")
        with temp.open("w", encoding="utf-8") as stream:
            json.dump(self.stages, stream, ensure_ascii=True)
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, self.journal)

    def _cleanup(self, stage):
        record = self.stages.get(str(stage))
        if record is None:
            return
        if os.path.lexists(stage):
            checked_path(str(stage), directory=True)
            info = snapshot(stage)
            if [info.st_dev, info.st_ino] != record:
                raise ValueError("临时目录身份已变化，未删除：" + str(stage))
            shutil.rmtree(stage)
        self.stages.pop(str(stage), None)
        self._save_stages()

    def _recover(self):
        if self.loaded:
            return
        if self.journal.exists():
            data = json.loads(self.journal.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or len(data) > 100:
                raise ValueError("文件任务临时目录记录损坏，请检查 file-jobs.json")
            for path, identity in data.items():
                if (not isinstance(path, str) or not Path(path).name.startswith(".lancmd-filejob-") or
                        not isinstance(identity, list) or len(identity) != 2 or any(type(n) is not int for n in identity)):
                    raise ValueError("文件任务恢复记录格式无效，未清理其他目录")
            self.stages = data
            errors = []
            for value in list(data):
                try:
                    self._cleanup(Path(value))
                except (OSError, ValueError) as exc:
                    errors.append(str(exc))
            self.warning = "；".join(errors)[:2000]
        self.loaded = True

    @staticmethod
    def _cancel(job):
        if job["cancel"].is_set():
            raise Cancelled()

    def _scan(self, root, job):
        plan = []
        def visit(path):
            self._cancel(job)
            info = snapshot(path)
            plan.append((path.relative_to(root), info))
            if len(plan) > MAX_ENTRIES:
                raise ValueError("单项超过 20000 个文件/目录，请分批处理")
            if stat.S_ISDIR(info.st_mode):
                for child in sorted(path.iterdir()):
                    visit(child)
        visit(root)
        return plan

    @staticmethod
    def _same(path, original, directory=False):
        current = snapshot(path)
        if (current.st_dev, current.st_ino) != (original.st_dev, original.st_ino) or (
                not directory and file_token(current) != file_token(original)):
            raise ValueError("文件已变化，未操作新的内容：" + str(path))

    def _remove_plan(self, source, plan, job):
        for relative, info in reversed(plan):
            self._cancel(job)
            target = source / relative
            folder = stat.S_ISDIR(info.st_mode)
            self._same(target, info, directory=folder)
            target.rmdir() if folder else target.unlink()

    def _copy(self, source, target, plan, job, result):
        total = sum(info.st_size for _, info in plan if stat.S_ISREG(info.st_mode))
        if shutil.disk_usage(target.parent).free - total < RESERVE:
            raise ValueError("目标空间不足，复制后须保留 1 GB 可用空间")
        result.update(bytes=0, total_bytes=total)
        stage = target.parent / (".lancmd-filejob-" + uuid.uuid4().hex)
        stage.mkdir()
        info = snapshot(stage)
        self.stages[str(stage)] = [info.st_dev, info.st_ino]
        try:
            self._save_stages()  # Durable ownership before writing any payload.
            payload = stage / source.name
            for relative, original in plan:
                self._cancel(job)
                src, dst = source / relative, payload / relative
                self._same(src, original)
                if stat.S_ISDIR(original.st_mode):
                    dst.mkdir()
                    continue
                with src.open("rb") as reader, dst.open("xb") as writer:
                    if file_token(os.fstat(reader.fileno())) != file_token(original):
                        raise ValueError("源文件在打开时已变化")
                    while True:
                        self._cancel(job)
                        chunk = reader.read(1024 * 1024)
                        if not chunk:
                            break
                        if shutil.disk_usage(stage).free - len(chunk) < RESERVE:
                            raise ValueError("目标剩余空间不足 1 GB，已停止复制")
                        writer.write(chunk); result["bytes"] += len(chunk)
                    writer.flush(); os.fsync(writer.fileno())
                    if file_token(os.fstat(reader.fileno())) != file_token(original):
                        raise ValueError("源文件在复制期间被修改，未发布副本")
                os.utime(dst, ns=(original.st_atime_ns, original.st_mtime_ns))
                if os.name == "nt" and getattr(original, "st_file_attributes", 0) & 6:
                    from ctypes import wintypes as w
                    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                    kernel.SetFileAttributesW.argtypes = [w.LPCWSTR, w.DWORD]
                    if not kernel.SetFileAttributesW(str(dst), 0x20 | (original.st_file_attributes & 6)):
                        raise ctypes.WinError(ctypes.get_last_error())
            latest = self._scan(source, job)
            if [(str(p), file_token(i)) for p, i in latest] != [(str(p), file_token(i)) for p, i in plan]:
                raise ValueError("源目录在复制期间发生变化，未发布副本")
            self._cancel(job)
            publish_new(payload, target)
            result["copied"] = True
        finally:
            try:
                self._cleanup(stage)
            except (OSError, ValueError) as exc:
                result["cleanup_warning"] = str(exc)

    def _run_item(self, spec, job):
        source = checked_path(spec["path"])
        if source.parent == source:
            raise ValueError("不能操作磁盘根目录")
        original = snapshot(source)
        if file_token(original) != spec["file_id"]:
            raise ValueError("所选文件已变化，请刷新后重新选择")
        operation = job["data"]["operation"]
        if operation in ("move", "delete") and any(source == p or source in p.parents or p in source.parents for p in self.protected):
            raise ValueError("不能移动或删除系统、接收端或恢复配置所在路径")
        target = None
        if operation != "delete":
            destination = checked_path(job["data"]["destination"], directory=True)
            target = destination / source.name
            if source == target or source in target.parents:
                raise ValueError("目标不能是源路径自身或其内部")
            if os.path.lexists(target):
                raise ValueError("目标已存在，未覆盖：" + str(target))
            spec["destination"] = str(target)
        plan = self._scan(source, job)
        self._same(source, original)
        if operation == "delete":
            self._remove_plan(source, plan, job)
            return
        if operation == "move" and original.st_dev == snapshot(target.parent).st_dev:
            self._cancel(job)
            publish_new(source, target)
            return
        self._copy(source, target, plan, job, spec)
        if operation == "move":
            try:
                self._remove_plan(source, plan, job)
            except Exception as exc:
                raise ValueError("已复制到 %s，但源未完全移除：%s" % (target, str(exc) or "操作已取消"))

    def _run(self, job):
        data = job["data"]
        try:
            data["status"] = "running"
            for item in data["items"]:
                if job["cancel"].is_set():
                    item["status"] = "cancelled"
                    continue
                item["status"] = "running"
                try:
                    self._run_item(item, job)
                    item["status"] = "completed"
                except Cancelled:
                    item["status"] = "cancelled"
                except Exception as exc:
                    item.update(status="failed", error=str(exc)[:1600])
            data["status"] = "cancelled" if job["cancel"].is_set() else "completed"
        finally:
            data["finished"] = time.time()
            job["done"].set()

    @staticmethod
    def _public(job):
        result = dict(job["data"])
        result["items"] = [dict(item) for item in result["items"]]
        return result

    def request(self, q, state, error):
        try:
            if os.name != "nt" and not self.allow_non_windows:
                raise ValueError("批量文件任务仅支持 Windows")
            if not state.client_id:
                raise ValueError("连接身份无效")
            with self.lock:
                self._recover()
                action = q.get("action", "list")
                if action == "list":
                    return {"jobs": [self._public(j) for j in reversed(list(self.jobs.values())) if j["owner"] == state.client_id][:5], "warning": self.warning}
                if action == "cancel":
                    job = self.jobs.get(q.get("job_id"))
                    if not job or job["owner"] != state.client_id:
                        raise ValueError("任务不存在或属于其他手机")
                    job["cancel"].set()
                    return {"cancel_requested": not job["done"].is_set()}
                if action != "create" or q.get("operation") not in ("copy", "move", "delete"):
                    raise ValueError("无效的文件任务")
                specs = q.get("items")
                if not isinstance(specs, list) or not 1 <= len(specs) <= MAX_ITEMS:
                    raise ValueError("每批请选择 1–100 项")
                if self.closed or any(not j["done"].is_set() for j in self.jobs.values()):
                    raise ValueError("已有文件任务在运行，请等待或取消")
                paths = set()
                for item in specs:
                    if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not os.path.isabs(item["path"]):
                        raise ValueError("所选路径无效")
                    token = item.get("file_id")
                    if not isinstance(token, str) or len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
                        raise ValueError("缺少文件版本，请更新 Windows 接收端并刷新目录")
                    normalized = os.path.normcase(os.path.abspath(item["path"]))
                    if normalized in paths:
                        raise ValueError("批次包含重复路径")
                    paths.add(normalized)
                destination = ""
                if q["operation"] != "delete":
                    destination = str(checked_path(q.get("destination"), directory=True))
                completed = [key for key, value in self.jobs.items() if value["done"].is_set()]
                while len(self.jobs) >= 100 and completed:
                    self.jobs.pop(completed.pop(0))
                data = dict(id=uuid.uuid4().hex, operation=q["operation"], destination=destination, status="queued", created=time.time(),
                            items=[dict(path=i["path"], file_id=i["file_id"], status="pending") for i in specs])
                job = dict(owner=state.client_id, cancel=threading.Event(), done=threading.Event(), data=data)
                self.jobs[data["id"]] = job
                threading.Thread(target=self._run, args=(job,), daemon=True, name="file-batch").start()
                return {"job": self._public(job)}
        except (OSError, ValueError, TypeError, KeyError) as exc:
            raise error(str(exc))

    def close(self):
        with self.lock:
            self.closed = True
            for job in self.jobs.values():
                if not job["done"].is_set():
                    job["cancel"].set()

    def stop_for_device(self, device):
        roots = [str(p).replace("/", "\\").rstrip("\\").casefold() + "\\" for p in device.get("paths", []) if p]
        with self.lock:
            active = [j for j in self.jobs.values() if not j["done"].is_set()]
            if active and not roots:
                raise ValueError("设备路径未知，请先停止文件任务")
            matches = [j for j in active if any(str(value).replace("/", "\\").casefold().startswith(root)
                for root in roots for value in [j["data"].get("destination", ""), *[i["path"] for i in j["data"]["items"]]])]
            for job in matches:job["cancel"].set()
        deadline = time.monotonic() + 5
        for job in matches:
            if not job["done"].wait(max(0, deadline-time.monotonic())):
                raise ValueError("文件任务仍在停止和清理，设备未禁用，请稍后重试")

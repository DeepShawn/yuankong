"""Batch last-write-time editing with an exact rollback journal.

Phone-driven counterpart of the local maintenance helper: instead of a file
picker it takes explicit paths (or the receiver's own folder), supports
recursive masks and a random spread so a batch of files does not share the
same second.  Every change is journalled before it happens; restoring verifies
file identity, size and the timestamp this module wrote, so a file edited in
the meantime is never overwritten.
"""
import datetime
import fnmatch
import json
import os
from pathlib import Path
import stat
import threading
import time
import uuid

from file_time import DocumentHandle

MAX_PATHS = 100
MAX_FILES = 20000
MAX_RECORDS = 200
TIME_MIN = 0                      # 1970-01-01T00:00:00 local
TIME_MAX = 4102444800             # 2100-01-01T00:00:00 local
REPARSE_POINT = 0x400


def _system_roots():
    keys = ("SystemRoot", "WINDIR", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")
    roots = []
    for key in keys:
        value = os.environ.get(key)
        if value:
            roots.append(Path(value).absolute())
    return roots


def parse_time(text):
    """'YYYY-MM-DD HH:MM:SS' in computer local time -> ns since epoch."""
    if not isinstance(text, str):
        raise ValueError("时间必须是字符串")
    text = text.strip().replace("T", " ")
    for pattern in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            moment = datetime.datetime.strptime(text, pattern)
            break
        except ValueError:
            continue
    else:
        raise ValueError("时间格式应为 YYYY-MM-DD HH:MM:SS")
    if not 1970 <= moment.year <= 2099:
        raise ValueError("时间须在 1970–2099 年之间")
    try:
        # timestamp() on Windows raises OSError for dates mktime refuses, so the
        # range is checked first and the conversion failure is reported as input.
        seconds = int(moment.timestamp())
    except (OSError, OverflowError, ValueError):
        raise ValueError("电脑无法把该时间当作本地时间处理")
    if not TIME_MIN <= seconds < TIME_MAX:
        raise ValueError("时间须在 1970–2099 年之间")
    return seconds * 10**9


def format_time(value):
    return datetime.datetime.fromtimestamp(value // 10**9).strftime("%Y-%m-%d %H:%M:%S")


def ordinary(path):
    """Refuse links, junctions and reparse points; they can escape the selection."""
    info = Path(path).lstat()
    if os.path.islink(path) or getattr(info, "st_file_attributes", 0) & REPARSE_POINT:
        raise ValueError("不能处理链接、目录联接或重解析点：%s" % path)
    return info


def guard(path, allowed=None):
    """Reject OS/program locations but keep the receiver's own folder usable."""
    path = Path(path).absolute()
    drive, tail = os.path.splitdrive(str(path))
    if not tail.strip("\\/") or str(path).startswith("\\\\"):
        raise ValueError("请选择具体的文件或文件夹，不能是磁盘根目录或网络路径：%s" % path)
    if allowed is not None and (path == allowed or allowed in path.parents):
        return path
    for root in _system_roots():
        if path == root or root in path.parents:
            raise ValueError("不能修改系统或程序目录中的文件：%s" % path)
    return path


def collect(paths, recursive, mask, include_self, self_dir):
    """Resolve the selection to a de-duplicated, sorted list of regular files."""
    if not isinstance(paths, list) or not all(isinstance(item, str) and item.strip() for item in paths):
        raise ValueError("路径列表无效")
    if len(paths) > MAX_PATHS:
        raise ValueError("一次最多选择 %d 个路径" % MAX_PATHS)
    if not isinstance(mask, str) or not mask or len(mask) > 64:
        raise ValueError("筛选通配符无效")
    roots = [Path(item).expanduser().absolute() for item in paths]
    if include_self:
        if not self_dir:
            raise ValueError("接收端无法定位自身目录")
        roots.append(Path(self_dir).absolute())
    allowed = Path(self_dir).absolute() if self_dir else None
    files = []
    seen = set()
    for root in dict.fromkeys(roots):
        guard(root, allowed)
        info = ordinary(root)
        if stat.S_ISDIR(info.st_mode):
            if not recursive:
                for entry in sorted(root.iterdir()):
                    if entry.is_file() and not entry.is_symlink() and fnmatch.fnmatch(entry.name, mask):
                        _push(files, seen, entry)
                        if len(files) > MAX_FILES:
                            raise ValueError("匹配文件超过 %d 个，请缩小范围" % MAX_FILES)
                continue
            for folder, dirs, names in os.walk(root, followlinks=False):
                dirs[:] = sorted(d for d in dirs if not _linked(Path(folder) / d))
                for name in sorted(names):
                    candidate = Path(folder) / name
                    if fnmatch.fnmatch(name, mask) and not _linked(candidate):
                        _push(files, seen, candidate)
                        if len(files) > MAX_FILES:
                            raise ValueError("匹配文件超过 %d 个，请缩小范围" % MAX_FILES)
        else:
            if fnmatch.fnmatch(root.name, mask):
                _push(files, seen, root)
    return files


def _linked(path):
    try:
        info = Path(path).lstat()
    except OSError:
        return True
    return os.path.islink(path) or bool(getattr(info, "st_file_attributes", 0) & REPARSE_POINT)


def _push(files, seen, path):
    key = os.path.normcase(str(path))
    if key in seen:
        return
    seen.add(key)
    files.append(path)


class BatchDates:
    """Serialize date jobs; one job at a time keeps the journal unambiguous."""

    def __init__(self, state_dir, self_dir, error, logger=None):
        self.folder = Path(state_dir) / "dates"
        self.self_dir = str(self_dir) if self_dir else ""
        self.error = error
        self.log = logger
        self.lock = threading.RLock()
        self.busy = None

    # ------------------------------------------------------------ records
    def records(self):
        items = []
        if not self.folder.is_dir():
            return items
        for path in sorted(self.folder.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:MAX_RECORDS]:
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            entries = data.get("entries") or []
            items.append(dict(
                id=path.stem, created=data.get("created", 0),
                mode=data.get("mode", ""), files=len(entries),
                applied=sum(1 for e in entries if e.get("status") == "applied"),
                restored=sum(1 for e in entries if e.get("status") == "restored"),
                failed=sum(1 for e in entries if e.get("status") == "failed"),
                target=data.get("target_text", ""),
            ))
        return items

    def record(self, ident):
        path = self.folder / (str(ident) + ".json")
        if not path.is_file():
            raise self.error("日期记录不存在")
        return path, json.loads(path.read_text(encoding="utf-8"))

    def save(self, path, data):
        self.folder.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)

    # -------------------------------------------------------------- target
    def target(self, q):
        fixed = q.get("time")
        start, end = q.get("start"), q.get("end")
        if fixed:
            value = parse_time(fixed)
            return "fixed", [value, value], format_time(value)
        if start and end:
            low, high = parse_time(start), parse_time(end)
            if low > high:
                low, high = high, low
            return "range", [low, high], "%s ~ %s" % (format_time(low), format_time(high))
        raise self.error("请提供 time，或 start 与 end")

    def _pick(self, mode, bounds, index):
        if mode == "fixed":
            return bounds[0]
        # Spread inside the window, one-second steps: a whole batch of identical
        # timestamps is exactly what a date check looks for.
        span = max(0, (bounds[1] - bounds[0]) // 10**9)
        step = (index * 7919) % (span + 1) if span else 0
        return bounds[0] + step * 10**9

    def prune(self, keep=MAX_RECORDS):
        """Oldest records fall off; the newest ones are what the phone lists."""
        items = sorted(self.folder.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
        for stale in items[keep:]:
            try:
                stale.unlink()
            except OSError:
                pass

    # ------------------------------------------------------------- actions
    def request(self, q, state, error=None):
        action = q.get("action", "records")
        if action == "records":
            return dict(items=self.records(), busy=self.busy)
        if action == "record":
            path, data = self.record(q.get("id", ""))
            entries = [dict(path=e.get("path"), status=e.get("status"),
                            old=format_time(e["old_mtime_ns"]) if isinstance(e.get("old_mtime_ns"), int) else "",
                            now=format_time(e["applied_mtime_ns"]) if isinstance(e.get("applied_mtime_ns"), int) else "",
                            error=e.get("error", "")) for e in (data.get("entries") or [])[:MAX_FILES]]
            return dict(id=path.stem, target=data.get("target_text", ""),
                        created=data.get("created", 0), entries=entries)
        if action == "delete_record":
            path, _ = self.record(q.get("id", ""))
            path.unlink()
            return dict(deleted=True, items=self.records())
        if action == "preview":
            return self.preview(q)
        if action == "apply":
            return self.apply(q)
        if action == "restore":
            return self.restore(q)
        raise self.error("无效的日期操作")

    def preview(self, q):
        files = collect(q.get("paths"), bool(q.get("recursive", True)), q.get("mask") or "*",
                        bool(q.get("include_self")), self.self_dir)
        if not files:
            raise self.error("没有匹配的文件")
        total = 0
        sample = []
        for path in files[:40]:
            try:
                total += path.stat().st_size
                sample.append(dict(path=str(path), size=path.stat().st_size,
                                   modified=format_time(path.stat().st_mtime_ns)))
            except OSError:
                continue
        return dict(count=len(files), bytes=total, sample=sample)

    def apply(self, q):
        with self.lock:
            if self.busy:
                raise self.error("已有日期任务在执行，请等待完成")
            mode, bounds, text = self.target(q)
            files = collect(q.get("paths"), bool(q.get("recursive", True)), q.get("mask") or "*",
                            bool(q.get("include_self")), self.self_dir)
            if not files:
                raise self.error("没有匹配的文件")
            if len(files) > MAX_FILES:
                raise self.error("匹配文件过多")
            ident = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
            path = self.folder / (ident + ".json")
            data = dict(format=1, operation="batch_dates", created=int(time.time()),
                        mode=mode, target=bounds, target_text=text, entries=[])
            for item in files:
                try:
                    with DocumentHandle(item) as handle:
                        snapshot = handle.snapshot()
                    data["entries"].append(dict(path=str(item), identity=snapshot["identity"],
                                                size=snapshot["size"], old_mtime_ns=snapshot["mtime_ns"],
                                                status="pending"))
                except (OSError, ValueError) as exc:
                    data["entries"].append(dict(path=str(item), identity=[0, 0], size=-1, old_mtime_ns=0,
                                                status="failed", error=str(exc)))
            self.save(path, data)
            self.prune()
            self.busy = ident
        applied = failed = 0
        try:
            for index, entry in enumerate(data["entries"]):
                if entry["status"] == "failed":
                    failed += 1
                    continue
                wanted = self._pick(mode, bounds, index)
                try:
                    with DocumentHandle(entry["path"]) as handle:
                        current = handle.snapshot()
                        if (current["identity"] != entry["identity"] or current["size"] != entry["size"]
                                or current["mtime_ns"] != entry["old_mtime_ns"]):
                            raise ValueError("文件在选择后已变化，未修改")
                        handle.set(wanted)
                        observed = handle.snapshot()["mtime_ns"]
                        if observed != wanted:
                            # FAT-style rounding is not a successful exact edit.
                            handle.set(entry["old_mtime_ns"])
                            raise ValueError("文件系统不能精确保存该时间，已恢复原日期")
                    entry.update(status="applied", applied_mtime_ns=wanted)
                    applied += 1
                except (OSError, ValueError) as exc:
                    entry.update(status="failed", error=str(exc))
                    failed += 1
                self.save(path, data)
        finally:
            with self.lock:
                self.busy = None
        if self.log:
            self.log.info("批量日期 %s：成功 %d 失败 %d（%s）", ident, applied, failed, text)
        return dict(id=ident, applied=applied, failed=failed, target=text,
                    errors=[dict(path=e["path"], error=e.get("error", "")) for e in data["entries"]
                            if e.get("status") == "failed"][:20])

    def restore(self, q):
        with self.lock:
            if self.busy:
                raise self.error("已有日期任务在执行，请等待完成")
            path, data = self.record(q.get("id", ""))
            if data.get("operation") != "batch_dates" or data.get("format") != 1:
                raise self.error("不是本功能生成的日期记录")
            self.busy = path.stem
        restored = skipped = 0
        try:
            for entry in data.get("entries") or []:
                if entry.get("status") == "restored":
                    restored += 1
                    continue
                if entry.get("status") != "applied":
                    skipped += 1
                    continue
                try:
                    with DocumentHandle(entry["path"]) as handle:
                        current = handle.snapshot()
                        if current["identity"] != entry["identity"] or current["size"] != entry["size"]:
                            raise ValueError("文件身份或大小已变化，未覆盖")
                        if current["mtime_ns"] == entry["old_mtime_ns"]:
                            entry["status"] = "restored"
                        elif current["mtime_ns"] == entry.get("applied_mtime_ns"):
                            handle.set(entry["old_mtime_ns"])
                            if handle.snapshot()["mtime_ns"] != entry["old_mtime_ns"]:
                                raise ValueError("未确认原日期已恢复")
                            entry["status"] = "restored"
                        else:
                            raise ValueError("文件后来已被修改，未覆盖")
                    entry.pop("error", None)
                    restored += 1
                except (OSError, ValueError) as exc:
                    entry["error"] = str(exc)
                    skipped += 1
                self.save(path, data)
        finally:
            with self.lock:
                self.busy = None
        return dict(id=path.stem, restored=restored, skipped=skipped,
                    errors=[dict(path=e["path"], error=e.get("error", "")) for e in data["entries"]
                            if e.get("status") != "restored"][:20])

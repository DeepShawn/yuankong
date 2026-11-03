"""Bounded, asynchronous, read-only summaries; never executes maintenance."""
import copy
import json
import os
from pathlib import Path
import stat
import threading
import time


class MaintenanceDiagnostics:
    MAX_SCAN = 256
    MAX_FILES = 48
    MAX_FILE = 256 * 1024
    MAX_TOTAL = 2 * 1024 * 1024

    def __init__(self, root, records, journals, pid):
        self.root, self.records, self.journals = str(root), Path(records), Path(journals)
        self.pid = pid
        self.lock = threading.Lock()
        self.loading = False
        self.updated = 0.0
        self.snapshot = dict(pending=[], items=[], warnings=[], partial=False)

    def request(self, q, error):
        if q.get("action", "status") != "status" or type(q.get("refresh", False)) is not bool:
            raise error("此接口只提供维护状态诊断")
        with self.lock:
            if not self.loading and (not self.updated or time.monotonic()-self.updated > (1 if q.get("refresh") else 5)):
                self.loading = True
                threading.Thread(target=self._worker, daemon=True, name="maintenance-diagnostics").start()
            return dict(root=self.root, pid=self.pid, loading=self.loading, **copy.deepcopy(self.snapshot))

    def _worker(self):
        try:
            result = self.scan()
        except Exception as exc:
            with self.lock:
                result = copy.deepcopy(self.snapshot)
            result.update(warnings=["维护状态未读完："+str(exc)[:300]],partial=True)
        with self.lock:
            self.snapshot = result
            self.updated = time.monotonic()
            self.loading = False

    def scan(self):
        result = dict(pending=[], items=[], warnings=[], partial=False)
        remaining = self.MAX_TOTAL
        deadline = time.monotonic()+2

        def warn(message):
            result["partial"] = True
            if message not in result["warnings"] and len(result["warnings"]) < 8:
                result["warnings"].append(message)

        def paths(folder, prefix, suffix):
            try:
                for parent in (folder, *folder.parents):
                    info = parent.lstat()
                    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                        raise ValueError("诊断不跟随链接或重解析点")
                found = []
                with os.scandir(folder) as entries:
                    for count, entry in enumerate(entries):
                        if count >= self.MAX_SCAN or time.monotonic() > deadline:
                            warn("记录数量或读取耗时达到上限，部分历史未加载")
                            break
                        if entry.name.startswith(prefix) and entry.name.endswith(suffix):
                            try:
                                found.append((entry.stat(follow_symlinks=False).st_mtime,entry.name,Path(entry.path)))
                            except OSError:
                                warn("部分记录无法读取属性，原文件保留")
                if len(found) > self.MAX_FILES:
                    warn("仅显示本次扫描中较新的部分记录")
                return [item[2] for item in sorted(found,reverse=True)[:self.MAX_FILES]]
            except FileNotFoundError:
                return []

        def read(path, tail=False):
            nonlocal remaining
            info = path.lstat()
            if (not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or info.st_nlink != 1 or
                    getattr(info, "st_file_attributes", 0) & 0x400):
                raise ValueError("记录不是普通单链接文件")
            if time.monotonic() > deadline or remaining <= 0:
                raise ValueError("读取预算达到上限")
            limit = min(self.MAX_FILE, remaining)
            if not tail and info.st_size > limit:
                raise ValueError("记录过大，请在电脑本机查看；原文件保留")
            with path.open("rb") as stream:
                actual = os.fstat(stream.fileno())
                if (actual.st_dev,actual.st_ino)!=(info.st_dev,info.st_ino):
                    raise ValueError("记录已变化")
                offset = max(0, info.st_size-limit) if tail else 0
                stream.seek(offset)
                raw = stream.read(limit+1)
            remaining -= len(raw)
            if len(raw) > limit:
                raise ValueError("记录读取超过上限")
            if offset:
                warn("大日志仅读取尾部，未看到完成标记时仅表示状态未知")
                raw = raw.partition(b"\n")[2]
            return raw.decode("utf-8", "strict")

        for path in paths(self.journals,"plan-",".json"):
            try:
                data = json.loads(read(path))
                if not isinstance(data,dict):
                    raise ValueError("维护计划格式无效")
                journal = Path(data.get("journal", "")).absolute()
                if journal.parent != self.journals.absolute() or not journal.name.startswith("remote-") or journal.suffix != ".jsonl":
                    raise ValueError("日志位置不属于诊断目录")
                done = False
                try:
                    for line in read(journal,tail=True).splitlines():
                        try:
                            entry = json.loads(line)
                        except ValueError:
                            continue
                        if isinstance(entry,dict) and entry.get("event") in ("deleted","renamed","failed","abort"):
                            done = True
                except FileNotFoundError:
                    pass
                if not done:
                    result["pending"].append(dict(operation=str(data.get("operation", ""))[:40],
                                                   plan=path.name, journal=str(journal)))
            except (OSError,ValueError,TypeError) as exc:
                warn("部分维护记录未加载："+str(exc)[:160])
        for path in paths(self.records,"",".json"):
            try:
                data = json.loads(read(path))
                if not isinstance(data,dict) or not isinstance(data.get("entries"),list):
                    raise ValueError("日期记录格式无效")
                entries = data["entries"]
                result["items"].append(dict(id=path.stem, target=str(data.get("target_text", ""))[:120],
                    files=len(entries), **{key:sum(isinstance(e,dict) and e.get("status")==value for e in entries)
                    for key,value in (("applied","applied"),("restored","restored"),("failed","failed"))}))
            except (OSError,ValueError,TypeError) as exc:
                warn("部分日期记录未加载："+str(exc)[:160])
        return result

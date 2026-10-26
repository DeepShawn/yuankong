"""In-memory, ordered command lists. Delays start after the preceding item finishes."""
import copy
import threading
import time
import uuid


class CommandQueue:
    def __init__(self, execute):
        self.execute = execute
        self.lock = threading.RLock()
        self.batches = {}
        self.slots = threading.BoundedSemaphore(4)

    def request(self, q, state, error):
        owner = state.client_id
        action = q.get("action", "list")
        with self.lock:
            cutoff = time.time() - 7200
            for key, batch in list(self.batches.items()):
                if batch.get("finished", time.time()) < cutoff:
                    del self.batches[key]
            if action == "list":
                batches = [self._public(b) for b in self.batches.values() if b["owner"] == owner]
                return {"batches": sorted(batches, key=lambda b: b["created"], reverse=True)}
            if action == "cancel":
                batch = self.batches.get(str(q.get("batch_id", "")))
                if batch is None or batch["owner"] != owner:
                    raise error("命令列表不存在或不属于当前设备")
                batch["cancel"].set()
                for item in batch["items"]:
                    if item["status"] in ("pending", "waiting"):
                        item["status"] = "cancelled"
                return {"batch": self._public(batch)}
            if action != "add":
                raise error("无效的命令计划操作")
            items = q.get("items")
            if not isinstance(items, list) or not 1 <= len(items) <= 50:
                raise error("每个列表需要 1 到 50 条命令")
            validated = []
            for item in items:
                if (not isinstance(item, dict) or not isinstance(item.get("line"), str) or
                        not item["line"].strip() or len(item["line"]) > 8192 or "\0" in item["line"] or
                        item.get("shell", "cmd") not in ("cmd", "powershell") or
                        type(item.get("delay", 0)) is not int or not 0 <= item.get("delay", 0) <= 86400):
                    raise error("命令、解释器或延迟无效；延迟需为 0 到 86400 的整数秒")
                validated.append({"line": item["line"], "shell": item.get("shell", "cmd"),
                                  "delay": item.get("delay", 0), "status": "pending"})
            if sum(b["owner"] == owner for b in self.batches.values()) >= 50:
                raise error("最多保留 50 个计划，请等待旧记录过期后再添加")
            if not self.slots.acquire(False):
                raise error("同时最多运行 4 个命令列表，请等待或取消已有计划")
            key = uuid.uuid4().hex
            batch = {"id": key, "owner": owner, "name": str(q.get("name") or "命令列表")[:80],
                     "created": time.time(), "items": validated, "cancel": threading.Event()}
            self.batches[key] = batch
            try:
                threading.Thread(target=self._run, args=(batch,), name="lancmd-command-list", daemon=True).start()
            except Exception:
                del self.batches[key]; self.slots.release(); raise
            return {"batch": self._public(batch)}

    @staticmethod
    def _public(batch):
        return copy.deepcopy({k: v for k, v in batch.items() if k not in ("owner", "cancel")})

    def _run(self, batch):
        try:
            for item in batch["items"]:
                with self.lock:
                    if batch["cancel"].is_set():
                        item["status"] = "cancelled"; continue
                    item["status"] = "waiting"
                    item["due"] = time.time() + item["delay"]
                if batch["cancel"].wait(item["delay"]):
                    with self.lock: item["status"] = "cancelled"
                    continue
                with self.lock:
                    if batch["cancel"].is_set():
                        item["status"] = "cancelled"; continue
                    item["status"] = "running"; item["started"] = time.time()
                try:
                    result = self.execute(item["line"], item["shell"])
                    result = {k: (v[:8192] if isinstance(v, str) else v) for k, v in result.items()}
                    with self.lock:
                        item["result"] = result
                        item["status"] = "completed" if result.get("code", 0) == 0 else "failed"
                except Exception as exc:
                    with self.lock:
                        item["status"] = "failed"; item["error"] = str(exc)[:2000]
                with self.lock: item["finished"] = time.time()
        finally:
            with self.lock: batch["finished"] = time.time()
            self.slots.release()

    def close(self):
        with self.lock:
            for batch in self.batches.values():
                batch["cancel"].set()

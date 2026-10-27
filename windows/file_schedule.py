"""Owner-scoped delayed file requests, independent of a phone connection."""
import atexit
import copy
import json
import threading
import time
import uuid
from pathlib import Path
from types import SimpleNamespace

ALLOWED = {"files", "rm", "rename", "mkdir", "put", "file_jobs", "placeholders", "scripts"}


class FileSchedule:
    def __init__(self, execute):
        self.execute = execute
        self.lock = threading.RLock()
        self.jobs = {}
        self.closed = False
        atexit.register(self.close)

    @staticmethod
    def public(job, result=False):
        return copy.deepcopy({k: v for k, v in job.items()
            if k not in ("owner", "cancel", "done", "params") and (result or k != "result")})

    def request(self, q, state, error):
        with self.lock:
            action = q.get("action", "list")
            owned = [j for j in self.jobs.values() if j["owner"] == state.client_id]
            if action == "list":
                return {"jobs": [self.public(j) for j in reversed(owned[-20:])]}
            if action in ("get", "cancel"):
                job = self.jobs.get(q.get("job_id"))
                if not job or job["owner"] != state.client_id:
                    raise error("文件计划不存在或属于其他手机")
                if action == "cancel":
                    if job["status"] == "waiting":job["cancel"].set()
                    else:raise error("已开始执行，不能撤销；批量文件任务请在文件任务中取消")
                return {"job": self.public(job, True)}
            delay = q.get("delay", 0)
            command, params = q.get("command"), q.get("params")
            if (action != "add" or command not in ALLOWED or not isinstance(params, dict) or
                    type(delay) is not int or not 0 <= delay <= 86400 or not state.client_id):
                raise error("文件操作或延迟无效，延迟须为 0–86400 整数秒")
            if command in ("file_jobs", "placeholders") and params.get("action") != "create":
                raise error("仅创建任务可延迟；查询和取消应立即执行")
            if command == "scripts" and params.get("action") != "run":raise error("只有脚本运行可延迟")
            if len(json.dumps(params)) > 4 * 1024 * 1024:
                raise error("文件操作内容超过 4 MB")
            if self.closed or sum(j["status"] in ("waiting", "running") for j in self.jobs.values()) >= 20:
                raise error("同时最多 20 个文件计划，请等待或取消")
            for key, value in list(self.jobs.items()):
                if len(self.jobs) < 50:break
                if value["done"].is_set():del self.jobs[key]
            params=copy.deepcopy(params)
            if params.get("path") and (command in ("rm","rename","scripts") or command=="files" and params.get("action") in ("open","extract","mtime","preview")):
                from file_jobs import file_token,snapshot
                try:params.setdefault("file_id",file_token(snapshot(Path(params["path"]))))
                except OSError:pass
                except ValueError as exc:raise error(str(exc))
            job = dict(id=uuid.uuid4().hex, owner=state.client_id, command=command,
                params=copy.deepcopy(params), path=str(params.get("path", params.get("destination", "")))[:1024],
                status="waiting", created=time.time(), due=time.time()+delay,
                cancel=threading.Event(), done=threading.Event())
            self.jobs[job["id"]] = job
            threading.Thread(target=self._run, args=(job, delay), daemon=True, name="file-schedule").start()
            return {"job": self.public(job)}

    def _run(self, job, delay):
        try:
            if job["cancel"].wait(delay):
                with self.lock:job["status"] = "cancelled"
                return
            with self.lock:
                if job["cancel"].is_set():job["status"] = "cancelled";return
                job["status"] = "running"
            # No transfer handles: this whitelist contains file actions, never chunk RPCs.
            state = SimpleNamespace(client_id=job["owner"])
            if job["params"].get("file_id") and job["params"].get("path"):
                from file_jobs import file_token,snapshot
                if file_token(snapshot(Path(job["params"]["path"])))!=job["params"]["file_id"]:
                    raise ValueError("文件在等待执行期间已变化，未执行；请刷新后重试")
            result = self.execute(job["command"], copy.deepcopy(job["params"]), state)
            with self.lock:job.update(status="completed", result=result)
        except Exception as exc:
            with self.lock:job.update(status="failed", error=str(exc)[:2000])
        finally:
            with self.lock:job["finished"] = time.time()
            job["done"].set()

    def stop_for_device(self, device):
        roots = [str(p).replace("/", "\\").rstrip("\\").casefold()+"\\" for p in device.get("paths", []) if p]
        with self.lock:
            active = [j for j in self.jobs.values() if not j["done"].is_set()]
            if active and not roots:raise ValueError("设备路径未知，请先停止延迟文件操作")
            def paths(value):
                if isinstance(value,dict):
                    for key,item in value.items():
                        if key in ("path","to","destination") and isinstance(item,str):yield item.replace("/","\\").casefold()
                        elif isinstance(item,(dict,list)):yield from paths(item)
                elif isinstance(value,list):
                    for item in value:yield from paths(item)
            matches = [j for j in active if any(value==root.rstrip("\\") or value.startswith(root) for root in roots for value in paths(j["params"]))]
            for job in matches:job["cancel"].set()
        deadline = time.monotonic()+5
        for job in matches:
            if not job["done"].wait(max(0, deadline-time.monotonic())):
                raise ValueError("延迟文件操作正在执行，设备未禁用，请稍后重试")

    def close(self):
        with self.lock:
            self.closed = True
            for job in self.jobs.values():job["cancel"].set()

"""Owner-scoped process suspension with a separate EOF/heartbeat recovery worker."""
import atexit
import ctypes
from ctypes import wintypes as w
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import Future


class NativeSuspension:
    def __init__(self,protected):
        from task_manager import NativeProcesses
        self.processes=NativeProcesses(protected)
        self.kernel=self.processes.kernel
        self.kernel.GetExitCodeProcess.argtypes=[w.HANDLE,ctypes.POINTER(w.DWORD)]
        self.nt=ctypes.WinDLL("ntdll",use_last_error=True)
        for name in ("NtSuspendProcess","NtResumeProcess"):
            fn=getattr(self.nt,name);fn.argtypes=[w.HANDLE];fn.restype=ctypes.c_long

    def list(self):return self.processes.list()
    def list_matching(self,path):return self.processes.list(name=Path(path).name)
    def terminate(self,pid,created):self.processes.terminate(pid,created)

    def suspend(self,item,path):
        handle=self.kernel.OpenProcess(0x1800,False,item["pid"])
        if not handle:raise ctypes.WinError(ctypes.get_last_error())
        try:
            current=self.processes.info(item["pid"],handle=handle)
            if (not current["can_terminate"] or current["created"]!=item["created"] or
                    os.path.normcase(str(Path(current["path"]).resolve()))!=os.path.normcase(str(Path(path).resolve()))):
                raise ValueError("进程已变化、受保护或无法验证，未冻结")
            status=self.nt.NtSuspendProcess(handle)
            if status:raise OSError("Windows 拒绝冻结进程 (0x%08X)"%(status&0xffffffff))
            return handle
        except Exception:
            self.kernel.CloseHandle(handle);raise

    def resume(self,handle):
        code=w.DWORD()
        if not self.kernel.GetExitCodeProcess(handle,ctypes.byref(code)):
            raise ctypes.WinError(ctypes.get_last_error())
        if code.value==259:
            status=self.nt.NtResumeProcess(handle)
            if status:raise OSError("Windows 拒绝解冻进程 (0x%08X)"%(status&0xffffffff))
        self.kernel.CloseHandle(handle)


class FreezeCore:
    def __init__(self,backend,validate):self.backend=backend;self.validate=validate;self.jobs={}

    def public(self,owner):
        return [dict(id=key,path=value["path"],processes=[dict(pid=p["pid"],created=p["created"]) for p in value["processes"]],
                     errors=value.get("errors",[])) for key,value in self.jobs.items() if value["owner"]==owner]

    def request(self,q):
        if q.get("action")=="release_all":
            errors=self.close()
            if errors:raise ValueError("解冻尚未完成："+"；".join(errors)[:1200])
            return dict(restored=True)
        owner=q.get("owner","")
        if not isinstance(owner,str) or not owner or len(owner)>200:raise ValueError("连接身份无效")
        action=q.get("action","list")
        if action=="list":return dict(items=self.public(owner))
        if action=="resume":
            key=q.get("freeze_id");job=self.jobs.get(key)
            if job is None or job["owner"]!=owner:raise ValueError("冻结记录不存在或属于其他手机")
            errors=self._resume(key)
            return dict(items=self.public(owner),resumed=not errors,errors=errors)
        if action not in ("freeze","close"):raise ValueError("无效的应用冻结操作")
        path=str(self.validate(q.get("path"))[0])
        if action=="freeze" and any(os.path.normcase(v["path"])==os.path.normcase(path) for v in self.jobs.values()):
            raise ValueError("此应用已由本接收端冻结，请先解冻")
        def canonical(value):
            try:return os.path.normcase(os.path.abspath(value)) if value else ""
            except (OSError,ValueError):return ""
        source=self.backend.list_matching(path) if hasattr(self.backend,"list_matching") else self.backend.list()
        matching=[p for p in source if canonical(p.get("path",""))==canonical(path)]
        if action=="close":
            if any(canonical(job["path"])==canonical(path) and job["owner"]!=owner for job in self.jobs.values()):
                raise ValueError("应用由其他手机冻结，请先由原手机解冻")
            errors=[];terminated=set()
            for item in matching:
                if not item.get("can_terminate"):
                    errors.append("进程 %s 受保护或无权验证，已跳过"%item["pid"]);continue
                try:self.backend.terminate(item["pid"],item["created"]);terminated.add((item["pid"],item["created"]))
                except (OSError,ValueError) as exc:errors.append("进程 %s：%s"%(item["pid"],exc))
            for key,job in list(self.jobs.items()):
                if job["owner"]!=owner:continue
                for item in list(job["processes"]):
                    if (item["pid"],item["created"]) in terminated:
                        try:self.backend.resume(item["handle"]);job["processes"].remove(item)
                        except OSError as exc:errors.append(str(exc))
                if not job["processes"]:self.jobs.pop(key)
            if errors and not terminated:raise ValueError("未关闭任何进程："+"；".join(errors)[:1000])
            return dict(items=self.public(owner),closed=len(terminated),errors=errors,partial=bool(errors))
        if not matching:raise ValueError("此应用没有正在运行的进程；禁止启动请使用应用锁")
        if len(self.jobs)>=50 or len(matching)>256:raise ValueError("最多冻结 50 个应用、每个应用 256 个进程")
        key=uuid.uuid4().hex
        job=dict(owner=owner,path=path,processes=[],errors=[]);self.jobs[key]=job
        for item in matching:
            if not item.get("can_terminate"):
                job["errors"].append("进程 %s 受保护或无权验证，已跳过"%item["pid"]);continue
            try:
                handle=self.backend.suspend(item,path)
                job["processes"].append(dict(pid=item["pid"],created=item["created"],handle=handle))
            except (OSError,ValueError) as exc:job["errors"].append("进程 %s：%s"%(item["pid"],exc))
        if not job["processes"]:
            self.jobs.pop(key)
            raise ValueError("未冻结任何进程："+"；".join(job["errors"])[:1000])
        return dict(items=self.public(owner),freeze_id=key,partial=bool(job["errors"]))

    def _resume(self,key):
        job=self.jobs[key];errors=[]
        for item in list(job["processes"]):
            try:self.backend.resume(item["handle"]);job["processes"].remove(item)
            except OSError as exc:errors.append("进程 %s：%s"%(item["pid"],exc))
        job["errors"]=errors
        if not job["processes"]:self.jobs.pop(key)
        return errors

    def close(self):
        errors=[]
        for key in list(self.jobs):errors+=self._resume(key)
        return errors


def serve_worker(core,source,destination,watchdog=12):
    inbox=queue.Queue();stopped=threading.Event()
    def reader():
        try:
            for line in source:
                inbox.put(json.loads(line))
        except (ValueError,OSError):pass
        finally:stopped.set()
    threading.Thread(target=reader,daemon=True).start()
    last=time.monotonic()
    try:
        while not stopped.is_set() and time.monotonic()-last<watchdog:
            try:q=inbox.get(timeout=.2)
            except queue.Empty:continue
            last=time.monotonic()
            try:
                result={} if q.get("action")=="ping" else core.request(q)
                result=dict(result,ok=True)
            except Exception as exc:result=dict(ok=False,error=str(exc)[:1600])
            destination.write(json.dumps(result)+"\n");destination.flush()
    finally:
        for _ in range(30):
            if not core.close():return 0
            time.sleep(1)
        return 1


def worker_main():
    from app_locks import AppLocks
    if sys.stdin is None or sys.stdout is None:
        import msvcrt
        kernel=ctypes.WinDLL("kernel32")
        kernel.GetStdHandle.argtypes=[w.DWORD];kernel.GetStdHandle.restype=w.HANDLE
        if sys.stdin is None:
            sys.stdin=os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-10&0xffffffff),os.O_RDONLY),"r",encoding="utf-8")
        if sys.stdout is None:
            sys.stdout=os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-11&0xffffffff),os.O_WRONLY),"w",encoding="utf-8")
    root=Path(__file__).resolve().parent
    install=Path(sys.executable).resolve().parent if getattr(sys,"frozen",False) else root
    protected=(root,install,Path(sys.executable),Path(os.environ.get("APPDATA",str(root)))/"LanCmd",os.environ.get("SystemRoot",r"C:\Windows"))
    locks=AppLocks(root/"unused-freeze-policy.json",protected=protected)
    return serve_worker(FreezeCore(NativeSuspension(protected),locks._target),sys.stdin,sys.stdout)


class AppFreeze:
    def __init__(self):
        self.lock=threading.RLock();self.process=None;self.recovering=None;self.closed=False;self.failure=""
        self.jobs_lock=threading.RLock();self.jobs={};self.snapshots={}
        atexit.register(self.close)
        threading.Thread(target=self._heartbeat,daemon=True,name="freeze-heartbeat").start()

    def _rpc(self,q):
        with self.lock:
            if self.closed:raise ValueError("接收端正在退出")
            if self.failure:raise ValueError(self.failure)
            if self.recovering is not None:
                if self.recovering.poll() is None:raise ValueError("冻结恢复仍在进行，请稍后重试")
                if self.recovering.returncode:
                    self.failure="冻结恢复未确认完成，请在 Windows 资源监视器检查并恢复应用后重启接收端"
                    raise ValueError(self.failure)
                self.recovering.stdout.close();self.recovering=None
            if self.process is not None and self.process.poll() is not None:
                self.process.stdout.close();self.process=None
                self.failure="冻结恢复进程异常结束，状态无法确认；请在 Windows 资源监视器恢复应用后重启接收端"
                raise ValueError(self.failure)
            if self.process is None:
                if os.name!="nt":raise ValueError("应用冻结仅支持 Windows")
                if getattr(sys,"frozen",False):command=[sys.executable,"--freeze-worker"]
                else:
                    executable=Path(sys.executable)
                    if executable.name.lower()=="pythonw.exe":executable=executable.with_name("python.exe")
                    command=[str(executable),"-u",str(Path(__file__).resolve())]
                self.process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                    text=True,encoding="utf-8",creationflags=0x08000000,close_fds=True)
            process=self.process;ready=Future()
            def read():
                try:ready.set_result(json.loads(process.stdout.readline()))
                except Exception as exc:ready.set_exception(exc)
            process.stdin.write(json.dumps(q)+"\n");process.stdin.flush()
            threading.Thread(target=read,daemon=True).start()
            try:result=ready.result(20)
            except Exception:
                process.stdin.close();self.recovering=process;self.process=None
                raise ValueError("应用冻结控制超时或连接结束，已请求独立进程恢复，请稍后刷新") from None
            if not result.get("ok"):raise ValueError(result.get("error","冻结操作失败"))
            return result

    def _finish_job(self,job,request):
        with self.jobs_lock:job["status"]="running"
        try:
            result=self._rpc(request)
            with self.jobs_lock:
                if "items" in result:self.snapshots[job["owner"]]=result["items"]
                job.update(status="completed",result=result)
        except Exception as exc:
            with self.jobs_lock:job.update(status="failed",error=str(exc)[:1600])

    def request(self,q,state,error):
        try:
            owner=state.client_id
            if not isinstance(owner,str) or not owner:raise ValueError("连接身份无效")
            action=q.get("action","list")
            if action=="state":
                with self.jobs_lock:return dict(items=list(self.snapshots.get(owner,[])),
                    jobs=[{key:value for key,value in job.items() if key!="owner"} for job in self.jobs.values()
                          if job["owner"]==owner and job["status"] in ("queued","running")])
            if action=="job":
                with self.jobs_lock:
                    job=self.jobs.get(q.get("job_id"))
                    if job is None or job["owner"]!=owner:raise ValueError("冻结任务不存在或属于其他手机")
                    return dict(job={key:value for key,value in job.items() if key!="owner"})
            if action=="submit":
                operation=q.get("operation")
                if operation not in ("freeze","resume","close"):raise ValueError("无效的应用冻结操作")
                with self.jobs_lock:
                    if self.closed:raise ValueError("接收端正在退出")
                    if any(job["status"] in ("queued","running") for job in self.jobs.values()):
                        raise ValueError("上一项冻结或解冻仍在处理，请等待结果")
                    while len(self.jobs)>=100:self.jobs.pop(next(iter(self.jobs)))
                    job=dict(id=uuid.uuid4().hex,owner=owner,status="queued",operation=operation)
                    self.jobs[job["id"]]=job
                    request=dict(action=operation,owner=owner)
                    for key in ("path","freeze_id"):
                        if key in q:request[key]=q[key]
                    threading.Thread(target=self._finish_job,args=(job,request),name="app-freeze-job",daemon=True).start()
                    return dict(job={key:value for key,value in job.items() if key!="owner"})
            if action not in ("list","freeze","resume","close"):raise ValueError("无效的应用冻结操作")
            # Legacy callers retain their synchronous protocol.
            result=self._rpc(dict(q,owner=owner))
            if "items" in result:
                with self.jobs_lock:self.snapshots[owner]=result["items"]
            return result
        except (OSError,ValueError) as exc:raise error(str(exc))

    def prepare_upgrade(self):
        with self.lock:
            with self.jobs_lock:
                if any(job["status"] in ("queued","running") for job in self.jobs.values()):
                    raise ValueError("应用操作仍在处理，请等待冻结、解冻或关闭完成后升级")
            if self.recovering is not None or self.failure:raise ValueError("冻结恢复未确认完成，请先恢复应用后再升级")
            if self.process is not None:self._rpc(dict(action="release_all"))
            with self.jobs_lock:self.snapshots.clear()

    def _heartbeat(self):
        while not self.closed:
            time.sleep(2)
            with self.lock:
                if self.process is not None:
                    try:self._rpc(dict(action="ping"))
                    except (OSError,ValueError):pass

    def close(self):
        with self.lock:
            self.closed=True
            if self.process is not None:
                try:self.process.stdin.close()
                except OSError:pass
                self.recovering=self.process;self.process=None


if __name__=="__main__":sys.exit(worker_main())

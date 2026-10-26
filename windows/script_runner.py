"""Explicit BAT/CMD/VBS jobs, hidden hosts, bounded output and checked owners."""
import atexit
import ctypes
import os
from pathlib import Path
import subprocess
import threading
import tempfile
import time
import uuid

class ProcessJob:
    def __init__(self,process):
        from ctypes import wintypes as w
        class Basic(ctypes.Structure):
            _fields_=[("process_time",ctypes.c_longlong),("job_time",ctypes.c_longlong),("flags",w.DWORD),
                ("min_working",ctypes.c_size_t),("max_working",ctypes.c_size_t),("active",w.DWORD),
                ("affinity",ctypes.c_size_t),("priority",w.DWORD),("scheduling",w.DWORD)]
        class Extended(ctypes.Structure):
            _fields_=[("basic",Basic),("io",ctypes.c_ulonglong*6)]+[(n,ctypes.c_size_t) for n in ("process_memory","job_memory","peak_process","peak_job")]
        self.Extended=Extended;self.kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes=[ctypes.c_void_p,w.LPCWSTR];self.kernel.CreateJobObjectW.restype=w.HANDLE
        self.kernel.SetInformationJobObject.argtypes=[w.HANDLE,ctypes.c_int,ctypes.c_void_p,w.DWORD]
        self.kernel.AssignProcessToJobObject.argtypes=[w.HANDLE,w.HANDLE]
        self.kernel.TerminateJobObject.argtypes=[w.HANDLE,w.UINT];self.kernel.CloseHandle.argtypes=[w.HANDLE]
        self.handle=self.kernel.CreateJobObjectW(None,None)
        if not self.handle:raise ctypes.WinError(ctypes.get_last_error())
        limits=Extended();limits.basic.flags=0x2000
        if not self.kernel.SetInformationJobObject(self.handle,9,ctypes.byref(limits),ctypes.sizeof(limits)) or not self.kernel.AssignProcessToJobObject(self.handle,int(process._handle)):
            error=ctypes.get_last_error()
            self.kernel.CloseHandle(self.handle);self.handle=None;raise ctypes.WinError(error)
    def terminate(self):
        if self.handle and not self.kernel.TerminateJobObject(self.handle,1):raise ctypes.WinError(ctypes.get_last_error())
    def close(self,normal=False):
        if not self.handle:return
        if normal:
            limits=self.Extended()
            self.kernel.SetInformationJobObject(self.handle,9,ctypes.byref(limits),ctypes.sizeof(limits))
        self.kernel.CloseHandle(self.handle);self.handle=None


def attach_process_job(process):
    try:
        return ProcessJob(process)
    except OSError:
        # A small BAT file may finish between CreateProcess and assignment.
        # Its result and remaining pipe bytes are still valid.
        if process.poll() is not None:
            return None
        raise


def script_command(path):
    env=dict(os.environ)
    if path.suffix.lower() in (".bat",".cmd"):
        # The path is expanded once, inside quotes. CALL and delayed expansion
        # would expand a filename containing percent signs or exclamation marks.
        env["LANCMD_SCRIPT_FILE"]=str(path)
        prefix=[str(Path(os.environ.get("WINDIR",r"C:\Windows"))/"System32"/"cmd.exe"),"/d","/q","/v:off","/s","/c"]
        return subprocess.list2cmdline(prefix)+' ""%LANCMD_SCRIPT_FILE%""',env
    cscript=str(Path(os.environ.get("WINDIR",r"C:\Windows"))/"System32"/"cscript.exe")
    # Batch mode prevents WSH alerts and input prompts. Scripts can return text
    # with WScript.StdOut/StdErr; WScript.Echo is suppressed in batch mode.
    # Select the engine explicitly instead of relying on .vbs file association.
    return [cscript,"//B","//Nologo","//U","//E:VBScript","//T:300",str(path)],env


def check_vbscript(run=None):
    """Prove the installed host executes VBScript before starting user code.

    New Windows builds may retain cscript.exe without a working VBScript engine.
    In particular, an exit status of zero alone is not proof that code ran.
    This fixed probe neither changes policies nor installs Windows components.
    """
    run=run or subprocess.run
    token="LanCmd-VBScript-check"
    try:
        with tempfile.TemporaryDirectory(prefix="LanCmd-vbs-check-") as directory:
            probe=Path(directory)/"check.vbs"
            probe.write_bytes(('WScript.StdOut.WriteLine "'+token+'"\r\nWScript.Quit 37\r\n').encode("ascii"))
            command,env=script_command(probe)
            command=["//T:5" if option=="//T:300" else option for option in command]
            result=run(command,cwd=directory,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                creationflags=0x08000000 if os.name=="nt" else 0,timeout=10)
            if result.returncode==37 and token.encode("utf-16-le") in result.stdout:
                return
            detail="CScript 返回码 %s，输出 %s/%s 字节" % (result.returncode,len(result.stdout),len(result.stderr))
    except (OSError,subprocess.SubprocessError) as exc:
        detail=str(exc)[:300]
    raise ValueError("VBScript 运行组件未通过执行检查（%s）；用户脚本未启动。请在电脑上检查 VBScript 可选功能和系统脚本策略。LanCmd 不会自动安装组件或更改策略。" % detail)


def read_available(stream):
    if os.name=="nt":
        import msvcrt
        from ctypes import wintypes as w
        kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        kernel.PeekNamedPipe.argtypes=[w.HANDLE,ctypes.c_void_p,w.DWORD,ctypes.c_void_p,ctypes.POINTER(w.DWORD),ctypes.c_void_p]
        available=w.DWORD()
        if not kernel.PeekNamedPipe(msvcrt.get_osfhandle(stream.fileno()),None,0,None,ctypes.byref(available),None):return b""
        return os.read(stream.fileno(),min(4096,available.value)) if available.value else b""
    import select
    return os.read(stream.fileno(),4096) if select.select([stream],[],[],0)[0] else b""


class ScriptRunner:
    def __init__(self,popen=None):self.popen=popen or subprocess.Popen;self.jobs={};self.lock=threading.RLock();atexit.register(self.close)
    def request(self,q,state,error):
        try:
            if os.name!="nt" and self.popen is subprocess.Popen:raise ValueError("脚本运行仅支持 Windows")
            action=q.get("action","list")
            with self.lock:
                if action=="list":return {"items":[dict(j["data"]) for j in reversed(list(self.jobs.values())) if j["owner"]==state.client_id]}
                if action=="cancel":
                    job=self.jobs.get(q.get("job_id"))
                    if not job or job["owner"]!=state.client_id:raise ValueError("脚本任务不存在或属于其他手机")
                    job["cancel"].set();return {"cancelled":True}
                if action!="run":raise ValueError("无效的脚本任务操作")
                value=q.get("path")
                if not isinstance(value,str) or not value or "\0" in value:raise ValueError("请选择脚本文件")
                path=Path(value).resolve(strict=True)
                if not path.is_file() or path.suffix.lower() not in (".bat",".cmd",".vbs"):raise ValueError("只支持运行 BAT、CMD、VBS 文件")
                if sum("finished" not in j["data"] for j in self.jobs.values())>=4:raise ValueError("最多同时运行 4 个脚本")
                history=[rid for rid,j in self.jobs.items() if "finished" in j["data"]]
                while len(self.jobs)>=100 and history:self.jobs.pop(history.pop(0))
                rid=uuid.uuid4().hex
                job={"owner":state.client_id,"cancel":threading.Event(),"data":dict(id=rid,path=str(path),status="starting",created=time.time(),out="",err="",timeout=300)}
                self.jobs[rid]=job
                threading.Thread(target=self._run,args=(job,path),daemon=True,name="file-script").start()
                return {"job":dict(job["data"])}
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))
    def _run(self,job,path):
        process=None;group=None;normal=False;data=job["data"]
        try:
            if job["cancel"].is_set():data["status"]="cancelled";return
            if path.suffix.lower()==".vbs":check_vbscript()
            command,env=script_command(path)
            if job["cancel"].is_set():data["status"]="cancelled";return
            process=self.popen(command,cwd=str(path.parent),env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,
                creationflags=0x08000000 if os.name=="nt" else 0,close_fds=True)
            if os.name=="nt":group=attach_process_job(process)
            data["status"]="running";data["started"]=time.time()
            deadline=time.monotonic()+300;output=bytearray();errors=bytearray();killed=False
            while True:
                if not killed and (job["cancel"].is_set() or time.monotonic()>deadline):
                    data["status"]="cancelled" if job["cancel"].is_set() else "timed_out"
                    if group:group.terminate()
                    else:process.kill()
                    killed=True;deadline=time.monotonic()+5
                for stream,buffer in ((process.stdout,output),(process.stderr,errors)):
                    chunk=read_available(stream)
                    if chunk:
                        if len(buffer)+len(chunk)>8192:data["truncated"]=True
                        buffer.extend(chunk[:max(0,8192-len(buffer))])
                code=process.poll()
                if code is not None:
                    for stream,buffer in ((process.stdout,output),(process.stderr,errors)):
                        for _ in range(32):
                            chunk=read_available(stream)
                            if not chunk:break
                            if len(buffer)+len(chunk)>8192:data["truncated"]=True
                            buffer.extend(chunk[:max(0,8192-len(buffer))])
                    normal=not killed;break
                if killed and time.monotonic()>deadline:process.kill();raise ValueError("脚本未及时退出，已终止运行器")
                time.sleep(.01)
            def decode(raw):
                if path.suffix.lower()==".vbs":return bytes(raw[:len(raw)//2*2]).decode("utf-16-le","replace")
                try:return bytes(raw).decode("utf-8")
                except UnicodeError:
                    codec="cp"+str(ctypes.windll.kernel32.GetOEMCP()) if os.name=="nt" else "gb18030"
                    return bytes(raw).decode(codec,"replace")
            with self.lock:
                data.update(code=code,out=decode(output),err=decode(errors),out_bytes=len(output),err_bytes=len(errors))
                if data["status"]=="running":data["status"]="completed" if code==0 else "failed"
                if code and not data["err"] and path.suffix.lower()==".vbs":
                    data["err"]="VBScript 退出码：%s。批处理模式不显示脚本提示框或交互式错误。" % code
        except Exception as exc:
            with self.lock:data.update(status="failed",err=str(exc)[:2000])
        finally:
            if group:group.close(normal)
            if process:
                if process.poll() is None:
                    process.kill()
                    try:process.wait(timeout=5)
                    except subprocess.TimeoutExpired:pass
                for stream in (process.stdout,process.stderr):
                    if stream:stream.close()
            data["finished"]=time.time()
    def close(self):
        with self.lock:
            for job in self.jobs.values():job["cancel"].set()

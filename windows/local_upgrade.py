"""Local, explicitly selected portable ZIP upgrades. This module never uses a network."""
from contextlib import contextmanager, nullcontext
import ctypes
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile

LIMIT=512*1024*1024
MANIFEST="release-manifest.json"
REQUIRED={"LanCmdAgent.exe","LanCmdCtl.exe","Start.exe","start.bat","lan_command_agent.py","local_upgrade.py","python/pythonw.exe","python/python311.dll"}
REMOTE_COMMANDS={"upgrade","capabilities","screen","camera","session","input","pointer","touch","local_input","cursor","gestures"}

class RecoveryError(OSError):pass


def version(value):
    if not isinstance(value,str) or not re.fullmatch(r"\d{1,4}\.\d{1,4}\.\d{1,4}",value):raise ValueError("升级包版本号无效")
    return tuple(map(int,value.split(".")))


def relative(value):
    if not isinstance(value,str) or not value or "\\" in value or ":" in value or "\0" in value:raise ValueError("升级包含无效路径")
    parts=value.split("/")
    if any(p in ("",".","..") or p.endswith(("."," ")) or re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)",p) for p in parts):raise ValueError("升级包含不安全路径")
    if parts[0].casefold() in ("upgrade",".upgrade","__pycache__"):raise ValueError("升级包试图覆盖升级数据")
    return PurePosixPath(value)


def ordinary(path,directory=False):
    info=Path(path).lstat()
    if getattr(info,"st_file_attributes",0)&0x400 or stat.S_ISLNK(info.st_mode) or not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError("升级路径不能是链接或特殊文件")
    return info


def digest(path):
    sha=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):sha.update(block)
    return sha.hexdigest()


def write_json(path,data):
    path=Path(path);temp=path.with_suffix(".tmp")
    with temp.open("w",encoding="utf-8") as stream:
        json.dump(data,stream,ensure_ascii=False,indent=2);stream.flush();os.fsync(stream.fileno())
    os.replace(temp,path)


def build_manifest(root,release,source=""):
    root=Path(root);version(release)
    files={}
    for path in sorted(root.rglob("*")):
        name=path.relative_to(root).as_posix()
        if path.is_dir() or name==MANIFEST or name.split("/")[0] in ("upgrade",".upgrade","__pycache__"):continue
        relative(name);info=ordinary(path)
        files[name]=dict(size=info.st_size,sha256=digest(path))
    if not REQUIRED.issubset(files):raise ValueError("Windows 包缺少必要运行文件")
    data=dict(product="LanCmd-Windows",format=1,arch="amd64",version=release,source=source,files=files)
    write_json(root/MANIFEST,data);return data


def unpack(package,destination,current):
    """Validate all entries and hashes before touching the installed directory."""
    package=Path(package);info=ordinary(package)
    if info.st_size>LIMIT:raise ValueError("升级 ZIP 不得超过 512 MiB")
    destination=Path(destination) if destination is not None else None
    with zipfile.ZipFile(package) as archive:
        members=archive.infolist()
        if len(members)>2000 or sum(m.file_size for m in members)>LIMIT:raise ValueError("升级包展开后过大或文件过多")
        by_name={}
        for item in members:
            name=item.filename.rstrip("/")
            if not name.startswith("LanCmd-Windows/"):raise ValueError("请选择包含 LanCmd-Windows 目录的完整 Windows ZIP")
            name=name[len("LanCmd-Windows/"):]
            if not name:
                if item.is_dir():continue
                raise ValueError("无效的升级包目录")
            # A packaged upgrade/README is informational, never installed by the updater.
            if name in ("upgrade","upgrade/README.txt"):
                if name=="upgrade" and not item.is_dir():raise ValueError("无效的 upgrade 目录")
                continue
            relative(name)
            if item.flag_bits&1 or stat.S_IFMT(item.external_attr>>16) in (stat.S_IFLNK,stat.S_IFCHR,stat.S_IFBLK,stat.S_IFIFO):raise ValueError("升级包不能含链接、加密或特殊文件")
            key=name.casefold()
            if key in by_name:raise ValueError("升级包含重复路径")
            by_name[key]=(name,item)
        manifest_entry=by_name.get(MANIFEST)
        if manifest_entry is None or manifest_entry[1].file_size>1024*1024:raise ValueError("升级包缺少校验清单，请使用支持本地升级的新版本 ZIP")
        manifest=json.loads(archive.read(manifest_entry[1]))
        if (not isinstance(manifest,dict) or manifest.get("product")!="LanCmd-Windows" or manifest.get("format")!=1 or manifest.get("arch")!="amd64" or version(manifest.get("version"))<=version(current)):
            raise ValueError("升级包不兼容，或版本没有高于当前版本")
        files=manifest.get("files")
        if not isinstance(files,dict) or not REQUIRED.issubset(files) or len(files)>1000:raise ValueError("升级包运行文件不完整")
        expected={MANIFEST}
        for name,record in files.items():
            relative(name)
            if name==MANIFEST or not isinstance(record,dict) or type(record.get("size")) is not int or not 0<=record["size"]<=LIMIT or not re.fullmatch("[0-9a-f]{64}",str(record.get("sha256",""))):raise ValueError("升级清单无效")
            if name.casefold() in expected:raise ValueError("升级清单含重复路径")
            expected.add(name.casefold())
            entry=by_name.get(name.casefold())
            if entry is None or entry[0]!=name or entry[1].is_dir() or entry[1].file_size!=record["size"]:raise ValueError("升级包与清单不一致："+name)
        actual={key for key,(_,entry) in by_name.items() if not entry.is_dir()}
        if actual!=expected:raise ValueError("升级包含未列入清单的文件")
        if destination is not None:destination.mkdir(parents=True,exist_ok=False)
        try:
            for name,record in files.items():
                target=destination/str(relative(name)) if destination is not None else None
                if target is not None:target.parent.mkdir(parents=True,exist_ok=True)
                sha=hashlib.sha256();size=0
                with archive.open(by_name[name.casefold()][1]) as source,(target.open("xb") if target is not None else nullcontext()) as out:
                    for block in iter(lambda:source.read(1024*1024),b""):
                        size+=len(block)
                        if size>record["size"]:raise ValueError("升级内容超过清单大小")
                        sha.update(block)
                        if out is not None:out.write(block)
                if size!=record["size"] or sha.hexdigest()!=record["sha256"]:raise ValueError("升级文件校验失败："+name)
            if destination is not None:write_json(destination/MANIFEST,manifest)
        except Exception:
            if destination is not None:shutil.rmtree(destination,ignore_errors=True)
            raise
    return manifest


def safe_target(root,name):
    target=Path(root)
    ordinary(target,True)
    for part in relative(name).parts:
        target=target/part
        if target.exists() or target.is_symlink():ordinary(target,target.is_dir())
    return target


def install(root,stage,backup,files,replace=os.replace):
    """Journal each file before replacement, rollback all changed files on failure."""
    root,stage,backup=map(Path,(root,stage,backup));backup.mkdir(parents=True,exist_ok=False)
    entries=[]
    try:
        for name in sorted(files)+[MANIFEST]:
            target=safe_target(root,name);source=stage/name;ordinary(source)
            old=backup/name;existed=target.exists()
            if existed and target.is_dir():raise ValueError("安装文件与已有文件夹冲突："+name)
            old.parent.mkdir(parents=True,exist_ok=True);target.parent.mkdir(parents=True,exist_ok=True)
            entries.append(dict(name=name,existed=existed,backed=False,installed=False))
            write_json(backup/"journal.json",entries)
            if existed:replace(target,old);entries[-1]["backed"]=True
            write_json(backup/"journal.json",entries)
            replace(source,target);entries[-1]["installed"]=True
            write_json(backup/"journal.json",entries)
    except Exception as original:
        try:rollback(root,backup,entries)
        except Exception as recovery:raise RecoveryError("安装失败且回退未完成：%s；%s"%(original,recovery)) from original
        raise
    return entries


def rollback(root,backup,entries):
    errors=[]
    for item in reversed(entries):
        try:
            target=safe_target(root,item["name"]);old=Path(backup)/item["name"]
            if item["backed"]:os.replace(old,target)
            elif item["installed"] and not item["existed"]:target.unlink(missing_ok=True)
        except OSError as exc:errors.append(str(exc))
    if errors:raise OSError("部分文件恢复失败，请保留备份目录："+str(backup)+"；"+"；".join(errors)[:1000])


class LocalUpgrade:
    def __init__(self,root,state,release,busy=lambda:None,stop=lambda:None,prepare=lambda:None):
        self.root=Path(root);self.state=Path(state);self.release=release;self.busy=busy;self.stop=stop;self.prepare=prepare
        self.lock=threading.RLock();self.active=0;self.draining=False;self.commands={}

    @contextmanager
    def operation(self,command):
        with self.lock:
            if self.draining and command not in ("upgrade","capabilities"):raise ValueError("接收端正在升级，请稍后重连")
            self.active+=1
            self.commands[command]=self.commands.get(command,0)+1
        try:yield
        finally:
            with self.lock:
                self.active-=1;self.commands[command]-=1
                if not self.commands[command]:self.commands.pop(command)

    def request(self,q,error):
        try:
            if os.name!="nt":raise ValueError("本地升级仅支持 Windows 便携完整包")
            with self.lock:
                directory=self.root/"upgrade";directory.mkdir(exist_ok=True);ordinary(directory,True)
                if q.get("action","list")=="list":
                    items=[]
                    for path in (list(directory.iterdir())[:200] if q.get("include_packages",True) else []):
                        if len(items)>=5:break
                        if path.suffix.lower()!=".zip":continue
                        try:
                            info=ordinary(path)
                            items.append(dict(name=path.name,size=info.st_size,sha256=digest(path) if info.st_size<=LIMIT else ""))
                        except (OSError,ValueError):continue
                    report={}
                    try:report=json.loads((self.state/"upgrade-status.json").read_text(encoding="utf-8"))
                    except (OSError,ValueError):pass
                    return dict(items=items,directory=str(directory),current=self.release,status=report)
                if q.get("action")!="apply" or self.draining:raise ValueError("升级已经开始或操作无效")
                request_id=q.get("request_id",uuid.uuid4().hex)
                if not isinstance(request_id,str) or not re.fullmatch("[0-9a-f]{32}",request_id):raise ValueError("升级请求编号无效")
                name=q.get("name")
                if not isinstance(name,str) or Path(name).name!=name or "/" in name or "\\" in name or not name.lower().endswith(".zip"):raise ValueError("请选择 upgrade 中的 ZIP")
                if getattr(sys,"frozen",False) or not (self.root/"python/pythonw.exe").is_file():raise ValueError("请先使用包含 python 文件夹的完整 Windows 便携包")
                if any(command not in REMOTE_COMMANDS for command in self.commands):raise ValueError("其他文件或系统操作正在进行，请稍后再试")
                reason=self.busy()
                if reason:raise ValueError(reason)
                self.draining=True
                try:
                    package=directory/name;ordinary(package)
                    if not re.fullmatch("[0-9a-f]{64}",str(q.get("sha256",""))):raise ValueError("请刷新升级列表后选择 ZIP")
                    self.state.mkdir(parents=True,exist_ok=True)
                    # Stage and backup must share the installation's volume.
                    # APPDATA is often C: while the portable receiver is on D:/USB.
                    workspace=self.root/".upgrade";workspace.mkdir(exist_ok=True);ordinary(workspace,True)
                    work=Path(tempfile.mkdtemp(prefix="run-",dir=workspace))
                    process=None
                    try:
                        copied=work/"package.zip";shutil.copyfile(package,copied)
                        if digest(copied)!=q["sha256"]:raise ValueError("ZIP 在选择后已变化，请刷新升级列表")
                        # Verify without extraction while the old receiver is live.
                        # Actual extraction happens only after its process exits.
                        manifest=unpack(copied,None,self.release)
                        self.prepare()
                        if shutil.disk_usage(self.root).free<sum(v["size"] for v in manifest["files"].values())*2+64*1024*1024:raise ValueError("安装盘空间不足以解压并保留回退备份")
                        runtime=work/"runtime";shutil.copytree(self.root/"python",runtime,symlinks=False)
                        shutil.copyfile(__file__,runtime/"local_upgrade.py")
                        plan=dict(root=str(self.root),work=str(work),parent=os.getpid(),state=str(self.state/"upgrade-status.json"),version=manifest["version"],previous=self.release,request_id=request_id,files=list(manifest["files"]))
                        write_json(work/"plan.json",plan)
                        process=subprocess.Popen([str(runtime/"pythonw.exe"),str(runtime/"local_upgrade.py"),"--apply",str(work/"plan.json")],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=0x08000000,close_fds=True,cwd=str(runtime))
                        deadline=time.monotonic()+10
                        while not (work/"ready").exists():
                            if process.poll() is not None or time.monotonic()>deadline:raise ValueError("升级恢复进程未就绪，接收端保持运行")
                            time.sleep(.05)
                        write_json(self.state/"upgrade-status.json",dict(status="prepared",version=manifest["version"],request_id=request_id,backup=str(work/"backup")))
                    except Exception:
                        if process is not None and process.poll() is None:
                            process.terminate();process.wait(timeout=5)
                        shutil.rmtree(work,ignore_errors=True);raise
                    def disconnect():
                        try:write_json(self.state/"upgrade-status.json",dict(status="disconnecting",version=manifest["version"],request_id=request_id,backup=str(work/"backup")))
                        finally:self.stop()
                    threading.Timer(1.5,disconnect).start()
                    return dict(restarting=True,version=manifest["version"],request_id=request_id,message="已校验本地 ZIP；将断开当前连接，解压替换后自动运行 start.bat 并关闭 CMD，请等待重连")
                except Exception:self.draining=False;raise
        except (OSError,ValueError,TypeError,zipfile.BadZipFile) as exc:raise error(str(exc))



def process_birth(kernel,handle):
    from ctypes import wintypes as w
    kernel.GetProcessTimes.argtypes=[w.HANDLE,*([ctypes.POINTER(w.FILETIME)]*4)]
    birth,exit_,system,user=w.FILETIME(),w.FILETIME(),w.FILETIME(),w.FILETIME()
    if not kernel.GetProcessTimes(handle,ctypes.byref(birth),ctypes.byref(exit_),ctypes.byref(system),ctypes.byref(user)):
        raise ctypes.WinError(ctypes.get_last_error())
    return str((birth.dwHighDateTime<<32)|birth.dwLowDateTime)


class WindowsProcess:
    """Hold the verified process handle; never terminate by a possibly reused PID."""
    def __init__(self,pid,created=None,image=None):
        from ctypes import wintypes as w
        if type(pid) is not int or pid<=0:raise ValueError("启动进程编号无效")
        self.pid=pid;self.handle=None
        self.kernel=kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE
        kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD]
        kernel.TerminateProcess.argtypes=[w.HANDLE,w.UINT];kernel.CloseHandle.argtypes=[w.HANDLE]
        self.handle=kernel.OpenProcess(0x101001,False,pid)  # synchronize/query/terminate
        if not self.handle:raise ctypes.WinError(ctypes.get_last_error())
        try:
            if created is not None and process_birth(kernel,self.handle)!=created:raise ValueError("启动进程已变化，未操作其他进程")
            if image is not None:
                kernel.QueryFullProcessImageNameW.argtypes=[w.HANDLE,w.DWORD,w.LPWSTR,ctypes.POINTER(w.DWORD)]
                value=ctypes.create_unicode_buffer(32768);length=w.DWORD(len(value))
                if not kernel.QueryFullProcessImageNameW(self.handle,0,value,ctypes.byref(length)) or Path(value.value).resolve()!=Path(image).resolve():
                    raise ValueError("启动进程路径不匹配，未操作其他进程")
        except Exception:self.close();raise
    def alive(self):return self.kernel.WaitForSingleObject(self.handle,0)==258
    def stop(self):
        if not self.alive():return
        if not self.kernel.TerminateProcess(self.handle,1):raise ctypes.WinError(ctypes.get_last_error())
        if self.kernel.WaitForSingleObject(self.handle,10000)!=0:raise ValueError("新版进程未退出，保留备份，未覆盖运行中的文件")
    def close(self):
        if self.handle is not None:self.kernel.CloseHandle(self.handle);self.handle=None


def launch_receiver():
    """The --upgrade branch of start.bat calls this without opening a console."""
    root=Path(__file__).resolve().parent
    started=os.environ.get("LANCMD_UPGRADE_STARTED","")
    nonce=os.environ.get("LANCMD_UPGRADE_NONCE","")
    if not started or not re.fullmatch("[0-9a-f]{32}",nonce):raise ValueError("缺少升级启动上下文")
    env=os.environ.copy();env["LANCMD_EXE"]=str(root/"LanCmdAgent.exe")
    env["PYTHONUTF8"]="1";env["PYTHONIOENCODING"]="utf-8"
    env.pop("LANCMD_UPGRADE_BATCH",None);env.pop("LANCMD_UPGRADE_STARTED",None)
    log=Path(started).with_suffix(".log")
    with log.open("ab") as output:
        process=subprocess.Popen([str(root/"python/pythonw.exe"),str(root/"lan_command_agent.py")],
            cwd=str(root),env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=output,creationflags=0x08000000,close_fds=True)
    try:
        kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        write_json(started,dict(pid=process.pid,created=process_birth(kernel,int(process._handle)),nonce=nonce))
    except Exception:
        if process.poll() is None:process.terminate();process.wait(10)
        raise


class ReceiverStart:
    def __init__(self,root,work,release):
        self.root=Path(root);self.work=Path(work);self.release=release
        self.nonce=uuid.uuid4().hex;self.receiver=None
        self.ready=self.work/("healthy-"+self.nonce+".json")
        self.started=self.work/("started-"+self.nonce+".json")
        env=os.environ.copy();env["LANCMD_EXE"]=str(self.root/"LanCmdAgent.exe")
        env["LANCMD_UPGRADE_READY"]=str(self.ready);env["LANCMD_UPGRADE_STARTED"]=str(self.started)
        env["LANCMD_UPGRADE_NONCE"]=self.nonce
        self.legacy=b"LANCMD_UPGRADE_BOOTSTRAP_V1" not in (self.root/"start.bat").read_bytes()
        self.output=(self.work/("start-"+self.nonce+".log")).open("ab")
        try:
            if self.legacy:
                # Old start.bat has pause and may invoke GUI error dialogs.
                # This path is only needed when recovering a pre-3.4.2 release.
                self.command=subprocess.Popen([str(self.root/"python/pythonw.exe"),str(self.root/"lan_command_agent.py")],
                    cwd=str(self.root),env=env,stdin=subprocess.DEVNULL,stdout=self.output,stderr=self.output,creationflags=0x08000000,close_fds=True)
                self.receiver=WindowsProcess(self.command.pid,image=self.root/"python/pythonw.exe")
            else:
                cmd=Path(os.environ.get("SystemRoot",r"C:\Windows"))/"System32/cmd.exe"
                env["LANCMD_UPGRADE_BATCH"]=str(self.root/"start.bat")
                # Expand the quoted path once via an environment variable. CALL
                # would expand '%' a second time and break legitimate folder names.
                command='"%s" /d /s /v:off /c ""%%LANCMD_UPGRADE_BATCH%%" --upgrade"'%cmd
                self.command=subprocess.Popen(command,executable=str(cmd),cwd=str(self.root),env=env,
                    stdin=subprocess.DEVNULL,stdout=self.output,stderr=self.output,creationflags=0x08000000,close_fds=True)
        except Exception:
            if hasattr(self,"command") and self.command.poll() is None:
                self.command.terminate();self.command.wait(5)
            self.output.close();raise

    def wait_ready(self,timeout=25):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if self.receiver is None and self.started.exists():
                info=json.loads(self.started.read_text(encoding="utf-8"))
                if info.get("nonce")!=self.nonce:raise ValueError("启动回执不匹配")
                self.receiver=WindowsProcess(info.get("pid"),info.get("created"),self.root/"python/pythonw.exe")
            failure=self.started.with_suffix(".error")
            if failure.exists():
                info=json.loads(failure.read_text(encoding="utf-8"))
                if info.get("nonce")==self.nonce:raise ValueError("start.bat 启动失败："+info.get("error","未知错误"))
            code=self.command.poll()
            if code is not None and (self.legacy or code!=0):raise ValueError("接收端启动命令失败，日志："+str(self.work))
            if self.receiver is not None:
                if not self.receiver.alive():raise ValueError("新版接收端启动后已退出")
                if self.ready.exists():
                    health=json.loads(self.ready.read_text(encoding="utf-8"))
                    expected=dict(version=self.release,pid=self.receiver.pid)
                    if not self.legacy:expected["nonce"]=self.nonce
                    if health==expected:
                        if not self.legacy:
                            try:code=self.command.wait(5)
                            except subprocess.TimeoutExpired:raise ValueError("start.bat 未退出，未确认 CMD 已关闭") from None
                            if code:raise ValueError("start.bat 执行失败")
                        return self.receiver.pid
            time.sleep(.1)
        raise ValueError("接收端启动超时，日志："+str(self.work))

    def stop(self):
        if self.receiver is not None:self.receiver.stop()
        if self.command.poll() is None and not self.legacy:
            self.command.terminate();self.command.wait(5)
    def close(self):
        if self.receiver is not None:self.receiver.close()
        self.output.close()


def helper(plan):
    """Wait for disconnection/old process exit, extract, replace, then run start.bat."""
    from ctypes import wintypes as w
    root=Path(plan["root"]);work=Path(plan["work"]);state=Path(plan["state"])
    def report(status,**details):write_json(state,dict(status=status,version=plan["version"],request_id=plan.get("request_id",""),backup=str(work/"backup"),**details))
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE
    kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD];kernel.CloseHandle.argtypes=[w.HANDLE]
    handle=kernel.OpenProcess(0x100000,False,plan["parent"])
    if not handle:raise ctypes.WinError(ctypes.get_last_error())
    (work/"ready").touch()
    try:
        if kernel.WaitForSingleObject(handle,60000)!=0:
            report("cancelled",error="原接收端未退出，升级已取消，原文件未替换");return
    finally:kernel.CloseHandle(handle)
    def replace(source,target):
        deadline=time.monotonic()+45
        while True:
            try:os.replace(source,target);return
            except PermissionError:
                if time.monotonic()>=deadline:raise
                time.sleep(.25)
    entries=None;process=None
    try:
        report("extracting")
        manifest=unpack(work/"package.zip",work/"stage",plan["previous"])
        if manifest["version"]!=plan["version"] or list(manifest["files"])!=plan["files"]:raise ValueError("升级计划与完整包不一致")
        if b"LANCMD_UPGRADE_BOOTSTRAP_V1" not in (work/"stage/start.bat").read_bytes():raise ValueError("升级包缺少自动退出的 start.bat，请使用完整新版 ZIP")
        report("installing")
        # Only this installation's panel is closed; unrelated CMD windows remain.
        user=ctypes.WinDLL("user32",use_last_error=True)
        user.FindWindowW.argtypes=[w.LPCWSTR,w.LPCWSTR];user.FindWindowW.restype=w.HWND
        hwnd=user.FindWindowW("LanCmdPanel",None)
        if hwnd:
            pid=w.DWORD();user.GetWindowThreadProcessId.argtypes=[w.HWND,ctypes.POINTER(w.DWORD)];user.GetWindowThreadProcessId(hwnd,ctypes.byref(pid))
            proc=kernel.OpenProcess(0x1000,False,pid.value)
            if proc:
                try:
                    buffer=ctypes.create_unicode_buffer(32768);length=w.DWORD(len(buffer))
                    kernel.QueryFullProcessImageNameW.argtypes=[w.HANDLE,w.DWORD,w.LPWSTR,ctypes.POINTER(w.DWORD)]
                    if kernel.QueryFullProcessImageNameW(proc,0,buffer,ctypes.byref(length)) and Path(buffer.value).resolve()==(root/"Start.exe").resolve():
                        user.PostMessageW.argtypes=[w.HWND,w.UINT,w.WPARAM,w.LPARAM];user.PostMessageW(hwnd,0x10,0,0)
                finally:kernel.CloseHandle(proc)
        entries=install(root,work/"stage",work/"backup",plan["files"],replace)
        report("starting")
        process=ReceiverStart(root,work,plan["version"])
        pid=process.wait_ready()
        report("completed",pid=pid,start_via="start.bat",cmd_closed=True)
        shutil.rmtree(work/"stage",ignore_errors=True)
    except Exception as exc:
        try:
            if process is not None:process.stop();process.close();process=None
            if isinstance(exc,RecoveryError):raise exc
            if entries is not None:rollback(root,work/"backup",entries)
            process=ReceiverStart(root,work,plan["previous"])
            pid=process.wait_ready()
            report("rolled_back",error=str(exc)[:1600],pid=pid)
        except Exception as recovery:report("failed",error=str(exc)[:1200]+"；恢复失败："+str(recovery)[:1200])
    finally:
        if process is not None:process.close()

if __name__=="__main__":
    if sys.argv[1:]==["--launch-receiver"]:
        try:launch_receiver()
        except Exception as exc:
            try:
                started=os.environ.get("LANCMD_UPGRADE_STARTED")
                if started:write_json(Path(started).with_suffix(".error"),dict(nonce=os.environ.get("LANCMD_UPGRADE_NONCE"),error=str(exc)[:1600]))
            except OSError:pass
            sys.exit(2)
        sys.exit(0)
    if len(sys.argv)==3 and sys.argv[1]=="--apply":
        plan=json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        try:helper(plan)
        except Exception as exc:write_json(plan["state"],dict(status="cancelled",error=str(exc)[:1600],version=plan["version"],request_id=plan.get("request_id","")))

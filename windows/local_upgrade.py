"""Local, explicitly selected portable ZIP upgrades. This module never uses a network."""
from contextlib import contextmanager
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
REQUIRED={"LanCmdAgent.exe","LanCmdCtl.exe","Start.exe","lan_command_agent.py","local_upgrade.py","python/pythonw.exe","python/python311.dll"}


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
        if path.is_dir() or name==MANIFEST or name.split("/")[0] in ("upgrade","__pycache__"):continue
        relative(name);info=ordinary(path)
        files[name]=dict(size=info.st_size,sha256=digest(path))
    if not REQUIRED.issubset(files):raise ValueError("Windows 包缺少必要运行文件")
    data=dict(product="LanCmd-Windows",format=1,arch="amd64",version=release,source=source,files=files)
    write_json(root/MANIFEST,data);return data


def unpack(package,destination,current):
    """Validate all entries and hashes before touching the installed directory."""
    package=Path(package);info=ordinary(package)
    if info.st_size>LIMIT:raise ValueError("升级 ZIP 不得超过 512 MiB")
    destination=Path(destination)
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
        destination.mkdir(parents=True,exist_ok=False)
        try:
            for name,record in files.items():
                target=destination/str(relative(name));target.parent.mkdir(parents=True,exist_ok=True)
                sha=hashlib.sha256();size=0
                with archive.open(by_name[name.casefold()][1]) as source,target.open("xb") as out:
                    for block in iter(lambda:source.read(1024*1024),b""):
                        size+=len(block)
                        if size>record["size"]:raise ValueError("升级内容超过清单大小")
                        sha.update(block);out.write(block)
                if size!=record["size"] or sha.hexdigest()!=record["sha256"]:raise ValueError("升级文件校验失败："+name)
            write_json(destination/MANIFEST,manifest)
        except Exception:shutil.rmtree(destination,ignore_errors=True);raise
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
    except Exception:
        rollback(root,backup,entries);raise
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
        self.lock=threading.RLock();self.active=0;self.draining=False

    @contextmanager
    def operation(self,command):
        with self.lock:
            if self.draining and command not in ("upgrade","capabilities"):raise ValueError("接收端正在升级，请稍后重连")
            self.active+=1
        try:yield
        finally:
            with self.lock:self.active-=1

    def request(self,q,error):
        try:
            if os.name!="nt":raise ValueError("本地升级仅支持 Windows 便携完整包")
            with self.lock:
                directory=self.root/"upgrade";directory.mkdir(exist_ok=True);ordinary(directory,True)
                if q.get("action","list")=="list":
                    items=[]
                    for path in list(directory.iterdir())[:200]:
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
                name=q.get("name")
                if not isinstance(name,str) or Path(name).name!=name or "/" in name or "\\" in name or not name.lower().endswith(".zip"):raise ValueError("请选择 upgrade 中的 ZIP")
                if getattr(sys,"frozen",False) or not (self.root/"python/pythonw.exe").is_file():raise ValueError("请先使用包含 python 文件夹的完整 Windows 便携包")
                if self.active>1:raise ValueError("其他操作正在进行，请停止共享并稍后再试")
                reason=self.busy()
                if reason:raise ValueError(reason)
                self.draining=True
                try:
                    package=directory/name
                    if not re.fullmatch("[0-9a-f]{64}",str(q.get("sha256",""))) or digest(package)!=q["sha256"]:raise ValueError("ZIP 在选择后已变化，请刷新升级列表")
                    self.state.mkdir(parents=True,exist_ok=True)
                    work=Path(tempfile.mkdtemp(prefix="lancmd-upgrade-",dir=self.state))
                    try:
                        manifest=unpack(package,work/"stage",self.release)
                        self.prepare()
                        if shutil.disk_usage(self.root).free<sum(v["size"] for v in manifest["files"].values())+64*1024*1024:raise ValueError("安装盘空间不足以保留回退备份")
                        runtime=work/"runtime";shutil.copytree(self.root/"python",runtime,symlinks=False)
                        shutil.copyfile(__file__,runtime/"local_upgrade.py")
                        plan=dict(root=str(self.root),work=str(work),parent=os.getpid(),state=str(self.state/"upgrade-status.json"),version=manifest["version"],files=list(manifest["files"]))
                        write_json(work/"plan.json",plan)
                        process=subprocess.Popen([str(runtime/"pythonw.exe"),str(runtime/"local_upgrade.py"),"--apply",str(work/"plan.json")],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=0x08000000,close_fds=True,cwd=str(runtime))
                        deadline=time.monotonic()+10
                        while not (work/"ready").exists():
                            if process.poll() is not None or time.monotonic()>deadline:raise ValueError("升级恢复进程未就绪，接收端保持运行")
                            time.sleep(.05)
                        write_json(self.state/"upgrade-status.json",dict(status="prepared",version=manifest["version"],backup=str(work/"backup")))
                    except Exception:shutil.rmtree(work,ignore_errors=True);raise
                    threading.Timer(1.5,self.stop).start()
                    return dict(restarting=True,version=manifest["version"],message="已校验本地 ZIP；正在关闭接收端并升级，请等待重连")
                except Exception:self.draining=False;raise
        except (OSError,ValueError,TypeError,zipfile.BadZipFile) as exc:raise error(str(exc))


def helper(plan):
    """Runs from a private runtime outside the installation, with no visible console."""
    from ctypes import wintypes as w
    root=Path(plan["root"]);work=Path(plan["work"]);state=Path(plan["state"])
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE
    kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD];kernel.CloseHandle.argtypes=[w.HANDLE]
    handle=kernel.OpenProcess(0x100000,False,plan["parent"])
    if not handle:raise ctypes.WinError(ctypes.get_last_error())
    (work/"ready").touch()
    try:
        # Timeout cancels instead of stopping any unrelated/reused PID.
        if kernel.WaitForSingleObject(handle,30000)!=0:raise ValueError("原接收端未退出，升级已取消")
    finally:kernel.CloseHandle(handle)
    def report(status,**details):write_json(state,dict(status=status,version=plan["version"],backup=str(work/"backup"),**details))
    def launch():
        env=os.environ.copy();env["LANCMD_EXE"]=str(root/"LanCmdAgent.exe");env["LANCMD_UPGRADE_READY"]=str(work/"healthy.json")
        return subprocess.Popen([str(root/"python/pythonw.exe"),str(root/"lan_command_agent.py")],cwd=str(root),env=env,creationflags=0x08000000,close_fds=True)
    def replace(source,target):
        deadline=time.monotonic()+45
        while True:
            try:os.replace(source,target);return
            except PermissionError:
                if time.monotonic()>=deadline:raise
                time.sleep(.25)
    entries=None
    try:
        report("installing")
        # Close only this installation's visible panel so its EXE can be replaced.
        from ctypes import wintypes
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
        process=launch()
        # The receiver writes an atomic readiness marker after opening its TCP port.
        ready=work/"healthy.json"
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            if ready.exists():
                health=json.loads(ready.read_text(encoding="utf-8"))
                if health==dict(version=plan["version"],pid=process.pid):break
            if process.poll() is not None:raise ValueError("新版接收端启动失败")
            time.sleep(.2)
        else:
            process.terminate();process.wait(10);raise ValueError("新版接收端启动超时")
        ready.unlink(missing_ok=True);report("completed")
        shutil.rmtree(work/"stage",ignore_errors=True)
    except Exception as exc:
        try:
            if entries is not None:rollback(root,work/"backup",entries)
            launch();report("rolled_back",error=str(exc)[:1600])
        except Exception as recovery:report("failed",error=str(exc)[:1200]+"；恢复失败："+str(recovery)[:1200])


if __name__=="__main__":
    if len(sys.argv)==3 and sys.argv[1]=="--apply":
        plan=json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        try:helper(plan)
        except Exception as exc:write_json(plan["state"],dict(status="cancelled",error=str(exc)[:1600],version=plan["version"]))

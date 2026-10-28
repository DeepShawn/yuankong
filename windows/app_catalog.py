"""Read-only desktop application discovery; never execute uninstall commands or icons."""
import hashlib
import os
from pathlib import Path
import re
import stat
import threading
import time
from itertools import islice


def executable(value):
    if not isinstance(value,str) or not value or "\0" in value:return None
    value=os.path.expandvars(value.strip())
    match=re.fullmatch(r'"([^"\r\n]+)"(?:,\s*-?\d+)?',value)
    if match:value=match.group(1)
    else:value=re.sub(r',\s*-?\d+$','',value)
    path=Path(value)
    if not path.is_absolute() or str(path).startswith("\\\\") or path.suffix.lower()!=".exe":return None
    try:
        info=path.lstat()
        if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or getattr(info,"st_file_attributes",0)&0x400:return None
        return path.resolve(strict=True)
    except (OSError,ValueError):return None


class NativeCatalog:
    def records(self):
        import winreg as r
        records=[]
        def value(key,name):
            try:
                result,kind=r.QueryValueEx(key,name)
                return result if kind in (r.REG_SZ,r.REG_EXPAND_SZ) and isinstance(result,str) else ""
            except OSError:return ""
        for hive in (r.HKEY_CURRENT_USER,r.HKEY_LOCAL_MACHINE):
            for view in (r.KEY_WOW64_64KEY,r.KEY_WOW64_32KEY):
                for kind,root in (("installed",r"Software\Microsoft\Windows\CurrentVersion\Uninstall"),
                                  ("app_paths",r"Software\Microsoft\Windows\CurrentVersion\App Paths")):
                    try:
                        with r.OpenKey(hive,root,0,r.KEY_READ|view) as parent:
                            for index in range(min(r.QueryInfoKey(parent)[0],2000)):
                                try:
                                    name=r.EnumKey(parent,index)
                                    with r.OpenKey(parent,name,0,r.KEY_READ|view) as key:
                                        if kind=="app_paths":
                                            records.append(dict(name=Path(name).stem,path=value(key,""),source=kind))
                                        else:
                                            title=value(key,"DisplayName")
                                            if not title:continue
                                            paths=[]
                                            icon=executable(value(key,"DisplayIcon"))
                                            if icon:paths.append(icon)
                                            location=value(key,"InstallLocation").strip('"')
                                            if location and Path(location).is_absolute() and not location.startswith("\\\\"):
                                                try:
                                                    directory=Path(location);info=directory.lstat()
                                                    if stat.S_ISDIR(info.st_mode) and not getattr(info,"st_file_attributes",0)&0x400:
                                                        for entry in islice(directory.iterdir(),100):
                                                            candidate=executable(str(entry))
                                                            if candidate and not re.search(r"unins|uninstall|setup|updat|crash",candidate.stem,re.I):paths.append(candidate)
                                                except OSError:pass
                                            base=dict(name=title,version=value(key,"DisplayVersion"),publisher=value(key,"Publisher"),source=kind)
                                            if paths:
                                                for path in dict.fromkeys(paths):records.append(dict(base,path=str(path)))
                                            else:records.append(dict(base,path=""))
                                except OSError:continue
                    except OSError:continue
        return records


class AppCatalog:
    def __init__(self,lock_target,processes=None,backend=None,protected=()):
        self.lock_target=lock_target;self.processes=processes;self.backend=backend
        self.protected=protected
        self.lock=threading.RLock();self.cached=None;self.updated=0

    def request(self,q,error):
        try:
            if q.get("action","list")!="list":raise ValueError("无效的应用列表操作")
            force=q.get("refresh",False)
            if type(force)is not bool:raise ValueError("刷新参数无效")
            with self.lock:
                if self.cached is not None and not force and time.monotonic()-self.updated<30:return self.cached
                if self.backend is None:
                    if os.name!="nt":raise ValueError("应用列表仅支持 Windows")
                    self.backend=NativeCatalog()
                if self.processes is None:
                    from task_manager import NativeProcesses
                    self.processes=NativeProcesses(self.protected)
                running=self.processes.list();by_path={}
                for process in running:
                    path=executable(process.get("path",""))
                    if path:by_path.setdefault(os.path.normcase(str(path)).casefold(),[]).append(process)
                records=self.backend.records()+[dict(name=p.get("name",""),path=p.get("path",""),source="running") for p in running if p.get("path")]
                items={}
                for record in records:
                    name=str(record.get("name","")).strip()[:200]
                    if not name:continue
                    path=executable(record.get("path",""))
                    key=str(path).casefold() if path else "metadata:"+name.casefold()+str(record.get("publisher","")).casefold()
                    if key in items:
                        for field in ("publisher","version"):
                            if record.get(field):items[key][field]=str(record[field])[:200]
                        continue
                    reason="未找到可验证的桌面 EXE；系统组件、商店应用或卸载条目可能不支持此操作"
                    can_lock=False
                    if path:
                        try:self.lock_target(str(path));can_lock=True;reason=""
                        except (OSError,ValueError) as exc:reason=str(exc)
                    processes=by_path.get(os.path.normcase(str(path)).casefold(),[]) if path else []
                    items[key]=dict(id=hashlib.sha256(key.encode()).hexdigest()[:32],name=name,path=str(path) if path else "",
                        publisher=str(record.get("publisher",""))[:200],version=str(record.get("version",""))[:200],
                        source=record.get("source","installed"),can_lock=can_lock,reason=reason,
                        processes=[dict(pid=p["pid"],created=p["created"],can_freeze=bool(p.get("can_terminate"))) for p in processes],
                        running=len(processes))
                    if len(items)>=2000:break
                self.cached=dict(items=sorted(items.values(),key=lambda item:item["name"].casefold()),updated=time.time())
                self.updated=time.monotonic()
                return self.cached
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))

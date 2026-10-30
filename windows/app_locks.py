"""Reversible execute denial for explicitly selected, non-system EXE files."""
import json
import ctypes
import os
from pathlib import Path
import subprocess
import threading
import uuid
import stat


def same_dacl(left,right):
    """Ignore only Windows' AUTO_INHERITED bookkeeping flag in DACL SDDL.

    Protection, inheritance requests, ACE order, masks, identities and inherited
    ACE flags still have to match exactly.
    """
    def normalized(value):
        if not isinstance(value,str) or not value.startswith("D:"):return value
        flags,separator,aces=value[2:].partition("(")
        return "D:"+flags.replace("AI","")+separator+aces
    return normalized(left)==normalized(right)


class _FileSecurityApi:
    """Unicode Win32 ACL access; no shell or PowerShell console encoding."""
    def __init__(self):
        from ctypes import wintypes as w
        self.w=w
        self.kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        self.advapi=ctypes.WinDLL("advapi32",use_last_error=True)
        k,a=self.kernel,self.advapi
        k.CreateFileW.argtypes=[w.LPCWSTR,w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD,w.DWORD,w.HANDLE]
        k.CreateFileW.restype=w.HANDLE
        k.CloseHandle.argtypes=[w.HANDLE]
        k.GetCurrentProcess.argtypes=[];k.GetCurrentProcess.restype=w.HANDLE
        k.LocalFree.argtypes=[ctypes.c_void_p];k.LocalFree.restype=ctypes.c_void_p
        a.GetSecurityInfo.argtypes=[w.HANDLE,w.DWORD,w.DWORD,*([ctypes.POINTER(ctypes.c_void_p)]*5)]
        a.GetSecurityInfo.restype=w.DWORD
        a.SetSecurityInfo.argtypes=[w.HANDLE,w.DWORD,w.DWORD,*([ctypes.c_void_p]*4)]
        a.SetSecurityInfo.restype=w.DWORD
        a.ConvertSecurityDescriptorToStringSecurityDescriptorW.argtypes=[ctypes.c_void_p,w.DWORD,w.DWORD,ctypes.POINTER(ctypes.c_void_p),ctypes.POINTER(w.ULONG)]
        a.ConvertSecurityDescriptorToStringSecurityDescriptorW.restype=w.BOOL
        a.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes=[w.LPCWSTR,w.DWORD,ctypes.POINTER(ctypes.c_void_p),ctypes.POINTER(w.ULONG)]
        a.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype=w.BOOL
        a.GetSecurityDescriptorDacl.argtypes=[ctypes.c_void_p,ctypes.POINTER(w.BOOL),ctypes.POINTER(ctypes.c_void_p),ctypes.POINTER(w.BOOL)]
        a.GetSecurityDescriptorDacl.restype=w.BOOL
        a.GetSecurityDescriptorControl.argtypes=[ctypes.c_void_p,ctypes.POINTER(w.WORD),ctypes.POINTER(w.DWORD)]
        a.GetSecurityDescriptorControl.restype=w.BOOL
        a.OpenProcessToken.argtypes=[w.HANDLE,w.DWORD,ctypes.POINTER(w.HANDLE)];a.OpenProcessToken.restype=w.BOOL
        a.GetTokenInformation.argtypes=[w.HANDLE,w.DWORD,ctypes.c_void_p,w.DWORD,ctypes.POINTER(w.DWORD)]
        a.GetTokenInformation.restype=w.BOOL
        a.ConvertSidToStringSidW.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]
        a.ConvertSidToStringSidW.restype=w.BOOL
        a.InitializeSecurityDescriptor.argtypes=[ctypes.c_void_p,w.DWORD];a.InitializeSecurityDescriptor.restype=w.BOOL
        a.SetSecurityDescriptorDacl.argtypes=[ctypes.c_void_p,w.BOOL,ctypes.c_void_p,w.BOOL]
        a.SetSecurityDescriptorDacl.restype=w.BOOL
        class Trustee(ctypes.Structure):
            _fields_=[("multiple",ctypes.c_void_p),("operation",ctypes.c_int),("form",ctypes.c_int),("type",ctypes.c_int),("name",ctypes.c_void_p)]
        class ExplicitAccess(ctypes.Structure):
            _fields_=[("permissions",w.DWORD),("mode",ctypes.c_int),("inheritance",w.DWORD),("trustee",Trustee)]
        class Descriptor(ctypes.Structure):
            _fields_=[("revision",ctypes.c_ubyte),("reserved",ctypes.c_ubyte),("control",w.WORD),
                      ("owner",ctypes.c_void_p),("group",ctypes.c_void_p),("sacl",ctypes.c_void_p),("dacl",ctypes.c_void_p)]
        self.ExplicitAccess=ExplicitAccess;self.Descriptor=Descriptor
        a.SetEntriesInAclW.argtypes=[w.ULONG,ctypes.POINTER(ExplicitAccess),ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]
        a.SetEntriesInAclW.restype=w.DWORD
        a.GetAce.argtypes=[ctypes.c_void_p,w.DWORD,ctypes.POINTER(ctypes.c_void_p)];a.GetAce.restype=w.BOOL
        a.InitializeAcl.argtypes=[ctypes.c_void_p,w.DWORD,w.DWORD];a.InitializeAcl.restype=w.BOOL
        a.AddAce.argtypes=[ctypes.c_void_p,w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD];a.AddAce.restype=w.BOOL

    def open(self,path,write=False,identity=None):
        import msvcrt
        desired=0x20080|(0x40000 if write else 0) # READ_CONTROL | FILE_READ_ATTRIBUTES | WRITE_DAC
        handle=self.kernel.CreateFileW(str(path),desired,3 if write else 7,None,3,0x200000,None)
        if handle==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
        fd=None
        try:
            fd=msvcrt.open_osfhandle(int(handle),os.O_RDONLY)
            info=os.fstat(fd)
            if getattr(info,"st_file_attributes",0)&0x400 or not stat.S_ISREG(info.st_mode):
                raise ValueError("应用锁只支持普通文件，不支持链接或重解析点")
            if identity is not None and ([info.st_dev,info.st_ino]!=identity or getattr(info,"st_nlink",1)>1):
                raise ValueError("文件身份发生变化或包含硬链接，未修改执行权限")
            return handle,fd
        except Exception:
            if fd is None:self.kernel.CloseHandle(handle)
            else:os.close(fd)
            raise

    def descriptor(self,handle):
        value=ctypes.c_void_p()
        result=self.advapi.GetSecurityInfo(handle,1,4,None,None,None,None,ctypes.byref(value))
        if result:raise ctypes.WinError(result)
        return value

    def sddl(self,value):
        text=ctypes.c_void_p();length=self.w.ULONG()
        if not self.advapi.ConvertSecurityDescriptorToStringSecurityDescriptorW(value,1,4,ctypes.byref(text),ctypes.byref(length)):
            raise ctypes.WinError(ctypes.get_last_error())
        try:return ctypes.wstring_at(text)
        finally:self.kernel.LocalFree(text)

    def control(self,value):
        flags=self.w.WORD();revision=self.w.DWORD()
        if not self.advapi.GetSecurityDescriptorControl(value,ctypes.byref(flags),ctypes.byref(revision)):
            raise ctypes.WinError(ctypes.get_last_error())
        return flags.value

    def dacl(self,value):
        present,defaulted=self.w.BOOL(),self.w.BOOL();dacl=ctypes.c_void_p()
        if not self.advapi.GetSecurityDescriptorDacl(value,ctypes.byref(present),ctypes.byref(dacl),ctypes.byref(defaulted)):
            raise ctypes.WinError(ctypes.get_last_error())
        if not present.value or not dacl.value:raise ValueError("不支持空 DACL，未修改权限")
        return dacl,defaulted

    def _explicit_acl(self,dacl):
        """Keep raw explicit ACEs; Windows will inherit the parent ACL once.

        Passing inherited ACEs back to SetSecurityInfo can turn them into
        explicit copies and add a second inherited set. Raw copying also keeps
        object/callback ACE formats that a hand-written SDDL parser would lose.
        """
        class Header(ctypes.Structure):
            _fields_=[("revision",ctypes.c_ubyte),("reserved",ctypes.c_ubyte),
                      ("size",self.w.WORD),("count",self.w.WORD),("reserved2",self.w.WORD)]
        class AceHeader(ctypes.Structure):
            _fields_=[("type",ctypes.c_ubyte),("flags",ctypes.c_ubyte),("size",self.w.WORD)]
        header=ctypes.cast(dacl,ctypes.POINTER(Header)).contents
        entries=[]
        for index in range(header.count):
            pointer=ctypes.c_void_p()
            if not self.advapi.GetAce(dacl,index,ctypes.byref(pointer)):
                raise ctypes.WinError(ctypes.get_last_error())
            ace=ctypes.cast(pointer,ctypes.POINTER(AceHeader)).contents
            if not ace.flags&0x10:entries.append(ctypes.string_at(pointer,ace.size))
        size=ctypes.sizeof(Header)+sum(len(v) for v in entries)
        buffer=ctypes.create_string_buffer(size)
        if not self.advapi.InitializeAcl(buffer,size,header.revision):
            raise ctypes.WinError(ctypes.get_last_error())
        for entry in entries:
            raw=ctypes.create_string_buffer(entry)
            if not self.advapi.AddAce(buffer,header.revision,0xffffffff,raw,len(entry)):
                raise ctypes.WinError(ctypes.get_last_error())
        return buffer

    def set_dacl(self,handle,target):
        """Set only the DACL, preserving native inheritance semantics."""
        desired=ctypes.c_void_p()
        try:
            if not self.advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(target,1,ctypes.byref(desired),None):
                raise ctypes.WinError(ctypes.get_last_error())
            dacl,_=self.dacl(desired)
            protected=bool(self.control(desired)&0x1000)
            explicit=None
            if not protected:
                explicit=self._explicit_acl(dacl)
                dacl=ctypes.cast(explicit,ctypes.c_void_p)
            information=4|(0x80000000 if protected else 0x20000000)
            result=self.advapi.SetSecurityInfo(handle,1,information,None,None,dacl,None)
            if result:raise ctypes.WinError(result)
            after=self.descriptor(handle)
            try:
                actual=self.sddl(after)
                if not same_dacl(actual,target):
                    raise ValueError("文件权限写入后未确认与原目标一致，请保留恢复备份并检查权限")
                return {"sddl":actual}
            finally:self.kernel.LocalFree(after)
        finally:
            if desired.value:self.kernel.LocalFree(desired)

    def plan(self,path,permissions=0x20,everyone=False):
        handle,fd=self.open(path)
        descriptor=ctypes.c_void_p();token=self.w.HANDLE();new_acl=ctypes.c_void_p();sid_text=ctypes.c_void_p()
        try:
            descriptor=self.descriptor(handle)
            dacl,defaulted=self.dacl(descriptor)
            original=self.sddl(descriptor)
            if not self.advapi.OpenProcessToken(self.kernel.GetCurrentProcess(),8,ctypes.byref(token)):
                raise ctypes.WinError(ctypes.get_last_error())
            length=self.w.DWORD()
            self.advapi.GetTokenInformation(token,1,None,0,ctypes.byref(length))
            buffer=ctypes.create_string_buffer(length.value)
            if not self.advapi.GetTokenInformation(token,1,buffer,length.value,ctypes.byref(length)):
                raise ctypes.WinError(ctypes.get_last_error())
            sid=ctypes.cast(buffer,ctypes.POINTER(ctypes.c_void_p)).contents.value
            if not self.advapi.ConvertSidToStringSidW(sid,ctypes.byref(sid_text)):
                raise ctypes.WinError(ctypes.get_last_error())
            if everyone:
                # The file lock denies data access, while keeping WRITE_DAC for
                # recovery. Use World, not just the elevated receiver account.
                world=ctypes.create_string_buffer(68);world_size=self.w.DWORD(len(world))
                self.advapi.CreateWellKnownSid.argtypes=[ctypes.c_int,ctypes.c_void_p,ctypes.c_void_p,ctypes.POINTER(self.w.DWORD)]
                self.advapi.CreateWellKnownSid.restype=self.w.BOOL
                if not self.advapi.CreateWellKnownSid(1,None,world,ctypes.byref(world_size)):
                    raise ctypes.WinError(ctypes.get_last_error())
                sid=ctypes.addressof(world)
            rule=self.ExplicitAccess();rule.permissions=permissions;rule.mode=3
            rule.trustee.form=0;rule.trustee.type=1;rule.trustee.name=sid
            result=self.advapi.SetEntriesInAclW(1,ctypes.byref(rule),dacl,ctypes.byref(new_acl))
            if result:raise ctypes.WinError(result)
            desired=self.Descriptor()
            if not self.advapi.InitializeSecurityDescriptor(ctypes.byref(desired),1):
                raise ctypes.WinError(ctypes.get_last_error())
            if not self.advapi.SetSecurityDescriptorDacl(ctypes.byref(desired),True,new_acl,defaulted):
                raise ctypes.WinError(ctypes.get_last_error())
            # Preserve the source's inheritance/protection state; owner/group and
            # SACL never participate in the plan or the subsequent write.
            desired.control|=self.control(descriptor)&0x1500
            return dict(sid="S-1-1-0" if everyone else ctypes.wstring_at(sid_text),original=original,applied=self.sddl(ctypes.byref(desired)))
        finally:
            if sid_text.value:self.kernel.LocalFree(sid_text)
            if new_acl.value:self.kernel.LocalFree(new_acl)
            if token.value:self.kernel.CloseHandle(token)
            if descriptor.value:self.kernel.LocalFree(descriptor)
            os.close(fd)

    def apply(self,path,expected,target,identity):
        # No FILE_SHARE_DELETE: compare and modify one bound file object.
        handle,fd=self.open(path,True,identity)
        current=ctypes.c_void_p()
        try:
            current=self.descriptor(handle)
            if not same_dacl(self.sddl(current),expected):raise ValueError("文件权限已被外部修改，未覆盖")
            return self.set_dacl(handle,target)
        finally:
            if current.value:self.kernel.LocalFree(current)
            os.close(fd)


def snapshot_file_dacl(path,require_write=False):
    """Read a file DACL and identity; optionally preflight WRITE_DAC access."""
    api=_FileSecurityApi();handle,fd=api.open(path,write=require_write)
    descriptor=ctypes.c_void_p()
    try:
        info=os.fstat(fd);descriptor=api.descriptor(handle)
        return {"sddl":api.sddl(descriptor),"identity":[info.st_dev,info.st_ino]}
    finally:
        if descriptor.value:api.kernel.LocalFree(descriptor)
        os.close(fd)


def restore_file_dacl(path,sddl,identity):
    """Restore DACL on the expected current file; never change owner or SACL.

    `identity` belongs to the file currently at `path`, for example the new file
    published by ReplaceFile. A replaced path, reparse point or hardlink alias is
    refused before changing permissions.
    """
    api=_FileSecurityApi();handle,fd=api.open(path,write=True,identity=identity)
    try:return api.set_dacl(handle,sddl)
    finally:os.close(fd)


class FilePermissions:
    def call(self,**q):
        api=_FileSecurityApi()
        action=q.get("action")
        if action=="plan":return api.plan(q["path"])
        if action=="apply":return api.apply(q["path"],q["expected"],q["target"],q["identity"])
        if action=="inspect":
            items=[]
            for path in q["paths"]:
                try:
                    handle,fd=api.open(path)
                    try:
                        descriptor=api.descriptor(handle)
                        try:items.append(dict(path=path,sddl=api.sddl(descriptor)))
                        finally:api.kernel.LocalFree(descriptor)
                    finally:os.close(fd)
                except (OSError,ValueError) as exc:items.append(dict(path=path,error=str(exc)))
            return items
        raise ValueError("无效的文件权限操作")


class AppLocks:
    def __init__(self, database, protected=(), backend=None):
        self.path = Path(database)
        self.protected = [Path(p).resolve() for p in protected]
        self.backend = backend or FilePermissions()
        self.lock = threading.RLock()

    def _load(self):
        if not self.path.exists(): return {}
        data=json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(data,dict): raise ValueError("应用锁备份损坏，请保留文件并检查")
        for rid,entry in data.items():
            if (not isinstance(rid,str) or len(rid)!=32 or any(c not in "0123456789abcdef" for c in rid) or not isinstance(entry,dict) or
                not isinstance(entry.get("path"),str) or not Path(entry["path"]).is_absolute() or "\0" in entry["path"] or
                not isinstance(entry.get("identity"),list) or len(entry["identity"])!=2 or
                any(type(v)is not int or v<0 for v in entry["identity"]) or
                any(not isinstance(entry.get(k),str) or not entry[k].startswith("D:") for k in ("original","applied"))):
                raise ValueError("应用锁备份损坏，请保留文件并检查")
        return data

    def _save(self, data):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix(".tmp")
        with temp.open("w",encoding="utf-8") as stream:
            json.dump(data,stream,ensure_ascii=False);stream.flush();os.fsync(stream.fileno())
        os.replace(temp,self.path)

    def _archive(self,rid,entry):
        directory=self.path.parent/"app-lock-backups"
        directory.mkdir(parents=True,exist_ok=True)
        target=directory/(rid+".json")
        payload={"id":rid,"entry":entry}
        try:
            with target.open("x",encoding="utf-8") as stream:
                json.dump(payload,stream,ensure_ascii=False);stream.flush();os.fsync(stream.fileno())
        except FileExistsError:
            if json.loads(target.read_text(encoding="utf-8"))!=payload:
                raise ValueError("恢复归档已存在但内容不一致，未移除原记录")
        return str(target)

    def _target(self,value):
        if not isinstance(value,str) or not value or "\0" in value: raise ValueError("请选择 EXE 文件")
        selected=Path(value).absolute()
        first=selected.lstat()
        if stat.S_ISLNK(first.st_mode) or getattr(first,"st_file_attributes",0)&0x400:
            raise ValueError("应用锁不支持链接或重解析点")
        path=selected.resolve(strict=True)
        if not path.is_file() or path.suffix.lower()!=".exe": raise ValueError("应用锁仅支持普通 EXE 文件")
        if os.name=="nt" and path.stat().st_file_attributes & 0x400: raise ValueError("不支持重解析点")
        roots=list(self.protected)
        if os.environ.get("WINDIR"):roots.append(Path(os.environ["WINDIR"]).resolve())
        if any(path==root or root in path.parents for root in roots): raise ValueError("不能锁定 Windows 系统目录或 LanCmd 自身组件")
        info=path.stat()
        if getattr(info,"st_nlink",1)>1:
            raise ValueError("应用锁不支持包含多个硬链接的 EXE")
        if not info.st_ino:
            raise ValueError("文件系统未提供稳定的文件身份，不能安全设置应用锁")
        return path,[info.st_dev,info.st_ino]

    def _identity(self,path,expected):
        resolved,identity=self._target(str(path))
        if str(resolved)!=str(path) or identity!=expected:
            raise ValueError("所选文件已被替换或移动，未修改执行权限")

    def _states(self,data):
        paths=[v["path"] for v in data.values()]
        result=self.backend.call(action="inspect",paths=paths) if paths else []
        inspections={v["path"]:v for v in result}
        out=[]
        for rid,entry in data.items():
            item={k:v for k,v in entry.items() if k not in ("original","applied")}
            item["id"]=rid; current=inspections.get(entry["path"],{})
            try:
                info=Path(entry["path"]).stat()
                if [info.st_dev,info.st_ino]!=entry["identity"]: item["status"]="replaced"
                elif same_dacl(current.get("sddl"),entry["applied"]): item["status"]="locked"
                elif same_dacl(current.get("sddl"),entry["original"]): item["status"]="unlocked"
                else: item["status"]="changed";item["error"]=current.get("error","权限已被外部修改，未覆盖")
            except FileNotFoundError:item["status"]="missing"
            except OSError as exc:item.update(status="changed",error=str(exc))
            out.append(item)
        return out

    def request(self,q,error):
        try:
            if os.name!="nt" and isinstance(self.backend,FilePermissions): raise ValueError("应用锁仅支持 Windows")
            with self.lock:
                data=self._load(); action=q.get("action","list")
                if action=="list": return {"items":self._states(data),"backup":str(self.path)}
                if action=="lock":
                    path,identity=self._target(q.get("path"))
                    if len(data)>=50: raise ValueError("最多保存 50 条应用锁，请先移除已解锁记录")
                    if any(v["path"].casefold()==str(path).casefold() for v in data.values()): raise ValueError("此文件已有记录，请先解锁并移除旧记录")
                    plan=self.backend.call(action="plan",path=str(path))
                    self._identity(path,identity)
                    if same_dacl(plan["original"],plan["applied"]):raise ValueError("原权限已限制执行，未新增应用锁")
                    rid=uuid.uuid4().hex
                    entry=dict(path=str(path),identity=identity,**plan)
                    data[rid]=entry
                    self._save(data)  # Recovery data must reach disk before ACL modification.
                    self._identity(path,identity)
                    self.backend.call(action="apply",path=str(path),expected=plan["original"],target=plan["applied"],identity=identity)
                    states=self._states(data)
                    if next(v for v in states if v["id"]==rid)["status"]!="locked":
                        raise ValueError("未确认应用锁状态，请刷新检查；恢复备份已保留")
                    return {"items":states}
                if action not in ("unlock","forget"): raise ValueError("无效的应用锁操作")
                rid=str(q.get("lock_id","")); entry=data.get(rid)
                if not entry: raise ValueError("应用锁记录不存在")
                current=next(v for v in self._states(data) if v["id"]==rid)
                if action=="forget":
                    if current["status"] not in ("unlocked","missing","replaced"): raise ValueError("请先解锁，权限冲突时不能丢弃恢复备份")
                    archive=self._archive(rid,entry)
                    data.pop(rid);self._save(data);return {"removed":True,"archive":archive}
                if current["status"]=="unlocked": return {"unlocked":True}
                if current["status"]!="locked": raise ValueError("文件已变化、缺失或权限被外部修改，未覆盖；请保留备份")
                self._identity(entry["path"],entry["identity"])
                self.backend.call(action="apply",path=entry["path"],expected=entry["applied"],target=entry["original"],identity=entry["identity"])
                states=self._states(data)
                if next(v for v in states if v["id"]==rid)["status"]!="unlocked":
                    raise ValueError("未确认原权限已恢复，请刷新检查；恢复备份已保留")
                return {"unlocked":True,"items":states}
        except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as exc:
            raise error(str(exc))

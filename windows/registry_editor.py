"""Native registry browsing and checked, backed-up value mutations."""
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

ROOTS=("HKCU","HKLM","HKCR","HKU","HKCC")
TYPES={"REG_SZ":1,"REG_EXPAND_SZ":2,"REG_BINARY":3,"REG_DWORD":4,"REG_MULTI_SZ":7,"REG_QWORD":11}
NAMES={v:k for k,v in TYPES.items()}
VALUE_LIMIT=64*1024
PACKED_LIMIT=256*1024


def packed(value):
    if value is None: return None
    kind,data=value
    if isinstance(data,(bytes,bytearray)):data=bytes(data).hex()
    if kind in (4,11):data=str(data)
    return {"type":NAMES.get(kind,str(kind)),"data":data}


def version(value):
    if value is None:return "missing"
    return hashlib.sha256(json.dumps(packed(value),ensure_ascii=True,sort_keys=True,separators=(",",":")).encode()).hexdigest()


def unpack(value):
    if not isinstance(value,dict) or value.get("type") not in TYPES:raise ValueError("请选择支持的注册表类型")
    kind,data=TYPES[value["type"]],value.get("data")
    if kind in (1,2):
        if not isinstance(data,str) or "\0" in data:raise ValueError("字符串不能包含空字符")
    elif kind==7:
        if not isinstance(data,list) or any(not isinstance(v,str) or not v or "\0" in v for v in data):raise ValueError("多字符串需为不含空项的字符串列表")
    elif kind==3:
        if not isinstance(data,str):raise ValueError("二进制值使用十六进制")
        try:data=bytes.fromhex(data)
        except ValueError:raise ValueError("二进制值需为完整的十六进制字节")
    else:
        if not isinstance(data,str) or len(data)>22 or not re.fullmatch(r"(?:0[xX][0-9a-fA-F]+|[0-9]+)",data):raise ValueError("整数值使用十进制或 0x 十六进制")
        data=int(data,16 if data.lower().startswith("0x") else 10)
        if not 0<=data<(1<<(32 if kind==4 else 64)):raise ValueError("整数超出类型范围")
    if kind in (1,2):size=len(data.encode("utf-16-le"))+2
    elif kind==7:size=len(("\0".join(data)+"\0\0").encode("utf-16-le"))
    elif kind==3:size=len(data)
    else:size=4 if kind==4 else 8
    if size>VALUE_LIMIT or len(json.dumps(packed((kind,data)),ensure_ascii=True))>PACKED_LIMIT:raise ValueError("注册表值过大，最多 64 KB 文本或二进制")
    return kind,data


class NativeRegistry:
    def __init__(self):
        import winreg
        self.w=winreg
        self.roots=dict(zip(ROOTS,(winreg.HKEY_CURRENT_USER,winreg.HKEY_LOCAL_MACHINE,winreg.HKEY_CLASSES_ROOT,winreg.HKEY_USERS,winreg.HKEY_CURRENT_CONFIG)))
    def open(self,root,path,view,write=False,access=None):
        rights=(access if access is not None else self.w.KEY_READ|(self.w.KEY_SET_VALUE if write else 0))|(self.w.KEY_WOW64_32KEY if view==32 else self.w.KEY_WOW64_64KEY)
        return self.w.OpenKey(self.roots[root],path,0,rights)
    def browse(self,root,path,view,ko,vo):
        with self.open(root,path,view) as key:
            kc,vc,_=self.w.QueryInfoKey(key);keys=[];values=[]
            for i in range(ko,min(ko+100,kc)):keys.append(self.w.EnumKey(key,i))
            for i in range(vo,min(vo+100,vc)):
                name,value,kind=self.w.EnumValue(key,i)
                encoded=packed((kind,value));display=json.dumps(encoded["data"],ensure_ascii=False)
                values.append({"name":name,"type":encoded["type"],"preview":display[:180],"revision":version((kind,value))})
            return {"keys":keys,"values":values,"key_count":kc,"value_count":vc,"key_offset":ko,"value_offset":vo}
    def read(self,root,path,view,name):
        with self.open(root,path,view) as key:
            try:value,kind=self.w.QueryValueEx(key,name);return kind,value
            except FileNotFoundError:return None
    def write(self,root,path,view,name,value):
        with self.open(root,path,view,access=self.w.KEY_SET_VALUE) as key:
            if value is None:self.w.DeleteValue(key,name)
            else:self.w.SetValueEx(key,name,0,value[0],value[1])
    def create(self,root,path,view,name):
        flag=self.w.KEY_WOW64_32KEY if view==32 else self.w.KEY_WOW64_64KEY
        with self.open(root,path,view,access=self.w.KEY_CREATE_SUB_KEY|self.w.KEY_ENUMERATE_SUB_KEYS) as parent:
            try:
                with self.w.OpenKey(parent,name,0,self.w.KEY_READ|flag):pass
            except FileNotFoundError:
                with self.w.CreateKeyEx(parent,name,0,self.w.KEY_WRITE|flag):pass
                return
            raise ValueError("注册表子项已存在")
    def delete(self,root,path,view):
        with self.open(root,path,view) as key:
            kc,vc,_=self.w.QueryInfoKey(key)
            if kc or vc:raise ValueError("只允许删除不含子项和值的空项")
        self.w.DeleteKeyEx(self.roots[root],path,self.w.KEY_WOW64_32KEY if view==32 else self.w.KEY_WOW64_64KEY,0)


class RegistryEditor:
    def __init__(self,backup,backend=None):
        self.backup=Path(backup);self.backend=backend;self.lock=threading.RLock()
    def _backend(self):
        if self.backend is None:
            if os.name!="nt":raise ValueError("注册表编辑仅支持 Windows")
            self.backend=NativeRegistry()
        return self.backend
    def _location(self,q):
        root,path,view=q.get("root","HKCU"),q.get("path",""),q.get("view",64)
        if root not in ROOTS or type(view)is not int or view not in (32,64):raise ValueError("注册表根项或视图无效")
        if not isinstance(path,str) or len(path)>16000 or "\0" in path or path.startswith("\\") or "/" in path:raise ValueError("注册表路径无效")
        parts=path.split("\\") if path else []
        if any(v in ("",".","..") for v in parts):raise ValueError("注册表路径无效")
        return root,path,view
    def _name(self,q):
        name=q.get("name","")
        if not isinstance(name,str) or len(name)>16383 or "\0" in name:raise ValueError("注册表值名称无效")
        return name
    def _journal(self,root,path,view,name,before,after):
        self.backup.mkdir(parents=True,exist_ok=True)
        record=dict(id=uuid.uuid4().hex,root=root,path=path,view=view,name=name,before=packed(before),after=packed(after),created=time.time())
        target=self.backup/(record["id"]+".json")
        with target.open("x",encoding="utf-8") as stream:json.dump(record,stream,ensure_ascii=True);stream.flush();os.fsync(stream.fileno())
        return record
    def request(self,q,error):
        try:
            backend=self._backend();action=q.get("action","browse")
            with self.lock:
                if action=="history":
                    records=[]
                    if self.backup.is_dir():
                        for p in sorted(self.backup.glob("*.json"),key=lambda p:p.stat().st_mtime,reverse=True)[:100]:
                            try:
                                r=json.loads(p.read_text());records.append({k:r[k] for k in ("id","root","path","view","name","created")})
                            except (OSError,ValueError,KeyError):continue
                    return {"items":records,"directory":str(self.backup)}
                if action=="restore":
                    rid=q.get("backup_id","")
                    if not isinstance(rid,str) or not re.fullmatch("[0-9a-f]{32}",rid):raise ValueError("备份编号无效")
                    record=json.loads((self.backup/(rid+".json")).read_text())
                    root,path,view=self._location(record);name=self._name(record)
                    before=unpack(record["before"]) if record["before"] is not None else None
                    after=unpack(record["after"]) if record["after"] is not None else None
                    current=backend.read(root,path,view,name)
                    if version(current)!=version(after):raise ValueError("当前值已变化，未覆盖，不能直接恢复此备份")
                    self._journal(root,path,view,name,current,before)
                    if version(backend.read(root,path,view,name))!=version(current):raise ValueError("备份期间注册表值已变化，未覆盖")
                    if current is not None or before is not None:backend.write(root,path,view,name,before)
                    return {"restored":True}
                root,path,view=self._location(q)
                if action=="browse":
                    ko,vo=q.get("key_offset",0),q.get("value_offset",0)
                    if any(type(v)is not int or not 0<=v<=1000000 for v in (ko,vo)):raise ValueError("分页位置无效")
                    return dict(backend.browse(root,path,view,ko,vo),root=root,path=path,view=view)
                if action=="create_key":
                    child=q.get("child")
                    if not isinstance(child,str) or not child or child in (".","..") or len(child)>255 or any(c in child for c in "\\/\0"):raise ValueError("请输入单个有效子项名称")
                    backend.create(root,path,view,child);return {"created":True}
                if action=="delete_key":
                    if not path:raise ValueError("不能删除注册表根项")
                    backend.delete(root,path,view);return {"deleted":True}
                name=self._name(q);current=backend.read(root,path,view,name)
                if action=="get":
                    if current is None:raise ValueError("注册表值不存在")
                    data=packed(current)
                    if current[0] in NAMES:unpack(data)
                    elif len(json.dumps(data,ensure_ascii=True))>128*1024:raise ValueError("此值过大，不支持在线编辑")
                    return dict(data,name=name,revision=version(current))
                if action not in ("set","delete_value"):raise ValueError("无效的注册表操作")
                if current is not None and current[0] not in NAMES:raise ValueError("此类型仅支持浏览，不支持在线修改")
                if current is not None:unpack(packed(current))
                if q.get("revision")!=version(current):raise ValueError("注册表值已变化，未覆盖；请刷新后再编辑")
                if action=="delete_value" and current is None:raise ValueError("注册表值不存在")
                after=unpack(q.get("value")) if action=="set" else None
                record=self._journal(root,path,view,name,current,after)
                # An external regedit may have written while the backup reached disk.
                if version(backend.read(root,path,view,name))!=version(current):raise ValueError("备份期间注册表值已变化，未覆盖")
                backend.write(root,path,view,name,after)
                return {"saved":True,"revision":version(after),"backup":record["id"]}
        except (OSError,ValueError,KeyError,TypeError,OverflowError) as exc:raise error(str(exc))

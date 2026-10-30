"""Recoverable per-file data ACL locks; passwords are never stored in plaintext.

No encryption, directory recursion, file-content changes or irreversible locks.
The DACL journal reaches disk before any change. An elevated local recovery
command can restore it without the phone/password, after verifying identity.
"""
import datetime
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import stat
import threading
import time
import uuid

from app_locks import AppLocks, FilePermissions, _FileSecurityApi, same_dacl


class DataPermissions(FilePermissions):
    def call(self, **q):
        if q.get("action") == "plan":
            return _FileSecurityApi().plan(q["path"], permissions=0x27, everyone=True)
        return super().call(**q)


class FileLocks(AppLocks):
    def __init__(self, database, protected=(), backend=None, clock=time.time, worker=True):
        super().__init__(database, protected, backend or DataPermissions())
        self.clock=clock;self.errors={};self.retry={};self.closed=threading.Event();self.failures={}
        self.started=False
        if worker and os.name=="nt":self.start()

    def start(self):
        with self.lock:
            if not self.started:
                self.started=True
                threading.Thread(target=self._watch,name="file-lock-expiry",daemon=True).start()

    def _target(self, value):
        if not isinstance(value,str) or not value.strip() or "\0" in value or len(value)>32767:
            raise ValueError("请选择一个普通本地文件")
        if os.name=="nt" and ":" in os.path.splitdrive(value)[1]:
            raise ValueError("不能锁定 NTFS 备用数据流；请选择文件本身")
        selected=Path(value)
        if not selected.is_absolute() or str(selected).startswith("\\\\"):
            raise ValueError("文件锁仅支持本机绝对路径")
        first=selected.lstat()
        if stat.S_ISLNK(first.st_mode) or getattr(first,"st_file_attributes",0)&0x404:
            raise ValueError("不能锁定系统文件、链接或重解析点")
        path=selected.resolve(strict=True);info=path.stat()
        if path.name.casefold() in {"pagefile.sys","hiberfil.sys","swapfile.sys","bootmgr","boot.ini","ntldr","bcd"}:
            raise ValueError("不能锁定 Windows 启动或虚拟内存文件")
        roots=list(self.protected)
        for key in ("SystemRoot","WINDIR"):
            if os.environ.get(key):roots.append(Path(os.environ[key]).resolve())
        if (not stat.S_ISREG(info.st_mode) or getattr(info,"st_nlink",1)>1 or not info.st_ino or
                getattr(info,"st_file_attributes",0)&0x400):
            raise ValueError("文件锁只支持有稳定身份的普通单链接文件")
        if any(path==root or root in path.parents for root in roots):
            raise ValueError("不能锁定 Windows、接收端或恢复记录")
        return path,[info.st_dev,info.st_ino]

    def _load(self):
        data=super()._load()
        for entry in data.values():
            if (not isinstance(entry.get("salt"),str) or len(entry["salt"])!=32 or
                    not isinstance(entry.get("verifier"),str) or len(entry["verifier"])!=64 or
                    type(entry.get("expires")) not in (int,float) or not 0<=entry["expires"]<4102444800):
                raise ValueError("文件锁恢复记录损坏，请保留原文件后检查")
            try:bytes.fromhex(entry["salt"]);bytes.fromhex(entry["verifier"])
            except ValueError:raise ValueError("文件锁恢复记录损坏")
        return data

    def _states(self, data):
        values=super()._states(data)
        for item in values:
            item.pop("salt",None);item.pop("verifier",None)
            if item["id"] in self.errors:item["error"]=self.errors[item["id"]]
            item["until_local"]=datetime.datetime.fromtimestamp(item["expires"]).strftime("%Y-%m-%d %H:%M:%S") if item["expires"] else ""
        return values

    def _restore(self, data, rid):
        entry=data[rid]
        current=next(v for v in self._states({rid:entry}) if v["id"]==rid)
        if current["status"]!="unlocked":
            if current["status"]!="locked":raise ValueError("文件身份或权限已变化，未覆盖；请保留本机恢复备份")
            self._identity(entry["path"],entry["identity"])
            self.backend.call(action="apply",path=entry["path"],expected=entry["applied"],target=entry["original"],identity=entry["identity"])
        entry["released"]=True
        self._save(data)
        self.errors.pop(rid,None);self.retry.pop(rid,None)

    def tick(self):
        with self.lock:
            if not self.path.exists():return
            data=self._load();now=self.clock()
            for rid,entry in data.items():
                if entry.get("released") or not entry["expires"] or now<entry["expires"] or now<self.retry.get(rid,0):continue
                try:self._restore(data,rid)
                except (OSError,ValueError) as exc:
                    self.errors[rid]=str(exc)[:1000];self.retry[rid]=now+60

    def _watch(self):
        while not self.closed.wait(1):
            try:self.tick()
            except (OSError,ValueError):pass  # Listing exposes corrupt journal errors; never overwrite it.

    def close(self):self.closed.set()

    def recover(self):
        """Only the elevated local CLI calls this, never an RPC action."""
        with self.lock:
            data=self._load();results=[]
            for rid,entry in data.items():
                try:self._restore(data,rid);results.append(dict(path=entry["path"],restored=True))
                except (OSError,ValueError) as exc:results.append(dict(path=entry["path"],restored=False,error=str(exc)))
            return results

    def request(self, q, error):
        password=q.pop("password",None)
        try:
            if os.name!="nt" and isinstance(self.backend,DataPermissions):raise ValueError("文件锁仅支持 Windows NTFS 文件权限")
            action=q.get("action","list")
            with self.lock:
                data=self._load()
                if action=="list":
                    return dict(items=self._states(data),computer_time=datetime.datetime.fromtimestamp(self.clock()).strftime("%Y-%m-%d %H:%M:%S"),backup=str(self.path))
                if action=="forget":
                    rid=q.get("lock_id");entry=data.get(rid)
                    if entry is None:raise ValueError("文件锁记录不存在")
                    state=self._states({rid:entry})[0]["status"]
                    if state!="unlocked":raise ValueError("请先解锁；文件缺失或权限冲突时须保留恢复记录")
                    self._archive(rid,entry);data.pop(rid);self._save(data)
                    return dict(removed=True)
                if action not in ("lock","unlock"):raise ValueError("无效的文件锁操作")
                if not isinstance(password,str) or not 8<=len(password)<=128:
                    raise ValueError("请输入 8–128 字符的文件锁密码")
                if action=="unlock":
                    rid=q.get("lock_id");entry=data.get(rid)
                    if entry is None:raise ValueError("文件锁记录不存在")
                    attempts,until=self.failures.get(rid,(0,0))
                    if self.clock()<until:raise ValueError("密码尝试过多，请稍后再试")
                    actual=hashlib.pbkdf2_hmac("sha256",password.encode(),bytes.fromhex(entry["salt"]),210000).hex()
                    if not hmac.compare_digest(actual,entry["verifier"]):
                        attempts+=1;self.failures[rid]=(attempts,self.clock()+60 if attempts>=5 else 0)
                        raise ValueError("文件锁密码不正确")
                    self.failures.pop(rid,None);self._restore(data,rid)
                    return dict(unlocked=True,items=self._states(data))
                path,identity=self._target(q.get("path"))
                if len(data)>=50:raise ValueError("最多保存 50 条文件锁，请先移除已解锁记录")
                if any(v["path"].casefold()==str(path).casefold() for v in data.values()):raise ValueError("文件已有锁定记录，请先解锁并移除旧记录")
                local=q.get("until_local","")
                if not isinstance(local,str):raise ValueError("解锁时间格式无效")
                expires=datetime.datetime.strptime(local,"%Y-%m-%d %H:%M:%S").timestamp() if local else 0
                if expires and not self.clock()<expires<min(self.clock()+5*366*86400,4102444800):raise ValueError("解锁时间须在电脑当前时间之后、五年以内")
                plan=self.backend.call(action="plan",path=str(path))
                if same_dacl(plan["original"],plan["applied"]):raise ValueError("原权限已限制读取，未重复锁定")
                self._identity(path,identity)
                salt=secrets.token_bytes(16)
                entry=dict(path=str(path),identity=identity,**plan,salt=salt.hex(),
                    verifier=hashlib.pbkdf2_hmac("sha256",password.encode(),salt,210000).hex(),expires=expires,released=False)
                rid=uuid.uuid4().hex;data[rid]=entry;self._save(data)
                self._identity(path,identity)
                self.backend.call(action="apply",path=str(path),expected=plan["original"],target=plan["applied"],identity=identity)
                if self._states({rid:entry})[0]["status"]!="locked":raise ValueError("锁定状态未确认，恢复备份已保留，请刷新检查")
                return dict(items=self._states(data),locked=True)
        except (OSError,ValueError,KeyError,TypeError) as exc:raise error(str(exc))

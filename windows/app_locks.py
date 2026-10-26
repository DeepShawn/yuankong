"""Reversible execute denial for explicitly selected, non-system EXE files."""
import json
import os
from pathlib import Path
import subprocess
import threading
import uuid

SCRIPT = r'''
$ErrorActionPreference='Stop'
[Console]::InputEncoding=[Text.Encoding]::UTF8
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$q=[Console]::In.ReadToEnd() | ConvertFrom-Json
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if($q.action -eq 'inspect') {
  $items=@(foreach($p in $q.paths) {
    try {
      $a=Get-Acl -LiteralPath $p
      @{path=$p;sid=$sid;sddl=$a.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)}
    } catch { @{path=$p;error=$_.Exception.Message} }
  })
  ConvertTo-Json -InputObject $items -Compress -Depth 5
} elseif($q.action -eq 'plan') {
  $a=Get-Acl -LiteralPath $q.path
  $original=$a.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)
  if($original -match 'NO_ACCESS_CONTROL') { throw 'Null DACL is not supported' }
  $rule=New-Object Security.AccessControl.FileSystemAccessRule(
    (New-Object Security.Principal.SecurityIdentifier($sid)),
    [Security.AccessControl.FileSystemRights]::ExecuteFile,
    [Security.AccessControl.AccessControlType]::Deny)
  $a.AddAccessRule($rule)
  @{sid=$sid;original=$original;applied=$a.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)} | ConvertTo-Json -Compress
} elseif($q.action -eq 'apply') {
  $a=Get-Acl -LiteralPath $q.path
  $current=$a.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)
  if($current -cne $q.expected) { throw 'File permissions changed outside LanCmd; nothing overwritten' }
  $a.SetSecurityDescriptorSddlForm($q.target,[Security.AccessControl.AccessControlSections]::Access)
  Set-Acl -LiteralPath $q.path -AclObject $a
  $after=(Get-Acl -LiteralPath $q.path).GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)
  @{sddl=$after} | ConvertTo-Json -Compress
} else { throw 'Invalid application lock operation' }
'''


class FilePermissions:
    def call(self, **q):
        result = subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command",SCRIPT],
            input=json.dumps(q,ensure_ascii=False).encode("utf-8"),stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            timeout=20,creationflags=0x08000000)
        if result.returncode:
            raise ValueError(result.stderr.decode("utf-8","replace").strip()[-1500:] or "文件权限操作失败，请检查管理员权限")
        return json.loads(result.stdout.decode("utf-8-sig"))


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
        return data

    def _save(self, data):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(data,ensure_ascii=False),encoding="utf-8");os.replace(temp,self.path)

    def _target(self,value):
        if not isinstance(value,str) or not value or "\0" in value: raise ValueError("请选择 EXE 文件")
        path=Path(value).resolve(strict=True)
        if not path.is_file() or path.suffix.lower()!=".exe": raise ValueError("应用锁仅支持普通 EXE 文件")
        if os.name=="nt" and path.stat().st_file_attributes & 0x400: raise ValueError("不支持重解析点")
        roots=list(self.protected)
        if os.environ.get("WINDIR"):roots.append(Path(os.environ["WINDIR"]).resolve())
        if any(path==root or root in path.parents for root in roots): raise ValueError("不能锁定 Windows 系统目录或 LanCmd 自身组件")
        info=path.stat()
        return path,[info.st_dev,info.st_ino]

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
                elif current.get("sddl")==entry["applied"]: item["status"]="locked"
                elif current.get("sddl")==entry["original"]: item["status"]="unlocked"
                else: item["status"]="changed";item["error"]=current.get("error","权限已被外部修改，未覆盖")
            except OSError: item["status"]="missing"
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
                    if plan["original"]==plan["applied"]:raise ValueError("原权限已限制执行，未新增应用锁")
                    rid=uuid.uuid4().hex
                    entry=dict(path=str(path),identity=identity,**plan)
                    data[rid]=entry
                    self._save(data)  # Recovery data must reach disk before ACL modification.
                    self.backend.call(action="apply",path=str(path),expected=plan["original"],target=plan["applied"])
                    return {"items":self._states(data)}
                if action not in ("unlock","forget"): raise ValueError("无效的应用锁操作")
                rid=str(q.get("id","")); entry=data.get(rid)
                if not entry: raise ValueError("应用锁记录不存在")
                current=next(v for v in self._states(data) if v["id"]==rid)
                if action=="forget":
                    if current["status"] not in ("unlocked","missing","replaced"): raise ValueError("请先解锁，权限冲突时不能丢弃恢复备份")
                    data.pop(rid);self._save(data);return {"removed":True}
                if current["status"]=="unlocked": return {"unlocked":True}
                if current["status"]!="locked": raise ValueError("文件已变化、缺失或权限被外部修改，未覆盖；请保留备份")
                self._target(entry["path"])
                self.backend.call(action="apply",path=entry["path"],expected=entry["applied"],target=entry["original"])
                return {"unlocked":True,"items":self._states(data)}
        except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as exc:
            raise error(str(exc))

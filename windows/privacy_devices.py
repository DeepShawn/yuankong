"""Explicit camera/microphone device control. Never changes privacy indicators."""
import ctypes
import json
import os
import subprocess
import threading
from touch_guard import TouchDevices
from system_controls import endpoint_audio

INVENTORY=r'''
$ErrorActionPreference='Stop';[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$items=@(foreach($d in @(Get-PnpDevice -PresentOnly)) {
  $kind=$null;$endpoint=$null
  if($d.Class -eq 'Camera') { $kind='camera' }
  elseif($d.Class -eq 'Image') {
    $ids=@((Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName 'DEVPKEY_Device_CompatibleIds' -ErrorAction SilentlyContinue).Data)
    if(@($ids | Where-Object { $_ -like 'USB\Class_0e*' }).Count -gt 0) { $kind='camera' }
  } elseif($d.Class -eq 'AudioEndpoint' -and $d.InstanceId -like 'SWD\MMDEVAPI\{0.0.1.*') {
    $kind='microphone';$endpoint=($d.InstanceId -split '\\')[-1]
  }
  if($kind) { @{id=[string]$d.InstanceId;name=[string]$d.FriendlyName;kind=$kind;endpoint=$endpoint} }
})
ConvertTo-Json -InputObject $items -Compress -Depth 5
'''


class PrivacyHardware(TouchDevices):
    def node(self,instance):
        node=self.w.DWORD();result=self.cm.CM_Locate_DevNodeW(ctypes.byref(node),instance,0)
        if result:raise RuntimeError("设备已断开或无法定位 (0x%X)，请刷新"%result)
        return node
    def inventory(self):
        executable=os.path.join(os.environ.get("SystemRoot",r"C:\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe")
        result=subprocess.run([executable,"-NoLogo","-NoProfile","-NonInteractive","-Command",INVENTORY],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30,creationflags=0x08000000)
        if result.returncode:raise RuntimeError("读取摄像头/麦克风设备失败："+result.stderr.decode("utf-8","replace")[-1000:])
        items=json.loads(result.stdout.decode("utf-8-sig"))
        for item in items:
            try:item.update(self.status(item["id"]))
            except RuntimeError as exc:item.update(enabled=False,problem=-1,error=str(exc))
        return items
    def status(self,instance):
        status,problem=self.w.ULONG(),self.w.ULONG();node=self.node(instance)
        result=self.cm.CM_Get_DevNode_Status(ctypes.byref(status),ctypes.byref(problem),node,0)
        if result:raise RuntimeError("读取设备状态失败 (0x%X)"%result)
        return {"enabled":bool(status.value&8) and problem.value==0,"problem":problem.value}
    def set_enabled(self,instance,enabled):
        node=self.node(instance)
        # Explicit persistent device disable, unlike the temporary touch guard.
        result=self.cm.CM_Enable_DevNode(node,0) if enabled else self.cm.CM_Disable_DevNode(node,5)
        if result:raise RuntimeError("设备启停失败，需要管理员权限或驱动支持 (0x%X)"%result)


class PrivacyDevices:
    def __init__(self,admin,backend=None,audio=endpoint_audio):self.admin=admin;self.backend=backend;self.audio=audio;self.lock=threading.RLock()
    def request(self,q,error):
        try:
            if self.backend is None:
                if os.name!="nt":raise ValueError("隐私设备管理仅支持 Windows")
                self.backend=PrivacyHardware()
            with self.lock:
                items=self.backend.inventory();action=q.get("action","list")
                if action=="list":
                    for item in items:
                        if item["kind"]=="microphone" and item.get("enabled"):
                            try:item.update(self.audio(device_id=item["endpoint"],flow=1))
                            except RuntimeError as exc:item["audio_error"]=str(exc)
                    return {"items":items,"admin":bool(self.admin())}
                item=next((v for v in items if v["id"]==q.get("id")),None)
                if not item:raise ValueError("设备已断开或不属于可管理的摄像头/麦克风，请刷新")
                if action=="set_enabled":
                    enabled=q.get("enabled")
                    if not isinstance(enabled,bool):raise ValueError("设备状态必须为布尔值")
                    if not self.admin():raise ValueError("设备启停需要管理员接收端")
                    if item.get("enabled")==enabled and (enabled or item.get("problem")==22):return {"unchanged":True}
                    self.backend.set_enabled(item["id"],enabled)
                    state=self.backend.status(item["id"])
                    if state["enabled"]!=enabled:raise ValueError("Windows 已接受请求，但状态尚未完成切换，请刷新；驱动可能需要重新插拔或重启")
                    return dict(state,changed=True)
                if action=="mute":
                    if item["kind"]!="microphone" or not item.get("enabled"):raise ValueError("请先启用目标麦克风")
                    muted=q.get("muted")
                    if not isinstance(muted,bool):raise ValueError("静音状态必须为布尔值")
                    return self.audio(muted=muted,device_id=item["endpoint"],flow=1)
                raise ValueError("无效的隐私设备操作")
        except (OSError,ValueError,RuntimeError,KeyError,TypeError,subprocess.SubprocessError) as exc:raise error(str(exc))

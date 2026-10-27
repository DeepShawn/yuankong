"""Explicit camera/microphone device control. Never changes privacy indicators."""
import ctypes
import json
import os
import subprocess
import threading
import re
import time
from touch_guard import TouchDevices
from system_controls import endpoint_audio

CM_DISABLE_UI_NOT_OK = 0x00000004
CM_DISABLE_PERSIST = 0x00000008

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


def capture_endpoint(instance):
    """Only capture MMDevice endpoints; never route a mute to a render device."""
    if not isinstance(instance,str):return None
    match=re.fullmatch(r"SWD\\MMDEVAPI\\(\{0\.0\.1\.[0-9a-f]{8}\}\.\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\})",instance,re.IGNORECASE)
    return match.group(1) if match else None


class PrivacyHardware(TouchDevices):
    def node(self,instance):
        node=self.w.DWORD();result=self.cm.CM_Locate_DevNodeW(ctypes.byref(node),instance,0)
        if result:raise RuntimeError("设备已断开或无法定位 (0x%X)，请刷新"%result)
        return node
    def inventory(self):
        executable=os.path.join(os.environ.get("SystemRoot",r"C:\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe")
        result=subprocess.run([executable,"-NoLogo","-NoProfile","-NonInteractive","-Command",INVENTORY],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30,creationflags=0x08000000)
        if result.returncode:raise RuntimeError("读取摄像头/麦克风设备失败："+result.stderr.decode("utf-8","replace")[-1000:])
        raw=json.loads(result.stdout.decode("utf-8-sig"))
        if not isinstance(raw,list):raise RuntimeError("设备列表格式无效")
        items=[]
        for item in raw:
            if not isinstance(item,dict) or item.get("kind") not in ("camera","microphone") or not isinstance(item.get("id"),str) or not item["id"]:
                continue
            if item["kind"]=="microphone":
                endpoint=capture_endpoint(item["id"])
                if endpoint is None:continue
                item["endpoint"]=endpoint
            item["name"]=item.get("name") or item["id"]
            try:item.update(self.status(item["id"]))
            except RuntimeError as exc:item.update(enabled=False,problem=-1,error=str(exc))
            items.append(item)
        return items
    def status(self,instance):
        status,problem=self.w.ULONG(),self.w.ULONG();node=self.node(instance)
        result=self.cm.CM_Get_DevNode_Status(ctypes.byref(status),ctypes.byref(problem),node,0)
        if result:raise RuntimeError("读取设备状态失败 (0x%X)"%result)
        return {"enabled":bool(status.value&8) and problem.value==0,"problem":problem.value}
    def set_enabled(self,instance,enabled):
        node=self.node(instance)
        # Explicit persistent device disable, unlike the temporary touch guard.
        result=self.cm.CM_Enable_DevNode(node,0) if enabled else self.cm.CM_Disable_DevNode(node,CM_DISABLE_UI_NOT_OK|CM_DISABLE_PERSIST)
        if result:
            if result==0x17:raise RuntimeError("驱动拒绝停用设备 (0x17 / CR_REMOVE_VETOED)。请先停止摄像头共享、录制并关闭占用软件后重试；驱动仍拒绝时本次未禁用，不能通过禁用父控制器替代")
            if result==0x33:raise RuntimeError("设备启停权限不足 (0x33)，请使用管理员接收端")
            if result==0x28:raise RuntimeError("此设备驱动不支持停用 (0x28)，本次未更改")
            raise RuntimeError("设备驱动拒绝启停 (0x%X)，请刷新检查状态和驱动支持"%result)


class PrivacyDevices:
    def __init__(self,admin,backend=None,audio=endpoint_audio):self.admin=admin;self.backend=backend;self.audio=audio;self.lock=threading.RLock()
    @staticmethod
    def _endpoint(item):
        endpoint=item.get("endpoint")
        if not isinstance(endpoint,str) or not endpoint or any(ord(c)<32 for c in endpoint):
            raise ValueError("麦克风端点编号无效，请刷新；不会修改默认音频设备")
        return endpoint
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
                            try:
                                response=self.audio(device_id=self._endpoint(item),flow=1)
                                if not isinstance(response,dict) or not isinstance(response.get("muted"),bool):
                                    raise RuntimeError("未能读取麦克风静音状态")
                                item.update(response)
                            except (OSError,RuntimeError,ValueError) as exc:item["audio_error"]=str(exc)
                    return {"items":items,"admin":bool(self.admin())}
                item=next((v for v in items if v["id"]==q.get("device_id")),None)
                if not item:raise ValueError("设备已断开或不属于可管理的摄像头/麦克风，请刷新")
                if item.get("kind") not in ("camera","microphone"):
                    raise ValueError("不支持控制此类设备")
                if action=="set_enabled":
                    enabled=q.get("enabled")
                    if not isinstance(enabled,bool):raise ValueError("设备状态必须为布尔值")
                    if not self.admin():raise ValueError("设备启停需要管理员接收端")
                    if item.get("error") or item.get("problem") not in (0,22) or not isinstance(item.get("enabled"),bool):
                        raise ValueError("设备状态异常或未知，未执行启停，请刷新检查驱动")
                    if item.get("enabled")==enabled and (enabled or item.get("problem")==22):return {"unchanged":True}
                    self.backend.set_enabled(item["id"],enabled)
                    deadline=time.monotonic()+1.5
                    while True:
                        state=self.backend.status(item["id"])
                        confirmed=state.get("enabled") is enabled and state.get("problem")== (0 if enabled else 22)
                        if confirmed:break
                        if time.monotonic()>=deadline:raise ValueError("Windows 已接受请求，但状态尚未完成切换，请刷新；驱动可能需要重新插拔或重启")
                        time.sleep(.1)
                    return dict(state,changed=True)
                if action=="mute":
                    if item["kind"]!="microphone" or not item.get("enabled"):raise ValueError("请先启用目标麦克风")
                    muted=q.get("muted")
                    if not isinstance(muted,bool):raise ValueError("静音状态必须为布尔值")
                    response=self.audio(muted=muted,device_id=self._endpoint(item),flow=1)
                    if not isinstance(response,dict) or response.get("muted") is not muted:
                        raise ValueError("麦克风未确认切换到所选静音状态，请刷新检查")
                    return response
                raise ValueError("无效的隐私设备操作")
        except (OSError,ValueError,RuntimeError,KeyError,TypeError,subprocess.SubprocessError) as exc:raise error(str(exc))

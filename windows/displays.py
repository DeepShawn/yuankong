"""Existing Windows display outputs and wallpaper settings. No hidden desktops or driver installs."""
import ctypes
from ctypes import wintypes as w
import os
from pathlib import Path
import shutil
import uuid


class NativeDisplays:
    def __init__(self):
        self.user=ctypes.WinDLL("user32",use_last_error=True)
        class Device(ctypes.Structure):
            _fields_=[("cb",w.DWORD),("name",w.WCHAR*32),("description",w.WCHAR*128),("flags",w.DWORD),("id",w.WCHAR*128),("key",w.WCHAR*128)]
        class Rect(ctypes.Structure):_fields_=[("left",w.LONG),("top",w.LONG),("right",w.LONG),("bottom",w.LONG)]
        class Monitor(ctypes.Structure):
            _fields_=[("cbSize",w.DWORD),("monitor",Rect),("work",Rect),("flags",w.DWORD),("device",w.WCHAR*32)]
        self.Device=Device;self.Monitor=Monitor;self.Rect=Rect
        self.callback=ctypes.WINFUNCTYPE(w.BOOL,w.HANDLE,w.HDC,ctypes.POINTER(Rect),w.LPARAM)
        self.user.EnumDisplayDevicesW.argtypes=[w.LPCWSTR,w.DWORD,ctypes.POINTER(Device),w.DWORD]
        self.user.EnumDisplayMonitors.argtypes=[w.HDC,ctypes.POINTER(Rect),self.callback,w.LPARAM]
        self.user.GetMonitorInfoW.argtypes=[w.HANDLE,ctypes.POINTER(Monitor)]

    def list(self):
        bounds={}
        def monitor(handle,dc,rect,data):
            info=self.Monitor();info.cbSize=ctypes.sizeof(info)
            if self.user.GetMonitorInfoW(handle,ctypes.byref(info)):
                r=info.monitor;bounds[info.device]=dict(left=r.left,top=r.top,width=r.right-r.left,height=r.bottom-r.top)
            return True
        if not self.user.EnumDisplayMonitors(None,None,self.callback(monitor),0):raise ctypes.WinError(ctypes.get_last_error())
        result=[]
        for index in range(64):
            device=self.Device();device.cb=ctypes.sizeof(device)
            if not self.user.EnumDisplayDevicesW(None,index,ctypes.byref(device),0):break
            if device.flags&8:continue
            rectangle=bounds.get(device.name)
            result.append(dict(id=device.name,name=device.description or device.name,primary=bool(device.flags&4),
                active=bool(rectangle),bounds=rectangle))
        return result

    def enable(self,device):
        # The device/driver must already exist. This never installs an unsigned IDD.
        class Point(ctypes.Structure):_fields_=[("x",w.LONG),("y",w.LONG)]
        class Mode(ctypes.Structure):
            _fields_=[("dmDeviceName",w.WCHAR*32),("dmSpecVersion",w.WORD),("dmDriverVersion",w.WORD),
                ("dmSize",w.WORD),("dmDriverExtra",w.WORD),("dmFields",w.DWORD),("position",Point),
                ("orientation",w.DWORD),("fixed",w.DWORD),("color",w.SHORT),("duplex",w.SHORT),
                ("resolution",w.SHORT),("tt",w.SHORT),("collate",w.SHORT),("form",w.WCHAR*32),
                ("pixels",w.WORD),("bits",w.DWORD),("width",w.DWORD),("height",w.DWORD),
                ("flags",w.DWORD),("frequency",w.DWORD),("icmmethod",w.DWORD),("icmintent",w.DWORD),
                ("media",w.DWORD),("dither",w.DWORD),("reserved1",w.DWORD),("reserved2",w.DWORD),
                ("panwidth",w.DWORD),("panheight",w.DWORD)]
        self.user.EnumDisplaySettingsW.argtypes=[w.LPCWSTR,w.DWORD,ctypes.POINTER(Mode)]
        self.user.ChangeDisplaySettingsExW.argtypes=[w.LPCWSTR,ctypes.POINTER(Mode),w.HWND,w.DWORD,ctypes.c_void_p]
        self.user.ChangeDisplaySettingsExW.restype=w.LONG
        items=self.list();selected=next((v for v in items if v["id"]==device),None)
        if selected is None:raise ValueError("显示输出已变化，请刷新")
        if selected["active"]:return
        mode=Mode();mode.dmSize=ctypes.sizeof(mode)
        if not self.user.EnumDisplaySettingsW(device,0xfffffffe,ctypes.byref(mode)) and not self.user.EnumDisplaySettingsW(device,0,ctypes.byref(mode)):
            raise ValueError("该输出没有可用显示模式，请先连接屏幕或在 Windows 安装受信任的虚拟显示驱动")
        if not mode.width or not mode.height:raise ValueError("显示驱动未提供有效分辨率")
        mode.dmDriverExtra=0
        mode.position.x=max((v["bounds"]["left"]+v["bounds"]["width"] for v in items if v["active"]),default=0)
        mode.position.y=0
        mode.dmFields=0x20|0x80000|0x100000
        code=self.user.ChangeDisplaySettingsExW(device,ctypes.byref(mode),None,1,None)
        if code!=0:raise ValueError("Windows 拒绝扩展该显示输出（%s），请检查驱动与连接"%code)


def monitor_bounds(device,backend=None):
    if not isinstance(device,str) or not device or len(device)>64:raise ValueError("显示器编号无效")
    item=next((v for v in (backend or NativeDisplays()).list() if v["id"]==device and v["active"]),None)
    if item is None:raise ValueError("所选显示器已断开或未启用，请刷新显示器列表")
    return item["bounds"]


def pointer_position(x,y,bounds,virtual):
    vx,vy,vw,vh=virtual
    if vw<=1 or vh<=1 or bounds["width"]<=0 or bounds["height"]<=0:raise ValueError("显示器尺寸无效")
    px=bounds["left"]+x*(bounds["width"]-1);py=bounds["top"]+y*(bounds["height"]-1)
    return max(0,min(1,(px-vx)/(vw-1))),max(0,min(1,(py-vy)/(vh-1)))


def display_request(q,error):
    try:
        if os.name!="nt":raise ValueError("显示器管理仅支持 Windows")
        backend=NativeDisplays();action=q.get("action","list")
        if action=="enable":backend.enable(q.get("display_id"))
        elif action!="list":raise ValueError("无效的显示器操作")
        return dict(items=backend.list(),notice="使用已有显示输出；新增虚拟显示器需先在 Windows 安装受信任驱动")
    except (OSError,ValueError,TypeError) as exc:raise error(str(exc))


class Wallpaper:
    def __init__(self,directory):self.directory=Path(directory)
    def request(self,q,error):
        try:
            if os.name!="nt":raise ValueError("壁纸设置仅支持 Windows")
            import winreg as r
            styles={"fill":"10","fit":"6","stretch":"2","center":"0","span":"22"}
            with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_READ) as key:
                def read(name):
                    try:return r.QueryValueEx(key,name)[0]
                    except OSError:return ""
                old_path=read("WallPaper");old_style=read("WallpaperStyle");old_tile=read("TileWallpaper")
            if q.get("action","get")=="get":return dict(path=old_path,style=next((k for k,v in styles.items() if v==old_style),"fill"))
            if q.get("action")!="set" or q.get("style","fill") not in styles:raise ValueError("壁纸操作或显示方式无效")
            path=Path(q.get("path","")).expanduser()
            if not path.is_absolute() or str(path).startswith("\\\\"):raise ValueError("请选择电脑上的本地图片")
            if path.suffix.lower() not in (".jpg",".jpeg",".png",".bmp"):raise ValueError("请选择 JPEG、PNG 或 BMP 图片")
            info=path.lstat()
            import stat
            if not stat.S_ISREG(info.st_mode) or getattr(info,"st_file_attributes",0)&0x400 or stat.S_ISLNK(info.st_mode) or info.st_size>50*1024**2:
                raise ValueError("壁纸须为不超过 50 MiB 的普通图片文件")
            with path.open("rb") as stream:header=stream.read(12)
            if not (header.startswith(b"\xff\xd8\xff") or header.startswith(b"\x89PNG\r\n\x1a\n") or header.startswith(b"BM")):
                raise ValueError("请选择 JPEG、PNG 或 BMP 图片")
            self.directory.mkdir(parents=True,exist_ok=True)
            target=self.directory/(uuid.uuid4().hex+path.suffix.lower())
            try:
                with path.open("rb") as source,target.open("xb") as out:
                    actual=os.fstat(source.fileno())
                    if (actual.st_dev,actual.st_ino,actual.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("图片已变化，请刷新后重试")
                    copied=0
                    while True:
                        block=source.read(1024*1024)
                        if not block:break
                        copied+=len(block)
                        if copied>info.st_size:raise ValueError("图片在复制时已变化")
                        out.write(block)
                    if copied!=info.st_size:raise ValueError("图片在复制时已变化")
                    out.flush();os.fsync(out.fileno())
            except Exception:
                target.unlink(missing_ok=True);raise
            user=ctypes.WinDLL("user32",use_last_error=True)
            user.SystemParametersInfoW.argtypes=[w.UINT,w.UINT,w.LPWSTR,w.UINT];user.SystemParametersInfoW.restype=w.BOOL
            try:
                with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_SET_VALUE) as key:
                    r.SetValueEx(key,"WallpaperStyle",0,r.REG_SZ,styles[q.get("style","fill")]);r.SetValueEx(key,"TileWallpaper",0,r.REG_SZ,"0")
                if not user.SystemParametersInfoW(20,0,str(target),3):raise ctypes.WinError(ctypes.get_last_error())
            except Exception:
                with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_SET_VALUE) as key:
                    r.SetValueEx(key,"WallpaperStyle",0,r.REG_SZ,old_style);r.SetValueEx(key,"TileWallpaper",0,r.REG_SZ,old_tile)
                user.SystemParametersInfoW(20,0,old_path,3)
                target.unlink(missing_ok=True);raise
            return dict(path=str(target),style=q.get("style","fill"),changed=True)
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))

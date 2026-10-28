"""Per-user Windows wallpaper with a persistent image copy and rollback."""
import ctypes
from ctypes import wintypes as w
import os
from pathlib import Path
import stat
import threading
import uuid

STYLES={"fill":"10","fit":"6","stretch":"2","center":"0","span":"22"}


class NativeWallpaper:
    def __init__(self):
        import winreg
        self.r=winreg;self.user=ctypes.WinDLL("user32",use_last_error=True)
        self.user.SystemParametersInfoW.argtypes=[w.UINT,w.UINT,w.LPWSTR,w.UINT];self.user.SystemParametersInfoW.restype=w.BOOL
    def snapshot(self):
        r=self.r;result={}
        with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_READ) as key:
            for name in ("WallPaper","WallpaperStyle","TileWallpaper"):
                try:result[name]=r.QueryValueEx(key,name)
                except FileNotFoundError:result[name]=None
        return result
    def image(self,path):
        if not self.user.SystemParametersInfoW(20,0,str(path),3):raise ctypes.WinError(ctypes.get_last_error())
    def apply(self,path,style):
        r=self.r
        with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_SET_VALUE) as key:
            r.SetValueEx(key,"WallpaperStyle",0,r.REG_SZ,STYLES[style]);r.SetValueEx(key,"TileWallpaper",0,r.REG_SZ,"0")
        self.image(path)
    def restore(self,snapshot):
        r=self.r
        self.image((snapshot.get("WallPaper") or ("",r.REG_SZ))[0])
        with r.OpenKey(r.HKEY_CURRENT_USER,r"Control Panel\Desktop",0,r.KEY_SET_VALUE) as key:
            for name,record in snapshot.items():
                if record is None:
                    try:r.DeleteValue(key,name)
                    except FileNotFoundError:pass
                else:r.SetValueEx(key,name,0,record[1],record[0])


class Wallpaper:
    def __init__(self,directory,backend=None):self.directory=Path(directory);self.backend=backend;self.lock=threading.RLock()
    def request(self,q,error):
        try:
            with self.lock:return self._request(q)
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))
    def _request(self,q):
        if self.backend is None:
            if os.name!="nt":raise ValueError("壁纸设置仅支持 Windows")
            self.backend=NativeWallpaper()
        if q.get("action","get")=="get":
            current=self.backend.snapshot()
            return dict(path=(current.get("WallPaper") or ("",))[0],style=next((k for k,v in STYLES.items() if v==(current.get("WallpaperStyle") or ("",))[0]),"fill"))
        style=q.get("style","fill")
        if q.get("action")!="set" or style not in STYLES:raise ValueError("壁纸操作或显示方式无效")
        path=Path(q.get("path","")).expanduser()
        if not path.is_absolute() or str(path).startswith("\\\\"):raise ValueError("请选择电脑上的本地图片")
        if path.suffix.lower() not in (".jpg",".jpeg",".png",".bmp"):raise ValueError("请选择 JPEG、PNG 或 BMP 图片")
        info=path.lstat()
        if not stat.S_ISREG(info.st_mode) or getattr(info,"st_file_attributes",0)&0x400 or stat.S_ISLNK(info.st_mode) or not 0<info.st_size<=50*1024**2:
            raise ValueError("壁纸须为不超过 50 MiB 的普通图片文件")
        self.directory.mkdir(parents=True,exist_ok=True)
        directory=self.directory.lstat()
        if stat.S_ISLNK(directory.st_mode) or getattr(directory,"st_file_attributes",0)&0x400:raise ValueError("壁纸保存目录不能是链接")
        target=self.directory/(uuid.uuid4().hex+path.suffix.lower())
        try:
            with path.open("rb") as source,target.open("xb") as out:
                actual=os.fstat(source.fileno())
                if (actual.st_dev,actual.st_ino,actual.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("图片已变化，请刷新后重试")
                header=source.read(12)
                if not (header.startswith(b"\xff\xd8\xff") or header.startswith(b"\x89PNG\r\n\x1a\n") or header.startswith(b"BM")):raise ValueError("图片内容不是 JPEG、PNG 或 BMP")
                source.seek(0);copied=0
                while True:
                    block=source.read(1024*1024)
                    if not block:break
                    copied+=len(block)
                    if copied>info.st_size:raise ValueError("图片在复制时已变化")
                    out.write(block)
                if copied!=info.st_size:raise ValueError("图片在复制时已变化")
                out.flush();os.fsync(out.fileno())
            previous=self.backend.snapshot()
            try:self.backend.apply(target,style)
            except Exception as original:
                try:self.backend.restore(previous)
                except Exception as recovery:raise ValueError("壁纸设置失败，旧设置恢复未确认：%s；%s"%(original,recovery)) from original
                raise
        except Exception:
            target.unlink(missing_ok=True);raise
        old=Path((previous.get("WallPaper") or ("",))[0])
        if old.parent==self.directory and len(old.stem)==32:
            try:
                if uuid.UUID(old.stem).hex==old.stem:old.unlink(missing_ok=True)
            except (ValueError,OSError):pass
        return dict(path=str(target),style=style,changed=True)

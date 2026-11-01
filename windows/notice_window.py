"""Native no-activation Windows announcement surface. No hooks or third-party assets."""
import ctypes as c
from ctypes import wintypes as w
import math
import queue
import struct
import threading
import time
import uuid
from announcements import motion_frame,primary_work_area,window_rect

class RectF(c.Structure):_fields_=[('x',c.c_float),('y',c.c_float),('width',c.c_float),('height',c.c_float)]
class SizeF(c.Structure):_fields_=[('width',c.c_float),('height',c.c_float)]

class Surface:
    def __init__(self,width,height,config,scale=1):
        self.width=width;self.height=height;self.config=config;self.scale=scale
        self.gdi=c.WinDLL('gdi32',use_last_error=True);self.user=c.WinDLL('user32',use_last_error=True);self.gp=c.WinDLL('gdiplus')
        self.gdi.CreateCompatibleDC.argtypes=[w.HDC];self.gdi.CreateCompatibleDC.restype=w.HDC
        self.gdi.CreateCompatibleBitmap.argtypes=[w.HDC,c.c_int,c.c_int];self.gdi.CreateCompatibleBitmap.restype=w.HBITMAP
        self.gdi.SelectObject.argtypes=[w.HDC,w.HGDIOBJ];self.gdi.SelectObject.restype=w.HGDIOBJ
        self.gdi.DeleteObject.argtypes=[w.HGDIOBJ];self.gdi.DeleteDC.argtypes=[w.HDC]
        self.gdi.BitBlt.argtypes=[w.HDC,c.c_int,c.c_int,c.c_int,c.c_int,w.HDC,c.c_int,c.c_int,w.DWORD]
        self.user.GetDC.argtypes=[w.HWND];self.user.GetDC.restype=w.HDC;self.user.ReleaseDC.argtypes=[w.HWND,w.HDC]
        ptr=c.c_void_p;f=c.c_float;i=c.c_int
        specs={
            'GdiplusStartup':([c.POINTER(c.c_size_t),ptr,ptr],i),
            'GdiplusShutdown':([c.c_size_t],None),
            'GdipCreateFromHDC':([w.HDC,c.POINTER(ptr)],i),'GdipDeleteGraphics':([ptr],i),
            'GdipGraphicsClear':([ptr,w.DWORD],i),'GdipSetSmoothingMode':([ptr,i],i),'GdipSetTextRenderingHint':([ptr,i],i),
            'GdipCreateSolidFill':([w.DWORD,c.POINTER(ptr)],i),'GdipDeleteBrush':([ptr],i),
            'GdipFillRectangle':([ptr,ptr,f,f,f,f],i),'GdipDrawLine':([ptr,ptr,f,f,f,f],i),
            'GdipCreatePen1':([w.DWORD,f,i,c.POINTER(ptr)],i),'GdipDeletePen':([ptr],i),
            'GdipCreateFontFamilyFromName':([w.LPCWSTR,ptr,c.POINTER(ptr)],i),'GdipGetGenericFontFamilySansSerif':([c.POINTER(ptr)],i),
            'GdipDeleteFontFamily':([ptr],i),'GdipCreateFont':([ptr,f,i,i,c.POINTER(ptr)],i),'GdipDeleteFont':([ptr],i),
            'GdipCreateStringFormat':([i,w.WORD,c.POINTER(ptr)],i),'GdipDeleteStringFormat':([ptr],i),
            'GdipSetStringFormatFlags':([ptr,i],i),'GdipSetClipRect':([ptr,f,f,f,f,i],i),'GdipResetClip':([ptr],i),
            'GdipDrawString':([ptr,w.LPCWSTR,i,ptr,c.POINTER(RectF),ptr,ptr],i),
            'GdipMeasureString':([ptr,w.LPCWSTR,i,ptr,c.POINTER(RectF),ptr,c.POINTER(RectF),c.POINTER(i),c.POINTER(i)],i)}
        for name,(args,result) in specs.items():fn=getattr(self.gp,name);fn.argtypes=args;fn.restype=result
        class Startup(c.Structure):_fields_=[('version',w.UINT),('callback',ptr),('suppress_thread',w.BOOL),('suppress_codecs',w.BOOL)]
        self.token=c.c_size_t();self.check(self.gp.GdiplusStartup(c.byref(self.token),c.byref(Startup(1,None,False,False)),None))
        screen=self.user.GetDC(None)
        try:self.dc=self.gdi.CreateCompatibleDC(screen);self.bitmap=self.gdi.CreateCompatibleBitmap(screen,width,height)
        finally:self.user.ReleaseDC(None,screen)
        if not self.dc or not self.bitmap:raise c.WinError(c.get_last_error())
        self.old=self.gdi.SelectObject(self.dc,self.bitmap);self.graphics=ptr();self.check(self.gp.GdipCreateFromHDC(self.dc,c.byref(self.graphics)))
        self.gp.GdipSetSmoothingMode(self.graphics,4);self.gp.GdipSetTextRenderingHint(self.graphics,3)
        self.family=ptr()
        if self.gp.GdipCreateFontFamilyFromName(config['font'],None,c.byref(self.family)):
            self.check(self.gp.GdipGetGenericFontFamilySansSerif(c.byref(self.family)))
        self.font=ptr();self.size=config['font_size']*scale
        self.check(self.gp.GdipCreateFont(self.family,self.size,1 if config['bold'] else 0,2,c.byref(self.font)))
        self.format=ptr();self.check(self.gp.GdipCreateStringFormat(0,0,c.byref(self.format)))
        self.single=config['motion'] in ('left','right','bounce')
        self.gp.GdipSetStringFormatFlags(self.format,0x1000 if self.single else 0)
        self.text='   '.join(config['text'].splitlines()) if self.single else config['text']
        self.margin=16*scale;self.close_size=40*scale
        self.header=0 if config['mode']=='ticker' else max(48*scale,self.size*1.5)
        prefix_width=self.measure(config['prefix'],1000000)[0] if config['prefix'] else 0
        self.prefix_width=min(prefix_width+self.size*1.6+self.margin,width*.45) if self.header==0 and config['prefix'] else 0
        self.viewport=RectF(self.margin+self.prefix_width,self.header+self.margin/2,
                            max(20,width-self.margin*2-self.prefix_width-(self.close_size if self.header==0 else 0)),max(20,height-self.header-self.margin*1.5))
        self.text_width,self.text_height=self.measure(self.text,1000000 if self.single else self.viewport.width)
    @staticmethod
    def check(status):
        if status:raise OSError('Windows GDI+ rendering failed (%d)'%status)
    @staticmethod
    def color(value):return 0xff000000|int(value[1:],16)
    def measure(self,text,width):
        bounds=RectF();layout=RectF(0,0,width,1000000);fitted=c.c_int();lines=c.c_int()
        self.check(self.gp.GdipMeasureString(self.graphics,text,-1,self.font,c.byref(layout),self.format,c.byref(bounds),c.byref(fitted),c.byref(lines)))
        return max(1,bounds.width),max(self.size*1.25,bounds.height)
    def text_at(self,text,x,y,width,height,color):
        brush=c.c_void_p();self.check(self.gp.GdipCreateSolidFill(self.color(color),c.byref(brush)))
        try:self.check(self.gp.GdipDrawString(self.graphics,text,-1,self.font,c.byref(RectF(x,y,width,height)),self.format,brush))
        finally:self.gp.GdipDeleteBrush(brush)
    def lines(self,segments,color,width=2):
        pen=c.c_void_p();self.check(self.gp.GdipCreatePen1(self.color(color),width*self.scale,2,c.byref(pen)))
        try:
            for segment in segments:self.gp.GdipDrawLine(self.graphics,pen,*segment)
        finally:self.gp.GdipDeletePen(pen)
    def paint(self,elapsed):
        self.gp.GdipGraphicsClear(self.graphics,self.color(self.config['background']))
        s=self.scale;size=self.size;v=self.viewport
        x,y,cycles,alpha=motion_frame(self.config['motion'],v.width,v.height,self.text_width,self.text_height,elapsed,self.config['speed']*s)
        if self.config['prefix']:
            px=self.margin;py=(self.height-size*1.25)/2 if not self.header else (self.header-size*1.25)/2
            # Own vector speaker, matching the blue-prefix announcement idiom.
            ix=px;iy=py+size*.55;z=size*.32
            self.lines([(ix,iy-z/2,ix+z*.5,iy-z/2),(ix+z*.5,iy-z/2,ix+z*1.1,iy-z),
                        (ix+z*1.1,iy-z,ix+z*1.1,iy+z),(ix+z*1.1,iy+z,ix+z*.5,iy+z/2),
                        (ix+z*.5,iy+z/2,ix,iy+z/2),(ix,iy+z/2,ix,iy-z/2),
                        (ix+z*1.45,iy-z*.6,ix+z*1.7,iy),(ix+z*1.7,iy,ix+z*1.45,iy+z*.6)],self.config['accent'])
            self.text_at(self.config['prefix']+'：',px+size*.8,py,max(20,(self.prefix_width-size*.8 if not self.header else self.width-self.close_size-size)),size*1.6,self.config['accent'])
        self.gp.GdipSetClipRect(self.graphics,v.x,v.y,v.width,v.height,0)
        self.text_at(self.text,v.x+x,v.y+y,max(self.text_width+size, v.width) if self.single else v.width,max(self.text_height+size,v.height),self.config['foreground'])
        self.gp.GdipResetClip(self.graphics)
        cx=self.width-self.close_size/2;cy=self.close_size/2;d=6*s
        bg=int(self.config['background'][1:],16)
        close_color='#000000' if ((bg>>16)*.299+((bg>>8)&255)*.587+(bg&255)*.114)>150 else '#FFFFFF'
        self.lines([(cx-d,cy-d,cx+d,cy+d),(cx+d,cy-d,cx-d,cy+d)],close_color)
        return cycles,alpha
    def copy_to(self,dc):self.gdi.BitBlt(dc,0,0,self.width,self.height,self.dc,0,0,0x00cc0020)
    def save_bmp(self,path):
        header=struct.pack('<IiiHHIIiiII',40,self.width,-self.height,1,32,0,self.width*self.height*4,0,0,0,0)
        info=c.create_string_buffer(header);data=c.create_string_buffer(self.width*self.height*4)
        self.gdi.GetDIBits.argtypes=[w.HDC,w.HBITMAP,w.UINT,w.UINT,c.c_void_p,c.c_void_p,w.UINT]
        # A bitmap must not be selected into a DC while GetDIBits reads it.
        self.gdi.SelectObject(self.dc,self.old)
        try:
            if not self.gdi.GetDIBits(self.dc,self.bitmap,0,self.height,data,info,0):raise c.WinError(c.get_last_error())
        finally:self.gdi.SelectObject(self.dc,self.bitmap)
        with open(path,'wb') as out:out.write(struct.pack('<2sIHHI',b'BM',54+len(data),0,0,54)+header+data.raw)
    def close(self):
        self.gp.GdipDeleteStringFormat(self.format);self.gp.GdipDeleteFont(self.font);self.gp.GdipDeleteFontFamily(self.family)
        self.gp.GdipDeleteGraphics(self.graphics);self.gdi.SelectObject(self.dc,self.old);self.gdi.DeleteObject(self.bitmap);self.gdi.DeleteDC(self.dc);self.gp.GdiplusShutdown(self.token)


class NoticeWindow:
    def __init__(self,config,source,emit):self.config=config;self.source=source;self.emit=emit;self.commands=queue.Queue();self.reason='closed';self.failure=None
    def run(self):
        user=c.WinDLL('user32',use_last_error=True);kernel=c.WinDLL('kernel32');gdi=c.WinDLL('gdi32')
        gdi.DeleteObject.argtypes=[w.HGDIOBJ]
        callback=c.WINFUNCTYPE(c.c_ssize_t,w.HWND,w.UINT,w.WPARAM,w.LPARAM)
        class WindowClass(c.Structure):
            _fields_=[('size',w.UINT),('style',w.UINT),('proc',callback),('cextra',c.c_int),('wextra',c.c_int),('instance',w.HINSTANCE),('icon',w.HICON),('cursor',w.HANDLE),('brush',w.HBRUSH),('menu',w.LPCWSTR),('name',w.LPCWSTR),('small',w.HICON)]
        class Paint(c.Structure):_fields_=[('dc',w.HDC),('erase',w.BOOL),('rect',w.RECT),('restore',w.BOOL),('update',w.BOOL),('reserved',c.c_byte*32)]
        spec={'RegisterClassExW':([c.POINTER(WindowClass)],w.WORD),'UnregisterClassW':([w.LPCWSTR,w.HINSTANCE],w.BOOL),
              'CreateWindowExW':([w.DWORD,w.LPCWSTR,w.LPCWSTR,w.DWORD,c.c_int,c.c_int,c.c_int,c.c_int,w.HWND,w.HMENU,w.HINSTANCE,c.c_void_p],w.HWND),
              'DefWindowProcW':([w.HWND,w.UINT,w.WPARAM,w.LPARAM],c.c_ssize_t),'DestroyWindow':([w.HWND],w.BOOL),
              'BeginPaint':([w.HWND,c.POINTER(Paint)],w.HDC),'EndPaint':([w.HWND,c.POINTER(Paint)],w.BOOL),
              'SetWindowPos':([w.HWND,w.HWND,c.c_int,c.c_int,c.c_int,c.c_int,w.UINT],w.BOOL),
              'SetLayeredWindowAttributes':([w.HWND,w.DWORD,w.BYTE,w.DWORD],w.BOOL),'InvalidateRect':([w.HWND,c.c_void_p,w.BOOL],w.BOOL),
              'GetMessageW':([c.POINTER(w.MSG),w.HWND,w.UINT,w.UINT],w.BOOL),'DispatchMessageW':([c.POINTER(w.MSG)],c.c_ssize_t),
              'TranslateMessage':([c.POINTER(w.MSG)],w.BOOL),'SetTimer':([w.HWND,c.c_size_t,w.UINT,c.c_void_p],c.c_size_t),
              'KillTimer':([w.HWND,c.c_size_t],w.BOOL),'SetWindowRgn':([w.HWND,w.HRGN,w.BOOL],c.c_int),
              'GetAsyncKeyState':([c.c_int],c.c_short)}
        for name,(args,result) in spec.items():fn=getattr(user,name);fn.argtypes=args;fn.restype=result
        kernel.GetModuleHandleW.argtypes=[w.LPCWSTR];kernel.GetModuleHandleW.restype=w.HMODULE
        try:user.SetProcessDpiAwarenessContext(c.c_void_p(-4))
        except AttributeError:
            try:user.SetProcessDPIAware()
            except AttributeError:pass
        area=primary_work_area();s=area['dpi']/96
        bounds=window_rect(self.config,area)
        x,y,width,height=(bounds[k] for k in ('x','y','width','height'))
        surface=Surface(width,height,self.config,s)
        start=time.monotonic();paused=None;paused_total=0.;last_report=0.;state='playing';cycles=0
        instance=kernel.GetModuleHandleW(None);name='LanCmdAnnouncement_'+uuid.uuid4().hex
        def elapsed():return ((paused if paused is not None else time.monotonic())-start-paused_total)
        def end(hwnd,reason):self.reason=reason;user.DestroyWindow(hwnd)
        @callback
        def proc(hwnd,message,wp,lp):
            nonlocal paused,paused_total,last_report,state,cycles
            try:
                if message==0x21:return 3 # MA_NOACTIVATE, preserve the user's foreground application.
                if message==0xf:
                    paint=Paint();dc=user.BeginPaint(hwnd,c.byref(paint))
                    try:surface.copy_to(dc)
                    finally:user.EndPaint(hwnd,c.byref(paint))
                    return 0
                if message==0x14:return 1
                if message==0x201:
                    px=lp&0xffff;py=(lp>>16)&0xffff
                    if px>=width-surface.close_size and py<=surface.close_size:end(hwnd,'local_close')
                    return 0
                if message==0x10:end(hwnd,'closed');return 0
                if message==2:user.KillTimer(hwnd,1);user.PostQuitMessage(0);return 0
                if message in (0x7e,0x2e0):end(hwnd,'display_changed');return 0
                if message==0x113:
                    try:
                        while True:
                            command=self.commands.get_nowait()
                            if command in ('stop','eof'):end(hwnd,command);return 0
                            if command=='pause' and paused is None:paused=time.monotonic();state='paused';last_report=0
                            if command=='resume' and paused is not None:paused_total+=time.monotonic()-paused;paused=None;state='playing';last_report=0
                    except queue.Empty:pass
                    if user.GetAsyncKeyState(0x1b)&0x8000:end(hwnd,'escape');return 0
                    now=elapsed()
                    if self.config['duration'] and now>=self.config['duration']:end(hwnd,'duration');return 0
                    cycles,alpha=surface.paint(now)
                    if self.config['repeats'] and cycles>=self.config['repeats']:end(hwnd,'repeats');return 0
                    user.SetLayeredWindowAttributes(hwnd,0,max(25,int(255*self.config['opacity']/100*alpha)),2)
                    user.InvalidateRect(hwnd,None,False)
                    if time.monotonic()-last_report>=1:
                        self.emit(dict(status=state,elapsed=round(now,2),cycles=cycles));last_report=time.monotonic()
                    return 0
                return user.DefWindowProcW(hwnd,message,wp,lp)
            except Exception as exc:
                self.failure=str(exc);user.DestroyWindow(hwnd);return 0
        cls=WindowClass(c.sizeof(WindowClass),0,proc,0,0,instance,None,None,None,None,name,None)
        hwnd=None;registered=False
        try:
            if not user.RegisterClassExW(c.byref(cls)):raise c.WinError(c.get_last_error())
            registered=True
            hwnd=user.CreateWindowExW(0x08080088,name,self.config['prefix'] or '桌面公告',0x80000000,x,y,width,height,None,None,instance,None)
            if not hwnd:raise c.WinError(c.get_last_error())
            if self.config['shape']!='rectangle':
                radius=height if self.config['shape']=='pill' else int(24*s)
                gdi.CreateRoundRectRgn.argtypes=[c.c_int]*6;gdi.CreateRoundRectRgn.restype=w.HRGN
                region=gdi.CreateRoundRectRgn(0,0,width+1,height+1,radius,radius)
                if not user.SetWindowRgn(hwnd,region,True):gdi.DeleteObject(region)
            surface.paint(0);user.SetLayeredWindowAttributes(hwnd,0,int(255*self.config['opacity']/100),2)
            user.SetWindowPos(hwnd,c.c_void_p(-1),x,y,width,height,0x50)
            if not user.SetTimer(hwnd,1,33,None):raise c.WinError(c.get_last_error())
            def controls():
                try:
                    for line in self.source:
                        if len(line)>1024:continue
                        try:
                            action=__import__('json').loads(line).get('action')
                            if action in ('stop','pause','resume'):self.commands.put(action)
                        except (ValueError,AttributeError):pass
                finally:self.commands.put('eof')
            threading.Thread(target=controls,daemon=True,name='announcement-controls').start()
            self.emit(dict(status='playing',elapsed=0,cycles=0,x=x,y=y,width=width,height=height))
            msg=w.MSG()
            while True:
                result=user.GetMessageW(c.byref(msg),None,0,0)
                if result<=0:break
                user.TranslateMessage(c.byref(msg));user.DispatchMessageW(c.byref(msg))
            if result<0:raise c.WinError(c.get_last_error())
            if self.failure:raise OSError(self.failure)
            self.emit(dict(status='completed' if self.reason in ('duration','repeats') else 'cancelled',reason=self.reason,elapsed=round(elapsed(),2),cycles=cycles))
            return 0
        finally:
            if hwnd:user.DestroyWindow(hwnd)
            surface.close()
            if registered:user.UnregisterClassW(name,instance)

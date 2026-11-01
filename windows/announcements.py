"""Explicit desktop announcements, with owned controls and a separate native UI process."""
import atexit
import copy
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import uuid

MODES=('ticker','popup','sticky')
MOTIONS=('left','right','up','down','bounce','static','fade')
FONTS=('Microsoft YaHei UI','Microsoft YaHei','SimHei','SimSun','Segoe UI')
DEFAULT=dict(text='',prefix='消息提醒',mode='ticker',motion='left',position='top',shape='rectangle',
             background='#081424',foreground='#FFFFFF',accent='#56B5F1',font='Microsoft YaHei UI',
             font_size=28,bold=True,speed=90,opacity=92,width=100,duration=0,repeats=5,placement='preset',rect=None)


def validate(value):
    if not isinstance(value,dict):raise ValueError('公告格式无效')
    result=dict(DEFAULT,**{k:v for k,v in value.items() if k in DEFAULT})
    for key,limit in (('text',2000),('prefix',30)):
        text=result[key]
        if not isinstance(text,str) or len(text)>limit or '\0' in text or any(ord(c)<32 and c not in '\r\n\t' for c in text):raise ValueError('公告文字或前缀无效')
    if not result['text'].strip():raise ValueError('请输入公告内容')
    for key,choices in (('mode',MODES),('motion',MOTIONS),('position',('top','center','bottom')),('shape',('rectangle','rounded','pill')),('font',FONTS)):
        if result[key] not in choices:raise ValueError('公告样式选项无效：'+key)
    for key,lower,upper in (('font_size',16,96),('speed',20,400),('opacity',30,100),('width',30,100),('duration',0,3600),('repeats',0,100)):
        if type(result[key])is not int or not lower<=result[key]<=upper:raise ValueError('公告数值超出范围：'+key)
    if type(result['bold'])is not bool:raise ValueError('粗体设置无效')
    if result['placement'] not in ('preset','custom'):raise ValueError('公告布局无效')
    if result['placement']=='custom':
        rect=result['rect']
        if (not isinstance(rect,dict) or set(rect)!=set(('x','y','width','height')) or
                any(type(rect[k])is not int for k in rect) or not 10<=rect['width']<=1000 or
                not 10<=rect['height']<=1000 or not 0<=rect['x']<=1000-rect['width'] or
                not 0<=rect['y']<=1000-rect['height']):raise ValueError('公告位置和尺寸须在电脑可用区域内')
        result['rect']=dict(rect)
    else:result['rect']=None
    for key in ('background','foreground','accent'):
        if not isinstance(result[key],str) or not re.fullmatch(r'#[0-9a-fA-F]{6}',result[key]):raise ValueError('颜色须为 #RRGGBB')
        result[key]=result[key].upper()
    return result


def window_rect(config,area):
    """Integer permille of the primary work area; mirrored by the phone editor."""
    width,height,dpi=area['width'],area['height'],area['dpi']
    if (any(type(area[k])is not int for k in ('x','y','width','height','dpi')) or
            not 1<=width<=32768 or not 1<=height<=32768 or not 48<=dpi<=768 or
            abs(area['x'])>1_000_000 or abs(area['y'])>1_000_000):raise ValueError('电脑可用显示区域无效')
    s=dpi/96
    if config['placement']=='custom':
        r=config['rect']
        width=min(width,max(int(160*s),(area['width']*r['width']+500)//1000))
        height=min(height,max(int(64*s),(area['height']*r['height']+500)//1000))
        x=area['x']+min(area['width']-width,(area['width']*r['x']+500)//1000)
        y=area['y']+min(area['height']-height,(area['height']*r['y']+500)//1000)
    else:
        width=max(240,int(area['width']*config['width']/100))
        if config['mode']=='ticker':height=min(int(max(64,config['font_size']*2.2)*s),area['height']//2)
        elif config['mode']=='popup':width=min(width,int(area['width']*.8));height=min(int(360*s),int(area['height']*.6))
        else:width=min(width,int(480*s));height=min(int(240*s),int(area['height']*.45))
        width=min(width,area['width']);height=min(area['height'],max(64,height))
        x=area['x']+(area['width']-width)//2
        y=area['y'] if config['position']=='top' else area['y']+(area['height']-height)//2 if config['position']=='center' else area['y']+area['height']-height
        if config['mode']=='sticky':x=area['x']+max(0,area['width']-width-int(12*s));y=max(area['y'],min(y,area['y']+area['height']-height-int(12*s)))
    return dict(x=x,y=y,width=width,height=height)


def primary_work_area():
    """Only called in private helpers; leaves the receiver DPI context alone."""
    import ctypes as c
    from ctypes import wintypes as w
    user=c.WinDLL('user32',use_last_error=True)
    try:user.SetProcessDpiAwarenessContext(c.c_void_p(-4))
    except AttributeError:
        try:user.SetProcessDPIAware()
        except AttributeError:pass
    user.SystemParametersInfoW.argtypes=[w.UINT,w.UINT,c.c_void_p,w.UINT]
    rect=w.RECT()
    if not user.SystemParametersInfoW(48,0,c.byref(rect),0):raise c.WinError(c.get_last_error())
    dpi=user.GetDpiForSystem() if hasattr(user,'GetDpiForSystem') else 96
    return dict(x=rect.left,y=rect.top,width=rect.right-rect.left,height=rect.bottom-rect.top,dpi=dpi)


def motion_frame(motion,width,height,text_width,text_height,elapsed,speed):
    """Pixel positions based on elapsed time, never frame count or wall clock."""
    elapsed=max(0,elapsed);center_y=(height-text_height)/2
    if motion in ('left','right'):
        distance=width+text_width+32;travel=elapsed*speed;cycle=int(travel/distance);offset=travel%distance
        return ((width-offset) if motion=='left' else (-text_width+offset),center_y,cycle,1.)
    if motion in ('up','down'):
        distance=height+text_height+24;travel=elapsed*speed;cycle=int(travel/distance);offset=travel%distance
        return (max(0,(width-text_width)/2),(height-offset) if motion=='up' else (-text_height+offset),cycle,1.)
    if motion=='bounce':
        span=max(1,abs(width-text_width));phase=elapsed*speed/(2*span);fraction=phase%1
        return ((width-text_width)*(1-abs(2*fraction-1)),center_y,int(phase),1.)
    if motion=='fade':
        phase=elapsed%5;alpha=min(1.,phase/.4,(5-phase)/.4)
        return (max(0,(width-text_width)/2),center_y,int(elapsed/5),alpha)
    return (max(0,(width-text_width)/2),center_y,int(elapsed/5),1.)


class WorkerProcess:
    def layout(self):
        command=([sys.executable,'--announcement-layout'] if getattr(sys,'frozen',False) else
                 [sys.executable,'-I','-X','utf8',str(Path(__file__).resolve()),'--layout'])
        try:
            result=subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                                  text=True,encoding='utf-8',timeout=8,creationflags=0x08000000 if os.name=='nt' else 0)
        except subprocess.TimeoutExpired as exc:raise ValueError('读取电脑可用显示区域超时，请稍后重试') from exc
        if result.returncode:raise ValueError('读取电脑可用显示区域失败，请完整更新 Windows 包')
        area=json.loads(result.stdout)
        window_rect(dict(DEFAULT),area)
        return area
    def run(self,config,report,commands,cancel):
        if os.name!='nt':raise ValueError('滚动公告仅支持 Windows')
        if getattr(sys,'frozen',False):command=[sys.executable,'--announcement-worker']
        else:
            exe=Path(sys.executable)
            if exe.name.casefold()=='pythonw.exe':exe=exe.with_name('python.exe')
            command=[str(exe),'-I','-X','utf8',str(Path(__file__).resolve()),'--worker']
        process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                                 text=True,encoding='utf-8',creationflags=0x08000000)
        incoming=queue.Queue()
        def read():
            try:
                for line in process.stdout:
                    if len(line)>32768:raise ValueError('公告状态过大')
                    incoming.put(json.loads(line))
            except (OSError,ValueError) as exc:incoming.put(dict(status='failed',error=str(exc)))
            finally:incoming.put(None)
        try:
            process.stdin.write(json.dumps(config,ensure_ascii=True)+'\n');process.stdin.flush()
            threading.Thread(target=read,daemon=True,name='announcement-status').start()
            ready=False;ended=False;started=time.monotonic();stopping=None
            while True:
                if cancel.is_set() and stopping is None:
                    commands.put('stop');stopping=time.monotonic()
                try:
                    while True:
                        command=commands.get_nowait()
                        process.stdin.write(json.dumps(dict(action=command))+'\n');process.stdin.flush()
                except queue.Empty:pass
                try:item=incoming.get(timeout=.1)
                except queue.Empty:
                    if not ready and time.monotonic()-started>12:raise ValueError('Windows 公告窗口启动超时')
                    if stopping is not None and time.monotonic()-stopping>3:raise ValueError('公告窗口未及时退出，已结束其专用进程')
                    continue
                if item is None:
                    if not ended:raise ValueError('公告窗口意外退出，请完整更新 Windows 包')
                    break
                ready=True
                report(item)
                if item.get('status') in ('completed','cancelled','failed'):ended=True
            if process.wait(3)!=0 and not ended:raise ValueError('公告进程异常退出')
        finally:
            try:process.stdin.close()
            except OSError:pass
            try:process.wait(3)
            except subprocess.TimeoutExpired:process.kill();process.wait(3)
            process.stdout.close()


class Announcements:
    def __init__(self,backend=None):
        self.backend=backend or WorkerProcess();self.lock=threading.RLock();self.jobs={};self.current=None;self.closed=False
        atexit.register(self.close)
    @staticmethod
    def public(job):return copy.deepcopy(job['data'])
    def request(self,q,state,error):
        try:
            owner=state.client_id
            if not owner:raise ValueError('请先连接电脑')
            action=q.get('action','status')
            if action=='layout':return dict(area=self.backend.layout())
            with self.lock:
                if action=='status':return dict(job=self.public(self.current) if self.current and self.current['owner']==owner else None,
                                                occupied=bool(self.current and self.current['owner']!=owner and not self.current['done'].is_set()))
                if action in ('stop','pause','resume'):
                    job=self.jobs.get(q.get('job_id'))
                    if not job or job['owner']!=owner:raise ValueError('公告不存在或属于其他手机')
                    if job['done'].is_set():return dict(job=self.public(job))
                    if action=='stop':job['cancel'].set();job['data']['status']='stopping'
                    elif job['data']['status'] in ('playing','paused'):job['commands'].put(action)
                    else:raise ValueError('请等待公告开始后再暂停或继续')
                    return dict(job=self.public(job))
                if action!='show':raise ValueError('无效的公告操作')
                config=validate(q.get('config'))
                previous=self.current
                if previous and not previous['done'].is_set():
                    if previous['owner']!=owner:raise ValueError('其他手机正在显示公告，请先在电脑关闭')
                    previous['cancel'].set()
                if self.closed:raise ValueError('接收端正在退出')
                job=dict(owner=owner,cancel=threading.Event(),done=threading.Event(),commands=queue.Queue(),
                         data=dict(id=uuid.uuid4().hex,status='starting',config=config,created=time.time(),cycles=0,elapsed=0))
                self.jobs[job['data']['id']]=job;self.current=job
                for key,value in list(self.jobs.items()):
                    if len(self.jobs)<=20:break
                    if value['done'].is_set():self.jobs.pop(key)
                threading.Thread(target=self._run,args=(job,previous),daemon=True,name='announcement').start()
                return dict(job=self.public(job))
        except (ValueError,TypeError,KeyError,OSError) as exc:raise error(str(exc))
    def _run(self,job,previous):
        try:
            if previous and not previous['done'].wait(8):raise ValueError('旧公告仍在退出，请稍后重试')
            if job['cancel'].is_set():job['data']['status']='cancelled';return
            def report(value):
                with self.lock:
                    for key in ('status','elapsed','cycles','error','reason','x','y','width','height'):
                        if key in value:job['data'][key]=value[key]
            self.backend.run(job['data']['config'],report,job['commands'],job['cancel'])
            with self.lock:
                if job['data']['status'] not in ('completed','cancelled','failed'):job['data']['status']='cancelled'
        except Exception as exc:
            with self.lock:job['data'].update(status='failed',error=str(exc)[:1000])
        finally:
            job['data']['finished']=time.time();job['done'].set()
    def close(self):
        with self.lock:
            self.closed=True
            for job in self.jobs.values():job['cancel'].set()


def _worker_streams():
    # A GUI-subsystem frozen receiver can have sys.stdin/stdout unset.
    if sys.stdin is None or sys.stdout is None:
        import ctypes,msvcrt
        from ctypes import wintypes as w
        kernel=ctypes.WinDLL('kernel32');kernel.GetStdHandle.argtypes=[w.DWORD];kernel.GetStdHandle.restype=w.HANDLE
        if sys.stdin is None:sys.stdin=os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-10&0xffffffff),os.O_RDONLY),'r',encoding='utf-8')
        if sys.stdout is None:sys.stdout=os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-11&0xffffffff),os.O_WRONLY),'w',encoding='utf-8')


def layout_main():
    _worker_streams()
    try:sys.stdout.write(json.dumps(primary_work_area())+'\n');sys.stdout.flush();return 0
    except Exception:return 1


def worker_main():
    _worker_streams()
    def emit(value):sys.stdout.write(json.dumps(value,ensure_ascii=True)+'\n');sys.stdout.flush()
    try:
        config=validate(json.loads(sys.stdin.readline()))
        sys.path.insert(0,str(Path(__file__).resolve().parent))
        from notice_window import NoticeWindow
        return NoticeWindow(config,sys.stdin,emit).run()
    except Exception as exc:emit(dict(status='failed',error=str(exc)[:1000]));return 1

if __name__=='__main__':sys.exit(layout_main() if '--layout' in sys.argv else worker_main())

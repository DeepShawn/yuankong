"""Per-phone Windows-side defaults for command and discrete operation delays.

Queries, real-time input, transfer chunks, authentication, recovery and cancellation
never enter the queue. Pending payloads (including secrets) exist only in memory.
"""
import atexit
import copy
import json
import os
from pathlib import Path
import threading
import time
from types import SimpleNamespace
import uuid


def category(command,q):
    action=q.get('action','')
    if command in ('exec','scripts'):
        return 'command' if command=='exec' or action=='run' else None
    if command=='commands':
        # The command-list editor specifies each item's delay explicitly.
        return 'command' if action=='add' and not any('delay' in i for i in q.get('items',[]) if isinstance(i,dict)) else None
    if command in ('put','rm','mkdir','rename','lock','power','kill','message'):return 'operation'
    choices={
        'files':{'open','extract','mtime','text_write','text_create'},
        'file_jobs':{'create'},'placeholders':{'create'},'apps':{'activate','minimize','close'},
        'browser':{'open'},'registry':{'set','delete_value','create_key','delete_key'},'wallpaper':{'set'},
        'devices':{'policy','eject','force_disconnect'},'privacy_devices':{'set_enabled','mute'},
        'app_locks':{'lock'},'file_locks':{'lock'},'app_freeze':{'freeze','close','submit'},
        'tasks':{'terminate'},'recordings':{'start'},'announcements':{'show'},
        'startup_network':{'configure','apply'},'network':{'submit'},'clip':{'set'}}
    if action not in choices.get(command,set()):return None
    if command=='privacy_devices' and (q.get('enabled') is True or q.get('muted') is False):return None
    if command=='app_freeze' and action=='submit' and q.get('operation') not in ('freeze','close'):return None
    # Restoring a device / cancelling the hotspot timer must remain available.
    if command=='network' and q.get('operation')=='cancel_restart':return None
    return 'operation'


class GlobalDelays:
    def __init__(self,path,execute):
        self.path=Path(path);self.execute=execute;self.lock=threading.RLock();self.jobs={};self.closed=False
        self.slots=threading.BoundedSemaphore(4);self.load_error=''
        try:self.settings=self._load()
        except (OSError,ValueError) as exc:self.settings={};self.load_error=str(exc)
        atexit.register(self.close)
    def _load(self):
        if not self.path.exists():return {}
        data=json.loads(self.path.read_text(encoding='utf-8'))
        if not isinstance(data,dict):raise ValueError('全局延迟设置损坏，请保留原文件后检查')
        for owner,profile in data.items():
            if not isinstance(owner,str) or not isinstance(profile,dict) or any(type(profile.get(k))is not int or not 0<=profile[k]<=86400 for k in ('command','operation')):
                raise ValueError('全局延迟设置无效')
        return data
    def _save(self,data):
        self.path.parent.mkdir(parents=True,exist_ok=True);temp=self.path.with_suffix('.tmp')
        with temp.open('w',encoding='utf-8') as out:json.dump(data,out);out.flush();os.fsync(out.fileno())
        os.replace(temp,self.path)
    @staticmethod
    def owner(state):
        owner=getattr(state,'client_id','')
        if not isinstance(owner,str) or not owner or len(owner)>256:raise ValueError('连接身份无效')
        return owner
    @staticmethod
    def public(job,result=False):
        value={k:v for k,v in job['data'].items() if result or k!='result'}
        value=copy.deepcopy(value)
        value['remaining']=max(0,math_ceil(job['deadline']-time.monotonic())) if value['status']=='waiting' else 0
        return value
    def intercept(self,command,q,state,error):
        try:
            kind=category(command,q)
            if kind is None:return None
            owner=self.owner(state)
            with self.lock:
                if self.load_error:raise ValueError('全局延迟设置损坏，请在全局延迟页面重新保存设置；未执行操作')
                seconds=q.get('delay_override',self.settings.get(owner,{}).get(kind,0))
                if type(seconds)is not int or not 0<=seconds<=86400:raise ValueError('延迟秒数须为 0–86400 的整数')
                if seconds==0:return None
                if self.closed or sum(not j['done'].is_set() for j in self.jobs.values())>=50:raise ValueError('全局延迟任务已满，请等待或取消')
                params=copy.deepcopy({k:v for k,v in q.items() if k not in ('id','cmd','delay_override')})
                if len(json.dumps(params))>4*1024*1024:raise ValueError('延迟操作内容超过 4 MB')
                guard=None
                if params.get('path') and (command in ('rm','rename','scripts','file_locks','app_locks') or command=='files' and params.get('action') in ('open','mtime','extract')):
                    from file_jobs import file_token,snapshot
                    try:guard=file_token(snapshot(Path(params['path'])))
                    except FileNotFoundError:pass
                ident=uuid.uuid4().hex
                job=dict(data=dict(id=ident,command=command,action=str(params.get('action',''))[:40],kind=kind,status='waiting',created=time.time(),due=time.time()+seconds),
                         owner=owner,params=params,guard=guard,deadline=time.monotonic()+seconds,done=threading.Event(),cancel=threading.Event(),
                         state=SimpleNamespace(client_id=owner,peer=getattr(state,'peer',''),authorized=True,remote_sessions={}))
                self.jobs[ident]=job
                for key,old in list(self.jobs.items()):
                    if len(self.jobs)<=100:break
                    if old['done'].is_set():self.jobs.pop(key)
                threading.Thread(target=self._run,args=(job,),daemon=True,name='global-delay').start()
                return dict(deferred=True,job=self.public(job))
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))
    def _run(self,job):
        acquired=False
        try:
            if job['cancel'].wait(max(0,job['deadline']-time.monotonic())):return
            while not job['cancel'].is_set():
                acquired=self.slots.acquire(timeout=.1)
                if acquired:break
            with self.lock:
                if job['cancel'].is_set():return
                job['data']['status']='running'
            if job['guard']:
                from file_jobs import file_token,snapshot
                if file_token(snapshot(Path(job['params']['path'])))!=job['guard']:raise ValueError('文件在等待期间已变化，未执行；请刷新后重试')
            result=self.execute(job['data']['command'],copy.deepcopy(job['params']),job['state'])
            with self.lock:job['data'].update(status='completed',result=result)
        except Exception as exc:
            with self.lock:job['data'].update(status='failed',error=str(exc)[:2000])
        finally:
            if acquired:self.slots.release()
            with self.lock:
                if job['data']['status']=='waiting':job['data']['status']='cancelled'
                job['data']['finished']=time.time();job['params'].clear();job['done'].set()
    def request(self,q,state,error):
        try:
            owner=self.owner(state);action=q.get('action','list')
            with self.lock:
                if action=='settings':return dict(settings=dict({'command':0,'operation':0},**self.settings.get(owner,{})),warning=self.load_error)
                if action=='configure':
                    values={key:q.get(key) for key in ('command','operation')}
                    if any(type(v)is not int or not 0<=v<=86400 for v in values.values()):raise ValueError('请输入 0–86400 的整数秒')
                    if self.load_error and self.path.exists():
                        import shutil
                        shutil.copy2(self.path,self.path.with_suffix('.corrupt-'+uuid.uuid4().hex+'.json'))
                    data=dict(self.settings);data[owner]=values;self._save(data);self.settings=data;self.load_error=''
                    return dict(settings=values)
                if action=='list':return dict(jobs=[self.public(j) for j in reversed(list(self.jobs.values())) if j['owner']==owner][:50])
                if action not in ('get','cancel'):raise ValueError('无效的全局延迟操作')
                job=self.jobs.get(q.get('job_id'))
                if not job or job['owner']!=owner:raise ValueError('延迟任务不存在或属于其他手机')
                if action=='cancel':
                    if job['data']['status']=='waiting':job['cancel'].set();job['data']['status']='cancelled'
                    elif job['data']['status'] not in ('cancelled','completed','failed'):raise ValueError('已开始执行，不能撤销；请使用相应功能的停止操作')
                return dict(job=self.public(job,True))
        except (OSError,ValueError,TypeError) as exc:raise error(str(exc))
    def busy(self):
        with self.lock:return any(not j['done'].is_set() for j in self.jobs.values())
    def stop_for_device(self,device):
        # File schedule performs conservative device/path handling for this queue too.
        roots=[str(p).replace('/','\\').rstrip('\\').casefold()+'\\' for p in device.get('paths',[]) if p]
        with self.lock:
            pending=[j for j in self.jobs.values() if not j['done'].is_set()]
            def paths(value):
                if isinstance(value,dict):
                    for key,item in value.items():
                        if key in ('path','to','destination') and isinstance(item,str):yield item.replace('/','\\').casefold()
                        elif isinstance(item,(dict,list)):yield from paths(item)
                elif isinstance(value,list):
                    for item in value:yield from paths(item)
            if not roots and any(list(paths(j['params'])) for j in pending):raise ValueError('设备路径未知，请先取消全局延迟文件任务')
            matches=[j for j in pending if any(v==r.rstrip('\\') or v.startswith(r) for r in roots for v in paths(j['params']))]
            for job in matches:
                if job['data']['status']=='waiting':job['cancel'].set()
        for job in matches:
            if not job['done'].wait(5):raise ValueError('全局延迟文件操作正在执行，请稍后再断开设备')
    def close(self):
        with self.lock:
            self.closed=True
            for job in self.jobs.values():
                if job['data']['status']=='waiting':job['cancel'].set()


def math_ceil(value):return int(__import__('math').ceil(value))

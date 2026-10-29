"""Asynchronous device management; helper credentials use stdin, never argv."""
import copy
from contextlib import contextmanager
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid

from native_wifi import NativeWifi, profile_xml

NETWORK_CHANGE_LOCK=threading.RLock()
AREAS=('hotspot','wifi','bluetooth','adapters')
OPERATIONS={
    'hotspot':{'start','stop','restart','configure'},
    'wifi':{'connect','disconnect','forget','radio'},
    'bluetooth':{'pair','unpair','radio'},
    'adapters':{'enable','disable','dns','renew'},
}
SECRET_KEYS={'password','passphrase','pin','keymaterial','key_material'}


@contextmanager
def network_change():
    if not NETWORK_CHANGE_LOCK.acquire(timeout=1):raise ValueError('其他网络修改正在执行，请稍后重试')
    try:yield
    finally:NETWORK_CHANGE_LOCK.release()


def clean(value,secrets=()):
    if isinstance(value,dict):return {k:clean(v,secrets) for k,v in value.items() if k.lower() not in SECRET_KEYS}
    if isinstance(value,list):return [clean(v,secrets) for v in value]
    if isinstance(value,str):
        for secret in secrets:
            if secret:value=value.replace(secret,'[已隐藏]')
    return value


def text(value,name,limit=256,empty=False):
    if not isinstance(value,str) or '\0' in value or len(value)>limit or (not empty and not value):raise ValueError(name+'无效')
    return value


def validate(area,operation,params):
    if area not in AREAS or operation not in OPERATIONS[area] or not isinstance(params,dict):raise ValueError('无效的网络操作')
    p={}
    if operation=='radio':
        p=dict(name=text(params.get('name'),'无线设备名称'),enabled=params.get('enabled'))
        if type(p['enabled']) is not bool:raise ValueError('无线开关必须明确选择开启或关闭')
    elif area=='hotspot' and operation=='configure':
        p=dict(name=text(params.get('name'),'热点名称',32),password=text(params.get('password',''),'热点密码',63,True),band=params.get('band','auto'))
        if not 1<=len(p['name'].encode('utf-8'))<=32:raise ValueError('热点名称的 UTF-8 长度必须为 1–32 字节')
        if p['password'] and (len(p['password'])<8 or any(ord(c)<32 or ord(c)>126 for c in p['password'])):raise ValueError('热点密码须为 8–63 个可打印 ASCII 字符；留空沿用原密码')
        if p['band'] not in ('auto','2.4','5'):raise ValueError('热点频段无效')
    elif area=='wifi':
        p['interface']=str(uuid.UUID(text(params.get('interface'),'Wi-Fi 网卡')))
        if operation in ('connect','forget'):p['profile']=text(params.get('profile',''),'Wi-Fi 配置名称',255,operation=='connect')
        if operation=='connect':
            p['password']=text(params.get('password',''),'Wi-Fi 密码',63,True)
            if not p['profile'] or p['password']:
                p.update(name=text(params.get('name'),'Wi-Fi 名称',255),ssid_hex=text(params.get('ssid_hex'),'SSID',64),auth=params.get('auth',7),hidden=params.get('hidden',False))
                if type(p['auth']) is not int or type(p['hidden']) is not bool:raise ValueError('Wi-Fi 认证参数无效')
                profile_xml(p['ssid_hex'],p['name'],p['auth'],p['password'],p['hidden'])
    elif area=='bluetooth':p['id']=text(params.get('id'),'蓝牙设备编号',2048)
    elif area=='adapters':
        p['adapter']=str(uuid.UUID(text(params.get('adapter'),'网卡编号')))
        if operation=='dns':
            servers=params.get('servers')
            if not isinstance(servers,list) or len(servers)>4 or any(not isinstance(x,str) for x in servers):raise ValueError('DNS 最多填写四个 IP 地址')
            p['servers']=[str(ipaddress.ip_address(x)) for x in servers]
    return p


class WindowsNetwork:
    def __init__(self):
        self.wifi=None

    def run(self,request,challenge=None):
        script=Path(__file__).with_name('network_winrt.ps1').read_text(encoding='utf-8')
        executable=Path(os.environ.get('SystemRoot',r'C:\Windows'))/'System32/WindowsPowerShell/v1.0/powershell.exe'
        process=subprocess.Popen([str(executable),'-NoLogo','-NoProfile','-NonInteractive','-Command',script],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace',
            creationflags=0x08000000,close_fds=True)
        lines=queue.Queue();errors=[]
        def read():
            try:
                for line in process.stdout:lines.put(line)
            finally:lines.put(None)
        def stderr():
            for line in process.stderr:
                if len(errors)<8:errors.append(line.strip())
        reader=threading.Thread(target=read,daemon=True);reader.start()
        err=threading.Thread(target=stderr,daemon=True);err.start()
        deadline=time.monotonic()+(150 if request.get('operation')=='configure' else 100 if request.get('operation')=='pair' else 45)
        try:
            process.stdin.write(json.dumps(request)+'\n');process.stdin.flush()
            while True:
                remaining=deadline-time.monotonic()
                if remaining<=0:raise TimeoutError('Windows 网络操作超时，请刷新实际状态确认结果')
                try:line=lines.get(timeout=min(remaining,1))
                except queue.Empty:continue
                if line is None:raise OSError('Windows 网络接口未返回结果：'+' '.join(errors)[-1200:])
                try:result=json.loads(line)
                except ValueError:continue
                if not isinstance(result,dict):continue
                if result.get('type')=='pairing':
                    answer=challenge(result) if challenge else dict(confirm=False)
                    process.stdin.write(json.dumps(dict(answer,id=result['id']))+'\n');process.stdin.flush()
                    continue
                if not result.get('ok'):raise OSError(result.get('error','Windows 网络操作失败'))
                return result.get('data',{})
        finally:
            if not process.stdin.closed:process.stdin.close()
            try:process.wait(3)
            except subprocess.TimeoutExpired:
                process.terminate();process.wait(5)
            reader.join(1);err.join(1);process.stdout.close();process.stderr.close()

    def wlan(self):
        if self.wifi is None:self.wifi=NativeWifi()
        return self.wifi

    def read(self,area):
        if area=='wifi':
            result=self.wlan().status()
            result['radios']=[r for r in self.run(dict(area='radios',operation='status')).get('radios',[]) if r['kind']=='WiFi']
            return result
        return self.run(dict(area=area,operation='status'))

    def perform(self,area,operation,params,challenge):
        if operation=='radio':
            self.run(dict(area='radios',operation='set',kind='WiFi' if area=='wifi' else 'Bluetooth',**params))
        elif area=='wifi':
            self.wlan().operation(operation,params)
            if operation in ('connect','disconnect'):
                deadline=time.monotonic()+15
                while True:
                    snapshot=self.wlan().status()
                    interface=next((i for i in snapshot['interfaces'] if i['id']==params['interface']),None)
                    matches=interface and (not interface['connected'] if operation=='disconnect' else any(n['connected'] and (n['profile']==params.get('profile') or n['ssid_hex']==params.get('ssid_hex')) for n in interface['networks']))
                    if matches:return dict(confirmed=True,message='Wi-Fi 连接状态已确认')
                    if time.monotonic()>=deadline:return dict(confirmed=False,message='请求已提交，Windows 尚未确认连接状态，请刷新；网络改变后手机可能需要重新连接')
                    time.sleep(.3)
        else:
            return self.run(dict(area=area,operation=operation,**params),challenge)
        return dict(confirmed=True,message='Windows 已执行请求')


class NetworkManagement:
    def __init__(self,backend=None,grace=.6):
        self.backend=backend or WindowsNetwork();self.grace=grace
        self.lock=threading.RLock();self.jobs={};self.reading={};self.snapshots={};self.mutation=None

    def busy(self):
        with self.lock:return self.mutation is not None

    def _question(self,job,event):
        challenge=dict(id=event.get('id'),kind=event.get('kind'),display_code=event.get('display_code',''))
        if not isinstance(challenge['id'],str) or not re.fullmatch('[0-9a-f]{32}',challenge['id']) or challenge['kind'] not in ('ConfirmOnly','DisplayPin','ProvidePin','ConfirmPinMatch'):
            raise ValueError('Windows 返回了无效的配对验证')
        with self.lock:
            job['public'].update(status='awaiting_input',question=challenge,message='请在手机确认蓝牙配对验证')
        if not job['answer_event'].wait(40):
            with self.lock:job['public']['question']=None
            raise TimeoutError('蓝牙配对验证超时，未自动接受')
        with self.lock:
            response=job.pop('answer',dict(confirm=False));job['public'].update(status='running',question=None)
        job['answer_event'].clear()
        return response

    def _run(self,job,params,refresh=False,scan=False):
        data=job['public'];area=data['area'];secrets=job['secrets']
        try:
            if refresh:
                result=self.backend.wlan().status(scan=True,ident=params.get('interface')) if scan and isinstance(self.backend,WindowsNetwork) else self.backend.read(area)
                if scan:result['message']='已请求 Wi-Fi 扫描；请数秒后刷新查看新发现的网络'
            else:
                time.sleep(self.grace)  # Let the phone receive its job ID before a link changes.
                with network_change():result=self.backend.perform(area,data['operation'],params,lambda event:self._question(job,event))
            if not refresh:
                try:
                    snapshot=self.backend.read(area)
                    with self.lock:self.snapshots[area]=dict(clean(snapshot,secrets),updated=time.time())
                except Exception as exc:
                    with self.lock:data['refresh_error']=clean(str(exc),secrets)
            result=clean(result,secrets)
            with self.lock:
                if refresh:self.snapshots[area]=dict(result,updated=time.time())
                data.update(status='completed' if result.get('confirmed',True) else 'unconfirmed',result=result,
                    message=result.get('message','状态已刷新' if refresh else '操作已完成'))
        except Exception as exc:
            with self.lock:
                message=clean(str(exc),secrets)[:1500]
                data.update(status='failed',message=message,question=None)
                if refresh:self.snapshots[area]=dict(supported=False,error=message,updated=time.time())
        finally:
            params.clear()
            with self.lock:
                job['secrets'].clear()
                data['finished']=time.time()
                if self.mutation==data['id']:self.mutation=None
                if self.reading.get(area)==data['id']:self.reading.pop(area,None)

    def request(self,q,owner,error):
        try:
            if not owner:raise ValueError('请先连接电脑')
            action=q.get('action','status');area=q.get('area','hotspot')
            if area not in AREAS:raise ValueError('网络分类无效')
            with self.lock:
                if action in ('refresh','scan','submit'):
                    refresh=action!='submit'
                    if action=='scan' and area!='wifi':raise ValueError('只支持扫描 Wi-Fi 网络')
                    if not refresh and q.get('confirm') is not True:raise ValueError('请先在手机确认网络更改')
                    operation=q.get('operation','refresh') if not refresh else 'refresh'
                    params=validate(area,operation,q.get('params',{})) if not refresh else {}
                    if action=='scan' and q.get('params',{}).get('interface'):
                        params['interface']=str(uuid.UUID(text(q['params']['interface'],'Wi-Fi 网卡')))
                    request_id=q.get('request_id')
                    if request_id is None:request_id=uuid.uuid4().hex
                    if not isinstance(request_id,str) or not re.fullmatch('[0-9a-f]{32}',request_id):raise ValueError('网络请求编号无效')
                    fingerprint=hashlib.sha256(json.dumps([area,operation,action=='scan',params],sort_keys=True).encode()).hexdigest()
                    existing=next((j for j in self.jobs.values() if j['owner']==owner and j['public']['request_id']==request_id),None)
                    if existing:
                        if existing['fingerprint']!=fingerprint:raise ValueError('请求编号已用于其他操作')
                        job=existing
                    elif refresh and area in self.reading and self.jobs[self.reading[area]]['owner']==owner:
                        job=self.jobs[self.reading[area]]
                    else:
                        if not refresh and self.mutation:raise ValueError('已有网络修改正在执行，请等待结果')
                        if len(self.jobs)>=80:
                            for key in list(self.jobs):
                                if 'finished' in self.jobs[key]['public']:self.jobs.pop(key);break
                            else:raise ValueError('网络任务过多，请等待当前任务')
                        ident=uuid.uuid4().hex
                        data=dict(id=ident,request_id=request_id,area=area,operation=operation,status='running',message='读取设备状态' if refresh else '正在提交 Windows 网络操作',created=time.time())
                        job=dict(owner=owner,public=data,fingerprint=fingerprint,answer_event=threading.Event(),
                            secrets=[v for k,v in params.items() if k in SECRET_KEYS and v])
                        self.jobs[ident]=job
                        if refresh:self.reading[area]=ident
                        else:self.mutation=ident
                        threading.Thread(target=self._run,args=(job,params,refresh,action=='scan'),daemon=True,name='network-management').start()
                elif action=='answer':
                    job=self.jobs.get(q.get('job'))
                    if not job or job['owner']!=owner:raise ValueError('配对任务不存在或不属于此手机')
                    question=job['public'].get('question')
                    if not question or question['id']!=q.get('question') or 'answer' in job:raise ValueError('配对验证已变化，请刷新')
                    confirm=q.get('accept')
                    if type(confirm) is not bool:raise ValueError('请明确确认或拒绝配对')
                    answer=dict(confirm=confirm)
                    if confirm and question['kind']=='ProvidePin':
                        answer['pin']=text(q.get('pin'),'配对 PIN',16)
                        job['secrets'].append(answer['pin'])
                    job['answer']=answer;job['answer_event'].set()
                elif action=='status':
                    job=self.jobs.get(q.get('job'))
                    if not job and q.get('request_id'):
                        job=next((j for j in self.jobs.values() if j['owner']==owner and j['public']['request_id']==q['request_id']),None)
                    if q.get('job') and not job:raise ValueError('网络任务已失效，请刷新实际状态；不会重复执行')
                    if job and job['owner']!=owner:raise ValueError('网络任务不属于此手机')
                else:raise ValueError('无效的网络管理请求')
                return dict(area=area,snapshot=copy.deepcopy(self.snapshots.get(area,{})),
                    job=copy.deepcopy(job['public']) if job else None,busy=self.mutation is not None)
        except (ValueError,OSError,TypeError,KeyError) as exc:raise error(str(exc))

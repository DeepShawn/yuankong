"""Asynchronous device management; helper credentials use stdin, never argv."""
import copy
from contextlib import contextmanager
import hashlib
import hmac
import secrets
from collections import deque
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
from privacy_devices import PrivacyHardware
from device_property_change import change_state
from hotspot_access import HotspotAccess,address as client_address

NETWORK_CLASSES=frozenset({'4d36e972-e325-11ce-bfc1-08002be10318','e0cbf06c-cd8b-4647-bb8a-263b43f0f974'})

class NetworkHardware(PrivacyHardware):
    def set_enabled(self,instance,enabled):return change_state(instance,enabled,NETWORK_CLASSES)

NETWORK_CHANGE_LOCK=threading.RLock()
AREAS=('hotspot','wifi','bluetooth','adapters')
OPERATIONS={
    'hotspot':{'start','stop','restart','configure','device','client_access','hotspot_interface','device_batch','client_access_batch'},
    'wifi':{'connect','disconnect','forget','radio','device','device_batch'},
    'bluetooth':{'pair','unpair','radio','device','device_batch'},
    'adapters':{'enable','disable','dns','renew','device','device_batch','adapter_batch'},
}
SECRET_KEYS={'password','passphrase','pin','keymaterial','key_material','network_token'}


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
    if operation in ('device_batch','adapter_batch','client_access_batch'):
        field='addresses' if operation=='client_access_batch' else 'adapters' if operation=='adapter_batch' else 'instances'
        values=params.get(field)
        if not isinstance(values,list) or not 1<=len(values)<=32:raise ValueError('每批请选择 1–32 台设备')
        convert=client_address if field=='addresses' else (lambda value:str(uuid.UUID(text(value,'网卡编号')))) if field=='adapters' else (lambda value:text(value,'设备实例编号',512))
        values=[convert(value) for value in values]
        if len(set(values))!=len(values):raise ValueError('批量设备不能重复')
        flag='blocked' if field=='addresses' else 'enabled'
        if type(params.get(flag)) is not bool:raise ValueError('请明确选择启用或停用')
        p={field:values,flag:params[flag]}
    elif operation=='hotspot_interface':
        selected=text(params.get('interface',''),'热点网卡',40,True)
        p=dict(interface=str(uuid.UUID(selected)) if selected else '')
    elif operation=='client_access':
        p=dict(address=client_address(params.get('address')),blocked=params.get('blocked'))
        if type(p['blocked']) is not bool:raise ValueError('请选择禁止或恢复联网')
    elif operation=='device':
        p=dict(instance=text(params.get('instance'),'设备实例编号',512),enabled=params.get('enabled'))
        if type(p['enabled']) is not bool:raise ValueError('设备开关必须明确选择启用或停用')
    elif operation=='radio':
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
    def __init__(self,access_path=None):
        self.wifi=None;self.hw=None;self.access=HotspotAccess(access_path);self.access_thread=None;self.access_lock=threading.Lock()
        if self.access.blocked:self.watch_access()

    def watch_access(self):
        if os.name!='nt':return
        with self.access_lock:
            if self.access_thread and self.access_thread.is_alive():return
            def run():
                while True:
                    if self.access.blocked:
                        try:snapshot=self.run(dict(area='hotspot',operation='status'))
                        except Exception as exc:snapshot=dict(state='Unavailable',error=str(exc))
                        self.access.sync(snapshot)
                    time.sleep(8)
            self.access_thread=threading.Thread(target=run,daemon=True,name='lancmd-hotspot-access');self.access_thread.start()

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

    def hardware(self):
        if self.hw is None:self.hw=NetworkHardware()
        items=self.run(dict(area='hardware',operation='status')).get('devices',[])
        for item in items:
            try:item.update(self.hw.status(item['id']))
            except (OSError,ValueError,RuntimeError) as exc:item.update(enabled=False,problem=-1,error=str(exc))
        return items

    def read(self,area):
        try:
            if area=='wifi':
                result=self.wlan().status()
                result['radios']=[r for r in self.run(dict(area='radios',operation='status')).get('radios',[]) if r['kind']=='WiFi']
            else:result=self.run(dict(area=area,operation='status'))
        except (OSError,RuntimeError) as exc:
            result=dict(supported=False,error=str(exc))
        if area=='hotspot':result=self.access.sync(result)
        try:result['hardware']=[d for d in self.hardware() if d['kind']==('bluetooth' if area=='bluetooth' else 'net')]
        except (OSError,RuntimeError) as exc:result['hardware_error']=str(exc)
        return result

    def perform(self,area,operation,params,challenge):
        if operation in ('device_batch','adapter_batch'):
            if not ctypes_admin():raise ValueError('批量启停需要管理员接收端；未自动提权')
            devices=self.hardware() if operation=='device_batch' else self.run(dict(area='adapters',operation='status')).get('adapters',[])
            by_id={d['id']:d for d in devices if operation=='adapter_batch' or d['kind']==('bluetooth' if area=='bluetooth' else 'net')}
            results=[]
            for ident in params['instances' if operation=='device_batch' else 'adapters']:
                item=by_id.get(ident);name=item.get('name',ident) if item else ident
                try:
                    if not item:raise ValueError('设备已变化或不属于当前分类，请刷新')
                    if operation=='device_batch':result=self._set_device(item,params['enabled'])
                    else:
                        self.run(dict(area='adapters',operation='enable' if params['enabled'] else 'disable',adapter=ident))
                        current=next((d for d in self.run(dict(area='adapters',operation='status')).get('adapters',[]) if d['id']==ident),None)
                        confirmed=current is not None and (current.get('status') in ('Up','Disconnected','Dormant','LowerLayerDown') if params['enabled'] else current.get('status')=='Disabled')
                        result=dict(confirmed=confirmed,message='设备状态已确认' if confirmed else 'Windows 尚未确认网卡状态，请刷新')
                    results.append(dict(id=ident,name=name,**result))
                except Exception as exc:results.append(dict(id=ident,name=name,confirmed=False,message=str(exc)))
            count=sum(row['confirmed'] for row in results)
            return dict(confirmed=count==len(results),items=results,message=f'批量操作完成：已确认 {count}/{len(results)} 台；逐项结果见下方')
        elif operation in ('client_access','client_access_batch'):
            if not ctypes_admin():raise ValueError('热点客户端限制需要管理员接收端；未自动提权')
            try:snapshot=self.run(dict(area='hotspot',operation='status'))
            except OSError:
                if params['blocked']:raise
                snapshot=dict(state='Unavailable')
            result=self.access.change_many(params['addresses'] if operation=='client_access_batch' else [params['address']],params['blocked'],snapshot)
            if self.access.blocked:self.watch_access()
            return result
        elif operation=='hotspot_interface':
            if self.access.blocked and not ctypes_admin():raise ValueError('重新应用联网限制需要管理员接收端；未自动提权')
            try:snapshot=self.run(dict(area='hotspot',operation='status'))
            except OSError:
                if params['interface']:raise
                snapshot=dict(state='Unavailable')
            result=self.access.choose_interface(params['interface'],snapshot)
            if self.access.blocked:self.watch_access()
            return result
        elif operation=='device':
            item=next((d for d in self.hardware() if d['id']==params['instance'] and d['kind']==('bluetooth' if area=='bluetooth' else 'net')),None)
            if not item:raise ValueError('设备已变化或不属于当前分类，请刷新后重新选择')
            return self._set_device(item,params['enabled'])
        elif operation=='radio':
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

    def _set_device(self,item,enabled):
        if item.get('problem') not in (0,22) or item.get('error'):raise ValueError('设备状态异常，未执行启停，请检查驱动')
        if not ctypes_admin():raise ValueError('设备级启停需要管理员接收端；未自动提权')
        if item.get('enabled') is enabled and item.get('problem')==(0 if enabled else 22):
            return dict(confirmed=True,message='设备已处于目标状态')
        metadata=self.hw.set_enabled(item['id'],enabled)
        deadline=time.monotonic()+4
        while True:
            state=self.hw.status(item['id'])
            if state.get('enabled') is enabled and state.get('problem')==(0 if enabled else 22):
                return dict(confirmed=True,message='已确认设备'+('启用' if enabled else '停用'),**state)
            if time.monotonic()>=deadline:
                return dict(confirmed=False,message='Windows 尚未确认设备状态，请刷新'+('；驱动要求重启' if metadata.get('reboot_required') else ''),**state)
            time.sleep(.1)


def ctypes_admin():
    import ctypes
    return bool(ctypes.windll.shell32.IsUserAnAdmin())


class NetworkAuthorization:
    """Feature password gate, independent of the existing LAN connection identity.

    Tokens live only in memory, are bound to a phone and expire after 30 minutes.
    Existing jobs continue after a token expires; querying them requires a new login.
    The transport is the existing LAN TCP protocol, not a new encrypted channel.
    """
    SALT=bytes.fromhex('ae86b360428848b09c9205ea01b3d99e')
    VERIFIER=bytes.fromhex('9f05ec41589a8ba6449f87cdbb3cf3ef960b6badf8e70eea83db7a1489d50129')
    ITERATIONS=210000

    def __init__(self,admin=None,salt=None,verifier=None,clock=time.monotonic):
        self.admin=admin or ctypes_admin;self.clock=clock
        self.salt=self.SALT if salt is None else salt;self.verifier=self.VERIFIER if verifier is None else verifier
        self.lock=threading.RLock();self.sessions={};self.failures={};self.attempts=deque()

    def _prune(self,now):
        self.sessions={k:v for k,v in self.sessions.items() if v['expires']>now}
        self.failures={k:v for k,v in self.failures.items() if v['time']>now-300}
        while self.attempts and self.attempts[0]<=now-60:self.attempts.popleft()

    def require(self,owner,token):
        with self.lock:
            self._prune(self.clock())
            session=self.sessions.get(token) if isinstance(token,str) else None
            if not self.admin():raise ValueError('NETWORK_ADMIN_REQUIRED: 请在电脑以管理员身份启动接收端；输入功能密码不会提升 Windows 权限')
            if not session or session['owner']!=owner:raise ValueError('NETWORK_AUTH_REQUIRED: 请先输入网络管理密码')

    def status(self):return dict(admin=bool(self.admin()),requires_password=True)

    def unlock(self,owner,password):
        with self.lock:
            now=self.clock();self._prune(now)
            if not self.admin():raise ValueError('NETWORK_ADMIN_REQUIRED: 请在电脑以管理员身份启动接收端；未自动提权')
            failed=self.failures.get(owner,dict(count=0,time=0))
            if len(self.attempts)>=30:raise ValueError('密码尝试过多，请一分钟后重试')
            if failed['count']>=5 and now<failed['time']+30:raise ValueError('密码尝试过多，请 30 秒后重试')
            self.attempts.append(now)
            valid=isinstance(password,str) and 1<=len(password)<=256 and '\0' not in password
            digest=hashlib.pbkdf2_hmac('sha256',password.encode('utf-8'),self.salt,self.ITERATIONS) if valid else b''
            if not hmac.compare_digest(digest,self.verifier):
                self.failures[owner]=dict(count=failed['count']+1,time=now)
                raise ValueError('网络管理密码错误')
            self.failures.pop(owner,None)
            if len(self.sessions)>=128:raise ValueError('网络管理会话过多，请稍后重试')
            token=secrets.token_hex(32)
            self.sessions[token]=dict(owner=owner,expires=now+1800)
            return dict(network_token=token,expires_in=1800,admin=True)

    def revoke(self,owner,token):
        with self.lock:
            if isinstance(token,str) and self.sessions.get(token,{}).get('owner')==owner:self.sessions.pop(token,None)


class NetworkManagement:
    def __init__(self,backend=None,grace=.6,authorization=None):
        self.backend=backend or WindowsNetwork();self.grace=grace;self.authorization=authorization or NetworkAuthorization()
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
            if action=='auth_status':return self.authorization.status()
            if action=='unlock':return self.authorization.unlock(owner,q.pop('password',None))
            if action=='lock':
                self.authorization.revoke(owner,q.get('network_token'));return dict(locked=True)
            self.authorization.require(owner,q.get('network_token'))
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

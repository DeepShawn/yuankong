"""Per-MAC hotspot traffic restrictions using scoped, dynamic Windows WFP rules.

These rules block network traffic, not 802.11 association. No drivers are installed
and no existing firewall rules are changed. An engine disconnect removes our rules.
"""
import ctypes as c
import ipaddress
import json
import os
from pathlib import Path
import re
import threading
import uuid
from native_wifi import Guid

INBOUND='effb7edb-0055-4f9a-a231-4ff8131ad191'
OUTBOUND='694673bc-d6db-4870-adee-0acdbdb7f4b2'
INTERFACE='4cd62a49-59c3-4969-b7f3-bda5d32890a4'
REMOTE_MAC='408f2ed4-3a70-4b4d-92a6-415ac20e2f12'
NOT_FOUND=0x80320003

def address(value):
    if not isinstance(value,str):raise ValueError('热点客户端 MAC 无效')
    value=value.replace('-',':').upper()
    if re.fullmatch('[0-9A-F]{12}',value):value=':'.join(value[i:i+2] for i in range(0,12,2))
    if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}',value) or int(value[:2],16)&1 or value=='00:00:00:00:00:00':
        raise ValueError('请选择有效的单个热点客户端 MAC')
    return value


def discover_private(snapshot,preferred=''):
    """Correlate current tethering clients with active Wi-Fi Direct adapters.

    ICS is useful evidence, but modern mobile hotspots may not publish its
    legacy sharing flag. Never guess from adapter names or 192.168.137.1 alone.
    """
    def ident(item):
        try:return str(uuid.UUID(item['id']))
        except (KeyError,ValueError,TypeError,AttributeError):return None
    client_macs=set();client_ips=set()
    for client in snapshot.get('clients',[]):
        try:client_macs.add(address(client['address']))
        except (KeyError,ValueError):pass
        for host in client.get('hosts',[]):
            try:
                ip=ipaddress.ip_address(host)
                if ip.version==4 and not ip.is_link_local and not ip.is_loopback and not ip.is_unspecified:client_ips.add(ip)
            except (ValueError,TypeError):pass
    legacy={ident(item):item for item in snapshot.get('private_adapters',[]) if isinstance(item,dict) and ident(item)}
    candidates={}
    for row in snapshot.get('adapter_inventory',[]):
        key=ident(row)
        if not key or row.get('up') is not True:continue
        if row.get('wifi_direct') is not True and key not in legacy:continue
        networks=[];ips=[]
        for item in row.get('ipv4',[]):
            try:
                ip=ipaddress.IPv4Interface('%s/%s'%(item['address'],item['prefix']))
                if ip.ip.is_link_local or ip.ip.is_loopback or ip.ip.is_unspecified:continue
                ips.append(str(ip))
                if 8<=ip.network.prefixlen<=30:networks.append(ip.network)
            except (ValueError,TypeError,KeyError):continue
        neighbors=set()
        for peer in row.get('neighbors',[]):
            if peer.get('state','') not in ('Reachable','Stale','Delay','Probe','Permanent'):continue
            try:neighbors.add(address(peer['address']))
            except (KeyError,ValueError):pass
        matched=len(neighbors & client_macs)
        if not ips and not matched and key not in legacy:continue
        candidates[key]=dict(id=key,name=row.get('name') or key,description=row.get('description',''),
            index=row.get('index'),ipv4=ips,mac_matches=matched,
            subnet_matches=sum(any(ip in network for network in networks) for ip in client_ips))
    # Preserve older snapshots and explicit legacy ICS evidence. A fresh
    # inventory, when supplied, must still show the adapter as active.
    if 'adapter_inventory' not in snapshot:
        for key,row in legacy.items():candidates[key]=dict(row,id=key,ipv4=[],mac_matches=0,subnet_matches=0)
    selected=None;source='';notice=''
    if snapshot.get('state')!='On':notice='热点尚未开启；开启后再识别热点专用网卡'
    elif preferred:
        selected=candidates.get(preferred);source='manual'
        if not selected:notice='所选热点网卡已变化，请重新选择或恢复自动识别'
    else:
        mac=[row for row in candidates.values() if row['mac_matches']>0]
        subnet=[row for row in candidates.values() if row['subnet_matches']>0]
        shared=[row for key,row in candidates.items() if key in legacy]
        if len(mac)==1:selected=mac[0];source='client_mac'
        elif len(mac)>1:
            corroborated=[row for row in mac if row['id'] in legacy or row['subnet_matches']>0]
            if len(corroborated)==1:selected=corroborated[0];source='client_mac'
        elif len(subnet)==1:selected=subnet[0];source='client_subnet'
        elif len(shared)==1:selected=shared[0];source='ics'
        if not selected:
            notice='热点已开启，发现多个候选热点网卡，请选择实际使用的网卡' if len(candidates)>1 else '热点已开启，尚未识别专用网卡；请刷新或选择热点网卡'
    return dict(selected=selected,source=source if selected else '',notice=notice,candidates=list(candidates.values()))

class Display(c.Structure):_fields_=[('name',c.c_wchar_p),('description',c.c_wchar_p)]
class Blob(c.Structure):_fields_=[('size',c.c_uint32),('data',c.c_void_p)]
class Data(c.Union):_fields_=[('uint8',c.c_uint8),('uint32',c.c_uint32),('pointer',c.c_void_p)]
class Value(c.Structure):_anonymous_=('data',);_fields_=[('type',c.c_uint32),('data',Data)]
class Condition(c.Structure):_fields_=[('field',Guid),('match',c.c_uint32),('value',Value)]
class Action(c.Structure):_fields_=[('type',c.c_uint32),('key',Guid)]
class Context(c.Union):_fields_=[('raw',c.c_uint64),('key',Guid)]
class Filter(c.Structure):
    _fields_=[('key',Guid),('display',Display),('flags',c.c_uint32),('provider',c.c_void_p),('data',Blob),
        ('layer',Guid),('sublayer',Guid),('weight',Value),('count',c.c_uint32),('conditions',c.POINTER(Condition)),
        ('action',Action),('context',Context),('reserved',c.c_void_p),('id',c.c_uint64),('effective_weight',Value)]
class Session(c.Structure):
    _fields_=[('key',Guid),('display',Display),('flags',c.c_uint32),('timeout',c.c_uint32),
        ('pid',c.c_uint32),('sid',c.c_void_p),('username',c.c_wchar_p),('kernel',c.c_int32)]
class Sublayer(c.Structure):
    _fields_=[('key',Guid),('display',Display),('flags',c.c_uint32),('provider',c.c_void_p),('data',Blob),('weight',c.c_uint16)]

class WfpError(OSError):
    def __init__(self,code):
        self.code=code
        super().__init__('Windows 无法应用热点客户端网络过滤（0x%08X）；请检查管理员权限、BFE 服务和驱动对二层过滤的支持，未报告禁止联网成功'%code)

class NativeAccess:
    def __init__(self):
        self.api=c.WinDLL('fwpuclnt');self.ip=c.WinDLL('iphlpapi');self.handle=c.c_void_p();self.ids=[]
        signatures={
            'FwpmEngineOpen0':[c.c_wchar_p,c.c_uint32,c.c_void_p,c.POINTER(Session),c.POINTER(c.c_void_p)],
            'FwpmEngineClose0':[c.c_void_p], 'FwpmSubLayerAdd0':[c.c_void_p,c.POINTER(Sublayer),c.c_void_p],
            'FwpmTransactionBegin0':[c.c_void_p,c.c_uint32], 'FwpmTransactionCommit0':[c.c_void_p], 'FwpmTransactionAbort0':[c.c_void_p],
            'FwpmFilterAdd0':[c.c_void_p,c.POINTER(Filter),c.c_void_p,c.POINTER(c.c_uint64)],
            'FwpmFilterDeleteById0':[c.c_void_p,c.c_uint64],
            'FwpmFilterGetById0':[c.c_void_p,c.c_uint64,c.POINTER(c.POINTER(Filter))],
        }
        for name,args in signatures.items():fn=getattr(self.api,name);fn.argtypes=args;fn.restype=c.c_uint32
        self.api.FwpmFreeMemory0.argtypes=[c.c_void_p];self.api.FwpmFreeMemory0.restype=None
        self.ip.ConvertInterfaceGuidToLuid.argtypes=[c.POINTER(Guid),c.POINTER(c.c_uint64)];self.ip.ConvertInterfaceGuidToLuid.restype=c.c_uint32
        session=Session();session.key=Guid.parse(str(uuid.uuid4()));session.display.name='Hotspot client access';session.flags=1
        self.check(self.api.FwpmEngineOpen0(None,10,None,c.byref(session),c.byref(self.handle)))
        try:
            self.sublayer=Guid.parse(str(uuid.uuid4()));layer=Sublayer();layer.key=self.sublayer
            layer.display.name='Hotspot client access';layer.weight=0xffff
            self.check(self.api.FwpmSubLayerAdd0(self.handle,c.byref(layer),None))
        except Exception:self.close();raise

    @staticmethod
    def check(code):
        if code:raise WfpError(code)

    def replace(self,entries):
        # Resolve GUIDs before beginning a transaction; NET_LUID avoids index reuse.
        resolved=[]
        for entry in entries:
            guid=Guid.parse(entry['interface']);luid=c.c_uint64()
            self.check(self.ip.ConvertInterfaceGuidToLuid(c.byref(guid),c.byref(luid)))
            resolved.append((address(entry['address']),luid))
        self.check(self.api.FwpmTransactionBegin0(self.handle,0));new=[]
        try:
            for ident in self.ids:
                code=self.api.FwpmFilterDeleteById0(self.handle,ident)
                if code!=NOT_FOUND:self.check(code)
            for mac,luid in resolved:
                raw=(c.c_uint8*6).from_buffer_copy(bytes.fromhex(mac.replace(':','')))
                conditions=(Condition*2)()
                conditions[0].field=Guid.parse(INTERFACE);conditions[0].value.type=4;conditions[0].value.pointer=c.addressof(luid)
                conditions[1].field=Guid.parse(REMOTE_MAC);conditions[1].value.type=18;conditions[1].value.pointer=c.addressof(raw)
                for layer in (INBOUND,OUTBOUND):
                    item=Filter();item.key=Guid.parse(str(uuid.uuid4()));item.display.name='Hotspot client '+mac
                    item.layer=Guid.parse(layer);item.sublayer=self.sublayer;item.weight.type=1;item.weight.uint8=15
                    item.count=2;item.conditions=conditions;item.action.type=0x1001
                    ident=c.c_uint64();self.check(self.api.FwpmFilterAdd0(self.handle,c.byref(item),None,c.byref(ident)));new.append(ident.value)
            self.check(self.api.FwpmTransactionCommit0(self.handle));self.ids=new
        except Exception:self.api.FwpmTransactionAbort0(self.handle);raise

    def inspect(self,ident):
        result=c.POINTER(Filter)();code=self.api.FwpmFilterGetById0(self.handle,ident,c.byref(result))
        if code==NOT_FOUND:return None
        self.check(code)
        try:
            item=result.contents;conditions=[]
            if item.count>8:raise ValueError('Windows 返回的过滤条件数量无效')
            for i in range(item.count):
                cond=item.conditions[i]
                if cond.value.type not in (4,18) or not cond.value.pointer:raise ValueError('Windows 返回的过滤条件类型无效')
                data=c.c_uint64.from_address(cond.value.pointer).value if cond.value.type==4 else c.string_at(cond.value.pointer,6).hex(':').upper()
                conditions.append(dict(field=cond.field.text(),type=cond.value.type,value=data))
            return dict(layer=item.layer.text(),action=item.action.type,conditions=conditions)
        finally:self.api.FwpmFreeMemory0(c.byref(result))

    def live(self):return bool(self.ids) and all(self.inspect(ident) is not None for ident in self.ids)
    def close(self):
        if self.handle.value:
            self.check(self.api.FwpmEngineClose0(self.handle));self.handle=c.c_void_p();self.ids=[]

class HotspotAccess:
    def __init__(self,path=None,factory=NativeAccess):
        self.path=Path(path) if path else None;self.factory=factory;self.engine=None;self.lock=threading.RLock()
        self.blocked=set();self.applied=[];self.error='';self.load_error='';self.interface=None;self.preferred=''
        if self.path and self.path.exists():
            try:
                if self.path.stat().st_size>65536:raise ValueError('设置文件过大')
                data=json.loads(self.path.read_text(encoding='utf-8'))
                if not isinstance(data,dict) or data.get('format')!=1 or not isinstance(data.get('blocked'),list) or len(data['blocked'])>64:raise ValueError('列表无效')
                blocked={address(mac) for mac in data['blocked']}
                preferred=data.get('preferred_interface','')
                if not isinstance(preferred,str):raise ValueError('热点网卡设置无效')
                preferred=str(uuid.UUID(preferred)) if preferred else ''
                self.blocked=blocked;self.preferred=preferred
            except (OSError,ValueError,TypeError) as exc:self.load_error=self.error='无法读取热点限制设置：'+str(exc)

    def private(self,snapshot):
        result=discover_private(snapshot,self.preferred)
        if not result['selected']:raise ValueError(result['notice'])
        return result['selected']

    def _replace(self,entries):
        if not entries and self.engine is None:return
        if self.engine is None:self.engine=self.factory()
        self.engine.replace(entries);self.applied=entries

    def _save(self,blocked,preferred=None):
        if self.path:
            self.path.parent.mkdir(parents=True,exist_ok=True);temp=self.path.with_suffix('.tmp')
            try:
                with temp.open('w',encoding='utf-8') as out:
                    json.dump(dict(format=1,blocked=sorted(blocked),preferred_interface=self.preferred if preferred is None else preferred),out);out.flush();os.fsync(out.fileno())
                os.replace(temp,self.path)
            finally:temp.unlink(missing_ok=True)

    def sync(self,snapshot):
        with self.lock:
            snapshot=dict(snapshot)
            discovery=discover_private(snapshot,self.preferred)
            snapshot.update(adapter_candidates=discovery['candidates'],selected_adapter=discovery['selected'],
                adapter_source=discovery['source'],adapter_notice=discovery['notice'],preferred_interface=self.preferred)
            snapshot.pop('adapter_inventory',None)  # Unrelated LAN neighbors are not part of the phone's client list.
            try:
                self.interface=discovery['selected']
                if self.blocked and not self.interface:raise ValueError(discovery['notice'])
                entries=[dict(address=mac,interface=self.interface['id']) for mac in sorted(self.blocked)] if self.interface else []
                if entries!=self.applied or entries and (self.engine is None or not self.engine.live()):self._replace(entries)
                self.error=self.load_error
            except Exception as exc:
                self.error=str(exc)
                # An unknown/changed hotspot must never leave a rule on an old NIC.
                try:self._replace([])
                except Exception:
                    if self.engine:
                        try:self.engine.close()
                        except Exception:pass
                        self.engine=None;self.applied=[]
            active={entry['address'] for entry in self.applied}
            snapshot['client_access']=[dict(address=mac,active=mac in active) for mac in sorted(self.blocked)]
            snapshot['access_error']=self.error
            for client in snapshot.get('clients',[]):
                try:mac=address(client['address']);client.update(address=mac,blocked=mac in self.blocked,access_active=mac in active)
                except (KeyError,ValueError):continue
            return snapshot

    def choose_interface(self,preferred,snapshot):
        preferred=str(uuid.UUID(preferred)) if preferred else ''
        with self.lock:
            discovered=discover_private(snapshot,preferred)
            if preferred and not discovered['selected']:raise ValueError(discovered['notice'])
            chosen=discovered['selected']
            entries=[dict(address=mac,interface=chosen['id']) for mac in sorted(self.blocked)] if chosen else []
            previous=list(self.applied);self._replace(entries)
            try:self._save(self.blocked,preferred)
            except Exception:
                try:self._replace(previous)
                except Exception:
                    if self.engine:
                        try:self.engine.close()
                        except Exception:pass
                        self.engine=None
                    self.applied=[]
                raise
            self.preferred=preferred;self.interface=chosen;self.load_error=self.error=''
            return dict(confirmed=bool(chosen) or not self.blocked,
                message='已选择热点网卡：'+chosen['name'] if chosen else '已恢复自动识别；'+discovered['notice'])

    def change(self,mac,blocked,snapshot):
        return self.change_many([mac],blocked,snapshot)

    def change_many(self,macs,blocked,snapshot):
        if not isinstance(macs,list) or not 1<=len(macs)<=32 or type(blocked) is not bool:raise ValueError('请选择 1–32 台客户端及目标状态')
        macs=[address(mac) for mac in macs]
        if len(set(macs))!=len(macs):raise ValueError('客户端不能重复')
        with self.lock:
            desired=set(self.blocked)
            if blocked:
                interface=self.private(snapshot)
                connected=set()
                for item in snapshot.get('clients',[]):
                    try:connected.add(address(item['address']))
                    except (KeyError,ValueError):continue
                if not set(macs)<=connected:raise ValueError('所选 MAC 当前未连接此热点，请刷新后重新选择')
                desired.update(macs)
                if len(desired)>64:raise ValueError('热点限制最多保留 64 台设备')
            else:
                desired.difference_update(macs)
                try:interface=self.private(snapshot)
                except ValueError:interface=None
            entries=[dict(address=value,interface=interface['id']) for value in sorted(desired)] if interface else []
            previous=list(self.applied)
            self._replace(entries)
            try:self._save(desired)
            except Exception:
                try:self._replace(previous)
                except Exception:
                    if self.engine:self.engine.close();self.engine=None
                    self.applied=[]
                raise
            self.blocked=desired;self.interface=interface;self.load_error=self.error=''
            return dict(confirmed=True,items=[dict(id=mac,name=mac,confirmed=True,message='已禁止联网' if blocked else '已恢复联网') for mac in macs],
                message=f'已禁止 {len(macs)} 台客户端联网，Wi-Fi 可能仍显示已连接' if blocked else f'已移除 {len(macs)} 台客户端的联网限制')

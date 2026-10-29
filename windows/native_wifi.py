"""Windows WLAN client APIs. Saved keys are never requested in plaintext."""
import ctypes as c
from ctypes import wintypes as w
import json
import re
import uuid
import xml.etree.ElementTree as E


class Guid(c.Structure):
    _fields_ = [('a', c.c_uint32), ('b', c.c_uint16), ('d', c.c_uint16), ('tail', c.c_ubyte * 8)]
    @classmethod
    def parse(cls, text):return cls.from_buffer_copy(uuid.UUID(text.strip('{}')).bytes_le)
    def text(self):return str(uuid.UUID(bytes_le=bytes(self)))


class Ssid(c.Structure):
    _fields_ = [('size', c.c_uint32), ('data', c.c_ubyte * 32)]


class Interface(c.Structure):
    _fields_ = [('guid', Guid), ('description', c.c_wchar * 256), ('state', c.c_uint32)]


class Available(c.Structure):
    _fields_ = [('profile', c.c_wchar * 256), ('ssid', Ssid), ('bss', c.c_uint32),
        ('bssids', c.c_uint32), ('connectable', c.c_int32), ('reason', c.c_uint32),
        ('phy_count', c.c_uint32), ('phys', c.c_uint32 * 8), ('more_phys', c.c_int32),
        ('signal', c.c_uint32), ('secure', c.c_int32), ('auth', c.c_uint32),
        ('cipher', c.c_uint32), ('flags', c.c_uint32), ('reserved', c.c_uint32)]


class Profile(c.Structure):
    _fields_ = [('name', c.c_wchar * 256), ('flags', c.c_uint32)]


class ConnectionParameters(c.Structure):
    _fields_ = [('mode', c.c_uint32), ('profile', c.c_wchar_p), ('ssid', c.c_void_p),
        ('desired', c.c_void_p), ('bss', c.c_uint32), ('flags', c.c_uint32)]


STATES = {0:'未就绪',1:'已连接',2:'临时网络',3:'正在断开',4:'未连接',5:'正在连接',6:'正在发现',7:'正在验证'}
AUTH = {1:'开放',3:'WPA 企业',4:'WPA-Personal',6:'WPA2 企业',7:'WPA2-Personal',8:'WPA3 企业',9:'WPA3-Personal',10:'OWE',11:'WPA3 企业'}


def profile_xml(ssid_hex, name, auth, password='', hidden=False):
    raw = bytes.fromhex(ssid_hex)
    if not 1 <= len(raw) <= 32 or not isinstance(name,str) or not 1 <= len(name) <= 255 or '\0' in name:
        raise ValueError('Wi-Fi 名称或 SSID 无效')
    modes = {1:('open','none'),4:('WPAPSK','AES'),7:('WPA2PSK','AES'),9:('WPA3SAE','AES'),10:('OWE','AES')}
    if auth not in modes:raise ValueError('此网络需要企业认证，请先在 Windows 配置企业 Wi-Fi 后选择已保存配置连接')
    if auth in (4,7,9) and (not isinstance(password,str) or not 8 <= len(password) <= 63 or any(ord(x)<32 or ord(x)>126 for x in password)):
        raise ValueError('Wi-Fi 密码须为 8–63 个可打印 ASCII 字符')
    ns = 'http://www.microsoft.com/networking/WLAN/profile/v1'
    E.register_namespace('',ns)
    def node(parent,key,value=None):
        item=E.SubElement(parent,'{'+ns+'}'+key)
        if value is not None:item.text=str(value)
        return item
    root=E.Element('{'+ns+'}WLANProfile');node(root,'name',name)
    config=node(root,'SSIDConfig');ssid=node(config,'SSID');node(ssid,'hex',raw.hex().upper())
    node(config,'nonBroadcast',str(bool(hidden)).lower())
    node(root,'connectionType','ESS');node(root,'connectionMode','manual')
    security=node(node(root,'MSM'),'security');encryption=node(security,'authEncryption')
    authentication,cipher=modes[auth]
    node(encryption,'authentication',authentication);node(encryption,'encryption',cipher);node(encryption,'useOneX','false')
    if auth in (4,7,9):
        key=node(security,'sharedKey');node(key,'keyType','passPhrase');node(key,'protected','false');node(key,'keyMaterial',password)
    return E.tostring(root,encoding='unicode')


class NativeWifi:
    def __init__(self):
        self.dll=c.WinDLL('wlanapi',use_last_error=True)
        signatures = {
            'WlanOpenHandle':([w.DWORD,c.c_void_p,c.POINTER(w.DWORD),c.POINTER(w.HANDLE)],w.DWORD),
            'WlanCloseHandle':([w.HANDLE,c.c_void_p],w.DWORD),
            'WlanEnumInterfaces':([w.HANDLE,c.c_void_p,c.POINTER(c.c_void_p)],w.DWORD),
            'WlanGetAvailableNetworkList':([w.HANDLE,c.POINTER(Guid),w.DWORD,c.c_void_p,c.POINTER(c.c_void_p)],w.DWORD),
            'WlanGetProfileList':([w.HANDLE,c.POINTER(Guid),c.c_void_p,c.POINTER(c.c_void_p)],w.DWORD),
            'WlanSetProfile':([w.HANDLE,c.POINTER(Guid),w.DWORD,w.LPCWSTR,w.LPCWSTR,w.BOOL,c.c_void_p,c.POINTER(w.DWORD)],w.DWORD),
            'WlanConnect':([w.HANDLE,c.POINTER(Guid),c.POINTER(ConnectionParameters),c.c_void_p],w.DWORD),
            'WlanDisconnect':([w.HANDLE,c.POINTER(Guid),c.c_void_p],w.DWORD),
            'WlanDeleteProfile':([w.HANDLE,c.POINTER(Guid),w.LPCWSTR,c.c_void_p],w.DWORD),
            'WlanScan':([w.HANDLE,c.POINTER(Guid),c.c_void_p,c.c_void_p,c.c_void_p],w.DWORD),
            'WlanFreeMemory':([c.c_void_p],None),
            'WlanReasonCodeToString':([w.DWORD,w.DWORD,w.LPWSTR,c.c_void_p],w.DWORD),
        }
        for name,(args,result) in signatures.items():
            fn=getattr(self.dll,name);fn.argtypes=args;fn.restype=result

    @staticmethod
    def check(code):
        if code:
            if code==1062:raise OSError('Windows WLAN AutoConfig 服务未启动')
            raise OSError('Windows Wi-Fi 操作失败（错误 %s）'%code)

    def open(self):
        handle=w.HANDLE();version=w.DWORD()
        self.check(self.dll.WlanOpenHandle(2,None,c.byref(version),c.byref(handle)))
        return handle

    def array(self, handle, method, typ, *args):
        pointer=c.c_void_p()
        self.check(getattr(self.dll,method)(handle,*args,None,c.byref(pointer)))
        try:
            count=c.c_uint32.from_address(pointer.value).value
            if count>2048:raise ValueError('Windows Wi-Fi 返回过多项目')
            return [typ.from_buffer_copy(c.string_at(pointer.value+8+i*c.sizeof(typ),c.sizeof(typ))) for i in range(count)]
        finally:self.dll.WlanFreeMemory(pointer)

    def _interfaces(self,handle):return self.array(handle,'WlanEnumInterfaces',Interface)

    def selected(self,handle,ident):
        guid=Guid.parse(ident)
        if not any(bytes(i.guid)==bytes(guid) for i in self._interfaces(handle)):
            raise ValueError('Wi-Fi 网卡已变化，请刷新后重新选择')
        return guid

    def status(self,scan=False,ident=None):
        handle=self.open()
        try:
            interfaces=[]
            for interface in self._interfaces(handle):
                guid=interface.guid
                item=dict(id=guid.text(),name=interface.description,state=STATES.get(interface.state,'未知'),connected=interface.state==1,networks=[],profiles=[])
                try:
                    if scan and (not ident or guid.text()==str(ident).strip('{}').lower()):self.check(self.dll.WlanScan(handle,c.byref(guid),None,None,None))
                    for p in self.array(handle,'WlanGetProfileList',Profile,c.byref(guid)):
                        item['profiles'].append(dict(name=p.name,policy=bool(p.flags&1)))
                    for n in self.array(handle,'WlanGetAvailableNetworkList',Available,c.byref(guid),2):
                        raw=bytes(n.ssid.data[:min(n.ssid.size,32)])
                        if not raw:continue
                        item['networks'].append(dict(ssid=raw.decode('utf-8','replace'),ssid_hex=raw.hex(),profile=n.profile,
                            connected=bool(n.flags&1),saved=bool(n.flags&2),signal=n.signal,secure=bool(n.secure),auth=n.auth,
                            auth_name=AUTH.get(n.auth,'其他认证'),connectable=bool(n.connectable),reason=n.reason))
                except (OSError,ValueError) as exc:item['error']=str(exc)
                interfaces.append(item)
            return dict(supported=True,interfaces=interfaces,scan_requested=scan)
        finally:self.dll.WlanCloseHandle(handle,None)

    def operation(self,operation,params):
        handle=self.open()
        try:
            guid=self.selected(handle,params['interface'])
            if operation=='disconnect':self.check(self.dll.WlanDisconnect(handle,c.byref(guid),None))
            elif operation=='forget':
                profiles=self.array(handle,'WlanGetProfileList',Profile,c.byref(guid))
                profile=next((p for p in profiles if p.name==params['profile']),None)
                if profile is None:raise ValueError('已保存的 Wi-Fi 配置不存在')
                if profile.flags&1:raise ValueError('此 Wi-Fi 由组织策略管理，不能删除')
                self.check(self.dll.WlanDeleteProfile(handle,c.byref(guid),profile.name,None))
            elif operation=='connect':
                profile=params.get('profile','')
                if params.get('password') or not profile:
                    name=params['name'];profile=name
                    xml=profile_xml(params['ssid_hex'],name,params['auth'],params.get('password',''),params.get('hidden',False))
                    reason=w.DWORD()
                    code=self.dll.WlanSetProfile(handle,c.byref(guid),2,xml,None,True,None,c.byref(reason))  # Current user only.
                    if code:
                        text=c.create_unicode_buffer(1024);self.dll.WlanReasonCodeToString(reason,1024,text,None)
                        raise OSError('Windows 无法保存 Wi-Fi 配置（%s）：%s'%(code,text.value))
                elif profile not in [p.name for p in self.array(handle,'WlanGetProfileList',Profile,c.byref(guid))]:
                    raise ValueError('Wi-Fi 配置不存在，请输入连接密码')
                connection=ConnectionParameters(0,profile,None,None,1,0)
                self.check(self.dll.WlanConnect(handle,c.byref(guid),c.byref(connection),None))
            else:raise ValueError('无效的 Wi-Fi 操作')
            return dict(submitted=True)
        finally:self.dll.WlanCloseHandle(handle,None)

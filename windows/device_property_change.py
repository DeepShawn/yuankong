"""Quiet, allowlisted SetupAPI property changes for exact device instances."""
import ctypes
import re
import uuid

PRIVACY_CLASSES=frozenset({"ca3e7ab9-b4c3-4ae6-8251-579ef933890f","6bdd1fc6-810f-11d0-bec7-08002be2092f","c166523c-fe0c-4a94-a586-f1a80cfbbf3e"})
USB_DISK_CLASSES=frozenset({"4d36e967-e325-11ce-bfc1-08002be10318"})

def capture_endpoint(instance):
    """Only capture MMDevice endpoints; never route a mute to a render device."""
    if not isinstance(instance,str):return None
    match=re.fullmatch(r"SWD\\MMDEVAPI\\(\{0\.0\.1\.[0-9a-f]{8}\}\.\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\})",instance,re.IGNORECASE)
    return match.group(1) if match else None


def change_state(instance,enabled,allowed_classes,scope=1):
    """Device Manager's property-change path, for the exact selected device only."""
    if scope not in (1,2) or not isinstance(enabled,bool):raise ValueError("设备变更参数无效")
    from ctypes import wintypes as w
    if not isinstance(instance,str) or not instance or len(instance)>512 or "\0" in instance:
        raise ValueError("设备编号无效")
    class DeviceInfo(ctypes.Structure):
        _fields_=[("cbSize",w.DWORD),("ClassGuid",w.BYTE*16),("DevInst",w.DWORD),("Reserved",ctypes.c_size_t)]
    class InstallParams(ctypes.Structure):
        _fields_=[("cbSize",w.DWORD),("Flags",w.DWORD),("FlagsEx",w.DWORD),("hwndParent",w.HWND),
            ("InstallMsgHandler",ctypes.c_void_p),("InstallMsgHandlerContext",ctypes.c_void_p),
            ("FileQueue",ctypes.c_void_p),("ClassInstallReserved",ctypes.c_size_t),("Reserved",w.DWORD),("DriverPath",w.WCHAR*260)]
    class Header(ctypes.Structure):
        _fields_=[("cbSize",w.DWORD),("InstallFunction",w.DWORD)]
    class Change(ctypes.Structure):
        _fields_=[("ClassInstallHeader",Header),("StateChange",w.DWORD),("Scope",w.DWORD),("HwProfile",w.DWORD)]
    api=ctypes.WinDLL("setupapi",use_last_error=True)
    api.SetupDiCreateDeviceInfoList.argtypes=[ctypes.c_void_p,w.HWND];api.SetupDiCreateDeviceInfoList.restype=w.HANDLE
    signatures={
        "SetupDiOpenDeviceInfoW":[w.HANDLE,w.LPCWSTR,w.HWND,w.DWORD,ctypes.POINTER(DeviceInfo)],
        "SetupDiGetDeviceInstallParamsW":[w.HANDLE,ctypes.POINTER(DeviceInfo),ctypes.POINTER(InstallParams)],
        "SetupDiSetDeviceInstallParamsW":[w.HANDLE,ctypes.POINTER(DeviceInfo),ctypes.POINTER(InstallParams)],
        "SetupDiSetClassInstallParamsW":[w.HANDLE,ctypes.POINTER(DeviceInfo),ctypes.POINTER(Change),w.DWORD],
        "SetupDiCallClassInstaller":[w.DWORD,w.HANDLE,ctypes.POINTER(DeviceInfo)],
        "SetupDiDestroyDeviceInfoList":[w.HANDLE]}
    for name,args in signatures.items():
        function=getattr(api,name);function.argtypes=args;function.restype=w.BOOL
    handle=api.SetupDiCreateDeviceInfoList(None,None)
    if not handle or handle==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
    def checked(value):
        if not value:raise ctypes.WinError(ctypes.get_last_error())
    try:
        info=DeviceInfo();info.cbSize=ctypes.sizeof(info)
        checked(api.SetupDiOpenDeviceInfoW(handle,instance,None,0,ctypes.byref(info)))
        device_class=str(uuid.UUID(bytes_le=bytes(info.ClassGuid)))
        if device_class not in allowed_classes or device_class=="c166523c-fe0c-4a94-a586-f1a80cfbbf3e" and capture_endpoint(instance) is None:
            raise ValueError("设备类别已变化，未操作；不会停用父控制器或扬声器")
        params=InstallParams();params.cbSize=ctypes.sizeof(params)
        checked(api.SetupDiGetDeviceInstallParamsW(handle,ctypes.byref(info),ctypes.byref(params)))
        params.Flags|=0x00800000  # DI_QUIETINSTALL: no installer-generated dialogs.
        params.hwndParent=None
        checked(api.SetupDiSetDeviceInstallParamsW(handle,ctypes.byref(info),ctypes.byref(params)))
        change=Change();change.ClassInstallHeader=Header(ctypes.sizeof(Header),0x12)
        change.StateChange=1 if enabled else 2;change.Scope=scope  # GLOBAL or current hardware profile; no parent device.
        checked(api.SetupDiSetClassInstallParamsW(handle,ctypes.byref(info),ctypes.byref(change),ctypes.sizeof(change)))
        checked(api.SetupDiCallClassInstaller(0x12,handle,ctypes.byref(info)))
        checked(api.SetupDiGetDeviceInstallParamsW(handle,ctypes.byref(info),ctypes.byref(params)))
        return {"method":"setupapi","reboot_required":bool(params.Flags&0x180)}
    finally:
        api.SetupDiDestroyDeviceInfoList(handle)

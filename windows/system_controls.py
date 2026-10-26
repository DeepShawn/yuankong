"""Windows endpoint audio, using the built-in Core Audio COM interfaces."""

import ctypes
import uuid


class GUID(ctypes.Structure):
    _fields_ = [("data", ctypes.c_ubyte * 16)]

    def __init__(self, value):
        super().__init__()
        self.data[:] = uuid.UUID(value).bytes_le


def _check(hr):
    if hr < 0:
        raise RuntimeError("无法访问电脑音频设备 (0x%08X)，请检查默认输出设备" % (hr & 0xffffffff))


def _method(pointer, index, *types):
    vtable = ctypes.cast(pointer, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    return ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, *types)(vtable[index])


def endpoint_audio(volume=None, muted=None):
    """Initialize COM on this RPC thread and always release its endpoint objects."""
    ole = ctypes.WinDLL("ole32")
    ole.CoInitializeEx.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    ole.CoInitializeEx.restype = ctypes.c_long
    ole.CoCreateInstance.argtypes = [ctypes.POINTER(GUID), ctypes.c_void_p, ctypes.c_ulong,
                                    ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p)]
    ole.CoCreateInstance.restype = ctypes.c_long
    hr = ole.CoInitializeEx(None, 0)
    # RPC_E_CHANGED_MODE means the thread already has a usable COM apartment.
    if hr < 0 and (hr & 0xffffffff) != 0x80010106:
        _check(hr)
    release_com = hr >= 0
    objects = []
    try:
        enumerator = ctypes.c_void_p()
        _check(ole.CoCreateInstance(ctypes.byref(GUID("BCDE0395-E52F-467C-8E3D-C4579291692E")), None, 23,
                                   ctypes.byref(GUID("A95664D2-9614-4F35-A746-DE8DB63617E6")), ctypes.byref(enumerator)))
        objects.append(enumerator)
        device = ctypes.c_void_p()
        _check(_method(enumerator, 4, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p))(
            enumerator, 0, 1, ctypes.byref(device)))
        objects.append(device)
        endpoint = ctypes.c_void_p()
        _check(_method(device, 3, ctypes.POINTER(GUID), ctypes.c_ulong, ctypes.c_void_p,
                       ctypes.POINTER(ctypes.c_void_p))(device,
            ctypes.byref(GUID("5CDF2C82-841E-4546-9722-0CF74078229A")), 23, None, ctypes.byref(endpoint)))
        objects.append(endpoint)
        if volume is not None:
            _check(_method(endpoint, 7, ctypes.c_float, ctypes.c_void_p)(endpoint, volume / 100.0, None))
        if muted is not None:
            _check(_method(endpoint, 14, ctypes.c_int, ctypes.c_void_p)(endpoint, int(muted), None))
        level = ctypes.c_float()
        mute = ctypes.c_int()
        _check(_method(endpoint, 9, ctypes.POINTER(ctypes.c_float))(endpoint, ctypes.byref(level)))
        _check(_method(endpoint, 15, ctypes.POINTER(ctypes.c_int))(endpoint, ctypes.byref(mute)))
        return {"volume": max(0, min(100, round(level.value * 100))), "muted": bool(mute.value)}
    finally:
        for pointer in reversed(objects):
            _method(pointer, 2)(pointer)
        if release_com:
            ole.CoUninitialize()


def audio_request(q, error, backend=endpoint_audio):
    action = q.get("action", "get")
    if action not in ("get", "set"):
        raise error("无效的音量操作")
    if action == "get":
        return backend()
    volume, muted = q.get("volume"), q.get("muted")
    if volume is None and muted is None:
        raise error("请提供音量或静音状态")
    if volume is not None and (isinstance(volume, bool) or not isinstance(volume, int) or not 0 <= volume <= 100):
        raise error("音量必须为 0 到 100 的整数")
    if muted is not None and not isinstance(muted, bool):
        raise error("静音状态必须为布尔值")
    return backend(volume, muted)

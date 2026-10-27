"""Temporary touchscreen pause with an independent pipe/heartbeat recovery worker."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import Future, TimeoutError as WorkerTimeout


class TouchDevices:
    def __init__(self):
        from ctypes import wintypes as w
        self.w = w
        self.cm = ctypes.WinDLL("cfgmgr32")
        self.cm.CM_Locate_DevNodeW.argtypes = [ctypes.POINTER(w.DWORD), w.LPWSTR, w.ULONG]
        self.cm.CM_Locate_DevNodeW.restype = w.ULONG
        self.cm.CM_Get_DevNode_Status.argtypes = [ctypes.POINTER(w.ULONG), ctypes.POINTER(w.ULONG), w.DWORD, w.ULONG]
        self.cm.CM_Get_DevNode_Status.restype = w.ULONG
        for name in ("CM_Disable_DevNode", "CM_Enable_DevNode"):
            getattr(self.cm, name).argtypes = [w.DWORD, w.ULONG]
            getattr(self.cm, name).restype = w.ULONG
        self.disabled = []

    @staticmethod
    def inventory():
        # Enumerate only present HIDClass nodes. No WMI, PowerShell startup or UI.
        from ctypes import wintypes as w
        class DeviceInfo(ctypes.Structure):
            _fields_ = [("cbSize", w.DWORD), ("ClassGuid", w.BYTE * 16),
                        ("DevInst", w.DWORD), ("Reserved", ctypes.c_size_t)]
        api = ctypes.WinDLL("setupapi", use_last_error=True)
        signatures = {
            "SetupDiGetClassDevsW": ([ctypes.c_void_p, w.LPCWSTR, w.HWND, w.DWORD], w.HANDLE),
            "SetupDiEnumDeviceInfo": ([w.HANDLE, w.DWORD, ctypes.POINTER(DeviceInfo)], w.BOOL),
            "SetupDiGetDeviceRegistryPropertyW": ([w.HANDLE, ctypes.POINTER(DeviceInfo), w.DWORD,
                ctypes.POINTER(w.DWORD), ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL),
            "SetupDiGetDeviceInstanceIdW": ([w.HANDLE, ctypes.POINTER(DeviceInfo), w.LPWSTR,
                w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL),
            "SetupDiDestroyDeviceInfoList": ([w.HANDLE], w.BOOL),
        }
        for name, (args, result) in signatures.items():
            function = getattr(api, name); function.argtypes = args; function.restype = result
        guid = (w.BYTE * 16).from_buffer_copy(uuid.UUID("745a17a0-74d3-11d0-b6fe-00a0c90f57da").bytes_le)
        handle = api.SetupDiGetClassDevsW(ctypes.byref(guid), None, None, 2)  # DIGCF_PRESENT
        if not handle or handle == ctypes.c_void_p(-1).value:
            raise RuntimeError("无法枚举 HID 触屏设备，请检查 Windows 设备服务")
        def ids_property(info, key):
            size, kind = w.DWORD(), w.DWORD()
            if not api.SetupDiGetDeviceRegistryPropertyW(handle, ctypes.byref(info), key,
                    ctypes.byref(kind), None, 0, ctypes.byref(size)):
                code = ctypes.get_last_error()
                if code == 13: return []  # Property absent, not an unreadable device.
                if code != 122: raise RuntimeError("读取 HID 属性失败 (%d)，未暂停输入" % code)
            if not size.value or size.value > 65536 or size.value % 2:
                raise RuntimeError("HID 属性长度无效，未暂停输入")
            data = (w.BYTE * size.value)()
            if not api.SetupDiGetDeviceRegistryPropertyW(handle, ctypes.byref(info), key,
                    ctypes.byref(kind), data, len(data), ctypes.byref(size)):
                raise RuntimeError("读取 HID 属性失败，未暂停输入")
            if kind.value != 7: raise RuntimeError("HID 标识属性类型无效，未暂停输入")  # REG_MULTI_SZ
            return bytes(data).decode("utf-16-le").rstrip("\0").upper().split("\0")
        try:
            ids = []
            for index in range(4096):
                info = DeviceInfo(); info.cbSize = ctypes.sizeof(info)
                if not api.SetupDiEnumDeviceInfo(handle, index, ctypes.byref(info)):
                    if ctypes.get_last_error() == 259: return ids
                    raise RuntimeError("枚举 HID 设备失败，未暂停输入")
                identifiers = ids_property(info, 1) + ids_property(info, 2)
                if not {"HID_DEVICE_UP:000D_U:0004", "HID_DEVICE_SYSTEM_TOUCH"}.intersection(identifiers):
                    continue
                instance = ctypes.create_unicode_buffer(513)
                if not api.SetupDiGetDeviceInstanceIdW(handle, ctypes.byref(info), instance, len(instance), None):
                    raise RuntimeError("读取触屏设备编号失败，未暂停输入")
                if not instance.value: raise RuntimeError("触屏设备编号为空，未暂停输入")
                if instance.value not in ids: ids.append(instance.value)
            raise RuntimeError("HID 设备数量超出限制，未暂停输入")
        finally:
            api.SetupDiDestroyDeviceInfoList(handle)

    @staticmethod
    def touch_expected():
        user=ctypes.WinDLL("user32")
        return bool(user.GetSystemMetrics(94) & 3 or user.GetSystemMetrics(95)>0)

    def status(self, instance):
        status,problem=self.w.ULONG(),self.w.ULONG()
        result=self.cm.CM_Get_DevNode_Status(ctypes.byref(status),ctypes.byref(problem),self.node(instance),0)
        if result:raise RuntimeError("读取触屏状态失败 (0x%X)，未确认锁定"%result)
        return bool(status.value&8),problem.value

    def node(self, instance):
        node = self.w.DWORD()
        result = self.cm.CM_Locate_DevNodeW(ctypes.byref(node), instance, 0)
        if result:
            raise RuntimeError("触屏已断开或无法定位 (0x%X)" % result)
        return node

    def pause(self, stopping):
        devices=self.inventory()
        self.detected=len(devices);self.already_disabled=0
        self.originally_disabled=[]
        if not devices and self.touch_expected():
            raise RuntimeError("Windows 报告触控设备，但无法识别标准 HID 触屏；未降级为仅暂停键鼠，请检查驱动")
        for instance in devices:
            if stopping.is_set():
                raise RuntimeError("输入暂停已取消")
            node = self.node(instance)
            active,problem=self.status(instance)
            if not active and problem==22:
                self.already_disabled+=1
                self.originally_disabled.append(instance)
                continue
            if not active or problem:
                raise RuntimeError("触屏状态异常，未确认锁定；请检查管理员权限和驱动")
            # CM_DISABLE_UI_NOT_OK; deliberately omit CM_DISABLE_PERSIST.
            result = self.cm.CM_Disable_DevNode(node, 4)
            if result:
                raise RuntimeError("Windows 拒绝暂停触屏，需要管理员权限 (0x%X)" % result)
            self.disabled.append(instance)
            deadline=time.monotonic()+2
            while True:
                if self.status(instance)==(False,22):break
                if time.monotonic()>=deadline:
                    raise RuntimeError("驱动未确认触屏已禁用，已请求恢复，未报告锁定成功")
                time.sleep(.1)
        return len(self.disabled)

    def verify(self):
        for instance in self.disabled+getattr(self,"originally_disabled",[]):
            if self.status(instance)!=(False,22):
                raise RuntimeError("触屏状态已被外部更改，正在解除本机输入暂停")

    def restore(self):
        errors = []
        for instance in list(self.disabled):
            try:
                node = self.node(instance)
                result = self.cm.CM_Enable_DevNode(node, 0)
                if result:
                    raise RuntimeError("触屏恢复失败 (0x%X)" % result)
                deadline=time.monotonic()+2
                while True:
                    if self.status(instance)==(True,0):break
                    if time.monotonic()>=deadline:raise RuntimeError("触屏驱动尚未确认恢复，恢复进程将重试")
                    time.sleep(.1)
                self.disabled.remove(instance)
            except Exception as exc:
                errors.append(str(exc))
        return errors


def recovery_worker(devices, input_stream, output_stream, watchdog=6):
    stop = threading.Event()
    seen = [time.monotonic()]
    def read():
        try:
            for line in input_stream:
                if json.loads(line).get("action") != "ping":
                    break
                seen[0] = time.monotonic()
        except Exception:
            pass
        finally:
            stop.set()
    threading.Thread(target=read, daemon=True).start()
    try:
        count = devices.pause(stop)
        if stop.is_set():
            raise RuntimeError("原接收端已结束，恢复触屏")
        seen[0] = time.monotonic()
        output_stream.write(json.dumps({"ok": True, "count": count,"detected":getattr(devices,"detected",count),
                                      "already_disabled":getattr(devices,"already_disabled",0)}) + "\n"); output_stream.flush()
        while not stop.wait(0.2):
            if time.monotonic() - seen[0] > watchdog:
                break
            if hasattr(devices,"verify"):devices.verify()
    except Exception as exc:
        output_stream.write(json.dumps({"ok": False, "error": str(exc)}) + "\n"); output_stream.flush()
    finally:
        # This process remains alive if the agent dies; retry transient driver failures.
        for attempt in range(30):
            if not devices.restore():
                return 0
            time.sleep(1)
    return 1


def worker_main():
    # Frozen GUI builds may have None stdio even though Popen supplied native pipes.
    if sys.stdin is None or sys.stdout is None:
        import msvcrt
        kernel = ctypes.WinDLL("kernel32")
        kernel.GetStdHandle.argtypes = [ctypes.c_ulong]
        kernel.GetStdHandle.restype = ctypes.c_void_p
        if sys.stdin is None:
            sys.stdin = os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-10 & 0xffffffff), os.O_RDONLY), "r", encoding="utf-8")
        if sys.stdout is None:
            sys.stdout = os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-11 & 0xffffffff), os.O_WRONLY), "w", encoding="utf-8")
    return recovery_worker(TouchDevices(), sys.stdin, sys.stdout)


class TouchPause:
    def __init__(self):
        self.process = None
        self.recovering = None
        self.count = 0
        self.detected = 0
        self.already_disabled = 0
        self.last_ping = 0

    def start(self):
        if self.recovering is not None:
            if self.recovering.poll() is None:
                raise RuntimeError("上次触屏暂停仍在恢复，请稍后重试；未重新锁定输入")
            self.recovering.stdout.close()
            self.recovering=None
        self.stop()
        # Probe HID even when desktop metrics are zero (external touchscreens).
        if getattr(sys, "frozen", False):
            command = [sys.executable, "--touch-worker"]
        else:
            executable = Path(sys.executable)
            if executable.name.lower() == "pythonw.exe":
                executable = executable.with_name("python.exe")
            command = [str(executable), str(Path(__file__).resolve())]
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
                                        creationflags=0x08000000, close_fds=True)
        future = Future()
        process = self.process
        def ready():
            try: future.set_result(json.loads(process.stdout.readline()))
            except Exception as exc: future.set_exception(exc)
        threading.Thread(target=ready, daemon=True).start()
        try:
            result = future.result(timeout=18)
            if not result.get("ok"):
                raise RuntimeError(result.get("error", "触屏暂停失败"))
            self.count = result["count"]
            self.detected=result.get("detected",self.count)
            self.already_disabled=result.get("already_disabled",0)
            if not self.count and not self.detected:
                detected,already=self.detected,self.already_disabled
                self.stop();self.detected=detected;self.already_disabled=already
                return
            self.ping()
        except WorkerTimeout:
            self.stop()
            raise RuntimeError("触屏识别或驱动操作超时，未确认输入暂停；已请求恢复，请稍后重试") from None
        except Exception:
            self.stop(); raise

    def ping(self):
        if self.process is None:
            return
        if self.process.poll() is not None:
            detail="触屏恢复进程已结束，将解除本机输入暂停"
            try:
                for line in self.process.stdout:
                    result=json.loads(line)
                    if result.get("error"):detail=str(result["error"])
            except (OSError,ValueError):pass
            raise RuntimeError(detail)
        if time.monotonic() - self.last_ping >= 0.5:
            self.process.stdin.write('{"action":"ping"}\n'); self.process.stdin.flush()
            self.last_ping = time.monotonic()

    def stop(self):
        process, self.process = self.process, None
        self.count = 0
        self.detected = 0
        self.already_disabled = 0
        if process is None:
            return
        try:
            if process.poll() is None:
                try:
                    process.stdin.write('{"action":"stop"}\n'); process.stdin.flush()
                except OSError:
                    pass
            process.stdin.close()
            try:
                code = process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                self.recovering=process
                raise RuntimeError("触屏驱动仍在恢复，独立恢复进程会继续重试；请勿结束该进程")
            if code:
                raise RuntimeError("触屏驱动恢复失败，请重新连接设备或重启 Windows")
        finally:
            if process.poll() is not None:
                process.stdout.close()


if __name__ == "__main__":
    raise SystemExit(worker_main())

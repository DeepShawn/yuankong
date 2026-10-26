"""Temporary touchscreen pause with an independent pipe/heartbeat recovery worker."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from concurrent.futures import Future


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
        script = r"""
$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
$ids = @(Get-PnpDevice -PresentOnly -Class HIDClass | Where-Object { $_.Status -eq 'OK' } | ForEach-Object {
  $compatible = @(Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName 'DEVPKEY_Device_CompatibleIds' -ErrorAction SilentlyContinue).Data
  if ($compatible -contains 'HID_DEVICE_UP:000D_U:0004') { [string]$_.InstanceId }
})
ConvertTo-Json -InputObject @($ids) -Compress
"""
        executable = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                                  "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        result = subprocess.run([executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=12, creationflags=0x08000000)
        if result.returncode:
            raise RuntimeError("无法识别触屏设备，请检查管理员权限与 HID 驱动")
        ids = json.loads(result.stdout.decode("utf-8-sig"))
        if not isinstance(ids, list) or not ids:
            raise RuntimeError("检测到触屏，但未找到可安全暂停的标准 HID 触屏；未进入输入暂停")
        return ids

    def node(self, instance):
        node = self.w.DWORD()
        result = self.cm.CM_Locate_DevNodeW(ctypes.byref(node), instance, 0)
        if result:
            raise RuntimeError("触屏已断开或无法定位 (0x%X)" % result)
        return node

    def pause(self, stopping):
        for instance in self.inventory():
            if stopping.is_set():
                raise RuntimeError("输入暂停已取消")
            node = self.node(instance)
            status, problem = self.w.ULONG(), self.w.ULONG()
            result = self.cm.CM_Get_DevNode_Status(ctypes.byref(status), ctypes.byref(problem), node, 0)
            if result or not status.value & 8:  # DN_STARTED; leave pre-disabled devices untouched.
                continue
            # CM_DISABLE_UI_NOT_OK; deliberately omit CM_DISABLE_PERSIST.
            result = self.cm.CM_Disable_DevNode(node, 4)
            if result:
                raise RuntimeError("Windows 拒绝暂停触屏，需要管理员权限 (0x%X)" % result)
            self.disabled.append(instance)
        if not self.disabled:
            raise RuntimeError("没有可暂停的运行中触屏设备")
        return len(self.disabled)

    def restore(self):
        errors = []
        for instance in list(self.disabled):
            try:
                node = self.node(instance)
                result = self.cm.CM_Enable_DevNode(node, 0)
                if result:
                    raise RuntimeError("触屏恢复失败 (0x%X)" % result)
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
        output_stream.write(json.dumps({"ok": True, "count": count}) + "\n"); output_stream.flush()
        while not stop.wait(0.2):
            if time.monotonic() - seen[0] > watchdog:
                break
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
        self.count = 0
        self.last_ping = 0

    def start(self):
        self.stop()
        user = ctypes.WinDLL("user32")
        if not user.GetSystemMetrics(94) & 3:  # SM_DIGITIZER, integrated/external touch.
            self.count = 0
            return
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
            self.ping()
        except Exception:
            self.stop(); raise

    def ping(self):
        if self.process is None:
            return
        if self.process.poll() is not None:
            raise RuntimeError("触屏恢复进程已结束，将解除本机输入暂停")
        if time.monotonic() - self.last_ping >= 0.5:
            self.process.stdin.write('{"action":"ping"}\n'); self.process.stdin.flush()
            self.last_ping = time.monotonic()

    def stop(self):
        process, self.process = self.process, None
        self.count = 0
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
                raise RuntimeError("触屏驱动仍在恢复，独立恢复进程会继续重试；请勿结束该进程")
            if code:
                raise RuntimeError("触屏驱动恢复失败，请重新连接设备或重启 Windows")
        finally:
            if process.poll() is not None:
                process.stdout.close()


if __name__ == "__main__":
    raise SystemExit(worker_main())

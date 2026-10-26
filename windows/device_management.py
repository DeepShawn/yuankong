"""Removable storage policies and non-interactive, veto-aware device ejection."""

import ctypes
import json
import os
import subprocess
import threading
from pathlib import Path

ROOT = r"SOFTWARE\Policies\Microsoft\Windows\RemovableStorageDevices"
CLASSES = {
    "storage": ("{53f5630d-b6bf-11d0-94f2-00a0c91efb8b}",),
    "portable": ("{6AC27878-A6FA-4155-BA85-F98F491D4F33}", "{F33FDC04-D1AC-4E8E-9A30-19BBD4B108AE}"),
}
NOTICE = "策略已保存；请重新插拔设备，必要时重启 Windows。域策略或其他设备限制可能覆盖本设置。"

# No request parameters are interpolated into this script.
INVENTORY = r"""
$items = @()
$physical = @(Get-CimInstance Win32_DiskDrive)
foreach ($disk in @(Get-Disk | Where-Object { $_.BusType -eq 'USB' })) {
    $hardware = $physical | Where-Object { $_.Index -eq $disk.Number } | Select-Object -First 1
    $paths = @()
    try { $paths = @(Get-Partition -DiskNumber $disk.Number -ErrorAction Stop | ForEach-Object { $_.AccessPaths }) } catch {}
    $items += [pscustomobject]@{
        id = [string]$hardware.PNPDeviceID; name = [string]$disk.FriendlyName
        kind = 'storage'; size = [long]$disk.Size; paths = @($paths)
        protected = [bool]($disk.IsBoot -or $disk.IsSystem)
        ejectable = [bool]($hardware.PNPDeviceID -and !$disk.IsBoot -and !$disk.IsSystem)
    }
}
foreach ($device in @(Get-PnpDevice -PresentOnly | Where-Object { $_.Class -eq 'WPD' })) {
    # Some phones expose a software WPD node whose parent is the USB interface.
    # Exclude disk volumes and non-USB WPD devices while following that ancestry.
    $ancestor = [string]$device.InstanceId
    $usb = $false
    for ($level = 0; $level -lt 8 -and $ancestor; $level++) {
        if ($ancestor -like 'USBSTOR\*' -or $ancestor -like 'SCSI\*') { break }
        if ($ancestor -like 'USB\*') { $usb = $true; break }
        $ancestor = [string](Get-PnpDeviceProperty -InstanceId $ancestor -KeyName 'DEVPKEY_Device_Parent' -ErrorAction SilentlyContinue).Data
    }
    if (!$usb) { continue }
    $items += [pscustomobject]@{
        id = [string]$device.InstanceId; name = [string]$device.FriendlyName
        kind = 'portable'; size = 0; paths = @(); protected = $false; ejectable = $true
    }
}
ConvertTo-Json -InputObject @($items) -Depth 5 -Compress
"""


def inventory():
    script = ("$ErrorActionPreference='Stop'; [Console]::OutputEncoding="
              "[System.Text.UTF8Encoding]::new($false); " + INVENTORY)
    executable = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                              "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    result = subprocess.run([executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=35, creationflags=0x08000000)
    if result.returncode:
        raise RuntimeError("读取可移动设备失败：" + result.stderr.decode("utf-8", "replace").strip()[:1200])
    items = json.loads(result.stdout.decode("utf-8-sig"))
    if not isinstance(items, list):
        raise RuntimeError("设备列表格式错误")
    return items


def eject_device(instance_id):
    from ctypes import wintypes as w
    cm = ctypes.WinDLL("cfgmgr32")
    cm.CM_Locate_DevNodeW.argtypes = [ctypes.POINTER(w.DWORD), w.LPWSTR, w.ULONG]
    cm.CM_Locate_DevNodeW.restype = w.ULONG
    cm.CM_Request_Device_EjectW.argtypes = [w.DWORD, ctypes.POINTER(w.DWORD), w.LPWSTR, w.ULONG, w.ULONG]
    cm.CM_Request_Device_EjectW.restype = w.ULONG
    node, veto = w.DWORD(), w.DWORD()
    reason = ctypes.create_unicode_buffer(512)
    result = cm.CM_Locate_DevNodeW(ctypes.byref(node), instance_id, 0)
    if result:
        raise RuntimeError("设备已断开或无法定位 (0x%X)，请刷新列表" % result)
    # Supplying veto outputs prevents the PnP manager from displaying a veto dialog.
    result = cm.CM_Request_Device_EjectW(node, ctypes.byref(veto), reason, len(reason), 0)
    if result:
        raise RuntimeError("无法安全弹出，设备可能正被使用或权限不足 (0x%X, veto=%d)：%s" %
                           (result, veto.value, reason.value))


class Registry:
    def read(self, path, name, user=False):
        import winreg as reg
        try:
            with reg.OpenKey(reg.HKEY_CURRENT_USER if user else reg.HKEY_LOCAL_MACHINE, path, 0,
                             reg.KEY_READ | reg.KEY_WOW64_64KEY) as key:
                value, kind = reg.QueryValueEx(key, name)
                if kind != reg.REG_DWORD:
                    raise RuntimeError("设备策略不是 DWORD，未修改：" + name)
                return value
        except FileNotFoundError:
            return None

    def write(self, path, name, value):
        import winreg as reg
        if value is None:
            try:
                with reg.OpenKey(reg.HKEY_LOCAL_MACHINE, path, 0,
                                 reg.KEY_SET_VALUE | reg.KEY_WOW64_64KEY) as key:
                    reg.DeleteValue(key, name)
            except FileNotFoundError:
                pass
        else:
            with reg.CreateKeyEx(reg.HKEY_LOCAL_MACHINE, path, 0,
                                 reg.KEY_SET_VALUE | reg.KEY_WOW64_64KEY) as key:
                reg.SetValueEx(key, name, 0, reg.REG_DWORD, value)


class DeviceManager:
    def __init__(self, state_path, admin, registry=None, enumerate_devices=inventory, eject=eject_device):
        self.path = Path(state_path)
        self.admin = admin
        self.registry = registry or Registry()
        self.enumerate = enumerate_devices
        self.eject = eject
        self.lock = threading.RLock()

    def _load(self):
        if not self.path.exists():
            return {}
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise ValueError()
            for scope, record in data.items():
                length = len(CLASSES[scope]) * 2
                for field in ("original", "applied"):
                    values = record[field]
                    if len(values) != length or any(v is not None and type(v) is not int for v in values):
                        raise ValueError()
            return data
        except (ValueError, KeyError, TypeError):
            raise RuntimeError("设备策略恢复记录损坏，请先检查 device-policy.json，未修改注册表")

    def _save(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, self.path)

    @staticmethod
    def _entries(scope):
        return [(ROOT + "\\" + cls, name) for cls in CLASSES[scope] for name in ("Deny_Read", "Deny_Write")]

    def policies(self):
        with self.lock:
            saved = self._load()
            result = {}
            for scope in CLASSES:
                entries = self._entries(scope)
                machine = [self.registry.read(*entry) for entry in entries]
                effective = [bool(value) or bool(self.registry.read(*entry, user=True))
                             for entry, value in zip(entries, machine)]
                mode = "blocked" if any(effective[::2]) else "read_only" if any(effective[1::2]) else "allow"
                result[scope] = {"mode": mode, "managed": scope in saved,
                                 "conflict": scope in saved and machine != saved[scope]["applied"]}
            result["deny_all"] = bool(self.registry.read(ROOT, "Deny_All") or
                                      self.registry.read(ROOT, "Deny_All", user=True))
            return result

    def set_policy(self, scope, mode):
        with self.lock:
            entries = self._entries(scope)
            previous = [self.registry.read(*entry) for entry in entries]
            saved = self._load()
            old_saved = json.loads(json.dumps(saved))
            record = saved.get(scope)
            if record and previous != record["applied"]:
                raise RuntimeError("策略已被其他程序更改，未覆盖；请由本机管理员检查")
            if mode == "allow":
                if record is None:
                    return {"policies": self.policies(), "notice": "没有 LanCmd 设置的锁；原有 Windows 策略已保留。"}
                desired = record["original"]
            else:
                desired = [int(mode == "blocked"), 1] * len(CLASSES[scope])
            saved[scope] = {"original": record["original"] if record else previous, "applied": desired}
            # Persist the recovery record before changing policy, including across crashes.
            self._save(saved)
            written = []
            try:
                for (path, name), value in zip(entries, desired):
                    self.registry.write(path, name, value)
                    written.append((path, name))
                if mode == "allow":
                    del saved[scope]
                    self._save(saved)
            except Exception:
                # Keep the recovery record if rollback itself fails.
                for entry, value in zip(entries, previous):
                    if entry in written:
                        self.registry.write(*entry, value)
                self._save(old_saved)
                raise
            return {"policies": self.policies(), "notice": NOTICE}

    def request(self, q, error):
        if os.name != "nt":
            raise error("可移动设备管理仅支持 Windows")
        action = q.get("action", "list")
        if action not in ("list", "policy", "eject"):
            raise error("无效的设备管理操作")
        try:
            if action == "list":
                return {"devices": self.enumerate(), "policies": self.policies(), "admin": self.admin()}
            if not self.admin():
                raise error("需要管理员权限，请在电脑 Start.exe 点击「管理员启动 / 重启」并允许 UAC；未更改设备")
            if action == "policy":
                scope, mode = q.get("scope"), q.get("mode")
                if not isinstance(scope, str) or scope not in CLASSES or mode not in ("allow", "read_only", "blocked"):
                    raise error("无效的设备策略")
                if scope == "portable" and mode == "read_only":
                    raise error("MTP/PTP 设备只支持锁定或恢复原策略")
                return self.set_policy(scope, mode)
            instance_id = q.get("device_id")
            if not isinstance(instance_id, str) or not instance_id or len(instance_id) > 512 or "\x00" in instance_id:
                raise error("无效的设备 ID")
            # Re-enumerate: never trust a stale list, disk number, or arbitrary caller-supplied PnP path.
            device = next((d for d in self.enumerate() if d["id"] == instance_id), None)
            if device is None or not device.get("ejectable") or device.get("protected"):
                raise error("设备已断开或不允许弹出，请刷新；系统磁盘不会被弹出")
            self.eject(instance_id)
            return {"ejected": instance_id, "notice": "设备已安全弹出"}
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as exc:
            raise error(str(exc))

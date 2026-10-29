"""Explicit per-install startup and Windows mobile hotspot configuration.

Uses the current user's normal Run entry and public Windows tethering API.
Never bypasses disk restoration policy, disables a firewall or raises UAC.
"""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import uuid
from local_upgrade import ordinary
from contextlib import nullcontext
from network_management import network_change


DEFAULTS = dict(autostart=False, auto_hotspot=False, auto_firewall=False)
PERSISTENCE_NOTICE = ("若 C 盘在重启时还原，请将完整接收端放在保留盘；还须在还原软件维护模式或保留策略中"
    "保存 Windows 自启动项和热点配置。仅把程序放在 D 盘不能保证开机触发，不绕过还原保护。")

HOTSPOT = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType=WindowsRuntime]
    $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
    $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
    if (!$profile) { throw '没有可用于 Windows 移动热点的网络连接，请先配置系统网络与移动热点' }
    $capability = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::GetTetheringCapabilityFromConnectionProfile($profile)
    if ($capability.ToString() -ne 'Enabled') { throw ('Windows 移动热点不可用：' + $capability.ToString()) }
    $manager = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
    if ($env:LANCMD_START_HOTSPOT -eq '1' -and $manager.TetheringOperationalState.ToString() -ne 'On') {
        $operation = $manager.StartTetheringAsync()
        $resultType = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
        $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Count -eq 1 -and
            $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1
        if (!$method) { throw '当前系统不支持移动热点异步调用' }
        $task = $method.MakeGenericMethod($resultType).Invoke($null, @($operation))
        if (!$task.Wait(25000)) { throw '等待 Windows 开启热点超时，请检查无线网卡和移动热点配置' }
        $result = $task.Result
        if ($result.Status.ToString() -ne 'Success') { throw ('Windows 开启热点失败：' + $result.Status.ToString() + ' ' + $result.AdditionalErrorMessage) }
    }
    $configuration = $manager.GetCurrentAccessPointConfiguration()
    @{supported=$true;state=$manager.TetheringOperationalState.ToString();name=$configuration.Ssid;
      clients=[int]$manager.ClientCount;max_clients=[int]$manager.MaxClientCount} | ConvertTo-Json -Compress
} catch {
    @{supported=$false;state='Unavailable';error=$_.Exception.Message} | ConvertTo-Json -Compress
}
"""


class WindowsStartupNetwork:
    def __init__(self, root, admin, ports, netsh):
        self.root = Path(root)
        self.admin, self.ports, self.netsh = admin, ports, netsh

    def registration(self):
        import winreg as r
        try:
            with r.OpenKey(r.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
                command, kind = r.QueryValueEx(key, "LanCmdAgent")
        except FileNotFoundError:
            command = ""
        expected = '"%s"' % (self.root / "LanCmdAgent.exe")
        return dict(enabled=command.casefold() == expected.casefold(), command=command,
                    other_installation=bool(command and command.casefold() != expected.casefold()))

    def register(self, enabled):
        import winreg as r
        executable = self.root / "LanCmdAgent.exe"
        ordinary(executable)
        with r.CreateKeyEx(r.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                           0, r.KEY_QUERY_VALUE | r.KEY_SET_VALUE) as key:
            if enabled:
                r.SetValueEx(key, "LanCmdAgent", 0, r.REG_SZ, '"%s"' % executable)
            elif self.registration()["enabled"]:
                r.DeleteValue(key, "LanCmdAgent")
        return self.registration()

    def firewall(self):
        if not self.admin():
            raise ValueError("自动放行端口需要管理员接收端；不会关闭防火墙或自动弹出 UAC")
        tcp, udp = self.ports()
        program = str(self.root / "python/pythonw.exe")
        ordinary(program)
        for suffix, protocol, port in (("TCP", "TCP", tcp), ("UDP", "UDP", udp)):
            if type(port) is not int or not 1 <= port <= 65535:
                raise ValueError("接收端端口配置无效")
            name = "LanCmd-" + suffix
            exists, _ = self.netsh(["advfirewall", "firewall", "show", "rule", "name=" + name])
            fields = ["dir=in", "action=allow", "protocol=" + protocol, "localport=" + str(port),
                      "program=" + program, "remoteip=LocalSubnet", "profile=any", "enable=yes"]
            command = ["advfirewall", "firewall", "set", "rule", "name=" + name, "new"] if exists == 0 else [
                       "advfirewall", "firewall", "add", "rule", "name=" + name]
            code, output = self.netsh(command + fields)
            if code:
                raise OSError("局域网端口放行失败：" + output[:1200])
        return dict(enabled=True, tcp=tcp, udp=udp, remote="LocalSubnet", program=program,
                    notice="仅放行本接收端的局域网入站端口，防火墙保持开启")

    def hotspot(self, start=False):
        executable = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"
        with network_change() if start else nullcontext():
            result = subprocess.run([str(executable), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", HOTSPOT],
                env=dict(os.environ, LANCMD_START_HOTSPOT="1" if start else "0"),
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                creationflags=0x08000000, timeout=40)
        if result.returncode:
            raise OSError("移动热点调用失败：" + result.stderr.decode("utf-8", "replace")[:1600])
        data = json.loads(result.stdout.decode("utf-8-sig"))
        if not isinstance(data, dict) or not isinstance(data.get("state"), str):
            raise ValueError("Windows 移动热点状态无效")
        if start and data.get("state") != "On":
            raise ValueError(data.get("error", "Windows 未确认热点已开启"))
        # Only the SSID and state are returned, never the system hotspot password.
        return {key: data[key] for key in ("supported", "state", "name", "clients", "max_clients", "error") if key in data}


class StartupNetwork:
    def __init__(self, root, backend, log=None):
        self.root = Path(root)
        self.path = self.root / "startup-network.json"
        self.backend, self.log = backend, log
        self.lock = threading.RLock()
        self.settings = dict(DEFAULTS)
        self.job = None
        self.hotspot = dict(state="Unknown")
        self.warning = ""
        self.loaded = False

    @staticmethod
    def validate(settings):
        if (not isinstance(settings, dict) or set(settings) != set(DEFAULTS) or
                any(type(value) is not bool for value in settings.values())):
            raise ValueError("自启动和网络设置须包含三个明确的开关")
        return dict(settings)

    def _load(self):
        try:
            if self.path.exists():
                if ordinary(self.path).st_size > 4096:
                    raise ValueError("启动配置过大")
                settings = self.validate(json.loads(self.path.read_text(encoding="utf-8")))
                with self.lock:self.settings = settings
        except (OSError, ValueError) as exc:
            with self.lock:self.warning = "启动配置读取失败：" + str(exc)
        finally:
            with self.lock:self.loaded = True

    def boot(self):
        # Reading the portable settings and running network APIs never blocks
        # the listener, phone handshake or a status/cancel request.
        self._start("boot")

    def _start(self, action, settings=None):
        with self.lock:
            if self.job and self.job["status"] == "running":
                raise ValueError("启动与网络操作正在执行，请等待当前结果")
            job = dict(id=uuid.uuid4().hex, status="running", phase="读取配置", created=time.time(), results={})
            self.job = job
            threading.Thread(target=self._run, args=(job, action, settings), daemon=True, name="startup-network").start()
            return copy.deepcopy(job)

    def _run(self, job, action, settings):
        def update(**fields):
            with self.lock:job.update(fields)
        def record(key, value):
            with self.lock:job["results"][key] = value
        try:
            if not self.loaded:self._load()
            if action == "configure":
                ordinary(self.root, True)
                if self.path.exists():ordinary(self.path)
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.root,
                            prefix=".startup-network-", suffix=".tmp", delete=False) as output:
                        temporary = Path(output.name)
                        json.dump(settings, output);output.flush();os.fsync(output.fileno())
                    os.replace(temporary, self.path)
                finally:
                    if temporary is not None:temporary.unlink(missing_ok=True)
                with self.lock:self.settings = dict(settings);self.warning = ""
                update(phase="设置当前用户自启动")
                record("autostart", self.backend.register(settings["autostart"]))
            with self.lock:options = dict(self.settings)
            errors = []
            if options["auto_firewall"] and action != "refresh":
                update(phase="放行接收端局域网端口")
                try:record("firewall", self.backend.firewall())
                except Exception as exc:errors.append(str(exc))
            if action != "boot" or options["auto_hotspot"]:
                update(phase="开启移动热点" if options["auto_hotspot"] and action != "refresh" else "读取移动热点状态")
                try:
                    hotspot = self.backend.hotspot(options["auto_hotspot"] and action != "refresh")
                    with self.lock:self.hotspot = dict(hotspot)
                    record("hotspot", hotspot)
                except Exception as exc:
                    errors.append(str(exc))
                    with self.lock:self.hotspot = dict(state="Unavailable", error=str(exc)[:1600])
            if errors:raise ValueError("；".join(errors))
            update(status="completed", phase="完成")
        except Exception as exc:
            update(status="failed", error=str(exc)[:2000])
            if self.log:self.log.warning("启动与网络设置：%s", exc)
        finally:
            update(finished=time.time())

    def request(self, q, error):
        try:
            if os.name != "nt" and isinstance(self.backend, WindowsStartupNetwork):
                raise ValueError("启动与移动热点仅支持 Windows")
            action = q.get("action", "status")
            if action in ("configure", "apply", "refresh"):
                settings = self.validate(q.get("settings")) if action == "configure" else None
                self._start(action, settings)
            elif action != "status":
                raise ValueError("无效的启动与网络操作")
            registration = self.backend.registration()
            with self.lock:
                return dict(settings=dict(self.settings), autostart=registration,
                            hotspot=copy.deepcopy(self.hotspot), job=copy.deepcopy(self.job),
                            directory=str(self.root), settings_file=str(self.path),
                            persistence_notice=PERSISTENCE_NOTICE, warning=self.warning)
        except (OSError, ValueError, TypeError) as exc:
            raise error(str(exc))

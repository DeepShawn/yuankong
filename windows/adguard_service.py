"""Opt-in startup of an already installed, ordinary AdGuard Home service.

No DNS, browser-history, credentials, downloads, service installation, elevation or
remote API. Consent is set only by the interactive Windows-local setup entry.
"""
import ctypes as c
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import time

SERVICE = 'AdGuardHome'
REGISTRY = r'SOFTWARE\LocalNetworkGuardian'
VALUE = 'AdGuardHomeOptIn'
STATES = {1: 'stopped', 2: 'starting', 3: 'stopping', 4: 'running', 5: 'resuming', 6: 'pausing', 7: 'paused'}


class RegistryConsent:
    def __init__(self, key=REGISTRY):
        import winreg
        self.api = winreg
        self.key = key

    def read(self):
        w = self.api
        try:
            with w.OpenKey(w.HKEY_LOCAL_MACHINE, self.key, 0, w.KEY_READ | w.KEY_WOW64_64KEY) as key:
                data, kind = w.QueryValueEx(key, VALUE)
        except FileNotFoundError:
            return None
        if kind != w.REG_SZ or not isinstance(data, str) or len(data) > 4096:
            raise ValueError('本机启用记录无效，请重新打开 AdGuardSetup.cmd 检查')
        return json.loads(data)

    def save(self, value):
        w = self.api
        with w.CreateKeyEx(w.HKEY_LOCAL_MACHINE, self.key, 0, w.KEY_SET_VALUE | w.KEY_WOW64_64KEY) as key:
            w.SetValueEx(key, VALUE, 0, w.REG_SZ, json.dumps(value, ensure_ascii=True))
            w.FlushKey(key)

    def disable(self):
        w = self.api
        try:
            with w.OpenKey(w.HKEY_LOCAL_MACHINE, self.key, 0, w.KEY_SET_VALUE | w.KEY_WOW64_64KEY) as key:
                try:
                    w.DeleteValue(key, VALUE)
                except FileNotFoundError:
                    pass
        except FileNotFoundError:
            pass


class Config(c.Structure):
    _fields_ = [('kind', c.c_uint32), ('start', c.c_uint32), ('error', c.c_uint32),
                ('binary', c.c_wchar_p), ('group', c.c_wchar_p), ('tag', c.c_uint32),
                ('dependencies', c.c_wchar_p), ('account', c.c_wchar_p), ('display', c.c_wchar_p)]


class ServiceStatus(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ('kind', 'state', 'controls', 'exit', 'specific', 'checkpoint', 'hint')]


class NativeService:
    def __init__(self):
        self.api = c.WinDLL('advapi32', use_last_error=True)
        specs = {
            'OpenSCManagerW': ([c.c_wchar_p, c.c_wchar_p, c.c_uint32], c.c_void_p),
            'OpenServiceW': ([c.c_void_p, c.c_wchar_p, c.c_uint32], c.c_void_p),
            'CloseServiceHandle': ([c.c_void_p], c.c_int),
            'QueryServiceConfigW': ([c.c_void_p, c.c_void_p, c.c_uint32, c.POINTER(c.c_uint32)], c.c_int),
            'QueryServiceStatus': ([c.c_void_p, c.POINTER(ServiceStatus)], c.c_int),
            'StartServiceW': ([c.c_void_p, c.c_uint32, c.c_void_p], c.c_int),
            'ControlService': ([c.c_void_p, c.c_uint32, c.POINTER(ServiceStatus)], c.c_int),
        }
        for name, (args, result) in specs.items():
            fn = getattr(self.api, name)
            fn.argtypes, fn.restype = args, result

    @contextmanager
    def opened(self, rights):
        scm = self.api.OpenSCManagerW(None, None, 1)
        if not scm:
            raise c.WinError(c.get_last_error())
        service = None
        try:
            service = self.api.OpenServiceW(scm, SERVICE, rights)
            if not service:
                code = c.get_last_error()
                if code == 1060:
                    raise OSError('未安装 AdGuard Home Windows 服务，请先在电脑完成官方安装和配置')
                raise c.WinError(code)
            yield service
        finally:
            if service:
                self.api.CloseServiceHandle(service)
            self.api.CloseServiceHandle(scm)

    def inspect(self):
        with self.opened(1 | 4) as service:
            size = c.c_uint32()
            self.api.QueryServiceConfigW(service, None, 0, c.byref(size))
            if not c.sizeof(Config) <= size.value <= 65536:
                raise OSError('Windows 服务配置长度无效')
            buffer = c.create_string_buffer(size.value)
            if not self.api.QueryServiceConfigW(service, buffer, len(buffer), c.byref(size)):
                raise c.WinError(c.get_last_error())
            config = c.cast(buffer, c.POINTER(Config)).contents
            if config.kind != 0x10:
                raise ValueError('仅允许独立进程的 AdGuard Home 服务')
            command = config.binary
            status = ServiceStatus()
            if not self.api.QueryServiceStatus(service, c.byref(status)):
                raise c.WinError(c.get_last_error())
        shell = c.WinDLL('shell32', use_last_error=True)
        shell.CommandLineToArgvW.argtypes = [c.c_wchar_p, c.POINTER(c.c_int)]
        shell.CommandLineToArgvW.restype = c.POINTER(c.c_wchar_p)
        kernel = c.WinDLL('kernel32')
        kernel.LocalFree.argtypes = [c.c_void_p]
        kernel.LocalFree.restype = c.c_void_p
        count = c.c_int()
        argv = shell.CommandLineToArgvW(command, c.byref(count))
        if not argv:
            raise OSError('无法读取 AdGuard Home 服务程序')
        try:
            executable = argv[0] if count.value else ''
        finally:
            kernel.LocalFree(argv)
        return dict(binary=executable, command_hash=hashlib.sha256(command.encode('utf-8')).hexdigest(),
                    state=STATES.get(status.state, 'unknown'), service_exit=status.exit)

    def start(self):
        with self.opened(0x10 | 4) as service:
            if not self.api.StartServiceW(service, 0, None):
                code = c.get_last_error()
                if code != 1056:
                    raise c.WinError(code)

    def stop(self):
        with self.opened(0x20 | 4) as service:
            status = ServiceStatus()
            if not self.api.ControlService(service, 1, c.byref(status)):
                code = c.get_last_error()
                if code != 1062:
                    raise c.WinError(code)


def fingerprint(info):
    path = Path(info['binary'])
    if not path.is_absolute() or path.name.lower() != 'adguardhome.exe' or str(path).startswith(('\\\\', '//')):
        raise ValueError('服务程序须为已在本机安装的 AdGuardHome.exe')
    for part in (path, *path.parents):
        meta = part.lstat()
        if stat.S_ISLNK(meta.st_mode) or getattr(meta, 'st_file_attributes', 0) & 0x400:
            raise ValueError('服务路径包含链接或重解析点，未自动启动')
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or not 0 < before.st_size <= 128 * 1024 * 1024:
            raise ValueError('服务程序文件无效')
        digest = hashlib.sha256()
        total = 0
        while True:
            data = stream.read(1024 * 1024)
            if not data:
                break
            total += len(data)
            if total > 128 * 1024 * 1024:
                raise ValueError('服务程序超出大小上限')
            digest.update(data)
        after = os.fstat(stream.fileno())
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or total != before.st_size:
        raise ValueError('服务程序正在更新，请完成更新后在本机重新启用')
    return dict(format=1, service=SERVICE, binary=str(path), sha256=digest.hexdigest(), command_hash=info['command_hash'])


class Companion:
    def __init__(self, consent, service):
        self.consent, self.service = consent, service

    def prepare(self):
        info = self.service.inspect()
        return info, fingerprint(info)

    def approve(self, expected):
        _, actual = self.prepare()
        if actual != expected:
            raise ValueError('服务配置已变化，未保存启用记录，请重新查看')
        self.consent.save(actual)

    def start_enabled(self):
        approved = self.consent.read()
        if approved is None:
            return dict(enabled=False, state='not_enabled', message='本机未启用 AdGuard Home 启动联动，未操作服务')
        info, actual = self.prepare()
        if approved != actual:
            raise ValueError('AdGuard Home 程序或服务配置已变化；请在本机重新确认，未自动启动')
        if info['state'] in ('running', 'starting'):
            return dict(enabled=True, state=info['state'], message='AdGuard Home 已在运行或启动中，没有重复启动')
        if info['state'] != 'stopped':
            raise ValueError('AdGuard Home 当前正在停止或已暂停，未自动改变此状态')
        self.service.start()
        current = self.service.inspect()
        if current['state'] not in ('running', 'starting'):
            raise OSError('Windows 启动请求后服务状态为 %s，退出码 %s；请在本机检查 AdGuard Home 配置' % (current['state'], current['service_exit']))
        return dict(enabled=True, state=current['state'], service_exit=current['service_exit'],
                    message='已向 Windows 请求启动 AdGuard Home；实际状态以上方 state 为准')


def local_console():
    if os.name != 'nt' or not sys.stdin or not sys.stdout or not sys.stdin.isatty() or not sys.stdout.isatty():
        raise OSError('请在 Windows 本机交互控制台运行 AdGuardSetup.cmd，未接受重定向设置输入')
    fn = c.WinDLL('kernel32').GetConsoleWindow
    fn.argtypes, fn.restype = [], c.c_void_p
    if not fn() or not c.windll.shell32.IsUserAnAdmin():
        raise PermissionError('请在电脑右键以管理员身份运行设置，未自动提权')


def setup():
    local_console()
    companion = Companion(RegistryConsent(), NativeService())
    print('AdGuard Home 正常 Windows 服务联动\n请先完成官方服务安装、管理密码和 HTTPS 配置。')
    print('仅启动本机已安装的服务，不修改 DNS/防火墙，不开启访问记录，不隐藏服务。')
    print('受控设备应明确知晓并使用此 DNS；记录与规则在 AdGuard Home 的独立管理页配置。')
    while True:
        try:
            approved = companion.consent.read()
            info = companion.service.inspect()
            print('\n启动联动：' + ('已启用' if approved else '未启用') + '；服务：' + info['state'])
        except (OSError, ValueError) as exc:
            print('\n状态：' + str(exc))
        choice = input('1 启用/重新确认联动 / 2 取消联动 / 3 按已启用设置启动 / 4 停止服务 / 0 退出：').strip()
        if choice == '0':
            return 0
        try:
            if choice == '1':
                _, expected = companion.prepare()
                print('服务程序：' + expected['binary'] + '\nSHA-256：' + expected['sha256'])
                if input('确认允许 start.bat 启动该服务？输入 ENABLE：').strip() == 'ENABLE':
                    companion.approve(expected)
                    print('已启用。后续启动不新增弹窗或通知；服务与管理状态仍可正常查看。')
            elif choice == '2':
                companion.consent.disable()
                print('已取消联动；没有停止独立服务或更改其 Windows 启动类型。')
            elif choice == '3':
                print(json.dumps(companion.start_enabled(), ensure_ascii=False))
            elif choice == '4' and input('停止 DNS 服务可能使连接设备无法解析域名。输入 STOP 确认：').strip() == 'STOP':
                companion.service.stop()
                print('已请求停止服务，稍后在此页或 Windows 服务中查看实际状态。')
        except (OSError, ValueError) as exc:
            print('未确认完成：' + str(exc))


def main():
    try:
        if sys.argv[1:] == ['--setup']:
            return setup()
        if sys.argv[1:] == ['--start-if-enabled']:
            if os.name != 'nt':
                raise OSError('仅支持 Windows 本机服务')
            # No grant => no SCM access, no program reads, no start request.
            consent = RegistryConsent()
            if consent.read() is None:
                result = dict(enabled=False, state='not_enabled', message='本机未启用启动联动')
            else:
                result = Companion(consent, NativeService()).start_enabled()
            print(json.dumps(dict(result, time=int(time.time())), ensure_ascii=False))
            return 0
        print('使用 AdGuardSetup.cmd 在本机设置；启动入口仅为 --start-if-enabled。')
        return 2
    except (OSError, ValueError, EOFError, KeyboardInterrupt) as exc:
        print(json.dumps(dict(state='error', message=str(exc), time=int(time.time())), ensure_ascii=False))
        return 1


if __name__ == '__main__':
    sys.exit(main())

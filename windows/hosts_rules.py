"""Locally confirmed website restrictions; no network/RPC or background entry point.

Only our labelled block changes. Existing hosts data remains byte-for-byte intact.
Run --settings in a local elevated console; importing this module changes nothing.
"""
from dataclasses import dataclass
import hashlib
import ipaddress
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import uuid


BEGIN = b'# BEGIN Local Website Rules v1'
END = b'# END Local Website Rules v1'
NOTE = b'# Explicitly confirmed locally. Exact domains only; no activity logging.'
MAX_BYTES = 1024 * 1024
MAX_DOMAINS = 256
BOM = b'\xef\xbb\xbf'


def domain(value):
    if not isinstance(value, str):
        raise ValueError('域名须为文字')
    value = value.strip().lower().removesuffix('.')
    if not value or any(ch in value for ch in '/\\:*#@$\0\r\n\t '):
        raise ValueError('请只填域名，不含网址路径、端口、通配符或空格')
    try:
        result = value.encode('idna').decode('ascii').lower()
    except UnicodeError as exc:
        raise ValueError('域名格式无效') from exc
    labels = result.split('.')
    if len(result) > 253 or len(labels) < 2 or any(
        not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label)
        for label in labels
    ):
        raise ValueError('请填写有效的完整域名，例如 example.com')
    try:
        ipaddress.ip_address(result)
    except ValueError:
        pass
    else:
        raise ValueError('此工具只限制域名，不修改 IP 地址或网络路由')
    if result == 'localhost' or result.endswith(('.localhost', '.local', '.arpa')):
        raise ValueError('不修改本机、局域网发现或反向解析名称')
    return result


def domains(values):
    if not isinstance(values, (list, tuple)) or len(values) > MAX_DOMAINS:
        raise ValueError(f'最多填写 {MAX_DOMAINS} 个域名')
    return sorted({domain(value) for value in values})


def split_block(data):
    """Return unowned bytes and domains, rejecting damaged/edited managed blocks."""
    if len(data) > MAX_BYTES:
        raise ValueError('hosts 文件超过 1 MiB，请在本机手动检查')
    if data.startswith((b'\xff\xfe', b'\xfe\xff')) or b'\0' in data:
        raise ValueError('不支持此 hosts 编码；未转换或改写原文件')
    prefix = BOM if data.startswith(BOM) else b''
    body = data[len(prefix):]
    if BEGIN not in body and END not in body:
        return data, []
    lines = body.splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines) if line.rstrip(b'\r\n') == BEGIN]
    ends = [i for i, line in enumerate(lines) if line.rstrip(b'\r\n') == END]
    if body.count(BEGIN) != 1 or body.count(END) != 1 or len(starts) != 1 or len(ends) != 1 or starts[0] >= ends[0]:
        raise ValueError('网站规则标记缺失、重复或损坏；未修改 hosts')
    start, end = starts[0], ends[0]
    parsed = {}
    for line in lines[start + 1:end]:
        raw = line.rstrip(b'\r\n')
        if raw == NOTE:
            continue
        match = re.fullmatch(rb'(0\.0\.0\.0|::) ([a-z0-9.-]+)', raw)
        if not match:
            raise ValueError('规则区含有非工具条目；请在本机检查，未自动删除')
        name = domain(match[2].decode('ascii'))
        family = match[1]
        if family in parsed.setdefault(name, set()):
            raise ValueError('规则区有重复条目，未修改 hosts')
        parsed[name].add(family)
    if len(parsed) > MAX_DOMAINS or any(pair != {b'0.0.0.0', b'::'} for pair in parsed.values()):
        raise ValueError('规则区条目不完整或过多，未修改 hosts')
    return prefix + b''.join(lines[:start] + lines[end + 1:]), sorted(parsed)


def external_names(data):
    """Ignore opaque comments/legacy text; detect existing valid ASCII mappings."""
    result = set()
    for line in data.removeprefix(BOM).splitlines():
        fields = line.split(b'#', 1)[0].split()
        if len(fields) < 2:
            continue
        try:
            ipaddress.ip_address(fields[0].decode('ascii'))
        except (ValueError, UnicodeError):
            continue
        for value in fields[1:]:
            try:
                result.add(domain(value.decode('ascii')))
            except (ValueError, UnicodeError):
                pass
    return result


def render(data, values):
    names = domains(values)
    other, _ = split_block(data)
    if not names:
        return other
    conflicts = set(names) & external_names(other)
    if conflicts:
        raise ValueError('域名已有其他 hosts 映射，请先在本机处理冲突：' + ', '.join(sorted(conflicts)))
    newline = b'\r\n' if b'\r\n' in other else b'\n'
    records = [BEGIN, NOTE]
    for name in names:
        raw = name.encode('ascii')
        records.extend((b'0.0.0.0 ' + raw, b':: ' + raw))
    records.append(END)
    block = newline.join(records) + newline
    # Prepending avoids borrowing/changing a trailing newline in existing data.
    if other.startswith(BOM):
        return BOM + block + other[len(BOM):]
    return block + other


def safe_path(path):
    """Do not follow junctions, symbolic links or other reparse points."""
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('路径含链接或重解析点，未访问 hosts')


@dataclass(frozen=True)
class Snapshot:
    data: bytes
    identity: tuple
    revision: str


class HostsFile:
    """Filesystem core, deliberately not imported by the receiver or its RPC."""
    def __init__(self, path, backup_dir):
        self.path = Path(os.path.abspath(path))
        self.backup_dir = Path(os.path.abspath(backup_dir))

    def read(self):
        safe_path(self.path)
        flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
        with os.fdopen(os.open(self.path, flags), 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > MAX_BYTES:
                raise ValueError('hosts 文件类型、链接数或大小异常，未修改')
            data = stream.read(MAX_BYTES + 1)
            current = os.fstat(stream.fileno())
        if len(data) > MAX_BYTES or (info.st_size, info.st_mtime_ns) != (current.st_size, current.st_mtime_ns):
            raise ValueError('hosts 正在被其他程序修改，请重试')
        split_block(data)
        identity = (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns)
        return Snapshot(data, identity, hashlib.sha256(data).hexdigest())

    def _assert_current(self, expected):
        actual = self.read()
        if actual.identity != expected.identity or actual.revision != expected.revision:
            raise ValueError('hosts 已被其他程序修改，请重新查看并确认；未覆盖修改')

    def _backup(self, data):
        # Validate the nearest existing ancestor before creating directories,
        # so a junction cannot redirect even the directory creation itself.
        existing = self.backup_dir
        while not existing.exists() and not existing.is_symlink():
            parent = existing.parent
            if parent == existing:
                raise ValueError('无法确定安全的备份目录')
            existing = parent
        safe_path(existing)
        self.backup_dir.mkdir(parents=True, exist_ok=True)
        safe_path(self.backup_dir)
        path = self.backup_dir / ('hosts-' + uuid.uuid4().hex + '.bak')
        flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
        with os.fdopen(os.open(path, flags, 0o600), 'wb') as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        return path

    def replace(self, expected, values):
        """Caller must obtain local confirmation of this exact snapshot first."""
        updated = render(expected.data, values)
        self._assert_current(expected)
        if updated == expected.data:
            return None
        if len(updated) > MAX_BYTES:
            raise ValueError('新 hosts 文件过大，未修改')
        backup = self._backup(expected.data)
        fd, name = tempfile.mkstemp(prefix='hosts-local-', suffix='.tmp', dir=self.path.parent)
        staging = Path(name)
        try:
            with os.fdopen(fd, 'wb') as out:
                out.write(updated)
                out.flush()
                os.fsync(out.fileno())
            self._assert_current(expected)
            if os.name == 'nt':
                # ReplaceFile preserves the original file's DACL. No permission,
                # read-only or system-attribute changes, and no in-place fallback.
                import ctypes as c
                api = c.WinDLL('kernel32', use_last_error=True)
                api.ReplaceFileW.argtypes = [c.c_wchar_p, c.c_wchar_p, c.c_wchar_p, c.c_uint32, c.c_void_p, c.c_void_p]
                api.ReplaceFileW.restype = c.c_int
                if not api.ReplaceFileW(str(self.path), str(staging), None, 0, None, None):
                    raise c.WinError(c.get_last_error())
            else:
                os.chmod(staging, stat.S_IMODE(self.path.stat().st_mode))
                os.replace(staging, self.path)
            actual = self.read()
            if actual.data != updated:
                raise OSError('写入后的 hosts 与确认内容不一致；保留备份，请在本机检查')
            return backup
        except Exception as exc:
            raise OSError(f'{exc}；修改前备份保留在 {backup}') from exc
        finally:
            staging.unlink(missing_ok=True)


def system_hosts():
    if os.name != 'nt':
        raise OSError('此工具仅用于 Windows 本机')
    import ctypes as c
    api = c.WinDLL('kernel32', use_last_error=True)
    api.GetSystemDirectoryW.argtypes = [c.c_wchar_p, c.c_uint]
    api.GetSystemDirectoryW.restype = c.c_uint
    buffer = c.create_unicode_buffer(32768)
    length = api.GetSystemDirectoryW(buffer, len(buffer))
    if not 0 < length < len(buffer):
        raise c.WinError(c.get_last_error())
    return Path(buffer.value) / 'drivers/etc/hosts'


def flush_dns():
    try:
        import ctypes as c
        fn = c.WinDLL('dnsapi').DnsFlushResolverCache
        fn.argtypes = []
        fn.restype = c.c_int
        return bool(fn())
    except (OSError, AttributeError):
        return False


def local_console():
    if os.name != 'nt' or not sys.stdin or not sys.stdout or not sys.stdin.isatty() or not sys.stdout.isatty():
        raise OSError('请在 Windows 本机控制台打开设置；不接受重定向输入或远控接口请求')
    import ctypes as c
    if not c.windll.kernel32.GetConsoleWindow():
        raise OSError('未找到本机控制台')
    if not c.windll.shell32.IsUserAnAdmin():
        raise PermissionError('请在电脑右键以管理员身份打开设置；未自动提权或修改权限')


def settings():
    local_console()
    root = os.environ.get('LOCALAPPDATA')
    if not root:
        raise OSError('无法确定备份目录，未访问 hosts')
    store = HostsFile(system_hosts(), Path(root) / 'LocalWebsiteRules/backups')
    print('本机网站限制 · hosts\n只限制明确列出的域名，不采集上网记录，也不控制热点客户端。')
    print('不覆盖子域名；www.example.com 要另行添加。加密 DNS、代理、直接 IP、缓存及已有连接可能绕过。')
    print('规则无需后台进程，重启后仍保留。撤销只移除本工具标记的规则，不恢复整份旧文件。')
    while True:
        before = store.read()
        _, old = split_block(before.data)
        print('\n当前规则：' + (', '.join(old) if old else '无'))
        choice = input('1 替换本工具规则 / 2 撤销本工具规则 / 0 退出：').strip()
        if choice == '0':
            return 0
        if choice not in ('1', '2'):
            continue
        if choice == '1':
            print('逐行填写完整域名，空行结束（新列表将替换当前列表）：')
            values = []
            while True:
                value = input().strip()
                if not value:
                    break
                values.append(value)
                if len(values) > MAX_DOMAINS:
                    raise ValueError('输入的域名过多，未修改')
            values = domains(values)
        else:
            values = []
        render(before.data, values)  # Check conflict/encoding before confirmation.
        print('将新增：' + (', '.join(sorted(set(values) - set(old))) or '无'))
        print('将移除：' + (', '.join(sorted(set(old) - set(values))) or '无'))
        if input('确认修改这台电脑的 hosts？输入 APPLY 确认，其余输入取消：').strip() != 'APPLY':
            print('已取消，未修改')
            continue
        backup = store.replace(before, values)
        if backup is None:
            print('规则未变化，无需写入')
            continue
        print('已写入并核对，备份：' + str(backup))
        print('已刷新 Windows DNS 缓存' if flush_dns() else '未确认 DNS 缓存刷新，请关闭并重新打开有关应用')
        print('已有连接及浏览器自己的 DNS 缓存不保证立即受影响。')


def main():
    if sys.argv[1:] != ['--settings']:
        print('请在 Windows 本机运行 hosts_rules.py --settings；没有自动应用或远程控制模式。')
        return 2
    try:
        return settings()
    except (OSError, ValueError, EOFError, KeyboardInterrupt) as exc:
        print('未确认完成：' + str(exc))
        return 1


if __name__ == '__main__':
    sys.exit(main())

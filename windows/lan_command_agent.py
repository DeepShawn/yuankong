#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LanCmd Agent - 局域网命令接收端（Windows 后台静默运行，兼容 Linux/macOS 调试）。

只依赖标准库。默认不弹任何窗口；日志写入用户配置目录。

常用:
    pythonw lan_command_agent.py                 # 静默启动（无控制台）
    python  lan_command_agent.py --console       # 前台启动，输出日志
    python  lan_command_agent.py --auto-accept   # 免确认，任何设备直接连
    python  lan_command_agent.py --install-autostart
    python  lan_command_agent.py --uninstall-autostart
    python  lan_command_agent.py --status
    python  lan_command_agent.py --stop
    python  lan_command_agent.py --reset-pairing
"""

import base64
import ctypes
import errno
import hashlib
import hmac
import io
import json
import logging
import logging.handlers
import os
import platform
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time

APP_ID = "LANCMD"
VERSION = "1.4.9"
DEF_TCP_PORT = 47823
DEF_UDP_PORT = 47824
MAX_LINE = 64 * 1024 * 1024  # 单帧上限 64MB（含 base64 文件）

IS_WIN = os.name == "nt"


def _frozen_name():
    if not getattr(sys, "frozen", False):
        return ""
    return os.path.splitext(os.path.basename(sys.executable))[0].lower()


# LanCmdCtl.exe 是同一个脚本打出来的控制台版，只做管理，绝不自己起服务
IS_CTL_EXE = _frozen_name() in ("lancmdctl",)

# ---------------------------------------------------------------- 配置与状态


def _app_dir():
    if IS_WIN:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        return os.path.join(base, "LanCmd")
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "lancmd")


APP_DIR = _app_dir()
CFG_PATH = os.path.join(APP_DIR, "agent.json")
PAIR_PATH = os.path.join(APP_DIR, "paired.json")
PID_PATH = os.path.join(APP_DIR, "agent.pid")
LOG_PATH = os.path.join(APP_DIR, "agent.log")

DEFAULT_CFG = {
    "name": "",
    "bind": "0.0.0.0",
    "port": DEF_TCP_PORT,
    "discovery_port": DEF_UDP_PORT,
    # 默认免确认：手机连上来不再弹「是否允许」对话框。
    # 需要恢复确认可在菜单/命令行用 --no-auto-accept。
    "auto_accept": True,
    "approval_timeout": 45,
    "exec_timeout": 300,
    "max_file_bytes": 32 * 1024 * 1024,
    "max_upload_bytes": 4 * 1024 * 1024 * 1024,
    "chunk_bytes": 512 * 1024,
    "transfer_idle_sec": 600,
    "log": True,
}

_cfg_lock = threading.Lock()
_pair_lock = threading.Lock()
log = logging.getLogger("lancmd")


def ensure_dirs():
    os.makedirs(APP_DIR, exist_ok=True)


def load_json(path, default):
    try:
        with io.open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else default
    except Exception:
        return default


def save_json(path, data):
    ensure_dirs()
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def load_cfg():
    cfg = dict(DEFAULT_CFG)
    disk = load_json(CFG_PATH, {})
    cfg.update(disk)
    if not cfg.get("name"):
        cfg["name"] = socket.gethostname() or "PC"

    # 一次性迁移：旧版本默认弹确认框，升级后应改为免确认。
    # 只在配置里还没有该标记时执行，避免覆盖用户之后的手动选择。
    if not disk.get("auto_accept_migrated"):
        cfg["auto_accept"] = True
        cfg["auto_accept_migrated"] = True
        try:
            save_cfg(cfg)
            log.info("已把 auto_accept 迁移为开启（不再弹确认框）")
        except Exception:
            pass
    return cfg


def save_cfg(cfg):
    save_json(CFG_PATH, cfg)


def setup_logging(console=False):
    ensure_dirs()
    log.setLevel(logging.INFO)
    log.handlers[:] = []
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%Y-%m-%d %H:%M:%S")
    if cfg_or_default().get("log", True):
        fh = logging.handlers.RotatingFileHandler(
            LOG_PATH, maxBytes=1024 * 1024, backupCount=2, encoding="utf-8"
        )
        fh.setFormatter(fmt)
        log.addHandler(fh)
    if console:
        sh = logging.StreamHandler(sys.stdout)
        sh.setFormatter(fmt)
        log.addHandler(sh)
    if not log.handlers:
        log.addHandler(logging.NullHandler())


_cfg_cache = {}


def cfg_or_default():
    if not _cfg_cache:
        _cfg_cache.update(load_cfg())
    return _cfg_cache


# ---------------------------------------------------------------- 工具函数


def decode_bytes(raw):
    """Windows 中文系统下 cmd 输出可能是 GBK/936，逐级回退。"""
    for enc in ("utf-8", "gbk", "mbcs", "latin-1"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def machine_id():
    raw = "|".join(
        [platform.node(), platform.machine(), str(os.cpu_count() or 0), APP_DIR]
    )
    return hashlib.sha1(raw.encode("utf-8", "replace")).hexdigest()[:10]


# ---------------------------------------------------------------- 防火墙
#
# 这是「Windows 开热点、手机连不上」的头号原因：
# 热点网络被系统判为「公用网络」，入站 47823/47824 默认被拦，
# 而局域网广播又能收到，表现出来就是「能发现但连不上」甚至完全没反应。

FW_RULES = (("LanCmd-TCP", "TCP", "47823"), ("LanCmd-UDP", "UDP", "47824"))


def is_admin():
    if not IS_WIN:
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _netsh(args, timeout=15):
    try:
        r = subprocess.run(
            ["netsh"] + args,
            stdin=DEVNULL_IN,
            capture_output=True,
            timeout=timeout,
            creationflags=_no_window_flags(),
        )
        return r.returncode, decode_bytes(r.stdout + r.stderr)
    except Exception as e:
        return 1, str(e)


def _block_inbound_always():
    """Windows 有个隐藏坑：即便加了放行规则，
    若当前配置文件是 BlockInboundAlways，入站仍然全被拒。
    热点/公用网络下这个设置很常见，必须单独查。"""
    rc, out = _netsh(["advfirewall", "show", "allprofiles"])
    if rc != 0:
        return False
    low = out.lower()
    return "blockinboundalways" in low.replace(" ", "")


def firewall_status():
    """返回 {'ok', 'rules', 'block_inbound', 'supported'}。只读，不需要管理员。"""
    if not IS_WIN:
        return {"ok": True, "rules": {}, "block_inbound": False, "supported": False}
    rules = {}
    for name, proto, port in FW_RULES:
        rc, out = _netsh(["advfirewall", "firewall", "show", "rule", "name=%s" % name])
        rules[name] = rc == 0 and "4782" in out
    blocked = _block_inbound_always()
    # 规则齐了但策略是“阻止所有入站”，实际照样连不上
    ok = all(rules.values()) and not blocked
    return {
        "ok": ok,
        "rules": rules,
        "block_inbound": blocked,
        "supported": True,
    }


def firewall_fix(remove=False):
    """添加/删除入站放行规则。需要管理员权限。"""
    if not IS_WIN:
        return {"ok": False, "error": "仅支持 Windows"}
    if not is_admin():
        return {"ok": False, "error": "需要管理员权限", "need_admin": True}

    results = []
    for name, proto, port in FW_RULES:
        _netsh(["advfirewall", "firewall", "delete", "rule", "name=%s" % name])
        if remove:
            results.append("%s 已移除" % name)
            continue
        rc, out = _netsh([
            "advfirewall", "firewall", "add", "rule",
            "name=%s" % name,
            "dir=in",
            "action=allow",
            "protocol=%s" % proto,
            "localport=%s" % port,
            "profile=any",
            "enable=yes",
        ])
        results.append("%s %s" % (name, "OK" if rc == 0 else "失败: %s" % out.strip()))
        log.info("防火墙规则 %s: %s", name, "OK" if rc == 0 else out.strip())

    status = firewall_status()
    return {"ok": status["ok"], "detail": results, "status": status}


def _elevate_self(args):
    """以管理员身份重新运行自己处理防火墙。"""
    try:
        if getattr(sys, "frozen", False):
            exe, params = sys.executable, " ".join(args)
        else:
            exe = sys.executable
            params = '"%s" %s' % (os.path.abspath(__file__), " ".join(args))
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, None, 1)
        return rc > 32
    except Exception as e:
        log.warning("提权失败: %s", e)
        return False


# 真 VPN：这类网卡有地址但到不了局域网，广播走它们必然失败，要排除
VPN_HINTS = ("tap", "tun", "vpn", "ppp", "wireguard", "utun", "ipsec",
             "zerotier", "tailscale", "clash", "sing-box", "singbox", "openvpn",
             "wireguard", "softether", "hamachi", "radmin")

# 开热点后 Windows 会多出这类网卡，它就是手机看到的“网关”，必须保留
HOTSPOT_HINTS = ("wi-fi direct", "wifi direct", "本地连接*", "local area connection*",
                 "mobile hotspot", "移动热点")


def _is_vpn_name(name):
    low = (name or "").lower()
    return any(h in low for h in VPN_HINTS)


def _is_hotspot_name(name):
    low = (name or "").lower()
    return any(h in low for h in HOTSPOT_HINTS)


def list_ipv4_interfaces():
    """返回 [{ip, adapter, virtual}]。

    VPN/虚拟网卡单独标记：它们通常有地址但到不了局域网，
    广播若走它们就会“发现了却连不上”。
    """
    items = []
    if IS_WIN:
        try:
            r = subprocess.run(
                ["ipconfig"],
                stdin=DEVNULL_IN,
                capture_output=True,
                timeout=8,
                creationflags=_no_window_flags(),
            )
            text = decode_bytes(r.stdout)
        except Exception:
            text = ""
        adapter = ""
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            low = line.lower()
            if low.startswith("adapter") or "适配器" in line:
                adapter = line.rstrip(":").split(":")[-1].strip()
            m = re.search(r"ipv4[^:]*:\s*([0-9]{1,3}(?:\.[0-9]{1,3}){3})", low)
            if m:
                ip = m.group(1)
                if ip.startswith("127.") or ip.startswith("169.254."):
                    continue
                items.append({
                    "ip": ip,
                    "adapter": adapter or "unknown",
                    "vpn": _is_vpn_name(adapter),
                    "hotspot": _is_hotspot_name(adapter),
                })
    else:
        try:
            r = subprocess.run(
                ["ip", "-4", "-o", "addr", "show"],
                stdin=DEVNULL_IN, capture_output=True, timeout=8
            )
            for line in decode_bytes(r.stdout).splitlines():
                m = re.search(r"\d+:\s+(\S+).*?inet\s+([0-9.]+)/", line)
                if not m:
                    continue
                name, ip = m.group(1), m.group(2)
                if ip.startswith("127.") or ip.startswith("169.254."):
                    continue
                items.append({
                    "ip": ip,
                    "adapter": name,
                    "vpn": _is_vpn_name(name),
                    "hotspot": _is_hotspot_name(name),
                })
        except Exception:
            pass

    if not items:
        # 兜底：靠默认路由探测出口地址
        try:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                probe.connect(("8.8.8.8", 53))
                ip = probe.getsockname()[0]
            finally:
                probe.close()
            if ip and not ip.startswith("127."):
                items.append({"ip": ip, "adapter": "default", "vpn": False, "hotspot": False})
        except Exception:
            pass

    # 去重（同一 IP 可能被多个适配器报告）
    seen, out = set(), []
    for it in items:
        if it["ip"] in seen:
            continue
        seen.add(it["ip"])
        out.append(it)
    return out


def lan_ips(include_virtual=False):
    return [i["ip"] for i in list_ipv4_interfaces() if include_virtual or not i["vpn"]]


def hotspot_ips():
    """Windows 开热点时手机侧的“网关”地址，通常是 192.168.137.1。"""
    return [i["ip"] for i in list_ipv4_interfaces() if i.get("hotspot")]


def human_size(n):
    units = ["B", "KB", "MB", "GB", "TB"]
    f = float(n)
    for u in units:
        if f < 1024 or u == units[-1]:
            return ("%d%s" % (f, u)) if u == "B" else ("%.1f%s" % (f, u))
        f /= 1024.0
    return str(n)


class ProtocolError(Exception):
    pass


class RpcError(Exception):
    def __init__(self, message, code=1):
        Exception.__init__(self, message)
        self.message = message
        self.code = code


# ---------------------------------------------------------------- 命令实现


def _no_window_flags():
    if IS_WIN:
        return 0x08000000  # CREATE_NO_WINDOW，避免闪黑框
    return 0


# pythonw.exe 没有标准输入输出。子进程若继承到无效句柄，
# 在 Windows 上可能直接报 [WinError 6] 句柄无效，导致 netsh 之类的调用失败。
DEVNULL_IN = subprocess.DEVNULL


def _shell_argv(shell, line):
    shell = (shell or "cmd").lower()
    if shell == "powershell":
        ps = shutil.which("powershell") or "powershell"
        return [
            ps,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " + line,
        ]
    if not IS_WIN:
        return ["/bin/sh", "-c", line]
    if shell in ("sh", "bash"):
        raise RpcError("Windows 接收端请使用 cmd 或 powershell 模式")
    return ["cmd", "/c", "chcp 65001>nul & " + line]


def run_exec(line, shell="cmd", cwd=None, timeout=None):
    if not line:
        raise RpcError("命令为空")
    cfg = cfg_or_default()
    timeout = float(timeout or cfg["exec_timeout"])
    argv = _shell_argv(shell, line)
    started = time.time()

    out_chunks, err_chunks = [], []

    def reader(stream, sink):
        # 必须用 read1：BufferedReader.read(n) 会一直攒够 n 字节或等到 EOF，
        # 遇到 hold 住管道的分离进程就一个字节也读不出来。
        read = getattr(stream, "read1", None) or stream.read
        try:
            while True:
                chunk = read(65536)
                if not chunk:
                    break
                sink.append(chunk)
        except Exception:
            pass
        finally:
            try:
                stream.close()
            except Exception:
                pass

    try:
        proc = subprocess.Popen(
            argv,
            cwd=cwd or None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            creationflags=_no_window_flags(),
        )
    except FileNotFoundError:
        raise RpcError("找不到解释器: %s" % argv[0])

    # 用独立线程读，进程退出后最多再等一小会儿就把管道关掉。
    #
    # 关键：不能用 subprocess.run / communicate()。
    # 它们会一直读到管道 EOF；而像 `start notepad`、`start cmd` 这类命令
    # 会派生一个脱离的进程并让它继承管道，EOF 永远不到，
    # 于是 handler 把整条连接卡满 300 秒，手机端心跳全超时后就判定掉线——
    # 用户看到的就是「执行命令直接报发送失败」。
    t_out = threading.Thread(target=reader, args=(proc.stdout, out_chunks), daemon=True)
    t_err = threading.Thread(target=reader, args=(proc.stderr, err_chunks), daemon=True)
    t_out.start()
    t_err.start()

    timed_out = False
    try:
        code = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        code = 124
        try:
            proc.kill()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            pass

    # 给读取线程一点收尾时间，但绝不等 EOF
    t_out.join(0.6)
    t_err.join(0.6)
    # 线程还没结束，说明有子进程仍持有管道（start / & 这类），输出可能不完整
    still_open = t_out.is_alive() or t_err.is_alive()

    out = decode_bytes(b"".join(out_chunks))
    err = decode_bytes(b"".join(err_chunks))
    if timed_out:
        err += "\n[超时 %.0fs，进程已终止]" % timeout

    return {
        "code": code,
        "out": out,
        "err": err,
        "ms": int((time.time() - started) * 1000),
        "detached": still_open,
    }


def cmd_info():
    cfg = cfg_or_default()
    if IS_WIN:
        try:
            uptime = int(ctypes.windll.kernel32.GetTickCount64() / 1000)
        except Exception:
            uptime = None
    else:
        try:
            with io.open("/proc/uptime", "r") as f:
                uptime = int(float(f.read().split()[0]))
        except Exception:
            uptime = None
    return {
        "name": cfg["name"],
        "host": socket.gethostname(),
        "user": os.environ.get("USERNAME") or os.environ.get("USER") or "",
        "os": "%s %s" % (platform.system(), platform.release()),
        "arch": platform.machine(),
        "python": platform.python_version(),
        "agent": VERSION,
        "cpus": os.cpu_count(),
        "cwd": os.getcwd(),
        "uptime_s": int(uptime) if uptime else None,
        "drives": _drives(),
        "lan_ips": lan_ips(),
        "hotspot_ips": hotspot_ips(),
        "interfaces": list_ipv4_interfaces(),
        "firewall_ok": firewall_status()["ok"],
        "firewall_block_inbound": firewall_status()["block_inbound"],
        "admin": is_admin(),
    }


def _drives():
    if IS_WIN:
        out = []
        for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
            root = letter + ":\\"
            if os.path.exists(root):
                try:
                    u = shutil.disk_usage(root)
                    out.append({"path": root, "total": u.total, "free": u.free})
                except Exception:
                    out.append({"path": root, "total": 0, "free": 0})
        return out
    try:
        u = shutil.disk_usage("/")
        return [{"path": "/", "total": u.total, "free": u.free}]
    except Exception:
        return []


def cmd_ls(path):
    target = path or (os.environ.get("USERPROFILE") if IS_WIN else os.path.expanduser("~"))
    target = os.path.expanduser(target)
    if not os.path.isdir(target):
        raise RpcError("目录不存在: %s" % target)
    items = []
    try:
        names = os.listdir(target)
    except PermissionError:
        raise RpcError("没有权限读取: %s" % target)
    for name in sorted(names, key=lambda s: s.lower()):
        full = os.path.join(target, name)
        try:
            st = os.stat(full)
            is_dir = os.path.isdir(full)
            items.append(
                {
                    "name": name,
                    "dir": is_dir,
                    "size": 0 if is_dir else st.st_size,
                    "mtime": int(st.st_mtime),
                }
            )
        except OSError:
            items.append({"name": name, "dir": False, "size": 0, "mtime": 0})
    return {"path": os.path.abspath(target), "items": items, "parent": os.path.dirname(os.path.abspath(target))}


def cmd_get(path, max_bytes=None):
    """小文件一次性返回；大文件请用 download begin/chunk。"""
    cfg = cfg_or_default()
    limit = int(max_bytes or cfg["max_file_bytes"])
    path = os.path.expanduser(path or "")
    if not path or not os.path.isfile(path):
        raise RpcError("文件不存在: %s" % path)
    size = os.path.getsize(path)
    if size > limit:
        raise RpcError(
            "文件 %s 超过单帧上限 %s，请用分块下载" % (human_size(size), human_size(limit)), 2
        )
    with open(path, "rb") as f:
        raw = f.read()
    return {
        "name": os.path.basename(path),
        "path": os.path.abspath(path),
        "size": size,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "data": base64.b64encode(raw).decode("ascii"),
    }


def cmd_put(path, data, overwrite=True):
    """小文件一次性写入；大文件请用 upload begin/chunk/end。"""
    cfg = cfg_or_default()
    if not path:
        raise RpcError("目标路径为空")
    path = os.path.expanduser(path)
    try:
        raw = base64.b64decode(data or "")
    except Exception:
        raise RpcError("payload 不是合法 base64")
    if len(raw) > cfg["max_file_bytes"]:
        raise RpcError("写入内容超过单帧上限 %s" % human_size(cfg["max_file_bytes"]), 2)
    if os.path.isdir(path):
        raise RpcError("目标是目录: %s" % path)
    if os.path.exists(path) and not overwrite:
        raise RpcError("目标已存在: %s" % path)
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    try:
        with open(path, "wb") as f:
            f.write(raw)
    except IsADirectoryError:
        raise RpcError("目标是目录: %s" % path)
    except PermissionError:
        raise RpcError("没有写入权限: %s" % path)
    except OSError as e:
        raise RpcError("写入失败: %s" % e)
    return {"path": os.path.abspath(path), "size": len(raw)}


# --- 分块上传：手机 -> 电脑 -------------------------------------------------


def _new_sid():
    return secrets.token_hex(8)


def _prune_transfers(st):
    """清掉长时间无进展的会话，避免手机中途消失后占着临时文件。"""
    idle = int(cfg_or_default().get("transfer_idle_sec", 600))
    now = time.time()
    for table in (st.uploads, st.downloads):
        for sid, sess in list(table.items()):
            if now - sess.get("ts", now) <= idle:
                continue
            try:
                sess["fh"].close()
            except Exception:
                pass
            part = sess.get("part")
            if part:
                try:
                    os.remove(part)
                except OSError:
                    pass
            table.pop(sid, None)
            log.info("传输会话超时清理 %s", sid)


def cmd_upload(q, st):
    cfg = cfg_or_default()
    action = str(q.get("action") or "begin")
    _prune_transfers(st)

    if action == "begin":
        path = os.path.expanduser(str(q.get("path") or ""))
        if not path:
            raise RpcError("目标路径为空")
        size = int(q.get("size") or 0)
        if size < 0:
            raise RpcError("文件大小非法")
        limit = int(cfg["max_upload_bytes"])
        if size > limit:
            raise RpcError("文件 %s 超过上限 %s" % (human_size(size), human_size(limit)), 2)
        overwrite = bool(q.get("overwrite", True))
        if os.path.exists(path) and not overwrite:
            raise RpcError("目标已存在: %s" % path)
        if os.path.isdir(path):
            raise RpcError("目标是目录: %s" % path)
        parent = os.path.dirname(os.path.abspath(path))
        if parent and not os.path.isdir(parent):
            os.makedirs(parent, exist_ok=True)

        sid = _new_sid()
        part = path + ".lancmd-part"
        fh = open(part, "wb")
        st.uploads[sid] = {
            "fh": fh,
            "path": path,
            "part": part,
            "size": size,
            "received": 0,
            "seq": 0,
            "sha": hashlib.sha256(),
            "ts": time.time(),
            "done": False,
        }
        log.info("上传开始 %s (%s) sid=%s", path, human_size(size), sid)
        return {"sid": sid, "chunk": int(cfg["chunk_bytes"]), "path": os.path.abspath(path)}

    sid = str(q.get("sid") or "")
    sess = st.uploads.get(sid)
    if sess is None:
        raise RpcError("上传会话不存在或已过期", 3)

    if action == "chunk":
        seq = int(q.get("seq") or 0)
        if seq != sess["seq"]:
            raise RpcError("分块序号错乱，期望 %d 收到 %d" % (sess["seq"], seq), 4)
        try:
            raw = base64.b64decode(q.get("data") or "")
        except Exception:
            raise RpcError("分块不是合法 base64")
        if not raw and sess["size"] > 0:
            raise RpcError("分块内容为空")
        sess["fh"].write(raw)
        sess["sha"].update(raw)
        sess["received"] += len(raw)
        sess["seq"] += 1
        sess["ts"] = time.time()
        if sess["received"] > sess["size"]:
            raise RpcError("收到数据超过声明大小", 5)
        return {"received": sess["received"], "total": sess["size"]}

    if action == "end":
        sess["fh"].close()
        if sess["received"] != sess["size"]:
            try:
                os.remove(sess["part"])
            except OSError:
                pass
            st.uploads.pop(sid, None)
            raise RpcError(
                "大小不匹配：收到 %d / 声明 %d" % (sess["received"], sess["size"]), 6
            )
        expect = str(q.get("sha256") or "")
        actual = sess["sha"].hexdigest()
        if expect and not hmac.compare_digest(expect.lower(), actual):
            try:
                os.remove(sess["part"])
            except OSError:
                pass
            st.uploads.pop(sid, None)
            raise RpcError("sha256 校验失败", 7)
        os.replace(sess["part"], sess["path"])
        sess["done"] = True
        st.uploads.pop(sid, None)
        log.info("上传完成 %s (%s)", sess["path"], human_size(sess["received"]))
        return {
            "path": os.path.abspath(sess["path"]),
            "size": sess["received"],
            "sha256": actual,
        }

    if action == "abort":
        try:
            sess["fh"].close()
        except Exception:
            pass
        try:
            os.remove(sess["part"])
        except OSError:
            pass
        st.uploads.pop(sid, None)
        log.info("上传已取消 sid=%s", sid)
        return {"aborted": True}

    raise RpcError("未知上传动作: %s" % action)


# --- 分块下载：电脑 -> 手机 -------------------------------------------------


def cmd_download(q, st):
    cfg = cfg_or_default()
    action = str(q.get("action") or "begin")
    _prune_transfers(st)

    if action == "begin":
        path = os.path.expanduser(str(q.get("path") or ""))
        if not path or not os.path.isfile(path):
            raise RpcError("文件不存在: %s" % path)
        size = os.path.getsize(path)
        limit = int(cfg["max_upload_bytes"])
        if size > limit:
            raise RpcError("文件 %s 超过上限 %s" % (human_size(size), human_size(limit)), 2)
        sid = _new_sid()
        st.downloads[sid] = {
            "fh": open(path, "rb"),
            "path": path,
            "size": size,
            "ts": time.time(),
        }
        log.info("下载开始 %s (%s) sid=%s", path, human_size(size), sid)
        return {
            "sid": sid,
            "name": os.path.basename(path),
            "path": os.path.abspath(path),
            "size": size,
            "chunk": int(cfg["chunk_bytes"]),
        }

    sid = str(q.get("sid") or "")
    sess = st.downloads.get(sid)
    if sess is None:
        raise RpcError("下载会话不存在或已过期", 3)

    if action == "chunk":
        offset = int(q.get("offset") or 0)
        want = int(q.get("len") or cfg["chunk_bytes"])
        if offset < 0 or offset > sess["size"]:
            raise RpcError("偏移越界")
        want = max(1, min(want, int(cfg["chunk_bytes"]) * 4))
        sess["fh"].seek(offset)
        raw = sess["fh"].read(want)
        sess["ts"] = time.time()
        return {
            "offset": offset,
            "data": base64.b64encode(raw).decode("ascii"),
            "eof": offset + len(raw) >= sess["size"],
            "size": sess["size"],
        }

    if action in ("end", "abort"):
        try:
            sess["fh"].close()
        except Exception:
            pass
        st.downloads.pop(sid, None)
        return {"closed": True}

    raise RpcError("未知下载动作: %s" % action)


def cmd_rm(path):
    path = os.path.expanduser(path or "")
    if not path or not os.path.exists(path):
        raise RpcError("路径不存在: %s" % path)
    if os.path.isdir(path):
        shutil.rmtree(path)
    else:
        os.remove(path)
    return {"removed": os.path.abspath(path)}


def cmd_rename(path, to):
    path = os.path.expanduser(path or "")
    to = os.path.expanduser(to or "")
    if not path or not to:
        raise RpcError("源路径或目标路径为空")
    if not os.path.exists(path):
        raise RpcError("路径不存在: %s" % path)
    if os.path.exists(to):
        raise RpcError("目标已存在: %s" % to)
    parent = os.path.dirname(os.path.abspath(to))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    os.rename(path, to)
    return {"from": os.path.abspath(path), "to": os.path.abspath(to)}


def cmd_mkdir(path):
    if not path:
        raise RpcError("路径为空")
    os.makedirs(os.path.expanduser(path), exist_ok=True)
    return {"path": os.path.abspath(os.path.expanduser(path))}


# --- 截屏（Windows GDI，直接输出 BMP，安卓可解码）

class _BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", ctypes.c_uint32),
        ("biWidth", ctypes.c_int32),
        ("biHeight", ctypes.c_int32),
        ("biPlanes", ctypes.c_uint16),
        ("biBitCount", ctypes.c_uint16),
        ("biCompression", ctypes.c_uint32),
        ("biSizeImage", ctypes.c_uint32),
        ("biXPelsPerMeter", ctypes.c_int32),
        ("biYPelsPerMeter", ctypes.c_int32),
        ("biClrUsed", ctypes.c_uint32),
        ("biClrImportant", ctypes.c_uint32),
    ]


class _BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", _BITMAPINFOHEADER), ("bmiColors", ctypes.c_uint32 * 3)]


def cmd_screenshot(scale=1.0):
    if not IS_WIN:
        raise RpcError("截屏仅在 Windows 接收端可用")
    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass
    w = user32.GetSystemMetrics(0)
    h = user32.GetSystemMetrics(1)
    if w <= 0 or h <= 0:
        raise RpcError("无法获取屏幕尺寸")
    hdc = user32.GetDC(0)
    memdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    old = gdi32.SelectObject(memdc, bmp)
    try:
        gdi32.BitBlt(memdc, 0, 0, w, h, hdc, 0, 0, 0x00CC0020)  # SRCCOPY
        bi = _BITMAPINFO()
        bi.bmiHeader.biSize = ctypes.sizeof(_BITMAPINFOHEADER)
        bi.bmiHeader.biWidth = w
        bi.bmiHeader.biHeight = -h  # 负数 = 自上而下
        bi.bmiHeader.biPlanes = 1
        bi.bmiHeader.biBitCount = 32
        bi.bmiHeader.biCompression = 0  # BI_RGB
        stride = w * 4
        buf = ctypes.create_string_buffer(stride * h)
        got = gdi32.GetDIBits(memdc, bmp, 0, h, buf, ctypes.byref(bi), 0)
        if got == 0:
            raise RpcError("GetDIBits 失败")
        pixels = buf.raw
    finally:
        gdi32.SelectObject(memdc, old)
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(memdc)
        user32.ReleaseDC(0, hdc)

    header = struct.pack("<2sIHHI", b"BM", 14 + 40 + len(pixels), 0, 0, 54)
    info = struct.pack(
        "<IiiHHIIiiII", 40, w, -h, 1, 32, 0, len(pixels), 2835, 2835, 0, 0
    )
    raw = header + info + pixels
    return {"w": w, "h": h, "format": "bmp", "data": base64.b64encode(raw).decode("ascii")}


def cmd_lock():
    if IS_WIN:
        subprocess.run(
            ["rundll32.exe", "user32.dll,LockWorkStation"],
            creationflags=_no_window_flags(),
        )
        return {"locked": True}
    raise RpcError("锁屏仅支持 Windows 接收端")


def cmd_power(action):
    action = (action or "").lower()
    table = {
        "shutdown": ["shutdown", "/s", "/t", "0"],
        "restart": ["shutdown", "/r", "/t", "0"],
        "logoff": ["shutdown", "/l"],
        "sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"],
    }
    if action not in table:
        raise RpcError("不支持的电源操作: %s" % action)
    if not IS_WIN:
        raise RpcError("电源操作仅支持 Windows 接收端")
    subprocess.Popen(table[action], creationflags=_no_window_flags())
    return {"action": action}


def cmd_clip(action, text=""):
    if not IS_WIN:
        raise RpcError("剪贴板仅支持 Windows 接收端")
    if action == "get":
        r = run_exec("Get-Clipboard -Raw", shell="powershell", timeout=15)
        return {"text": r["out"].rstrip("\r\n")}
    if action == "set":
        payload = base64.b64encode(text.encode("utf-8")).decode("ascii")
        run_exec(
            "Set-Clipboard -Value ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')))"
            % payload,
            shell="powershell",
            timeout=15,
        )
        return {"text": text}
    raise RpcError("action 需为 get 或 set")


def cmd_ps(filter_text=""):
    """列出进程。Windows 用 tasklist 拿 PID/内存/会话，再按需附加窗口标题。"""
    needle = (filter_text or "").strip().lower()
    if IS_WIN:
        r = run_exec("tasklist /fo csv /nh", shell="cmd", timeout=30)
        items = []
        for row in r["out"].splitlines():
            row = row.strip()
            if not row:
                continue
            parts = [p.strip().strip('"') for p in row.split('","')]
            if len(parts) < 5:
                continue
            name, pid, session, sess_no, mem = parts[0], parts[1], parts[2], parts[3], parts[4]
            if not pid.isdigit():
                continue
            if needle and needle not in name.lower() and needle != pid:
                continue
            items.append(
                {
                    "name": name,
                    "pid": int(pid),
                    "session": session,
                    "mem": mem,
                    "mem_bytes": _parse_mem(mem),
                }
            )
        items.sort(key=lambda x: (-x["mem_bytes"], x["pid"]))
        return {
            "items": items,
            "total": len(items),
            "count": len(items),
            "self_pid": os.getpid(),
        }

    r = run_exec("ps -eo pid,rss,comm --sort=-rss", shell="sh", timeout=20)
    items = []
    for row in r["out"].splitlines()[1:]:
        parts = row.split(None, 2)
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        name = parts[2].strip()
        if needle and needle not in name.lower() and needle != parts[0]:
            continue
        items.append(
            {
                "name": name,
                "pid": int(parts[0]),
                "session": "",
                "mem": human_size(int(parts[1]) * 1024),
                "mem_bytes": int(parts[1]) * 1024,
            }
        )
    return {"items": items, "total": len(items), "count": len(items), "self_pid": os.getpid()}


def _parse_mem(text):
    """tasklist 的内存列是 '12,345 K' 这类本地化格式。"""
    digits = "".join(ch for ch in str(text) if ch.isdigit())
    if not digits:
        return 0
    return int(digits) * 1024


def cmd_kill(pid, force=True):
    try:
        pid = int(pid)
    except Exception:
        raise RpcError("pid 非法")
    # 负数在 POSIX 下表示进程组，会把接收端所在的整组一起杀掉，必须拦住
    if pid <= 1 or pid == os.getpid():
        raise RpcError("拒绝结束该进程（系统进程或接收端自身）", 8)
    if IS_WIN:
        argv = ["taskkill", "/PID", str(pid)] + (["/F"] if force else [])
        r = subprocess.run(argv, stdin=DEVNULL_IN, capture_output=True,
                           creationflags=_no_window_flags())
        text = decode_bytes(r.stdout + r.stderr).strip()
        if r.returncode != 0:
            raise RpcError(text or ("结束进程失败，退出码 %d" % r.returncode), 9)
        log.info("已结束进程 pid=%s", pid)
        return {"pid": pid, "killed": True, "out": text}
    try:
        os.kill(pid, 9 if force else 15)
    except ProcessLookupError:
        raise RpcError("进程不存在: %d" % pid, 9)
    except PermissionError:
        raise RpcError("权限不足，无法结束 pid %d" % pid, 9)
    log.info("已结束进程 pid=%s", pid)
    return {"pid": pid, "killed": True}


HANDLERS = {
    "info": lambda q, st: cmd_info(),
    "exec": lambda q, st: run_exec(
        q.get("line", ""), q.get("shell", "cmd"), q.get("cwd"), q.get("timeout")
    ),
    "ls": lambda q, st: cmd_ls(q.get("path", "")),
    "get": lambda q, st: cmd_get(q.get("path", ""), q.get("max")),
    "put": lambda q, st: cmd_put(q.get("path", ""), q.get("data", ""), q.get("overwrite", True)),
    "upload": cmd_upload,
    "download": cmd_download,
    "rm": lambda q, st: cmd_rm(q.get("path", "")),
    "mkdir": lambda q, st: cmd_mkdir(q.get("path", "")),
    "rename": lambda q, st: cmd_rename(q.get("path", ""), q.get("to", "")),
    "screenshot": lambda q, st: cmd_screenshot(),
    "lock": lambda q, st: cmd_lock(),
    "power": lambda q, st: cmd_power(q.get("action", "")),
    "clip": lambda q, st: cmd_clip(q.get("action", "get"), q.get("text", "")),
    "ps": lambda q, st: cmd_ps(q.get("filter", "")),
    "kill": lambda q, st: cmd_kill(q.get("pid"), q.get("force", True)),
}


# ---------------------------------------------------------------- 配对与授权


def load_pairs():
    data = load_json(PAIR_PATH, {})
    return data if isinstance(data, dict) else {}


def save_pairs(pairs):
    save_json(PAIR_PATH, pairs)


def _ask_approval(client_name, peer_ip, timeout):
    """首次配对确认。默认 45 秒无人应答视为拒绝。"""
    title = "LanCmd 连接请求"
    msg = "设备「%s」(%s) 请求控制本机。\n\n允许后该设备下次自动连接，无需再次确认。" % (
        client_name or "未知设备",
        peer_ip,
    )
    try:
        import tkinter as tk
        import tkinter.messagebox as mb
    except Exception:
        # 打包版 runtime 不含 tkinter，直接用系统原生确认框（同样能点“是/否”）
        if IS_WIN:
            res = ctypes.windll.user32.MessageBoxW(
                None, msg + "\n\n是否允许？", title, 0x04 | 0x40 | 0x10000
            )
            return res == 6
        log.warning("无 GUI，无法弹出确认窗口，按拒绝处理")
        return False

    result = {"ok": False}

    def _popup():
        try:
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            root.after(int(max(5, timeout)) * 1000, root.destroy)
            answered = mb.askyesno(title, msg, parent=root)
            result["ok"] = bool(answered)
            root.destroy()
        except Exception as e:
            log.warning("确认窗口异常: %s", e)

    t = threading.Thread(target=_popup, daemon=True)
    t.start()
    t.join(timeout + 5)
    return result["ok"]


class ClientState(object):
    """一条连接的上下文，文件传输会话挂在连接上，断开即回收。"""

    def __init__(self, sock, addr):
        self.sock = sock
        self.addr = addr
        self.peer = addr[0]
        self.authorized = False
        self.client_id = ""
        self.client_name = ""
        self.token = ""
        self.uploads = {}
        self.downloads = {}
        self.last_active = time.time()

    def touch(self):
        self.last_active = time.time()

    def close_transfers(self):
        for table in (self.uploads, self.downloads):
            for s in list(table.values()):
                try:
                    s["fh"].close()
                except Exception:
                    pass
                part = s.get("part")
                if part and not s.get("done"):
                    try:
                        os.remove(part)
                    except OSError:
                        pass
            table.clear()


# ---------------------------------------------------------------- 网络层

def read_frame(f):
    line = f.readline(MAX_LINE)
    if not line:
        return None
    if not line.endswith(b"\n") and len(line) >= MAX_LINE:
        raise ProtocolError("单帧超过 %d 字节" % MAX_LINE)
    try:
        obj = json.loads(line.decode("utf-8"))
    except Exception as e:
        raise ProtocolError("非法 JSON: %s" % e)
    if not isinstance(obj, dict):
        raise ProtocolError("帧必须是 JSON 对象")
    return obj


def write_frame(f, obj):
    data = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    f.write(data.encode("utf-8") + b"\n")
    f.flush()


def _handle_hello(state, req, cfg):
    cid = str(req.get("client_id") or "").strip()
    if not cid:
        raise RpcError("缺少 client_id")
    name = str(req.get("client") or "安卓设备")[:64]
    token = str(req.get("token") or "")

    with _pair_lock:
        pairs = load_pairs()

    rec = pairs.get(cid)
    if rec and token and hmac.compare_digest(str(rec.get("token", "")), token):
        state.authorized = True
        state.client_id = cid
        state.client_name = name
        log.info("设备重连(免确认): %s %s", name, state.peer)
        return {
            "ok": True,
            "paired": True,
            "token": rec["token"],
            "agent": cfg["name"],
            "agent_id": machine_id(),
        }

    if cfg.get("auto_accept"):
        allow = True
        log.info("auto_accept 开启，直接授权 %s %s", name, state.peer)
        # 直接返回，不进任何弹窗逻辑
    else:
        allow = _ask_approval(name, state.peer, int(cfg.get("approval_timeout", 45)))
        log.info("配对确认 %s -> %s", name, "允许" if allow else "拒绝")

    if not allow:
        return {"ok": False, "error": "denied", "message": "电脑端拒绝了连接"}

    new_token = secrets.token_hex(16)
    with _pair_lock:
        pairs = load_pairs()
        pairs[cid] = {
            "name": name,
            "peer": state.peer,
            "token": new_token,
            "paired_at": int(time.time()),
        }
        save_pairs(pairs)
    state.authorized = True
    state.client_id = cid
    state.client_name = name
    state.token = new_token
    return {
        "ok": True,
        "paired": True,
        "token": new_token,
        "agent": cfg["name"],
        "agent_id": machine_id(),
        "fresh": True,
    }


def serve_client(sock, addr, cfg):
    global CLIENT_COUNT
    state = ClientState(sock, addr)
    sock.settimeout(300)
    try:
        rf = sock.makefile("rb")
        wf = sock.makefile("wb")
        while True:
            try:
                req = read_frame(rf)
            except ProtocolError as e:
                # 坏帧只回一条错误，不断开连接：客户端还能继续用
                log.warning("坏帧 %s: %s", state.peer, e)
                try:
                    write_frame(wf, {"id": None, "ok": False, "error": str(e)})
                except Exception:
                    break
                continue
            if req is None:
                break
            rid = req.get("id")
            cmd = str(req.get("cmd") or "")
            try:
                if cmd == "hello":
                    resp = _handle_hello(state, req, cfg)
                    resp["id"] = rid
                    write_frame(wf, resp)
                    if not resp.get("ok"):
                        break
                    continue

                if not state.authorized:
                    raise RpcError("未授权，请先握手", 401)

                if cmd == "ping":
                    write_frame(wf, {"id": rid, "ok": True, "ts": int(time.time() * 1000)})
                    continue

                if cmd == "agent":
                    action = req.get("action")
                    if action == "status":
                        write_frame(
                            wf,
                            {
                                "id": rid,
                                "ok": True,
                                "name": cfg["name"],
                                "ver": VERSION,
                                "uptime_s": int(time.time() - START_TS),
                                "clients": CLIENT_COUNT,
                            },
                        )
                        continue
                    if action == "stop":
                        write_frame(wf, {"id": rid, "ok": True, "stopping": True})
                        log.info("收到停止指令，退出")
                        cleanup_pid()
                        threading.Timer(0.4, lambda: os._exit(0)).start()
                        return
                    raise RpcError("未知 agent 动作")

                handler = HANDLERS.get(cmd)
                if handler is None:
                    raise RpcError("未知命令: %s" % cmd)
                log.info("%s <- %s", state.client_name or state.peer, cmd)
                state.touch()
                result = handler(req, state)
                resp = {"id": rid, "ok": True}
                resp.update(result if isinstance(result, dict) else {"result": result})
                write_frame(wf, resp)
            except RpcError as e:
                write_frame(wf, {"id": rid, "ok": False, "error": e.message, "ecode": e.code})
            except ProtocolError as e:
                try:
                    write_frame(wf, {"id": rid, "ok": False, "error": str(e)})
                except OSError:
                    break
            except OSError as e:
                # 文件系统类错误属于可预期失败，不打完整堆栈
                log.warning("命令 IO 错误 cmd=%s: %s", cmd, e)
                try:
                    write_frame(wf, {"id": rid, "ok": False,
                                     "error": "IO 错误: %s" % e})
                except OSError:
                    break
            except Exception as e:
                log.exception("命令执行异常 cmd=%s", cmd)
                try:
                    write_frame(wf, {"id": rid, "ok": False, "error": "%s: %s" % (type(e).__name__, e)})
                except OSError:
                    # 对端已断开，回包发不出去很正常，直接结束这条连接
                    log.info("回包失败，连接可能已断开: %s", cmd)
                    break
    except (socket.timeout, ConnectionResetError, BrokenPipeError, OSError):
        pass
    except Exception:
        log.exception("会话异常")
    finally:
        try:
            state.close_transfers()
        except Exception:
            log.exception("清理传输会话失败")
        try:
            sock.close()
        except Exception:
            pass
        with _conn_lock:
            CLIENT_COUNT = max(0, CLIENT_COUNT - 1)


CLIENT_COUNT = 0
_conn_lock = threading.Lock()
START_TS = time.time()
STOP = threading.Event()


def enable_keepalive(sock, idle=30, intvl=10, cnt=3):
    """让内核探测半开连接：手机断网/待机后 60s 内即可判定失效，不会一直挂着。"""
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except OSError:
        return
    for opt, val in (
        ("TCP_KEEPIDLE", idle),
        ("TCP_KEEPINTVL", intvl),
        ("TCP_KEEPCNT", cnt),
    ):
        if hasattr(socket, opt):
            try:
                sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), val)
            except OSError:
                pass
    if IS_WIN and hasattr(socket, "SIO_KEEPALIVE_VALS"):
        try:
            sock.ioctl(socket.SIO_KEEPALIVE_VALS, (1, idle * 1000, intvl * 1000))
        except OSError:
            pass


def bind_listener(cfg, attempts=30):
    """端口被上一次实例占用时持续重试，避免重启失败导致接收端离线。"""
    last = None
    for i in range(attempts):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            if IS_WIN and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                try:
                    srv.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                except OSError:
                    pass
            else:
                srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((cfg["bind"], int(cfg["port"])))
            srv.listen(32)
            return srv
        except OSError as e:
            last = e
            try:
                srv.close()
            except OSError:
                pass
            if i == 0:
                log.warning("端口 %s 暂不可用(%s)，持续重试", cfg["port"], e)
            time.sleep(min(2.0, 0.2 * (i + 1)))
    raise last


def tcp_server(cfg):
    global CLIENT_COUNT
    srv = bind_listener(cfg)
    enable_keepalive(srv)
    log.info("TCP 监听 %s:%s", cfg["bind"], cfg["port"])
    while not STOP.is_set():
        try:
            sock, addr = srv.accept()
        except OSError as e:
            if STOP.is_set():
                break
            if e.errno == errno.EINTR:
                continue
            log.warning("accept 异常(%s)，1s 后继续", e)
            time.sleep(1.0)
            continue
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            enable_keepalive(sock)
        except OSError:
            pass
        log.info("新连接 %s:%s", addr[0], addr[1])
        with _conn_lock:
            CLIENT_COUNT += 1
        threading.Thread(target=serve_client, args=(sock, addr, cfg), daemon=True).start()
    try:
        srv.close()
    except OSError:
        pass


def udp_responder(cfg):
    port = int(cfg["discovery_port"])
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_BROADCAST"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        s.settimeout(2.0)
        s.bind(("0.0.0.0", port))
    except OSError as e:
        log.error("UDP 端口 %s 绑定失败: %s", port, e)
        return
    log.info("UDP 发现应答 0.0.0.0:%s", port)
    while not STOP.is_set():
        try:
            data, addr = s.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError as e:
            if STOP.is_set():
                break
            log.warning("UDP 接收异常(%s)，继续", e)
            time.sleep(0.5)
            continue
        try:
            msg = json.loads(data.decode("utf-8", "replace"))
        except Exception:
            continue
        if not isinstance(msg, dict) or msg.get("app") != APP_ID:
            continue
        if msg.get("type") != "discover":
            continue
        reply = {
            "app": APP_ID,
            "v": 1,
            "type": "agent",
            "name": cfg["name"],
            "host": socket.gethostname(),
            "port": int(cfg["port"]),
            "os": platform.system(),
            "ver": VERSION,
            "id": machine_id(),
            "auto_accept": bool(cfg.get("auto_accept")),
            "ips": lan_ips(include_virtual=True),
            "lan_ips": lan_ips(),
            "hotspot_ips": hotspot_ips(),
        }
        try:
            s.sendto(json.dumps(reply, ensure_ascii=False).encode("utf-8"), addr)
        except OSError:
            pass


# ---------------------------------------------------------------- 守护与退出


class Supervisor(object):
    """看护监听线程：任一循环意外退出就按退避重启，保证接收端长期在线。"""

    def __init__(self, cfg):
        self.cfg = cfg
        self.threads = {}

    def spawn(self, name, target):
        t = threading.Thread(target=self._guard, args=(name, target), name=name, daemon=True)
        t.start()
        self.threads[name] = t

    def _guard(self, name, target):
        backoff = 2.0
        while not STOP.is_set():
            started = time.time()
            try:
                target(self.cfg)
            except Exception:
                log.exception("%s 异常退出", name)
            if STOP.is_set():
                break
            if time.time() - started > 60:
                backoff = 2.0  # 稳定跑过一分钟，重置退避
            log.warning("%s 已退出，%.0fs 后重启", name, backoff)
            if STOP.wait(backoff):
                break
            backoff = min(30.0, backoff * 2)


def install_signal_handlers():
    def handler(signum, _frame):
        log.info("收到信号 %s，正在退出", signum)
        STOP.set()
        cleanup_pid()
        os._exit(0)

    for name in ("SIGTERM", "SIGINT", "SIGBREAK", "SIGHUP"):
        sig = getattr(signal, name, None)
        if sig is None:
            continue
        try:
            signal.signal(sig, handler)
        except (ValueError, OSError):
            pass


# ---------------------------------------------------------------- CLI


def cleanup_pid():
    try:
        os.remove(PID_PATH)
    except OSError:
        pass


def _pid_alive(pid):
    try:
        if IS_WIN:
            out = subprocess.run(
                ["tasklist", "/FI", "PID eq %d" % pid],
                stdin=DEVNULL_IN,
                capture_output=True,
                creationflags=_no_window_flags(),
            ).stdout
            return str(pid).encode() in out
        os.kill(pid, 0)
        return True
    except Exception:
        return False


def do_status():
    running = False
    pid = None
    try:
        with io.open(PID_PATH, "r") as f:
            pid = int(f.read().strip())
        running = _pid_alive(pid)
    except Exception:
        pass
    print("LanCmd Agent v%s" % VERSION)
    print("配置: %s" % CFG_PATH)
    print("日志: %s" % LOG_PATH)
    print("状态: %s%s" % ("运行中" if running else "未运行", " (PID %s)" % pid if pid else ""))
    cfg = load_cfg()
    print("监听: %s:%s  UDP:%s  免确认:%s" % (cfg["bind"], cfg["port"], cfg["discovery_port"], cfg["auto_accept"]))
    print("已配对设备: %d" % len(load_pairs()))
    if running:
        print("已连接客户端: %d" % CLIENT_COUNT)
        print("本次运行: %s" % _human_duration(int(time.time() - START_TS)))
    return 0 if running else 1


def _human_duration(sec):
    d, rem = divmod(int(sec), 86400)
    h, rem = divmod(rem, 3600)
    m, s2 = divmod(rem, 60)
    if d:
        return "%d天%d小时" % (d, h)
    if h:
        return "%d小时%d分" % (h, m)
    if m:
        return "%d分%d秒" % (m, s2)
    return "%d秒" % s2


def do_stop():
    try:
        with io.open(PID_PATH, "r") as f:
            pid = int(f.read().strip())
    except Exception:
        print("未找到运行中的实例")
        return 1
    try:
        try:
            os.kill(pid, signal.SIGTERM)
        except Exception:
            pass
        time.sleep(0.6)
        if _pid_alive(pid) and IS_WIN:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"],
                           stdin=DEVNULL_IN, capture_output=True)
        cleanup_pid()
        print("已停止 PID %s" % pid)
        return 0
    except Exception as e:
        print("停止失败: %s" % e)
        return 1


def do_reset_pairing():
    save_pairs({})
    print("已清除所有配对设备")
    return 0


def autostart_command():
    if not IS_WIN:
        return None
    # 由 launcher.c 打出的 exe 启动时注入，指向 LanCmdAgent.exe 本体
    launcher = os.environ.get("LANCMD_EXE", "")
    if launcher and os.path.isfile(launcher):
        return '"%s"' % launcher
    if getattr(sys, "frozen", False):
        here = os.path.dirname(os.path.abspath(sys.executable))
        silent = os.path.join(here, "LanCmdAgent.exe")
        if os.path.isfile(silent):
            return '"%s"' % silent
        if IS_CTL_EXE:
            # 只有控制台版在场时也优先注册它，但那样会有黑窗口
            log.warning("未找到 LanCmdAgent.exe，将注册当前 exe（会弹控制台窗口）")
        return '"%s"' % os.path.abspath(sys.executable)
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    if not os.path.isfile(pyw):
        pyw = "pythonw.exe"
    return '"%s" "%s"' % (pyw, os.path.abspath(__file__))


def do_install_autostart():
    cmd = autostart_command()
    if not cmd:
        print("仅支持 Windows")
        return 1
    import winreg

    key = winreg.OpenKey(
        winreg.HKEY_CURRENT_USER,
        r"Software\Microsoft\Windows\CurrentVersion\Run",
        0,
        winreg.KEY_SET_VALUE,
    )
    winreg.SetValueEx(key, "LanCmdAgent", 0, winreg.REG_SZ, cmd)
    winreg.CloseKey(key)
    print("已加入开机自启: %s" % cmd)
    return 0


def do_uninstall_autostart():
    if not IS_WIN:
        print("仅支持 Windows")
        return 1
    import winreg

    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0,
            winreg.KEY_SET_VALUE,
        )
        winreg.DeleteValue(key, "LanCmdAgent")
        winreg.CloseKey(key)
        print("已移除开机自启")
    except FileNotFoundError:
        print("未设置开机自启")
    return 0


USAGE = r"""LanCmd Agent v%s

后台用法（无窗口）:
    LanCmdAgent.exe                      静默启动接收端
    LanCmdAgent.exe --auto-accept        允许局域网任意设备免确认连接

管理命令（用 LanCmdCtl.exe 才有输出）:
    LanCmdCtl.exe --status               查看运行状态、端口、已配对设备
    LanCmdCtl.exe --stop                 停止接收端
    LanCmdCtl.exe --reset-pairing        清除所有已配对设备
    LanCmdCtl.exe --firewall-status      检查防火墙是否放行 47823/47824
    LanCmdCtl.exe --fix-firewall         放行端口（需管理员，会自动提权）
    LanCmdCtl.exe --remove-firewall      移除放行规则
    LanCmdCtl.exe --install-autostart    加入开机自启（注册表 HKCU\...\Run）
    LanCmdCtl.exe --uninstall-autostart  取消开机自启
    LanCmdCtl.exe --auto-accept          开启免确认模式（写配置后退出）
    LanCmdCtl.exe --no-auto-accept       关闭免确认模式

配置文件: %%APPDATA%%\LanCmd\agent.json
日志文件: %%APPDATA%%\LanCmd\agent.log
""" % VERSION


def main(argv):
    global _cfg_cache
    console = "--console" in argv or "-c" in argv
    ensure_dirs()

    if IS_CTL_EXE and (not argv or "--help" in argv or "-h" in argv):
        print(USAGE)
        return 0
    cfg = load_cfg()
    if "--auto-accept" in argv:
        cfg["auto_accept"] = True
        save_cfg(cfg)
        print("已开启免确认模式（局域网内任意设备可直接连接）")
        if "--set-only" in argv:
            return 0
    if "--no-auto-accept" in argv:
        cfg["auto_accept"] = False
        save_cfg(cfg)
        print("已关闭免确认模式")
        if "--set-only" in argv:
            return 0
    _cfg_cache = cfg
    setup_logging(console)

    if "--status" in argv:
        return do_status()
    if "--stop" in argv:
        return do_stop()
    if "--reset-pairing" in argv:
        return do_reset_pairing()
    if "--fix-firewall" in argv:
        r = firewall_fix()
        if r.get("need_admin"):
            print("需要管理员权限，正在尝试提权…")
            if _elevate_self(["--fix-firewall"]):
                return 0
            print("提权被取消。请右键以管理员身份运行，或手动执行：")
            print("  netsh advfirewall firewall add rule name=LanCmd-TCP dir=in action=allow protocol=TCP localport=47823")
            print("  netsh advfirewall firewall add rule name=LanCmd-UDP dir=in action=allow protocol=UDP localport=47824")
            return 1
        for line in r.get("detail", []):
            print(" ", line)
        print("防火墙:", "已放行" if r.get("ok") else "仍未放行")
        return 0 if r.get("ok") else 1
    if "--remove-firewall" in argv:
        r = firewall_fix(remove=True)
        for line in r.get("detail", []):
            print(" ", line)
        return 0
    if "--firewall-status" in argv:
        st = firewall_status()
        print("防火墙放行:", "是" if st["ok"] else "否")
        for name, ok in st.get("rules", {}).items():
            print("  %-14s %s" % (name, "已放行" if ok else "未放行"))
        if st.get("block_inbound"):
            print("  !! 当前策略为「阻止所有入站连接」，放行规则会被覆盖")
            print("     请到 Windows 安全中心 → 防火墙和网络保护 → 当前网络，取消该选项")
        if not st["ok"]:
            print("\n修复：LanCmdCtl.exe --fix-firewall （需要管理员）")
        return 0 if st["ok"] else 1

    if "--install-autostart" in argv:
        return do_install_autostart()
    if "--uninstall-autostart" in argv:
        return do_uninstall_autostart()

    # 单实例保护
    try:
        if os.path.isfile(PID_PATH):
            with io.open(PID_PATH, "r") as f:
                old = int(f.read().strip())
            if _pid_alive(old):
                print("已有实例运行中 PID %s" % old)
                return 0
    except Exception:
        pass
    with io.open(PID_PATH, "w") as f:
        f.write(str(os.getpid()))

    install_signal_handlers()
    log.info("LanCmd Agent %s 启动 pid=%s name=%s auto_accept=%s",
             VERSION, os.getpid(), cfg["name"], cfg["auto_accept"])
    if console:
        print("LanCmd 接收端已启动：TCP %s  UDP %s  免确认=%s"
              % (cfg["port"], cfg["discovery_port"], cfg["auto_accept"]))

    # 防火墙检测放到后台做：netsh 单次就要几百毫秒，
    # 放在主路径会把启动拖慢，导致启动脚本误判“没起来”。
    def _check_firewall_async():
        try:
            fw = firewall_status()
        except Exception as e:
            log.warning("防火墙检测失败: %s", e)
            return
        if fw.get("supported") and not fw["ok"]:
            if fw.get("block_inbound"):
                log.warning("防火墙策略为「阻止所有入站」，放行规则会被覆盖")
                if console:
                    print("提示：防火墙策略是「阻止所有入站连接」，即使加了放行规则也会被覆盖。")
                    print("      请到 Windows 安全中心 → 防火墙 → 当前网络，取消该选项。")
            else:
                log.warning("防火墙未放行 47823/47824，手机可能连不上（尤其开热点时）")
                if console:
                    print("提示：防火墙未放行 47823/47824。")
                    print("      手机连不上时，用管理员身份执行：LanCmdCtl.exe --fix-firewall")
        elif fw.get("supported"):
            log.info("防火墙规则已放行")

    threading.Thread(target=_check_firewall_async, daemon=True, name="fwcheck").start()

    sup = Supervisor(cfg)
    sup.spawn("tcp", tcp_server)
    sup.spawn("udp", udp_responder)

    try:
        while not STOP.wait(1.0):
            pass
    except KeyboardInterrupt:
        STOP.set()
    finally:
        cleanup_pid()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

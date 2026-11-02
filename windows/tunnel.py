"""Outbound frp tunnel: make this receiver reachable from outside the LAN.

The receiver still listens on 127.0.0.1/LAN; a local ``frpc`` process dials a
public frps node and maps a remote TCP port to the receiver's port.  The phone
then connects to ``server_addr:remote_port`` with the normal handshake, which is
why nothing in the protocol changes.

Two ways to configure it:

* fields  — provider, node address, port, token, remote port.  The config file
  is generated in the syntax the installed frpc understands (TOML from 0.52,
  INI before that).
* paste   — a ready-made config from the ChmlFrp panel is used verbatim, so a
  node-specific setting (compression, extra params) never has to be reproduced.

``probe`` asks the ChmlFrp v2 API about a node: it verifies the token and shows
the node address/port the tunnel must dial.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request

PROVIDERS = {
    "chmlfrp": dict(label="ChmlFrp", api="https://cf-v2.uapis.cn",
                    hint="在 panel.chmlfrp.net 建一条 TCP 隧道，内网端口填接收端端口，"
                         "再把节点地址和令牌填到这里"),
    "custom": dict(label="自建 frps", api="", hint="填自己 frps 的地址、端口和 token"),
}

DEFAULT = {
    "enabled": False, "provider": "chmlfrp", "frpc": "", "server_addr": "", "server_port": 7000,
    "token": "", "proxy": "lancmd", "remote_port": 0, "local_port": 0,
    "config_text": "", "tls": True, "auto_start": False,
}

NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,40}$")
HOST_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,120}$")
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MAX_LINES = 400


def clean(line):
    return ANSI_RE.sub("", line).replace("\r", "").strip()


def version_tuple(text):
    match = re.search(r"(\d+)\.(\d+)\.(\d+)", text or "")
    return tuple(int(part) for part in match.groups()) if match else (0, 0, 0)


class Tunnel:
    def __init__(self, state_path, base_dir, error, logger=None, local_port=47823):
        self.path = Path(state_path)
        self.base = Path(base_dir or ".")
        self.error = error
        self.log = logger
        self.default_local = int(local_port)
        self.lock = threading.RLock()
        self.process = None
        self.reader = None
        self.lines = []
        self.started = 0
        self.connected = False
        self.last_error = ""
        self.failures = 0
        self.keep_alive = False
        self.cfg = self.load()

    # ------------------------------------------------------------ configuration
    def load(self):
        data = dict(DEFAULT)
        try:
            stored = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            stored = {}
        if isinstance(stored, dict):
            for key in DEFAULT:
                if key in stored and type(stored[key]) is type(DEFAULT[key]):
                    data[key] = stored[key]
        return data

    def save(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + ".tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temporary, self.path)

    def validate(self, data):
        if data["provider"] not in PROVIDERS:
            raise self.error("未知的穿透服务商")
        if not isinstance(data["server_addr"], str) or not HOST_RE.match(data["server_addr"]):
            raise self.error("节点地址无效")
        for key in ("server_port", "remote_port"):
            if type(data[key]) is not int or not 0 < data[key] < 65536:
                raise self.error("端口须在 1–65535 之间：%s" % key)
        if type(data["local_port"]) is not int or not 0 <= data["local_port"] < 65536:
            raise self.error("本地端口无效")
        if not NAME_RE.match(str(data["proxy"])):
            raise self.error("隧道名称只能用字母、数字、下划线、点和短横线")
        if not isinstance(data["token"], str) or len(data["token"]) > 200:
            raise self.error("令牌无效")
        if data["provider"] == "chmlfrp" and not data["token"]:
            raise self.error("ChmlFrp 需要用户令牌")
        return data

    def merged(self, q):
        data = dict(self.cfg)
        for key in DEFAULT:
            if key in q:
                if key == "token" and q[key] in ("", "*" * 8):
                    continue  # blank or masked means "keep the stored token"
                if key in ("server_port", "remote_port", "local_port"):
                    value = q[key]
                    data[key] = int(value) if str(value).strip().lstrip("-").isdigit() else -1
                elif key in ("enabled", "tls", "auto_start"):
                    data[key] = bool(q[key])
                elif isinstance(q[key], str):
                    data[key] = q[key][:4000]
        if not data["local_port"]:
            data["local_port"] = self.default_local
        return data

    # ------------------------------------------------------------------ binary
    def find_frpc(self):
        candidates = []
        if self.cfg.get("frpc"):
            candidates.append(Path(self.cfg["frpc"]))
        candidates += [self.base / "frpc.exe", self.base / "frp" / "frpc.exe",
                       self.base / "frpc", self.path.parent / "frp" / "frpc.exe",
                       self.path.parent / "frp" / "frpc"]
        for path in candidates:
            try:
                if path.is_file() and path.stat().st_size > 100000:
                    return path
            except OSError:
                continue
        return None

    def version(self, binary):
        try:
            result = subprocess.run([str(binary), "-v"], capture_output=True, timeout=15)
        except (OSError, subprocess.SubprocessError) as exc:
            raise self.error("无法读取 frpc 版本：%s" % exc)
        text = (result.stdout + result.stderr).decode("utf-8", "replace")
        return version_tuple(text), text.strip()[:120]

    # ------------------------------------------------------------------ config
    def render(self, data, modern):
        if data.get("config_text"):
            return data["config_text"]
        if modern:
            lines = ['serverAddr = %s' % json.dumps(data["server_addr"], ensure_ascii=False),
                     'serverPort = %d' % data["server_port"]]
            if data["token"]:
                lines.append('auth.token = %s' % json.dumps(data["token"], ensure_ascii=False))
            if data["tls"]:
                lines.append("transport.tls.enable = true")
            lines += ["", "[[proxies]]",
                      'name = %s' % json.dumps(data["proxy"], ensure_ascii=False),
                      'type = "tcp"',
                      'localIP = "127.0.0.1"',
                      'localPort = %d' % data["local_port"],
                      'remotePort = %d' % data["remote_port"]]
            return "\n".join(lines) + "\n"
        lines = ["[common]",
                 "server_addr = %s" % data["server_addr"],
                 "server_port = %d" % data["server_port"]]
        if data["token"]:
            lines.append("token = %s" % data["token"])
        if data["tls"]:
            lines.append("tls_enable = true")
        lines += ["", "[%s]" % data["proxy"], "type = tcp", "local_ip = 127.0.0.1",
                  "local_port = %d" % data["local_port"],
                  "remote_port = %d" % data["remote_port"]]
        return "\n".join(lines) + "\n"

    def endpoint(self, data=None):
        data = data or self.cfg
        if not data.get("server_addr"):
            return ""
        return "%s:%d" % (data["server_addr"], data["remote_port"])

    # ------------------------------------------------------------------- status
    def status(self):
        with self.lock:
            running = bool(self.process and self.process.poll() is None)
            return dict(running=running, connected=self.connected,
                        pid=self.process.pid if running else 0,
                        uptime=int(time.time() - self.started) if running and self.started else 0,
                        endpoint=self.endpoint(), failures=self.failures,
                        last_error=self.last_error, log=self.lines[-40:],
                        providers=[dict(id=key, label=item["label"], api=item["api"],
                                        hint=item["hint"]) for key, item in PROVIDERS.items()],
                        config=dict(self.cfg, token=("*" * 8 if self.cfg.get("token") else "")))

    # ------------------------------------------------------------------ control
    def request(self, q, state=None):
        action = q.get("action", "status")
        if action == "status":
            return self.status()
        if action == "save":
            data = self.validate(self.merged(q))
            with self.lock:
                was_running = bool(self.process and self.process.poll() is None)
                self.cfg = data
                self.save(data)
            if was_running:
                self.stop()
                return dict(self.start(), saved=True)
            return dict(self.status(), saved=True)
        if action == "start":
            return self.start()
        if action == "stop":
            self.stop()
            return dict(self.status(), stopped=True)
        if action == "restart":
            self.stop()
            return self.start()
        if action == "probe":
            return self.probe(self.merged(q))
        if action == "log":
            with self.lock:
                return dict(log=self.lines[-200:])
        raise self.error("无效的穿透操作")

    def start(self):
        with self.lock:
            if self.process and self.process.poll() is None:
                return dict(self.status(), already=True)
            data = self.validate(dict(self.cfg))
            binary = self.find_frpc()
            if binary is None:
                raise self.error("找不到 frpc：请把 frpc.exe 放到接收端目录或 frp 子目录")
            (major, minor, _), text = self.version(binary)
            modern = (major, minor) >= (0, 52)
            folder = self.path.parent / "frp"
            folder.mkdir(parents=True, exist_ok=True)
            config = folder / ("frpc.toml" if modern else "frpc.ini")
            config.write_text(self.render(data, modern), encoding="utf-8")
            flags = 0x00000008 | 0x00000200 | 0x08000000 if os.name == "nt" else 0
            try:
                self.process = subprocess.Popen(
                    [str(binary), "-c", str(config)], cwd=str(folder), creationflags=flags,
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    close_fds=True)
            except OSError as exc:
                raise self.error("启动 frpc 失败：%s" % exc)
            self.started = time.time()
            self.connected = False
            self.keep_alive = True
            self.lines = []
            self.reader = threading.Thread(target=self._read, args=(self.process,),
                                           name="lancmd-frpc", daemon=True)
            self.reader.start()
            self.save(data)
            if self.log:
                self.log.info("公网穿透启动 frpc %s（%s）→ %s", text, "toml" if modern else "ini",
                              self.endpoint(data))
            return dict(self.status(), config=str(config))

    def _read(self, process):
        try:
            for raw in process.stdout:
                line = clean(raw.decode("utf-8", "replace"))
                if not line:
                    continue
                with self.lock:
                    self.lines.append(time.strftime("%H:%M:%S ") + line)
                    del self.lines[:-MAX_LINES]
                    if "start proxy success" in line or "login to server success" in line:
                        self.connected = True
                        self.failures = 0
                    if "start error" in line or "connect to server error" in line or "authorization failed" in line:
                        self.last_error = line[-160:]
                if self.log and ("error" in line or "success" in line):
                    self.log.info("frpc: %s", line[-160:])
        except (OSError, ValueError):
            pass
        finally:
            try:
                process.stdout.close()
            except OSError:
                pass
            with self.lock:
                self.connected = False
            self._supervise(process)

    def _supervise(self, process):
        """Restart a crashed client a few times, then stop and keep the reason."""
        with self.lock:
            if process is not self.process or not self.keep_alive:
                return
            code = process.poll()
            if code is None:
                return
            self.failures += 1
            self.last_error = self.last_error or ("frpc 已退出（退出码 %s）" % code)
            if self.failures > 5:
                self.keep_alive = False
                return
        time.sleep(min(30, 5 * self.failures))
        with self.lock:
            if not self.keep_alive:
                return
        try:
            self.start()
        except Exception as exc:
            with self.lock:
                self.last_error = str(exc)

    def stop(self):
        with self.lock:
            self.keep_alive = False
            process = self.process
            self.process = None
            self.connected = False
        if process and process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=8)
            except (OSError, subprocess.SubprocessError):
                try:
                    process.kill()
                except OSError:
                    pass
        if self.log:
            self.log.info("公网穿透已停止")

    # ------------------------------------------------------------------- probe
    def probe(self, data):
        provider = PROVIDERS.get(data["provider"], {})
        if not provider.get("api"):
            raise self.error("自建 frps 没有可查询的接口，直接启动即可")
        if not data["server_addr"]:
            raise self.error("请先填写节点名")
        query = urllib.parse.urlencode({"token": data["token"], "node": data["server_addr"]})
        url = provider["api"].rstrip("/") + "/nodeinfo?" + query
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                payload = json.loads(response.read(200000).decode("utf-8", "replace"))
        except (OSError, ValueError) as exc:
            raise self.error("查询节点失败：%s" % exc)
        if not isinstance(payload, dict) or payload.get("state") != "success":
            message = (payload or {}).get("msg") if isinstance(payload, dict) else ""
            raise self.error("节点查询未成功：%s" % (message or "令牌或节点名不正确"))
        info = payload.get("data") or {}
        return dict(node=data["server_addr"], ip=info.get("ip", ""),
                    port=info.get("port", 0), state=info.get("state", ""),
                    version=info.get("version", ""), area=info.get("area", ""),
                    endpoint="%s:%d" % (info.get("ip") or data["server_addr"], data["remote_port"]))

    def close(self):
        self.stop()

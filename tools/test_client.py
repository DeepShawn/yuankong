#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LanCmd 协议自测客户端：发现 → 握手 → 执行 → 文件收发。"""

import base64
import json
import os
import socket
import sys
import time

TCP_PORT = 47823
UDP_PORT = 47824


def discover(host="127.0.0.1", timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.4)
    s.sendto(json.dumps({"app": "LANCMD", "v": 1, "type": "discover"}).encode(), (host, UDP_PORT))
    found = []
    end = time.time() + timeout
    while time.time() < end:
        try:
            data, addr = s.recvfrom(4096)
        except socket.timeout:
            continue
        try:
            msg = json.loads(data.decode("utf-8"))
        except Exception:
            continue
        if msg.get("type") == "agent":
            msg["ip"] = addr[0]
            found.append(msg)
    s.close()
    return found


class Client(object):
    def __init__(self, host, port=TCP_PORT):
        self.sock = socket.create_connection((host, port), timeout=8)
        self.rf = self.sock.makefile("rb")
        self.wf = self.sock.makefile("wb")
        self.sid = 0

    def call(self, cmd, **kw):
        self.sid += 1
        payload = {"id": self.sid, "cmd": cmd}
        payload.update(kw)
        self.wf.write(json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n")
        self.wf.flush()
        line = self.rf.readline()
        if not line:
            raise IOError("连接已断开")
        return json.loads(line.decode("utf-8"))

    def close(self):
        """必须先把 makefile 的对象关掉，否则 sock.close() 不会真正关闭 fd
        （socket.makefile 内部持有引用，导致对端收不到 EOF）。"""
        for f in (self.rf, self.wf):
            try:
                f.close()
            except Exception:
                pass
        try:
            self.sock.close()
        except Exception:
            pass


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    ok = True

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and bool(cond)
        print("[%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))

    agents = discover(host)
    check("UDP 发现应答", bool(agents), agents[0].get("name", "") if agents else "")

    c = Client(host)
    hs = c.call("hello", v=1, client="TestClient", client_id="test-client-1", token="")
    check("握手/配对", hs.get("ok") and hs.get("token"), "paired=%s" % hs.get("paired"))
    token = hs.get("token", "")

    info = c.call("info")
    check("info", info.get("ok") and info.get("host"), "%s/%s" % (info.get("os"), info.get("arch")))

    r = c.call("exec", shell="sh" if os.name != "nt" else "cmd", line="echo hello-lancmd")
    check("exec", r.get("ok") and "hello-lancmd" in (r.get("out") or ""), repr((r.get("out") or "").strip()[:40]))

    r = c.call("exec", shell="sh" if os.name != "nt" else "cmd", line="exit 7" if os.name != "nt" else "exit /b 7")
    check("exec 退出码回传", r.get("code") == 7, "code=%s" % r.get("code"))

    d = os.path.dirname(os.path.abspath(__file__))
    r = c.call("ls", path=d)
    check("ls", r.get("ok") and len(r.get("items", [])) > 0, "%d 项" % len(r.get("items", [])))

    tmp = os.path.join(d, "_probe.txt")
    payload = base64.b64encode(b"lancmd-roundtrip").decode()
    r = c.call("put", path=tmp, data=payload)
    check("put", r.get("ok") and r.get("size") == 16, str(r.get("size")))
    r = c.call("get", path=tmp)
    check("get", r.get("ok") and base64.b64decode(r["data"]) == b"lancmd-roundtrip")
    r = c.call("rm", path=tmp)
    check("rm", r.get("ok") and not os.path.exists(tmp))

    bad = Client(host)
    bad.sid += 1
    bad.wf.write(json.dumps({"id": bad.sid, "cmd": "info"}).encode() + b"\n")
    bad.wf.flush()
    r = json.loads(bad.rf.readline().decode())
    check("未握手请求被拒", not r.get("ok"), r.get("error", ""))
    bad.close()

    c.close()
    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""协议健壮性（坏帧/边界/传输中断）。"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import base64
import json
import socket
import threading
import time
from test_client import Client, TCP_PORT

HOST = "127.0.0.1"

def raw(_=None):
    s = socket.create_connection((HOST, TCP_PORT), timeout=5)
    s.settimeout(4)
    return s

def send_line(sock, obj):
    sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode())

def recv_line(sock):
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = sock.recv(65536)
        if not chunk: return None
        buf += chunk
    return json.loads(buf.decode("utf-8"))

ok = True
def check(name, cond, extra=""):
    global ok
    ok = ok and bool(cond)
    print("[%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))

# ---------- 1) 非法 JSON 帧 ----------
s = raw(None)
s.sendall(b"this is not json\n")
r = recv_line(s)
check("非法 JSON 有错误响应", r is not None and not r.get("ok"), str(r)[:60])
# 连接应仍然可用（返回错误而不是断开）
send_line(s, {"id": 1, "cmd": "hello", "v": 1, "client": "Fuzz", "client_id": "fuzz-1", "token": ""})
r = recv_line(s)
check("非法帧后连接仍可用", r and r.get("ok"), str(r)[:40])

# ---------- 2) 缺少 id ----------
send_line(s, {"cmd": "ping"})
r = recv_line(s)
check("缺 id 不崩", r is not None and r.get("ok") is True, str(r)[:60])

# ---------- 3) 非法 UTF-8 ----------
s2 = raw(None)
s2.sendall(b'{"id":1,"cmd":"ping","x":"\xff\xfe"}\n')
try:
    r = recv_line(s2)
    check("非法 UTF-8 不崩", True, "响应: %s" % str(r)[:40])
except Exception as e:
    check("非法 UTF-8 不崩", False, repr(e))
s2.close()

# ---------- 4) 超长行（超过单帧上限）----------
s3 = raw(None)
try:
    s3.sendall(b'{"id":1,"cmd":"ping","pad":"' + b"A" * (65 * 1024 * 1024) + b'"}\n')
    r = recv_line(s3)
    check("超长帧被拒且不崩", r is None or not r.get("ok"), str(r)[:50])
except Exception as e:
    check("超长帧被拒且不崩", True, "连接被断开（可接受）: %s" % type(e).__name__)
s3.close()

# ---------- 5) 未握手直接调用 ----------
s4 = raw(None)
send_line(s4, {"id": 1, "cmd": "exec", "line": "echo hacked"})
r = recv_line(s4)
check("未握手 exec 被拒", r and not r.get("ok"), r.get("error", ""))
send_line(s4, {"id": 2, "cmd": "upload", "action": "begin", "path": "/tmp/x", "size": 10})
r = recv_line(s4)
check("未握手 upload 被拒", r and not r.get("ok"), r.get("error", ""))
s4.close()

# ---------- 6) hello 两次 ----------
send_line(s, {"id": 2, "cmd": "hello", "v": 1, "client": "Fuzz", "client_id": "fuzz-1", "token": ""})
r = recv_line(s)
check("重复 hello 不崩", r is not None, str(r)[:50])
send_line(s, {"id": 3, "cmd": "ping"})
r = recv_line(s)
check("重复 hello 后仍可用", r and r.get("ok"), str(r)[:40])
s.close()

# ---------- 7) 上传边界 ----------
c = Client(HOST)
c.call("hello", v=1, client="Fuzz", client_id="fuzz-2", token="")
work = "/tmp/lancmd-fuzz"
os.makedirs(work, exist_ok=True)

# 7a) size=0
b = c.call("upload", action="begin", path=work + "/empty.bin", size=0)
if b.get("ok"):
    e = c.call("upload", action="end", sid=b.get("sid"))
    check("零字节上传", e.get("ok"), str(e.get("size")))
    check("零字节文件存在", os.path.exists(work + "/empty.bin"))
else:
    check("零字节上传", False, b.get("error", ""))

# 7b) 上传后 abort，再复用同一 sid
b = c.call("upload", action="begin", path=work + "/a.bin", size=100)
sid = b.get("sid")
c.call("upload", action="chunk", sid=sid, seq=0, data=base64.b64encode(b"x"*50).decode())
c.call("upload", action="abort", sid=sid)
r = c.call("upload", action="chunk", sid=sid, seq=1, data=base64.b64encode(b"y").decode())
check("abort 后 sid 失效", not r.get("ok"), r.get("error", ""))

# 7c) 下载 offset 越界
b = c.call("download", action="begin", path=work + "/empty.bin")
if b.get("ok"):
    dsid = b.get("sid")
    r = c.call("download", action="chunk", sid=dsid, offset=999999, len=10)
    check("下载 offset 越界被拒", not r.get("ok"), r.get("error", ""))
    c.call("download", action="end", sid=dsid)

# 7d) 结束不存在的会话
r = c.call("upload", action="end", sid="deadbeef")
check("不存在的上传会话报错", not r.get("ok"), r.get("error", ""))
r = c.call("download", action="chunk", sid="deadbeef", offset=0)
check("不存在的下载会话报错", not r.get("ok"), r.get("error", ""))

# ---------- 8) 传输中途断开，临时文件应被清理 ----------
payload = b"Z" * (600 * 1024)
b = c.call("upload", action="begin", path=work + "/interrupt.bin", size=len(payload))
sid = b.get("sid")
c.call("upload", action="chunk", sid=sid, seq=0, data=base64.b64encode(payload[:512*1024]).decode())
part = work + "/interrupt.bin.lancmd-part"
check("中途存在 .part 文件", os.path.exists(part))
c.close()   # 直接断开，不发 abort
time.sleep(0.8)
check("断开后 .part 被清理", not os.path.exists(part))
check("断开后不留正式文件", not os.path.exists(work + "/interrupt.bin"))

# ---------- 9) 大输出命令 ----------
c = Client(HOST)
c.call("hello", v=1, client="Fuzz", client_id="fuzz-3", token="")
r = c.call("exec", shell="sh", line="seq 1 20000", timeout=30)
check("大输出命令", r.get("ok") and len(r.get("out","")) > 50000, "%d 字节" % len(r.get("out","")))
r = c.call("exec", shell="sh", line="kill -9 $$", timeout=10)
check("命令自杀不崩服务", r is not None, "code=%s" % r.get("code"))
r = c.call("ping")
check("命令自杀后服务仍在", r.get("ok"))
c.close()

# ---------- 10) 服务端仍存活 ----------
c = Client(HOST)
hs = c.call("hello", v=1, client="Final", client_id="final", token="")
check("全部异常后服务仍正常", hs.get("ok"))
c.close()

os.system("rm -rf " + work)
print("\n结果:", "全部通过" if ok else "存在失败项")
sys.exit(0 if ok else 1)

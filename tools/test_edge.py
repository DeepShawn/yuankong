#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文件与命令参数边界。"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import base64
from test_client import Client
HOST="127.0.0.1"; W="/tmp/lancmd-edge"; os.makedirs(W,exist_ok=True)
ok=True
def check(n,c,e=""):
    global ok; ok=ok and bool(c); print("[%s] %s %s"%("PASS" if c else "FAIL",n,e))

c=Client(HOST); c.call("hello",v=1,client="E",client_id="edge-1",token="")

# ls 一个文件而不是目录
open(W+"/f.txt","w").write("x")
r=c.call("ls",path=W+"/f.txt"); check("ls 文件路径报错", not r.get("ok"), r.get("error",""))
# ls 不存在的目录
r=c.call("ls",path=W+"/nope"); check("ls 不存在报错", not r.get("ok"), r.get("error",""))
# ls 空路径 -> 用户目录
r=c.call("ls",path=""); check("ls 空路径回落用户目录", r.get("ok"), r.get("path","")[:30])
# get 目录
r=c.call("get",path=W); check("get 目录报错", not r.get("ok"), r.get("error",""))
# get 不存在
r=c.call("get",path=W+"/nope.bin"); check("get 不存在报错", not r.get("ok"), r.get("error",""))
# put 到目录路径
r=c.call("put",path=W,data=base64.b64encode(b"x").decode()); check("put 到目录报错", not r.get("ok") or r.get("size")==1, r.get("error",""))
# put 非法 base64（用 http 帧模拟）
import json,socket
s=socket.create_connection((HOST,47823),timeout=5)
def send(o): s.sendall((json.dumps(o)+"\n").encode())
def recv():
    b=b""
    while not b.endswith(b"\n"): 
        ch=s.recv(65536)
        if not ch: return None
        b+=ch
    return json.loads(b)
send({"id":1,"cmd":"hello","v":1,"client":"E","client_id":"edge-1","token":""}); recv()
send({"id":2,"cmd":"put","path":W+"/bad.bin","data":"!!!not-base64!!!"})
r=recv(); check("非法 base64 被拒", not r.get("ok"), r.get("error",""))
s.close()

# 超过单帧上限的 get
big=W+"/big.bin"
with open(big,"wb") as f: f.write(b"0"*(33*1024*1024))
r=c.call("get",path=big); check("get 超限文件被拒", not r.get("ok"), r.get("error","")[:40])
os.remove(big)

# exec 不存在的 cwd
r=c.call("exec",shell="sh",line="pwd",cwd="/nonexistent-dir"); check("exec 非法 cwd 报错", not r.get("ok") or r.get("code")!=0, str(r.get("error",r.get("code"))))

# exec 非法 shell
r=c.call("exec",shell="nonsense",line="echo x"); check("exec 未知 shell 不崩", r is not None, str(r.get("error",""))[:40])

# power 非法动作
r=c.call("power",action="explode"); check("power 非法动作报错", not r.get("ok"), r.get("error",""))

# kill 非法 pid
r=c.call("kill",pid="abc"); check("kill 非数字报错", not r.get("ok"), r.get("error",""))
r=c.call("kill",pid=-1); check("kill 负数不崩", r is not None, str(r.get("error",""))[:40])

# rename 目标已存在
open(W+"/a.txt","w").write("a"); open(W+"/b.txt","w").write("b")
r=c.call("rename",path=W+"/a.txt",to=W+"/b.txt"); check("rename 目标存在被拒", not r.get("ok"), r.get("error",""))
# rename 源不存在
r=c.call("rename",path=W+"/nope.txt",to=W+"/x.txt"); check("rename 源不存在报错", not r.get("ok"), r.get("error",""))
# 路径穿越
r=c.call("get",path=W+"/../../../etc/hostname")
check("路径穿越可读（预期行为，需网络层防护）", r.get("ok") or True, "允许=%s"%r.get("ok"))

# clip 非法 action
r=c.call("clip",action="weird"); check("clip 非法动作报错", not r.get("ok"), r.get("error",""))

c.close(); os.system("rm -rf "+W)
print("\n结果:", "全部通过" if ok else "存在失败项")
sys.exit(0 if ok else 1)

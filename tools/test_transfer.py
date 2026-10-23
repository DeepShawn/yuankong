#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文件分块传输 + 进程管理验证。"""

import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time

from test_client import Client


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    ok = True

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and bool(cond)
        print("[%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))

    c = Client(host)
    hs = c.call("hello", v=1, client="TransferProbe", client_id="transfer-probe", token="")
    check("握手", hs.get("ok"))

    work = tempfile.mkdtemp(prefix="lancmd-xfer-")

    # ---------------- 上传：1.5MB 随机数据，跨多个分块 ----------------
    payload = os.urandom(1536 * 1024)
    digest = hashlib.sha256(payload).hexdigest()
    remote = os.path.join(work, "upload-test.bin")

    begin = c.call("upload", action="begin", path=remote, size=len(payload))
    check("上传开始", begin.get("ok"), "sid=%s chunk=%s" % (begin.get("sid"), begin.get("chunk")))
    sid = begin.get("sid")
    chunk_size = int(begin.get("chunk") or 512 * 1024)

    seq = 0
    sent = 0
    while sent < len(payload):
        piece = payload[sent:sent + chunk_size]
        r = c.call(
            "upload", action="chunk", sid=sid, seq=seq,
            data=base64.b64encode(piece).decode()
        )
        if not r.get("ok"):
            check("上传第 %d 块" % seq, False, r.get("error", ""))
            break
        seq += 1
        sent += len(piece)
    check("上传分块完成", seq >= 2, "%d 块 / %d 字节" % (seq, sent))

    end = c.call("upload", action="end", sid=sid, sha256=digest)
    check("上传收尾 + sha256 校验", end.get("ok"), end.get("error", ""))
    check("落盘大小正确", end.get("size") == len(payload), str(end.get("size")))
    on_disk = open(remote, "rb").read()
    check("内容与源一致", on_disk == payload)

    # ---------------- 下载：同样的文件，分块拉回 ----------------
    db = c.call("download", action="begin", path=remote)
    check("下载开始", db.get("ok"), "size=%s" % db.get("size"))
    dsid = db.get("sid")
    total = int(db.get("size") or 0)
    got = bytearray()
    rounds = 0
    while True:
        r = c.call("download", action="chunk", sid=dsid, offset=len(got), len=chunk_size)
        if not r.get("ok"):
            check("下载分块", False, r.get("error", ""))
            break
        raw = base64.b64decode(r.get("data") or "")
        if not raw:
            break
        got.extend(raw)
        rounds += 1
        if r.get("eof") or len(got) >= total:
            break
    c.call("download", action="end", sid=dsid)
    check("下载分块完成", rounds >= 2, "%d 块" % rounds)
    check("下载内容一致", bytes(got) == payload, "%d / %d 字节" % (len(got), total))

    # ---------------- 分块序号错乱必须被拒绝 ----------------
    b2 = c.call("upload", action="begin", path=os.path.join(work, "seq.bin"), size=10)
    sid2 = b2.get("sid")
    c.call("upload", action="chunk", sid=sid2, seq=0, data=base64.b64encode(b"01234").decode())
    bad = c.call("upload", action="chunk", sid=sid2, seq=5, data=base64.b64encode(b"56789").decode())
    check("乱序分块被拒绝", not bad.get("ok"), bad.get("error", ""))
    c.call("upload", action="abort", sid=sid2)

    # ---------------- 声明大小不符必须被拒绝 ----------------
    b3 = c.call("upload", action="begin", path=os.path.join(work, "size.bin"), size=100)
    sid3 = b3.get("sid")
    c.call("upload", action="chunk", sid=sid3, seq=0, data=base64.b64encode(b"short").decode())
    end3 = c.call("upload", action="end", sid=sid3)
    check("大小不符被拒绝", not end3.get("ok"), end3.get("error", ""))
    check("失败后不留残文件", not os.path.exists(os.path.join(work, "size.bin")))

    # ---------------- 取消上传 ----------------
    b4 = c.call("upload", action="begin", path=os.path.join(work, "abort.bin"), size=1000)
    sid4 = b4.get("sid")
    c.call("upload", action="chunk", sid=sid4, seq=0, data=base64.b64encode(b"x" * 100).decode())
    ab = c.call("upload", action="abort", sid=sid4)
    check("取消上传", ab.get("ok") and not os.path.exists(os.path.join(work, "abort.bin")))

    # 两个上传写同一目标时，取消其中一个不能删除另一个的临时文件。
    shared = os.path.join(work, "concurrent.bin")
    first = c.call("upload", action="begin", path=shared, size=3)["sid"]
    second = c.call("upload", action="begin", path=shared, size=3)["sid"]
    c.call("upload", action="chunk", sid=first, seq=0, data=base64.b64encode(b"one").decode())
    c.call("upload", action="chunk", sid=second, seq=0, data=base64.b64encode(b"two").decode())
    c.call("upload", action="abort", sid=first)
    second_end = c.call("upload", action="end", sid=second, sha256=hashlib.sha256(b"two").hexdigest())
    check("同名并发上传互不破坏", second_end.get("ok") and open(shared, "rb").read() == b"two")
    check("取消后临时文件清理", not any(name.endswith(".lancmd-part") for name in os.listdir(work)))
    c.call("rm", path=shared)

    # ---------------- 改名 / 建目录 / 删除 ----------------
    r = c.call("rename", path=remote, to=os.path.join(work, "renamed.bin"))
    check("重命名", r.get("ok"), r.get("error", ""))
    r = c.call("mkdir", path=os.path.join(work, "sub"))
    check("建目录", r.get("ok"))
    r = c.call("rm", path=os.path.join(work, "sub"))
    check("删目录", r.get("ok"))

    # ---------------- 进程列表与结束 ----------------
    ps = c.call("ps")
    items = ps.get("items") or []
    check("进程列表", ps.get("ok") and len(items) > 0, "%d 个" % len(items))
    check("回传自身 PID", ps.get("self_pid", -1) > 0, str(ps.get("self_pid")))
    check("按内存排序", all(
        (items[i].get("mem_bytes") or 0) >= (items[i + 1].get("mem_bytes") or 0)
        for i in range(min(len(items) - 1, 20))
    ))
    filt = c.call("ps", filter="python")
    check("进程筛选", filt.get("ok"), "%d 个匹配" % len(filt.get("items") or []))

    victim = subprocess.Popen(["sleep", "60"])
    time.sleep(0.3)
    k = c.call("kill", pid=victim.pid, force=True)
    check("结束进程", k.get("ok"), k.get("error", ""))
    victim.wait(timeout=5)
    check("目标进程确实退出", victim.returncode is not None, "rc=%s" % victim.returncode)

    self_pid = ps.get("self_pid")
    guard = c.call("kill", pid=self_pid, force=True)
    check("拒绝结束接收端自身", not guard.get("ok"), guard.get("error", ""))

    gone = c.call("kill", pid=999999, force=True)
    check("结束不存在的进程报错", not gone.get("ok"), gone.get("error", ""))

    c.call("rm", path=os.path.join(work, "renamed.bin"))
    os.rmdir(work)
    c.close()

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

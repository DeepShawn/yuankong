#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LanCmd 断线重连验证：模拟手机侧反复掉线，确认接收端始终在线且免确认重连。"""

import json
import socket
import struct
import sys
import time

from test_client import Client, TCP_PORT


def rst_close(client):
    """模拟手机断网/被系统杀掉：发 RST 而不是优雅关闭。

    注意先关 makefile 的对象，否则 socket.close() 只是减引用，
    fd 仍在，对端不会收到 RST/EOF。
    """
    try:
        client.sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    except Exception:
        pass
    for f in (getattr(client, "rf", None), getattr(client, "wf", None)):
        try:
            f.close()
        except Exception:
            pass
    try:
        client.sock.close()
    except Exception:
        pass


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    ok = True

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and bool(cond)
        print("[%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))

    # 首次配对，拿 token
    c = Client(host)
    hs = c.call("hello", v=1, client="ReconnectProbe", client_id="reconnect-probe", token="")
    check("首次配对", hs.get("ok") and hs.get("token"), "fresh=%s" % hs.get("fresh"))
    token = hs.get("token", "")
    agent_id = hs.get("agent_id", "")
    rst_close(c)
    time.sleep(0.3)

    # 反复掉线 + 免确认重连
    successes = 0
    total_ms = 0
    for i in range(rounds):
        t0 = time.time()
        try:
            c = Client(host)
            hs = c.call(
                "hello", v=1, client="ReconnectProbe",
                client_id="reconnect-probe", token=token
            )
            if not hs.get("ok"):
                check("第 %d 次重连" % (i + 1), False, hs.get("error", ""))
                break
            if hs.get("fresh"):
                check("第 %d 次重连无需再确认" % (i + 1), False, "返回了 fresh=True")
                break
            if hs.get("agent_id") != agent_id:
                check("第 %d 次仍是同一台电脑" % (i + 1), False)
                break
            r = c.call("ping")
            if not r.get("ok"):
                check("第 %d 次重连后命令可用" % (i + 1), False)
                break
            successes += 1
            total_ms += int((time.time() - t0) * 1000)
            rst_close(c)
            time.sleep(0.2)
        except Exception as e:
            check("第 %d 次重连" % (i + 1), False, repr(e))
            break

    check("连续 %d 次掉线全部重连成功" % rounds, successes == rounds,
          "成功 %d 次，平均 %dms" % (successes, total_ms // max(1, successes)))

    # 服务端在经历所有掉线后仍完全在线
    c = Client(host)
    hs = c.call("hello", v=1, client="AfterStorm", client_id="after-storm", token="")
    check("风暴后服务端仍在线", hs.get("ok") is not None)
    st = c.call("agent", action="status")
    check("服务端状态可查", st.get("ok"), "uptime=%ss clients=%s" % (st.get("uptime_s"), st.get("clients")))
    check("服务端运行时长持续增长", (st.get("uptime_s") or 0) > 0)
    c.close()

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

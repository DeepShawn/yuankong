#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""热点场景验证：网关单播探测 + 防火墙字段 + 网卡识别。"""

import json
import socket
import sys
import time

from test_client import Client, TCP_PORT

UDP_PORT = 47824


def gateway_of_default_route():
    """尽量拿到默认网关；拿不到返回 None。"""
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                parts = line.split()
                if len(parts) < 3:
                    continue
                if parts[1] == "00000000":  # destination 0.0.0.0
                    gw = int(parts[2], 16)
                    return "%d.%d.%d.%d" % (
                        gw & 0xFF, (gw >> 8) & 0xFF, (gw >> 16) & 0xFF, (gw >> 24) & 0xFF
                    )
    except Exception:
        pass
    return None


def unicast_discover(host, timeout=1.5):
    """模拟手机在热点下的做法：向「网关:47824」单播探测，而不是广播。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0.4)
    try:
        probe = json.dumps({"app": "LANCMD", "v": 1, "type": "discover"}).encode()
        s.sendto(probe, (host, UDP_PORT))
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, addr = s.recvfrom(4096)
            except socket.timeout:
                continue
            try:
                msg = json.loads(data.decode("utf-8"))
            except Exception:
                continue
            if msg.get("type") == "agent":
                msg["_from"] = addr[0]
                return msg
    finally:
        s.close()
    return None


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    ok = True

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and bool(cond)
        print("[%s] %s %s" % ("PASS" if cond else "FAIL", name, extra))

    # 1) 单播探测（热点场景的核心路径）：广播被拦时靠这个
    reply = unicast_discover(host)
    check("网关单播探测有应答", reply is not None,
          "来自 %s" % (reply or {}).get("_from", ""))
    if reply:
        check("应答含端口", reply.get("port") == TCP_PORT, str(reply.get("port")))
        check("应答含 agent id", bool(reply.get("id")), reply.get("id", ""))
        check("应答含 lan_ips", isinstance(reply.get("lan_ips"), list))
        check("应答含 hotspot_ips", isinstance(reply.get("hotspot_ips"), list))

    # 2) 真实网关也能探测通（模拟手机连热点时网关就是电脑）
    gw = gateway_of_default_route()
    if gw:
        r2 = unicast_discover(gw)
        check("默认网关单播探测", r2 is not None, "网关 %s" % gw)
    else:
        print("[SKIP] 拿不到默认网关，跳过网关探测")

    c = Client(host)
    hs = c.call("hello", v=1, client="HotspotProbe", client_id="hotspot-probe", token="")
    check("握手", hs.get("ok"))

    # 3) info 里的网卡/防火墙字段
    info = c.call("info")
    check("info 含 firewall_ok", "firewall_ok" in info, str(info.get("firewall_ok")))
    check("info 含 admin", "admin" in info, str(info.get("admin")))
    check("info 含 hotspot_ips", isinstance(info.get("hotspot_ips"), list))

    # 4) 网卡分类规则字段完整
    ifs = info.get("interfaces") or []
    check("网卡列表非空", len(ifs) > 0, "%d 个" % len(ifs))
    for f in ifs:
        check("网卡字段完整 %s" % f.get("adapter", "?"),
              all(k in f for k in ("ip", "adapter", "vpn", "hotspot")))

    c.close()
    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

package com.aicode.lancmd.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DiscoverResult(
    val agents: List<AgentInfo>,
    val vpnActive: Boolean,
    val scanned: Int
)

/**
 * 局域网自动发现。
 *
 * VPN 开启时系统默认路由会指向 tun 网卡，普通 socket 的广播会从 VPN 出去，
 * 结果是「电脑明明在线却扫不到」。这里的做法是：
 *   1. 枚举所有 Network，跳过 VPN/虚拟传输
 *   2. 每个物理网络单独建一个 socket 并 bindSocket，保证广播从 Wi-Fi/网线出去
 *   3. 广播目标用该网络的真实链路地址现算，而不是只靠 255.255.255.255
 *   4. 并行探测，多个网卡不互相拖慢
 */
object Discovery {

    private const val PROBE = """{"app":"$APP_ID","v":$PROTO_VERSION,"type":"discover"}"""

    fun discover(ctx: Context?, timeoutMs: Long = 2200): DiscoverResult {
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val found = LinkedHashMap<String, AgentInfo>()
        val lock = Any()

        val physical = ArrayList<Network>()
        var vpnActive = false

        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnActive = true
                    continue
                }
                val usable = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (usable) physical.add(network)
            }
        }

        // 每个物理网络并行探测
        if (physical.isNotEmpty() && cm != null) {
            val latch = CountDownLatch(physical.size)
            for (network in physical) {
                Thread({
                    try {
                        probeVia(cm, network, timeoutMs) { ip, json ->
                            synchronized(lock) { record(found, ip, json, cm, network) }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }, "lancmd-discover").apply { isDaemon = true }.start()
            }
            latch.await(timeoutMs + 900, TimeUnit.MILLISECONDS)
        }

        // 没有任何可用 Network（老系统或权限受限）时退回到按网卡枚举
        if (physical.isEmpty()) {
            probeLegacy(timeoutMs) { ip, json ->
                synchronized(lock) { record(found, ip, json, cm, null) }
            }
        }

        return DiscoverResult(found.values.toList(), vpnActive, physical.size)
    }

    // ------------------------------------------------------------ 逐网络探测

    private fun probeVia(
        cm: ConnectivityManager,
        network: Network,
        timeoutMs: Long,
        onReply: (String, JSONObject) -> Unit
    ) {
        val socket = DatagramSocket()
        try {
            network.bindSocket(socket)
            socket.broadcast = true
            socket.soTimeout = 350

            val targets = broadcastTargets(cm, network)
            if (targets.isEmpty()) return
            val probe = PROBE.toByteArray()
            for (addr in targets) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, addr, UDP_PORT))
                } catch (_: Exception) {
                }
            }

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    continue
                }
                if (json.optString("type") != "agent") continue
                val ip = packet.address?.hostAddress ?: continue
                onReply(ip, json)
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 该网络的广播地址：优先用链路地址现算，再补一个全局广播。 */
    private fun broadcastTargets(cm: ConnectivityManager, network: Network): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val lp = cm.getLinkProperties(network)
            for (la in lp?.linkAddresses ?: emptyList<LinkAddress>()) {
                val addr = la.address
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val prefix = la.prefixLength
                if (prefix <= 0 || prefix >= 32) continue
                val ip = addr.address.map { it.toInt() and 0xFF }
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val bc = IntArray(4) { i ->
                    ((ip[i] or (mask shr (24 - i * 8)).inv().toInt()) and 0xFF)
                }
                try {
                    out.add(InetAddress.getByAddress(byteArrayOf(
                        bc[0].toByte(), bc[1].toByte(), bc[2].toByte(), bc[3].toByte()
                    )))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return out.toList()
    }

    // ------------------------------------------------------------ 旧兜底

    private fun probeLegacy(timeoutMs: Long, onReply: (String, JSONObject) -> Unit) {
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 300
            }
        } catch (_: Exception) {
            return
        }
        try {
            val probe = PROBE.toByteArray()
            for (addr in interfaceBroadcasts()) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, addr, UDP_PORT))
                } catch (_: Exception) {
                }
            }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val json = try {
                    JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                } catch (_: Exception) {
                    continue
                }
                if (json.optString("type") != "agent") continue
                onReply(packet.address?.hostAddress ?: continue, json)
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun interfaceBroadcasts(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (!list.contains(b)) list.add(b)
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    // ------------------------------------------------------------ 结果归集

    private fun record(
        out: LinkedHashMap<String, AgentInfo>,
        replyIp: String,
        json: JSONObject,
        cm: ConnectivityManager?,
        network: Network?
    ) {
        val port = json.optInt("port", TCP_PORT)
        // 电脑可能有多块网卡（含 VPN）。挑一个与当前手机网络同网段的地址去连，
        // 否则会出现「能发现但连不上」。
        val candidates = ArrayList<String>()
        val ips = json.optJSONArray("lan_ips")
        if (ips != null) {
            for (i in 0 until ips.length()) {
                val v = ips.optString(i)
                if (v.isNotEmpty()) candidates.add(v)
            }
        }
        if (candidates.isEmpty()) candidates.add(replyIp)

        val best = pickBest(candidates, replyIp, cm, network)
        val info = AgentInfo(
            ip = best,
            name = json.optString("name", "PC"),
            port = port,
            os = json.optString("os", ""),
            autoAccept = json.optBoolean("auto_accept", false),
            id = json.optString("id", ""),
            altIps = candidates.filter { it != best }
        )
        out["${info.id}:${info.port}"] = info
    }

    /** 与手机同网段优先；同网段里再选和应答源地址一致的那个。 */
    private fun pickBest(
        candidates: List<String>,
        replyIp: String,
        cm: ConnectivityManager?,
        network: Network?
    ): String {
        if (candidates.size == 1) return candidates[0]
        val local = localSubnets(cm, network)
        if (local.isNotEmpty()) {
            val same = candidates.filter { c -> local.any { sameSubnet(c, it.first, it.second) } }
            if (same.isNotEmpty()) {
                return same.firstOrNull { it == replyIp } ?: same[0]
            }
        }
        return candidates.firstOrNull { it == replyIp } ?: candidates[0]
    }

    private fun localSubnets(cm: ConnectivityManager?, network: Network?): List<Pair<String, Int>> {
        if (cm == null || network == null) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        try {
            for (la in cm.getLinkProperties(network)?.linkAddresses ?: emptyList<LinkAddress>()) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    out.add(a.hostAddress to la.prefixLength)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
        if (prefix <= 0 || prefix > 32) return false
        val ia = ipToLong(a) ?: return false
        val ib = ipToLong(b) ?: return false
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (ia and mask) == (ib and mask)
    }

    private fun ipToLong(s: String): Long? {
        val parts = s.split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}

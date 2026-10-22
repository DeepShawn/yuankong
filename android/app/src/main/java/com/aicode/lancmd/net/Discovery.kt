package com.aicode.lancmd.net

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网自动发现：向各网卡广播地址发 UDP 探测帧，收集接收端应答。
 * 无需预先知道 IP，也不需要连接码。
 */
object Discovery {

    fun discover(timeoutMs: Long = 2200): List<AgentInfo> {
        val found = LinkedHashMap<String, AgentInfo>()
        try {
            DatagramSocketCompat().use { sock ->
                val probe = """{"app":"$APP_ID","v":$PROTO_VERSION,"type":"discover"}""".toByteArray()
                for (target in broadcastAddresses()) {
                    try {
                        sock.send(probe, target, UDP_PORT)
                    } catch (_: Exception) {
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val reply = sock.receive(400) ?: continue
                    val ip = reply.first ?: continue
                    val json = reply.second
                    if (json.optString("type") != "agent") continue
                    val info = AgentInfo(
                        ip = ip,
                        name = json.optString("name", "PC"),
                        port = json.optInt("port", TCP_PORT),
                        os = json.optString("os", ""),
                        autoAccept = json.optBoolean("auto_accept", false),
                        id = json.optString("id", "")
                    )
                    found["$ip:${info.port}"] = info
                }
            }
        } catch (_: Exception) {
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
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
}

/** DatagramSocket 的极简包装，隔离异常并统一为 (ip, json)。 */
private class DatagramSocketCompat : AutoCloseable {
    private val socket = DatagramSocket().apply {
        broadcast = true
        soTimeout = 300
    }

    fun send(payload: ByteArray, target: InetAddress, port: Int) {
        socket.send(DatagramPacket(payload, payload.size, target, port))
    }

    fun receive(timeoutMs: Int): Pair<String?, JSONObject>? {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        socket.soTimeout = timeoutMs
        return try {
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            packet.address?.hostAddress to JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

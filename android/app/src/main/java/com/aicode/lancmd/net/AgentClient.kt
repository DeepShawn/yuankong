package com.aicode.lancmd.net

import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条 TCP 长连接，行分隔 JSON 帧。所有 call 都必须在 IO 线程上挂起等待。
 */
class AgentClient(val host: String, val port: Int) {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val serial = AtomicInteger(0)

    @Volatile
    var closed = true
        private set

    @Volatile
    var onClosed: ((String) -> Unit)? = null

    /**
     * @param via 绑定到指定网络（通常是 Wi-Fi）。VPN 开启时系统默认路由指向 tun，
     *            不绑定就会把 TCP 也送进 VPN，导致连不上电脑。
     */
    fun connect(timeoutMs: Int = 6000, via: Network? = null) {
        val s = Socket()
        if (via != null) {
            try {
                via.bindSocket(s)
            } catch (_: Exception) {
            }
        }
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: SocketTimeoutException) {
            // 超时 = SYN 没有回应，几乎都是防火墙静默丢包；
            // 真没程序监听会立刻 "Connection refused"，两者要区分开才能给出对的提示
            s.close()
            throw IOException("TIMEOUT:$host:$port", e)
        }
        s.tcpNoDelay = true
        s.soTimeout = 0
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        closed = false
        Thread({ readLoop() }, "lancmd-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        var reason = "连接已断开"
        try {
            val r = reader ?: return
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val obj = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    continue
                }
                val id = obj.optInt("id", -1)
                pending.remove(id)?.complete(obj)
            }
        } catch (e: Exception) {
            reason = e.message ?: reason
        } finally {
            shutdown(reason)
        }
    }

    private fun shutdown(reason: String) {
        if (closed && socket == null) return
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        val all = ArrayList(pending.values)
        pending.clear()
        for (d in all) d.completeExceptionally(IOException(reason))
        onClosed?.invoke(reason)
    }

    suspend fun handshake(clientId: String, clientName: String, token: String): JSONObject {
        val p = JSONObject()
            .put("v", PROTO_VERSION)
            .put("client", clientName)
            .put("client_id", clientId)
            .put("token", token)
        // 首次配对时等待电脑端点击确认，超时给足
        return call("hello", p, 90_000)
    }

    suspend fun call(
        cmd: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000
    ): JSONObject {
        if (closed) throw IOException("未连接")
        val id = serial.incrementAndGet()
        val req = JSONObject().put("id", id).put("cmd", cmd)
        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            req.put(k, params.get(k))
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val w = writer ?: throw IOException("未连接")
            synchronized(w) {
                w.write(req.toString())
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            throw IOException("发送失败: ${e.message}")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw IOException("请求超时: $cmd")
        }
    }

    fun close() {
        shutdown("已断开")
    }
}

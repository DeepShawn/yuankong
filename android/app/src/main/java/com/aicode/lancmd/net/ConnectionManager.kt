package com.aicode.lancmd.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.aicode.lancmd.Prefs
import com.aicode.lancmd.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 连接状态机：首次自动发现 → 握手 → 心跳保活 → 掉线按退避无限重连。
 * 电脑端是常驻服务，手机断开不影响它；这里只管把手机侧重新接回去。
 */
object ConnectionManager {

    enum class State { IDLE, CONNECTING, CONNECTED, WAITING, STOPPED }

    enum class LogKind { PLAIN, CMD, OK, ERROR, INFO }

    data class Status(
        val state: State = State.IDLE,
        val name: String = "",
        val host: String = "",
        val port: Int = TCP_PORT,
        val latencyMs: Long = -1,
        val attempt: Int = 0,
        val nextRetrySec: Int = 0,
        val message: String = "",
        val paired: Boolean = false,
        val reconnects: Int = 0
    ) {
        val connected: Boolean get() = state == State.CONNECTED
    }

    interface Listener {
        fun onStatus(status: Status)
        fun onEvent(text: String, kind: LogKind)
    }

    private const val PING_INTERVAL_MS = 20_000L
    private const val PING_TIMEOUT_MS = 8_000L
    private const val PING_MAX_MISS = 2

    private val ioExec = Executors.newCachedThreadPool()
    private val io = ioExec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var appCtx: Context? = null
    @Volatile private var client: AgentClient? = null
    @Volatile private var status = Status()
    @Volatile private var running = false
    @Volatile private var forceRetry = false
    @Volatile private var wantsPairing = false

    private var loopJob: Job? = null
    private var pingJob: Job? = null
    private var disconnectSignal: CompletableDeferred<Unit>? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnects = 0

    // ---------------------------------------------------------------- 对外

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        main.post { l.onStatus(status) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun snapshot(): Status = status

    /** 启动常驻自动连接；重复调用无副作用。 */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running) {
            forceRetry = true
            return
        }
        running = true
        registerNetworkCallback()
        loopJob = scope.launch { connectLoop() }
    }

    /** 立即重试（跳过等待倒计时）。 */
    fun retryNow() {
        forceRetry = true
        if (!running) appCtx?.let { start(it) }
    }

    /** 用户主动断开：停止自动重连。 */
    fun stop() {
        running = false
        forceRetry = false
        pingJob?.cancel()
        loopJob?.cancel()
        disconnectSignal?.complete(Unit)
        val c = client
        client = null
        c?.close()
        publish(status.copy(state = State.STOPPED, nextRetrySec = 0, message = "已断开"))
    }

    suspend fun send(cmd: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val c = client ?: throw IOException("未连接")
        if (c.closed) throw IOException("连接已断开")
        return c.call(cmd, params, timeoutMs)
    }

    fun isConnected(): Boolean = client?.closed == false

    fun forget(ctx: Context) {
        Prefs.forget(ctx)
        reconnects = 0
    }

    // ---------------------------------------------------------------- 主循环

    private suspend fun connectLoop() {
        var fails = 0
        while (running) {
            val target = resolveTarget(fails)
            if (target == null) {
                publish(
                    status.copy(
                        state = State.WAITING,
                        message = "没有发现电脑，确认接收端在运行",
                        nextRetrySec = 0
                    )
                )
                waitOrWake(2000)
                continue
            }

            publish(
                status.copy(
                    state = State.CONNECTING,
                    name = target.name,
                    host = target.host,
                    port = target.port,
                    attempt = fails + 1,
                    nextRetrySec = 0,
                    message = if (fails == 0) "正在连接…" else "第 ${fails + 1} 次重连…"
                )
            )

            val c = AgentClient(target.host, target.port)
            try {
                c.connect()
                val token = Prefs.token(ctx(), target.host, target.port)
                val hs = c.handshake(Prefs.clientId(ctx()), deviceLabel(), token)
                if (!hs.optBoolean("ok")) {
                    val msg = hs.optString("message").ifEmpty { hs.optString("error", "连接被拒绝") }
                    throw IOException(msg)
                }

                Prefs.saveToken(ctx(), target.host, target.port, hs.optString("token"))
                Prefs.saveTarget(
                    ctx(),
                    Target(
                        host = target.host,
                        port = target.port,
                        name = hs.optString("agent").ifEmpty { target.name },
                        agentId = hs.optString("agent_id").ifEmpty { target.agentId }
                    )
                )

                client = c
                val paired = token.isNotEmpty()
                fails = 0
                publish(
                    Status(
                        state = State.CONNECTED,
                        name = hs.optString("agent").ifEmpty { target.name },
                        host = target.host,
                        port = target.port,
                        paired = paired,
                        reconnects = reconnects
                    )
                )
                if (reconnects > 0) {
                    event("已恢复连接（第 $reconnects 次重连成功）", LogKind.OK)
                } else {
                    event("已连接 ${target.host}:${target.port}", LogKind.OK)
                }

                startPing(c)
                awaitDisconnect(c)
                stopPing()

                val wasRunning = running
                client = null
                if (!wasRunning) break
                reconnects++
                publish(
                    status.copy(
                        state = State.WAITING,
                        latencyMs = -1,
                        message = "连接已断开，正在重连…",
                        nextRetrySec = 0,
                        reconnects = reconnects
                    )
                )
                event("连接断开，开始自动重连", LogKind.ERROR)
                waitOrWake(600)
            } catch (e: CancellationException) {
                c.close()
                client = null
                throw e
            } catch (e: Exception) {
                c.close()
                client = null
                fails++
                val waitMs = backoffMs(fails)
                publish(
                    status.copy(
                        state = State.WAITING,
                        latencyMs = -1,
                        attempt = fails,
                        message = e.message ?: "连接失败",
                        reconnects = reconnects
                    )
                )
                countdown(waitMs, fails)
            }
        }
    }

    private fun backoffMs(fails: Int): Long = when {
        fails <= 1 -> 800L
        fails == 2 -> 1500L
        fails == 3 -> 2500L
        fails == 4 -> 4000L
        fails == 5 -> 6000L
        fails <= 8 -> 8000L
        else -> 12_000L
    }

    private data class Resolved(val host: String, val port: Int, val name: String, val agentId: String)

    private suspend fun resolveTarget(fails: Int): Resolved? {
        val ctx = ctx()
        val last = Prefs.target(ctx)
        // 连续失败或从未连过时，重新扫描一次，处理电脑 IP 变化
        val shouldScan = last == null || fails == 0 || fails % 3 == 0
        if (shouldScan) {
            val found = withContext(Dispatchers.IO) { Discovery.discover(1800) }
            if (found.isNotEmpty()) {
                val known = last?.agentId ?: ""
                val pick = found.firstOrNull { it.id.isNotEmpty() && it.id == known } ?: found.first()
                val target = Target(pick.ip, pick.port, pick.name, pick.id)
                Prefs.saveTarget(ctx, target)
                return Resolved(target.host, target.port, target.name, target.agentId)
            }
        }
        return last?.let { Resolved(it.host, it.port, it.name, it.agentId) }
    }

    private suspend fun awaitDisconnect(c: AgentClient) {
        val signal = CompletableDeferred<Unit>()
        disconnectSignal = signal
        c.onClosed = { reason ->
            if (!signal.isCompleted) {
                signal.complete(Unit)
                if (running) event("连接已关闭：$reason", LogKind.ERROR)
            }
        }
        if (c.closed) signal.complete(Unit)
        signal.await()
    }

    private fun startPing(c: AgentClient) {
        pingJob?.cancel()
        pingJob = scope.launch {
            var miss = 0
            while (isActive && running && !c.closed) {
                delay(PING_INTERVAL_MS)
                if (c.closed || !running) break
                try {
                    val t0 = System.currentTimeMillis()
                    val r = c.call("ping", JSONObject(), PING_TIMEOUT_MS)
                    if (!r.optBoolean("ok")) throw IOException("ping 失败")
                    miss = 0
                    val latency = System.currentTimeMillis() - t0
                    publish(status.copy(latencyMs = latency))
                } catch (e: Exception) {
                    miss++
                    if (miss >= PING_MAX_MISS) {
                        event("心跳超时，主动断开以触发重连", LogKind.ERROR)
                        c.close()
                        break
                    }
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    // ---------------------------------------------------------------- 等待

    private suspend fun waitOrWake(ms: Long) {
        var left = ms
        while (left > 0 && running) {
            if (forceRetry) {
                forceRetry = false
                return
            }
            val step = minOf(200L, left)
            delay(step)
            left -= step
        }
        forceRetry = false
    }

    private suspend fun countdown(ms: Long, fails: Int) {
        var left = ms
        var lastPublished = -1
        while (left > 0 && running) {
            if (forceRetry) {
                forceRetry = false
                publish(status.copy(nextRetrySec = 0))
                return
            }
            val sec = ((left + 999) / 1000).toInt()
            if (sec != lastPublished) {
                lastPublished = sec
                publish(status.copy(nextRetrySec = sec, attempt = fails))
            }
            val step = minOf(250L, left)
            delay(step)
            left -= step
        }
        publish(status.copy(nextRetrySec = 0))
    }

    // ---------------------------------------------------------------- 网络变化

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val ctx = ctx()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (running) {
                    event("网络已恢复，立即重连", LogKind.INFO)
                    forceRetry = true
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
                netCallback = cb
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun ctx(): Context = appCtx ?: throw IllegalStateException("ConnectionManager 未初始化")

    private fun publish(next: Status) {
        status = next
        main.post {
            for (l in listeners) l.onStatus(next)
        }
    }

    private fun event(text: String, kind: LogKind) {
        main.post {
            for (l in listeners) l.onEvent(text, kind)
        }
    }

    private fun deviceLabel(): String {
        val man = Build.MANUFACTURER ?: "Android"
        val model = Build.MODEL ?: "device"
        val name = if (model.lowercase().startsWith(man.lowercase())) model else "$man $model"
        return name.take(48)
    }
}

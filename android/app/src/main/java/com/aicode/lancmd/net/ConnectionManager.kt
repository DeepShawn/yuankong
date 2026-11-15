package com.aicode.lancmd.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.aicode.lancmd.Prefs
import com.aicode.lancmd.ComputerBook
import com.aicode.lancmd.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        val reconnects: Int = 0,
        /** 检测到 VPN 在跑时为 true，UI 据此给出排查提示。 */
        val vpnHint: Boolean = false,
        /** 连接超时（无响应），大概率是电脑防火墙拦了端口，UI 给出修复步骤。 */
        val needFirewallCheck: Boolean = false,
        val agentId: String = ""
    ) {
        val connected: Boolean get() = state == State.CONNECTED
    }

    interface Listener {
        fun onStatus(status: Status)
        fun onEvent(text: String, kind: LogKind)
    }

    private const val PING_INTERVAL_MS = 20_000L
    private const val PING_TIMEOUT_MS = 10_000L
    private const val PING_MAX_MISS = 3

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
    private val controlLock=Any()
    private var switchJob:Job?=null
    private var selectionVersion=0L
    @Volatile private var switching=false

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
    fun start(ctx: Context) = synchronized(controlLock) {
        appCtx = ctx.applicationContext
        if(switching)return@synchronized
        if (running) {
            forceRetry = true
            return@synchronized
        }
        running = true
        registerNetworkCallback()
        val previous=loopJob
        loopJob = scope.launch { previous?.join();currentCoroutineContext().ensureActive();connectLoop() }
    }

    /** 立即重试（跳过等待倒计时）。 */
    fun retryNow() {
        forceRetry = true
        if (!running) appCtx?.let { start(it) }
    }

    /** 用户主动断开：停止自动重连。 */
    fun stop() = synchronized(controlLock) {
        selectionVersion++
        switchJob?.cancel();switchJob=null;switching=false
        stopLoopLocked()
        publish(status.copy(state = State.STOPPED, nextRetrySec = 0, latencyMs=-1, message = "已断开"))
    }

    private fun stopLoopLocked() {
        running = false
        forceRetry = false
        pingJob?.cancel()
        loopJob?.cancel()
        disconnectSignal?.complete(Unit)
        val c = client
        client = null
        c?.close()
    }

    /** Explicit foreground selection. Close/join the previous loop before committing a new target. */
    fun selectTarget(context:Context,target:Target):Boolean = synchronized(controlLock) {
        require(target.host.isNotBlank() && target.host.length<=253 && target.host.none{it.isISOControl() || it.isWhitespace()} && target.port in 1..65535){"电脑地址或端口无效"}
        val ctx=context.applicationContext
        appCtx=ctx
        val saved=Prefs.target(ctx)
        val active=client
        if(active?.closed==false && active.host==target.host && active.port==target.port &&
            (target.agentId.isBlank() || target.agentId==saved?.agentId)) {
            Prefs.selectTarget(ctx,saved?.takeIf{it.host==target.host && it.port==target.port} ?: target)
            return@synchronized false
        }
        val version=++selectionVersion
        switchJob?.cancel()
        switching=true
        stopLoopLocked()
        publish(Status(state=State.CONNECTING,name=target.name,host=target.host,port=target.port,
            agentId=target.agentId,message="正在切换电脑，等待旧连接释放…"))
        val previous=loopJob
        switchJob=scope.launch {
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                synchronized(controlLock) commit@{
                    if(version!=selectionVersion || !isActive)return@commit
                    Prefs.selectTarget(ctx,target)
                    reconnects=0
                    switching=false
                    start(ctx)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){synchronized(controlLock){
                if(version==selectionVersion)publish(Status(state=State.STOPPED,name=target.name,host=target.host,
                    port=target.port,agentId=target.agentId,message="切换失败：${e.message ?: "无法保存目标"}"))
            }} finally {synchronized(controlLock){if(version==selectionVersion)switching=false}}
        }
        true
    }

    suspend fun send(cmd: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val c = client ?: throw IOException("未连接")
        if (c.closed) throw IOException("连接已断开")
        return c.call(cmd, params, timeoutMs)
    }

    fun isConnected(): Boolean = client?.closed == false

    fun sessionClient(): AgentClient = client?.takeUnless { it.closed } ?: throw IOException("未连接")

    /** A feature owns and closes this client; keep long commands off the input connection. */
    suspend fun connectFeatureClient(feature: AgentClient, origin: AgentClient) = withContext(Dispatchers.IO) {
        try {
            if (client !== origin || origin.closed || feature.host != origin.host || feature.port != origin.port) {
                throw IOException("连接已切换，请重新开始远程桌面")
            }
            feature.connect(via = physicalNetwork())
            val expected = Prefs.target(ctx())?.agentId.orEmpty()
            val hello = feature.handshake(Prefs.clientId(ctx()), deviceLabel(), Prefs.token(ctx(), feature.host, feature.port))
            if (!hello.optBoolean("ok")) throw IOException(hello.optString("error", "无法连接电脑"))
            if (client !== origin || origin.closed || (expected.isNotEmpty() && hello.optString("agent_id") != expected)) {
                throw IOException("连接目标已改变，请重新开始远程桌面")
            }
        } catch (e: Exception) {
            feature.close()
            throw e
        }
    }

    fun forget(ctx: Context) {
        Prefs.forget(ctx)
        reconnects = 0
    }

    // ---------------------------------------------------------------- 主循环

    private suspend fun connectLoop() {
        val worker=currentCoroutineContext()[Job]!!
        fun update(next:Status)=publishConnection(worker,next)
        var fails = 0
        while (running) {
            try {
            worker.ensureActive()
            val target = resolveTarget(fails)
            worker.ensureActive()
            if (target == null) {
                update(
                    status.copy(
                        state = State.WAITING,
                        message = if (vpnActive())
                            "没有发现电脑：VPN 可能拦截了广播" else "没有发现电脑，确认接收端在运行",
                        nextRetrySec = 0,
                        vpnHint = vpnActive()
                    )
                )
                waitOrWake(2000)
                continue
            }

            update(
                status.copy(
                    state = State.CONNECTING,
                    name = target.name,
                    host = target.host,
                    port = target.port,
                    agentId = target.agentId,
                    attempt = fails + 1,
                    nextRetrySec = 0,
                    message = if (fails == 0) "正在连接…" else "第 ${fails + 1} 次重连…"
                )
            )

            // 依次尝试该电脑的各网卡地址，任一成功即用
            val via = physicalNetwork()
            val attempts = target.ips.ifEmpty { listOf(target.host) }
            var c: AgentClient? = null
            var lastError: Exception? = null
            var usedIp = target.host
            var hs: JSONObject? = null

            for (candidate in attempts) {
                worker.ensureActive()
                val candidateClient = AgentClient(candidate, target.port)
                try {
                    candidateClient.connect(
                        timeoutMs = if (target.isGateway) 2500 else 6000,
                        via = via
                    )
                    worker.ensureActive()
                    val stored = Prefs.token(ctx(), candidate, target.port)
                        .ifEmpty { Prefs.token(ctx(), target.host, target.port) }
                    val resp = candidateClient.handshake(Prefs.clientId(ctx()), deviceLabel(), stored)
                    if (!resp.optBoolean("ok")) {
                        val msg = resp.optString("message")
                            .ifEmpty { resp.optString("error", "连接被拒绝") }
                        throw IOException(msg)
                    }
                    worker.ensureActive()
                    if(Prefs.explicitlySelected(ctx()) && target.agentId.isNotBlank() && resp.optString("agent_id")!=target.agentId){
                        throw IOException("目标电脑标识不一致，未连接其他电脑；请刷新列表并重新选择")
                    }
                    c = candidateClient
                    usedIp = candidate
                    hs = resp
                    break
                } catch(e:CancellationException){candidateClient.close();throw e}
                catch (e: Exception) {
                    candidateClient.close()
                    lastError = e
                }
            }

            if (c == null || hs == null) {
                worker.ensureActive()
                // 关键：这里以前是 throw，而它位于所有 try 之外。
                // 异常会一路逃出 connectLoop → launch → 线程默认处理器，
                // 在真机上就是「显示正在连接时闪退」。现在改为走正常的退避重连。
                fails++
                val waitMs = backoffMs(fails)
                val err = lastError
                val timedOut = err != null && isTimeout(err)
                update(
                    status.copy(
                        state = State.WAITING,
                        latencyMs = -1,
                        attempt = fails,
                        message = if (timedOut) timeoutMessage(target) else buildFailureMessage(
                            err ?: IOException("无法连接 ${target.host}:${target.port}"),
                            target
                        ),
                        reconnects = reconnects,
                        vpnHint = vpnActive(),
                        needFirewallCheck = timedOut
                    )
                )
                countdown(waitMs, fails)
                continue
            }

            var visitId:String?=null
            fun endVisit(){
                val id=visitId?:return
                visitId=null
                runCatching{ComputerBook.ended(ctx(),id)}
            }
            try {
                synchronized(controlLock) {
                worker.ensureActive()
                val token = Prefs.token(ctx(), usedIp, target.port)
                Prefs.saveToken(ctx(), usedIp, target.port, hs.optString("token"))
                Prefs.saveTarget(
                    ctx(),
                    Target(
                        host = usedIp,
                        port = target.port,
                        name = hs.optString("agent").ifEmpty { target.name },
                        agentId = hs.optString("agent_id").ifEmpty { target.agentId }
                    )
                )

                client = c
                val paired = token.isNotEmpty()
                fails = 0
                update(
                    Status(
                        state = State.CONNECTED,
                        name = hs.optString("agent").ifEmpty { target.name },
                        host = usedIp,
                        port = target.port,
                        agentId = hs.optString("agent_id").ifEmpty { target.agentId },
                        paired = paired,
                        reconnects = reconnects
                    )
                )
                }
                visitId=runCatching{ComputerBook.connected(ctx(),Target(usedIp,target.port,
                    hs.optString("agent").ifEmpty{target.name},hs.optString("agent_id").ifEmpty{target.agentId}))}.getOrNull()
                if (reconnects > 0) {
                    event("已恢复连接（第 $reconnects 次重连成功）", LogKind.OK)
                } else {
                    event("已连接 $usedIp:${target.port}", LogKind.OK)
                }

                startPing(c)
                awaitDisconnect(c)
                endVisit()
                stopPing()

                val wasRunning = running
                if(client===c)client = null
                if (!wasRunning) break
                reconnects++
                update(
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
                endVisit()
                c?.close()
                if(client===c)client = null
                throw e
            } catch (e: Exception) {
                endVisit()
                c?.close()
                if(client===c)client = null
                fails++
                val waitMs = backoffMs(fails)
                val timedOut = isTimeout(e)
                update(
                    status.copy(
                        state = State.WAITING,
                        latencyMs = -1,
                        attempt = fails,
                        message = if (timedOut) timeoutMessage(target) else buildFailureMessage(e, target),
                        reconnects = reconnects,
                        vpnHint = vpnActive(),
                        needFirewallCheck = timedOut
                    )
                )
                countdown(waitMs, fails)
            } finally {
                endVisit()
            }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 最后一道防线：任何未预期异常都不能让协程把进程带崩（真机上即闪退）
                android.util.Log.e("LanCmd", "连接循环异常: " + t.message, t)
                event("连接异常：" + (t.message ?: t.javaClass.simpleName), LogKind.ERROR)
                synchronized(controlLock){if(worker.isActive)client = null}
                fails++
                countdown(backoffMs(fails), fails)
            }
        }
    }

    /** 「超时」说明包被丢了；「拒绝」说明端口没人监听。两者原因完全不同。 */
    private fun isTimeout(e: Throwable?): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur is java.net.SocketTimeoutException) return true
            if (cur.message?.startsWith("TIMEOUT:") == true) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun timeoutMessage(target: Resolved): String {
        return "连接 ${target.host}:${target.port} 超时（无响应）\n" +
            "数据包发出去没有任何回应，通常是被电脑防火墙拦了"
    }

    private fun buildFailureMessage(e: Exception, target: Resolved): String {
        val base = e.message ?: "连接失败"
        if (!vpnActive()) return base
        return "$base（检测到 VPN，已尝试绕过）"
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

    private data class Resolved(
        val host: String,
        val port: Int,
        val name: String,
        val agentId: String,
        val ips: List<String> = emptyList(),
        /** 由"电脑开热点"推断出的网关地址，探测时用更短的超时。 */
        val isGateway: Boolean = false
    )

    private suspend fun resolveTarget(fails: Int): Resolved? {
        val ctx = ctx()
        val last = Prefs.target(ctx)
        val selected=Prefs.explicitlySelected(ctx)
        val cached=last?.let{Resolved(it.host,it.port,it.name,it.agentId)}
        if(selected && (fails==0 || last?.agentId.isNullOrBlank()))return cached
        // 连续失败或从未连过时，重新扫描一次，处理电脑 IP 变化
        val shouldScan = if(selected)fails==1 || fails%3==0 else last == null || fails == 0 || fails % 3 == 0
        if (shouldScan) {
            val result = withContext(Dispatchers.IO) { Discovery.discover(ctx, 1800) }
            val found = result.agents
            if (found.isNotEmpty()) {
                val pick = TargetRouting.pick(last,found,selected)
                if(pick!=null){
                currentCoroutineContext().ensureActive()
                val target = Target(pick.ip, pick.port, pick.name, pick.id)
                Prefs.saveTarget(ctx, target)
                return Resolved(target.host, target.port, target.name, target.agentId, pick.allIps())
                }
            }
        }
        if(selected)return cached
        // 电脑开热点时手机连的是它的热点，广播常被系统拦掉，
        // 但网关就是那台电脑，直接连网关即可。
        // 连着“无互联网 Wi-Fi”基本就是这种场景，第一次就试网关，不用等失败。
        if (cached == null || fails >= 1 || onLocalOnlyWifi()) {
            gatewayCandidate()?.let { return it }
        }
        return cached
    }

    /** 当前网络的默认网关。电脑开热点时，这个地址就是电脑自己。 */
    private fun gatewayCandidate(): Resolved? {
        val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = physicalNetwork() ?: return null
        return try {
            val lp = cm.getLinkProperties(network) ?: return null
            for (route in lp.routes) {
                if (!route.isDefaultRoute) continue
                val gw = route.gateway
                if (gw is java.net.Inet4Address && !gw.isLoopbackAddress) {
                    val ip = gw.hostAddress ?: continue
                    if (ip.startsWith("0.")) continue
                    return Resolved(ip, TCP_PORT, "热点网关 $ip", "", emptyList(), true)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
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
                // 有别的请求在路上时服务端可能正忙着执行长命令，
                // 它和 ping 共用同一条连接线程，此时 ping 必然慢。
                // 这种情况不计入超时，否则跑个 pip install 就会被当成掉线断开。
                if (c.pendingRequests() > 0) {
                    miss = 0
                    continue
                }
                try {
                    val t0 = System.currentTimeMillis()
                    val r = c.call("ping", JSONObject(), PING_TIMEOUT_MS)
                    if (!r.optBoolean("ok")) throw IOException("心跳无响应")
                    miss = 0
                    val latency = System.currentTimeMillis() - t0
                    synchronized(controlLock){if(client===c && running && !switching)publish(status.copy(latencyMs = latency))}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 同样：超时期间若冒出新请求，说明只是忙，不算掉线
                    if (c.pendingRequests() > 0) {
                        miss = 0
                        continue
                    }
                    miss++
                    if (miss >= PING_MAX_MISS) {
                        event("心跳连续超时，断开以触发重连", LogKind.ERROR)
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
        val worker=currentCoroutineContext()[Job]!!
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
                publishConnection(worker,status.copy(nextRetrySec = sec, attempt = fails))
            }
            val step = minOf(250L, left)
            delay(step)
            left -= step
        }
        publishConnection(worker,status.copy(nextRetrySec = 0))
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

    private fun ctxOrNull(): Context? = appCtx

    private fun ctx(): Context = ctxOrNull()
        ?: throw IllegalStateException("ConnectionManager 未初始化")

    /** 有 VPN 在跑时，默认路由会被它接管，必须显式绑定物理网卡。 */
    fun vpnActive(): Boolean {
        val cm = ctxOrNull()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 当前是否连着一个「没有互联网」的 Wi-Fi。
     * 电脑开热点时手机就是这样：连着 Wi-Fi 但上不了网。
     * 这种情况必须优先走 Wi-Fi，绝不能因为“无网络”退回移动数据。
     */
    fun onLocalOnlyWifi(): Boolean {
        val cm = ctxOrNull()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!hasInternet) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 选一个能到局域网物理网络（Wi-Fi / 网线优先，移动网络兜底）。 */
    private fun physicalNetwork(): Network? {
        val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        var fallback: Network? = null
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return n
                }
                if (fallback == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    fallback = n
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun publishConnection(worker:Job,next:Status)=synchronized(controlLock){
        if(worker.isActive && running && !switching && loopJob===worker)publish(next)
    }

    private fun publish(next: Status) {
        status = next
        main.post {
            // 单个 listener 抛异常不能连累其他 listener，更不能把主线程带崩
            for (l in listeners) {
                try {
                    l.onStatus(next)
                } catch (t: Throwable) {
                    android.util.Log.e("LanCmd", "onStatus 失败: " + t.message, t)
                }
            }
        }
    }

    private fun event(text: String, kind: LogKind) {
        main.post {
            for (l in listeners) {
                try {
                    l.onEvent(text, kind)
                } catch (t: Throwable) {
                    android.util.Log.e("LanCmd", "onEvent 失败: " + t.message, t)
                }
            }
        }
    }

    private fun deviceLabel(): String {
        // Do not send the phone's manufacturer, model or user-assigned device name.
        // The persistent client_id still identifies pairing and audit records.
        return "LanCmd 手机"
    }
}

package com.aicode.lancmd

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperSwitch
import kotlin.coroutines.coroutineContext

/** 画面、触控/键鼠与终端共用一个工作区。界面使用 miuix。 */
class DesktopActivity : MiuixActivity(), ConnectionManager.Listener {
    private data class InputCommand(val command: String, val params: JSONObject)

    private val shells = listOf("CMD", "PowerShell")
    private val combinations = listOf("Ctrl+C", "Ctrl+V", "Ctrl+A", "Alt+Tab")
    private val pointerKeys = listOf("左键", "右键", "双击", "滚动↑", "滚动↓")

    /** 画面和虚拟键盘是自绘 View，Compose 里用 AndroidView 承载。 */
    private lateinit var screen: RemoteScreenView
    private lateinit var virtual: VirtualKeyboard

    internal var sharing by mutableStateOf(false)
    internal var mode by mutableIntStateOf(0)
    internal var fullscreen by mutableStateOf(false)
    internal var touchShared by mutableStateOf(true)
    internal var virtualKeyboardExpanded by mutableStateOf(false)
    internal var keyboardText by mutableStateOf("")
    internal var commandLine by mutableStateOf("")
    internal var shellIndex by mutableIntStateOf(0)
    internal var terminalText by mutableStateOf("命令输出显示在这里\n")
    internal var commandBusy by mutableStateOf(false)
    internal var localPauseEnabled by mutableStateOf(false)
    internal var localPauseLabel by mutableStateOf("暂停本机输入")
    internal var localStatusText by mutableStateOf("本机输入暂停包含标准 HID 触屏，需 Windows 1.9.0")

    private var touchOptionsExpanded by mutableStateOf(false)
    private var landscape by mutableStateOf(false)
    private var cursorSupported=false
    private var touchPauseSupported = false
    private var sessionJob: Job? = null
    private var terminalJob: Job? = null
    private var commands: Channel<InputCommand>? = null
    private var terminalClient: AgentClient? = null
    private var source: AgentClient? = null
    private var pointer = RemotePoint(0.5f, 0.5f)
    private var localLease: RemoteLease? = null
    private var localRequest: Job? = null
    private var localPaused = false
    private var pageVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppVisibility.observe(this) { if (sessionJob?.isActive == true) stopSession() }
        screen = RemoteScreenView(this).apply {
            id = R.id.desktopScreen
            onPointer = { params ->
                pointer = RemotePoint(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                enqueue("pointer", params)
            }
        }
        virtual = VirtualKeyboard(this) { names ->
            enqueue("input", JSONObject().put("keys", JSONArray(names)))
        }.apply { id = R.id.desktopVirtualKeyboard }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        status("屏幕、触控、键鼠与终端 · 点击开始共享连接电脑")
        content { Screen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) showFullscreen(false) else finish() }
        })
    }

    @Composable
    private fun Screen() {
        // 全屏只保留画面和退出按钮。
        if (fullscreen) Box(modifier = Modifier.fillMaxSize()) {
            HostedView(screen, Modifier.fillMaxSize())
            Action("退出全屏", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                showFullscreen(false)
            }
        } else FixedPage("远程桌面") {
                ActionRow {
                    Action(
                        "开始共享",
                        enabled = !sharing,
                        primary = true,
                        modifier = Modifier.weight(1f)
                    ) { begin() }
                    Action("停止共享", enabled = sharing, modifier = Modifier.weight(1f)) { stopSession() }
                }
            WorkingArea(screen,Modifier.fillMaxWidth().weight(1f),controlFraction=if(mode==1 && virtualKeyboardExpanded).72f else .52f,onResize={screen.cancelGesture()},horizontal=landscape) { Dock() }

        }
    }

    @Composable
    private fun Dock() {
        SectionCard {
            ActionRow {
                listOf("触控", "键盘", "终端").forEachIndexed { index, title ->
                    SelectionAction(
                        title,
                        selected = index == mode,
                        modifier = Modifier.weight(1f)
                    ) { screen.cancelGesture();mode = index;updateCursorVisibility() }
                }
                Action("全屏", modifier = Modifier.weight(1f)) { showFullscreen(true) }
            }
        }
        when (mode) {
            0 -> TouchPanel()
            1 -> KeyboardPanel()
            else -> TerminalPanel()
        }
    }

    @Composable
    private fun TouchPanel() {
        SectionCard {
            ActionRow {
                SelectionAction(if(touchShared)"触控已开启" else "触控已关闭",selected=touchShared,enabled=sharing,modifier=Modifier.weight(1f)) {
                    touchShared=!touchShared;screen.touchEnabled=touchShared && commands!=null
                    updateCursorVisibility()
                }
                SelectionAction(if(touchOptionsExpanded)"收起更多" else "更多操作",selected=touchOptionsExpanded,modifier=Modifier.weight(1f)) {touchOptionsExpanded=!touchOptionsExpanded}
            }
            ActionRow {
                pointerKeys.forEachIndexed { index, text ->
                    Action(text, enabled = sharing, modifier = Modifier.weight(1f)) { pointerKey(index) }
                }
            }
            ActionRow {
                Action("电脑音量", modifier = Modifier.weight(1f)) { AudioDialog(this@DesktopActivity).show() }
                Action(
                    localPauseLabel,
                    enabled = localPauseEnabled,
                    modifier = Modifier.weight(1f)
                ) { changeLocalInput() }
            }
            if(touchOptionsExpanded) {
            Action("运行中的桌面程序") {
                startActivity(Intent(this@DesktopActivity, AppsActivity::class.java))
            }
            ActionRow {
                Action("录屏 / 录音", modifier = Modifier.weight(1f)) {
                    startActivity(
                        Intent(this@DesktopActivity, RecordingsActivity::class.java)
                            .putExtra("kind", "screen")
                    )
                }
                Action("手势记录板", modifier = Modifier.weight(1f)) {
                    startActivity(Intent(this@DesktopActivity, GesturesActivity::class.java))
                }
            }
            Note(localStatusText)
            }
        }
    }

    @Composable
    private fun KeyboardPanel() {
        SectionCard {
            ActionRow {
                combinations.forEach { name ->
                    Action(name, enabled = sharing, modifier = Modifier.weight(1f)) { sendKeys(name) }
                }
            }
            Action(
                if (virtualKeyboardExpanded) "收起虚拟键盘" else "展开虚拟键盘",
                enabled = sharing
            ) { toggleVirtualKeyboard() }
            if (virtualKeyboardExpanded) {
                virtual.isEnabled = sharing
                HostedView(virtual, Modifier.fillMaxWidth().height(300.dp).padding(vertical = 6.dp))
            }
            Field(
                label = "输入文字到电脑",
                value = keyboardText,
                onValueChange = { keyboardText = it.take(1000) },
                singleLine = false,
                maxLines = 2
            )
            Action("发送文字", enabled = sharing) { sendText() }
        }
    }

    @Composable
    private fun TerminalPanel() {
        SectionCard {
            Note(terminalText.takeLast(4000))
            ChoiceField(
                items = shells, title = "终端类型",
                selectedIndex = shellIndex,
                onSelectedIndexChange = { shellIndex = it },
                enabled = sharing
            )
            Field(
                label = "输入命令…",
                value = commandLine,
                onValueChange = { commandLine = it.take(8192) }
            )
            Action("执行", enabled = sharing && !commandBusy) { execute() }
            Action("常用命令 · 点选填入") {
                CommandHints.show(this@DesktopActivity, shellIndex == 1) { line -> commandLine = line }
            }
            Action("命令列表 / 延迟执行") {
                startActivity(
                    Intent(this@DesktopActivity, CommandsActivity::class.java)
                        .putExtra("line", commandLine)
                        .putExtra("shell", if (shellIndex == 1) "powershell" else "cmd")
                )
            }
        }
    }

    private fun updateCursorVisibility() {
        // One input lease spans touch, keyboard, terminal and internal child
        // pages. AppVisibility ends that lease when the whole app leaves.
        if(cursorSupported && sharing)enqueue("cursor",JSONObject().put("hidden",true))
    }

    internal fun pointerKey(index: Int) {
        screen.cancelGesture()
        val params = JSONObject().put("x", pointer.x.toDouble()).put("y", pointer.y.toDouble())
            .put("action", when (index) { 2 -> "double_click"; 3, 4 -> "scroll"; else -> "click" })
            .put("button", if (index == 1) "right" else "left")
        if (index >= 3) params.put("delta", if (index == 3) 3 else -3)
        enqueue("pointer", params)
    }

    internal fun sendKeys(name: String) {
        val symbol = mapOf("退格" to "BACKSPACE", "←" to "LEFT", "↑" to "UP", "↓" to "DOWN", "→" to "RIGHT")
        val keys = (symbol[name] ?: name).uppercase().split("+")
        enqueue("input", JSONObject().put("keys", JSONArray(keys)))
    }

    internal fun sendText() {
        if (keyboardText.isNotEmpty()) enqueue("input", JSONObject().put("text", keyboardText))
    }

    internal fun toggleVirtualKeyboard() {
        screen.cancelGesture()
        virtual.resetModifiers()
        virtualKeyboardExpanded = !virtualKeyboardExpanded
    }

    internal fun showFullscreen(value: Boolean) {
        screen.cancelGesture()
        fullscreen = value
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.cancelGesture()
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun enqueue(command: String, params: JSONObject) {
        val queue = commands ?: return
        if (queue.trySend(InputCommand(command, params)).isFailure) {
            status("输入等待过多，请重新开始共享", true)
            sessionJob?.cancel()
        }
    }

    private fun checkOrigin(client: AgentClient) {
        check(!client.closed && ConnectionManager.sessionClient() === client) { "连接已切换，请重新开始共享" }
    }

    internal fun begin() {
        if (sessionJob?.isActive == true) return
        sharing = true
        sessionJob = lifecycleScope.launch {
            val owned = ArrayList<AgentClient>()
            var screenLease: RemoteLease? = null
            var inputLease: RemoteLease? = null
            val queue = Channel<InputCommand>(64)
            try {
                val origin = ConnectionManager.sessionClient()
                source = origin
                val capabilities = origin.call("capabilities").optJSONArray("features")
                if (capabilities == null || listOf("screen", "input", "pointer").any { required ->
                        (0 until capabilities.length()).none { capabilities.optString(it) == required }
                    }
                ) error("请完整更新 Windows 1.6.0 接收端，新版本支持屏幕触控")
                if ((0 until capabilities.length()).none { capabilities.optString(it) == "cursor_visibility_verified" }) {
                    error("远程控制需要确认 Windows 系统光标已隐藏，请完整更新 Windows 3.5.3 接收端后再开始；手机触控圆点仍保留")
                }
                if ((0 until capabilities.length()).none { capabilities.optString(it) == "cursor_visibility_input_queue" }) {
                    error("电脑端仍使用旧光标逻辑，请停止旧接收端并完整更新 Windows 3.5.3 后重新启动；只更新安卓无法修复电脑上的光标")
                }
                status("正在连接画面、键鼠与终端…")
                suspend fun connection(): AgentClient {
                    val client = AgentClient(origin.host, origin.port)
                    owned.add(client) // 可取消的 connect 返回前先持有所有权。
                    ConnectionManager.connectFeatureClient(client, origin)
                    return client
                }
                val frames = connection()
                val inputs = connection()
                val terminal = connection()
                val preview = RemoteLease("screen", frames).also { screenLease = it }
                val controls = RemoteLease("input", inputs).also { inputLease = it }
                preview.open(); controls.open()
                coroutineContext.ensureActive(); checkOrigin(origin)
                val cursorState = controls.call("cursor",JSONObject().put("hidden",true))
                if(!cursorState.optBoolean("hidden") || !cursorState.optBoolean("verified")) {
                    error("Windows 未确认系统光标隐藏，已停止本次控制；请完整更新接收端后重试")
                }
                terminalClient = terminal
                commands = queue
                cursorSupported=(0 until capabilities.length()).any {capabilities.optString(it)=="cursor_visibility"}
                updateCursorVisibility()
                val hasLocalInput = (0 until capabilities.length())
                    .any { capabilities.optString(it) == "local_input" }
                touchPauseSupported = (0 until capabilities.length())
                    .any { capabilities.optString(it) == "touch_pause" }
                localLease = if (hasLocalInput) controls else null
                localPauseEnabled = hasLocalInput && touchPauseSupported
                screen.touchEnabled = touchShared
                coroutineScope {
                    launch {
                        while(isActive) {
                            val cursorState=controls.call("cursor",JSONObject().put("hidden",true))
                            if(!cursorState.optBoolean("hidden") || !cursorState.optBoolean("verified")) {
                                error("Windows 系统光标隐藏失效，已停止本次控制")
                            }
                            delay(1000)
                        }
                    }
                    launch {
                        while (isActive) {
                            checkOrigin(origin)
                            if (!hasLocalInput) { controls.keepAlive(); delay(3000); continue }
                            val state = controls.call("local_input")
                            localPaused = state.optBoolean("paused")
                            localPauseLabel = if (localPaused) "恢复本机输入" else "暂停本机输入"
                            localStatusText = if (localPaused) {
                                pauseSummary(state) + "\n本机解除：A R T E X + Ctrl + Delete，或 Ctrl+Alt+Del"
                            } else if (state.optString("warning").isNotBlank()) {
                                "已恢复本机输入：${state.optString("warning")}"
                            } else if (touchPauseSupported) {
                                "本机输入可用 · 校验 HID 触屏实际状态，断线自动恢复"
                            } else "触屏暂停需完整更新 Windows 接收端"
                            delay(3000)
                        }
                    }
                    launch {
                        for (command in queue) {
                            checkOrigin(origin)
                            controls.call(command.command, command.params)
                        }
                    }
                    while (isActive) {
                        checkOrigin(origin)
                        if (!pageVisible) { preview.keepAlive(); delay(1000); continue }
                        val result = preview.call("screen")
                        val bitmap = withContext(Dispatchers.Default) {
                            val bytes = android.util.Base64.decode(
                                result.getString("data"), android.util.Base64.DEFAULT
                            )
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码电脑画面")
                        }
                        coroutineContext.ensureActive(); checkOrigin(origin)
                        screen.showFrame(bitmap)
                        status("共享中 · ${bitmap.width} × ${bitmap.height} · 触控与终端可同时使用")
                        delay(350)
                    }
                }
            } catch (e: CancellationException) { status("共享已停止"); throw e }
            catch (e: Exception) { status(e.message ?: "无法开始远程桌面", true) }
            finally {
                screen.touchEnabled = false
                commands = null; queue.cancel()
                terminalJob?.cancel(); terminalClient = null; source = null
                localRequest?.cancel(); localLease = null; localPaused = false
                localPauseEnabled = false
                cursorSupported=false
                localPauseLabel = "暂停本机输入"
                localStatusText = "共享已结束，本机输入将恢复"
                if (!isDestroyed) screen.clearFrame()
                // 先结束键鼠租约，被打断的拖动才不会让按键一直按着。
                inputLease?.close(); screenLease?.close()
                owned.forEach { it.close() }
                sharing = false
            }
        }
    }

    internal fun changeLocalInput() {
        val lease = localLease ?: return
        if (localRequest?.isActive == true) return
        fun apply(action: String) {
            localPauseEnabled = false
            localRequest = task {
                try {
                    // 明确传 0 秒，连到 1.7.0/1.7.1 接收端时仍是手动模式。
                    val state = lease.call(
                        "local_input", JSONObject().put("action", action).put("seconds", 0), 75_000
                    )
                    localPaused = state.optBoolean("paused")
                    localPauseLabel = if (localPaused) "恢复本机输入" else "暂停本机输入"
                    localStatusText = if (localPaused) pauseSummary(state) + " · 本机组合键或手机可恢复"
                    else "本机输入已恢复"
                    status(localStatusText)
                } finally { localPauseEnabled = localLease === lease && touchPauseSupported }
            }
        }
        if (localPaused) { apply("release"); return }
        WorkspaceDialog.Builder(this).setTitle("暂停电脑本机输入")
            .setMessage(
                "暂停键盘、鼠标及已识别的标准 HID 触屏，手机仍可控制。触屏暂停需要管理员权限，" +
                    "结果显示实际识别与禁用数量；未识别的厂商触控设备不能保证锁定。\n\n" +
                    "本机解除：A、R、T、E、X + Ctrl + Delete；或 Ctrl+Alt+Del。\n\n" +
                    "退出共享或心跳中断 12 秒恢复。独立恢复进程会在接收端退出或失去心跳后重新启用触屏；" +
                    "驱动恢复可能稍有延迟。"
            )
            .setPositiveButton("开始暂停（手动解除）") { _, _ -> apply("pause") }
            .setNegativeButton("取消", null).show()
    }

    private fun pauseSummary(state: JSONObject): String {
        val disabled = state.optInt("touch_devices")
        val detected = state.optInt("touch_detected", disabled)
        val original = state.optInt("touch_already_disabled")
        return if (detected == 0) "键鼠已暂停 · 未识别到标准 HID 触屏"
        else "键鼠已暂停 · 触屏确认禁用 ${disabled + original}/$detected（本次 $disabled，原已禁用 $original）"
    }

    internal fun execute() {
        val client = terminalClient ?: return
        val origin = source ?: return
        if (terminalJob?.isActive == true) return
        val line = commandLine.trim()
        if (line.isEmpty()) return
        val selectedShell = if (shellIndex == 1) "powershell" else "cmd"
        commandLine = ""
        commandBusy = true
        appendTerminal("\n$ $line\n")
        terminalJob = lifecycleScope.launch {
            try {
                checkOrigin(origin)
                val result = client.call(
                    "exec",
                    JSONObject().put("shell", selectedShell).put("line", line).put("timeout", 300),
                    310_000
                )
                if (!result.optBoolean("ok")) error(result.optString("error", "命令失败"))
                appendTerminal(
                    result.optString("out") + result.optString("err") + "\n退出码 ${result.optInt("code")}\n"
                )
            } catch (e: CancellationException) { appendTerminal("\n已停止等待命令结果\n"); throw e }
            catch (e: Exception) { appendTerminal("\n${e.message ?: "命令失败"}\n") }
            finally { commandBusy = false }
        }
    }

    private fun appendTerminal(text: String) {
        if (isDestroyed) return
        terminalText = (terminalText + text).takeLast(50_000)
    }

    internal fun stopSession() {
        status("正在停止共享…")
        screen.cancelGesture()
        sessionJob?.cancel(); terminalJob?.cancel()
    }

    override fun onStart() {
        super.onStart()
        pageVisible = true
        updateCursorVisibility()
        ConnectionManager.addListener(this)
    }

    override fun onStatus(status: ConnectionManager.Status) {
        val origin = source ?: return
        if (runCatching { ConnectionManager.sessionClient() }.getOrNull() !== origin) {
            sessionJob?.cancel(); terminalJob?.cancel()
        }
    }

    override fun onEvent(text: String, kind: ConnectionManager.LogKind) = Unit

    override fun onStop() {
        ConnectionManager.removeListener(this)
        pageVisible = false
        updateCursorVisibility()
        screen.cancelGesture()
        if (isFinishing) { sessionJob?.cancel(); terminalJob?.cancel(); localRequest?.cancel() }
        super.onStop()
    }
}

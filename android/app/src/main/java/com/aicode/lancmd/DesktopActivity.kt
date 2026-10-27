package com.aicode.lancmd

import android.graphics.BitmapFactory
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.FrameLayout
import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.RemoteLease
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
import kotlin.coroutines.coroutineContext

/** Screen, touch/mouse, keyboard and command console share one visible workspace. */
class DesktopActivity : FeatureActivity(), ConnectionManager.Listener {
    private data class InputCommand(val command: String, val params: JSONObject)
    private lateinit var screen: RemoteScreenView
    private lateinit var screenHost: FrameLayout
    private lateinit var exitFullscreen: MaterialButton
    private lateinit var toolbarView: View
    private var touchPauseSupported = false
    private lateinit var start: MaterialButton
    private lateinit var stop: MaterialButton
    private lateinit var fullscreenButton: MaterialButton
    private lateinit var touchSwitch: SwitchMaterial
    private lateinit var sessionRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var workspace: LinearLayout
    private lateinit var controlDock: LinearLayout
    private lateinit var controlScroll: ScrollView
    private lateinit var panelHost: LinearLayout
    private lateinit var terminalLog: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var commandText: EditText
    private lateinit var commandSend: MaterialButton
    private lateinit var shell: Spinner
    private val panels = ArrayList<View>()
    private val modeButtons = ArrayList<MaterialButton>()
    private val activeControls = ArrayList<View>()
    private var mode = 0
    private var fullscreen = false
    private var landscape = false
    private var virtualKeyboardExpanded = false
    private var sessionJob: Job? = null
    private var terminalJob: Job? = null
    private var commands: Channel<InputCommand>? = null
    private var terminalClient: AgentClient? = null
    private var origin: AgentClient? = null
    private var pointer = RemotePoint(0.5f, 0.5f)
    private lateinit var localPause: MaterialButton
    private lateinit var localStatus: TextView
    private var localLease: RemoteLease? = null
    private var localRequest: Job? = null
    private var localPaused = false
    private var pageVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("远程桌面", "屏幕、触控、键鼠与终端 · 点击开始共享连接电脑")
        AppVisibility.observe(this) { if(sessionJob?.isActive==true)stopSession() }
        toolbarView = (body.parent as android.view.ViewGroup).getChildAt(0)
        statusText.id = R.id.desktopStatus
        sessionRow = LinearLayout(this)
        start = button("开始共享") { begin() }.apply { id = R.id.desktopStart }
        primary(start)
        stop = button("停止共享") { stopSession() }.apply { id = R.id.desktopStop; isEnabled = false }
        sessionRow.addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        sessionRow.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(sessionRow)
        screen = RemoteScreenView(this).apply {
            id = R.id.desktopScreen
            onPointer = { params ->
                pointer = RemotePoint(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                enqueue("pointer", params)
            }
        }
        workspace = LinearLayout(this)
        workspace.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (virtualKeyboardExpanded && bottom - top != oldBottom - oldTop) configureWorkspace()
        }
        body.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        screenHost = FrameLayout(this)
        screenHost.addView(screen, FrameLayout.LayoutParams(-1, -1))
        exitFullscreen = compactButton("退出全屏") { setFullscreen(false) }.apply {
            id = R.id.desktopExitFullscreen; visibility = View.GONE
        }
        screenHost.addView(exitFullscreen, FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END)
            .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        workspace.addView(screenHost)
        controlDock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workspace.addView(controlDock)
        modeRow = LinearLayout(this)
        listOf("触控", "键盘", "终端").forEachIndexed { index, title ->
            val item = compactButton(title) { mode = index; showMode() }
            item.id = listOf(R.id.desktopTouchTab, R.id.desktopKeyboardTab, R.id.desktopTerminalTab)[index]
            modeButtons.add(item)
            modeRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        fullscreenButton = compactButton("全屏") { setFullscreen(!fullscreen) }.apply { id = R.id.desktopFullscreen }
        modeRow.addView(fullscreenButton, LinearLayout.LayoutParams(0, -2, 1f))
        controlDock.addView(modeRow)
        panelHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlScroll = ScrollView(this).apply { addView(panelHost) }
        controlDock.addView(controlScroll)
        touchPanel()
        keyboardPanel()
        terminalPanel()
        activeControls.forEach { it.isEnabled = false }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        configureWorkspace()
        showMode()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) setFullscreen(false) else finish() }
        })
    }

    private fun compactButton(text: String, action: () -> Unit) = button(text, action).apply {
        textSize = 12f; minWidth = 0; setPadding(dp(4), 0, dp(4), 0); setSingleLine()
    }

    private fun field(hint: String, multiline: Boolean = false, limit: Int = 1000) = EditText(this).apply {
        this.hint = hint
        setTextColor(color(R.color.text)); setHintTextColor(color(R.color.dim))
        setBackgroundResource(R.drawable.input_bg)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        filters = arrayOf(InputFilter.LengthFilter(limit))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            (if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        maxLines = if (multiline) 2 else 1
        minHeight = dp(48)
        activeControls.add(this)
    }

    private fun touchPanel() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        touchSwitch = SwitchMaterial(this).apply {
            text = "共享触控"; isChecked = true; setTextColor(color(R.color.text))
            setOnCheckedChangeListener { _, checked -> screen.touchEnabled = checked && commands != null }
        }
        activeControls.add(touchSwitch)
        panel.addView(touchSwitch)
        panel.addView(label("轻点单击 · 双击 · 长按右键 · 单指拖动 · 双指滚动", 11f, R.color.dim))
        val row = LinearLayout(this)
        listOf("左键", "右键", "双击", "滚动↑", "滚动↓").forEachIndexed { index, text ->
            val item = compactButton(text) {
                screen.cancelGesture()
                val params = JSONObject().put("x", pointer.x.toDouble()).put("y", pointer.y.toDouble())
                    .put("action", when (index) { 2 -> "double_click"; 3, 4 -> "scroll"; else -> "click" })
                    .put("button", if (index == 1) "right" else "left")
                if (index >= 3) params.put("delta", if (index == 3) 3 else -3)
                enqueue("pointer", params)
            }
            activeControls.add(item)
            row.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(row)
        val system = LinearLayout(this)
        system.addView(compactButton("电脑音量") { AudioDialog(this).show() }, LinearLayout.LayoutParams(0, -2, 1f))
        localPause = compactButton("暂停本机输入") { changeLocalInput() }.apply {
            id = R.id.desktopLocalInput; isEnabled = false
        }
        system.addView(localPause, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(system)
        panel.addView(compactButton("运行中的桌面程序") { startActivity(Intent(this, AppsActivity::class.java)) })
        val recordingTools = LinearLayout(this)
        recordingTools.addView(compactButton("录屏 / 录音") { startActivity(Intent(this, RecordingsActivity::class.java).putExtra("kind", "screen")) }, LinearLayout.LayoutParams(0, -2, 1f))
        recordingTools.addView(compactButton("手势记录板") { startActivity(Intent(this, GesturesActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(recordingTools)
        localStatus = label("本机输入暂停包含标准 HID 触屏，需 Windows 1.9.0", 11f, R.color.dim)
            .apply { id = R.id.desktopLocalStatus }
        panel.addView(localStatus)
        panels.add(panel); panelHost.addView(panel)
    }

    private fun keyboardPanel() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(listOf("Ctrl+C", "Ctrl+V", "Ctrl+A", "Alt+Tab")).forEach { labels ->
            val row = LinearLayout(this)
            labels.forEach { name ->
                val item = compactButton(name) {
                    val symbol = mapOf("退格" to "BACKSPACE", "←" to "LEFT", "↑" to "UP", "↓" to "DOWN", "→" to "RIGHT")
                    val keys = (symbol[name] ?: name).uppercase().split("+")
                    enqueue("input", JSONObject().put("keys", JSONArray(keys)))
                }
                activeControls.add(item)
                row.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
            panel.addView(row)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val text = field("输入文字到电脑", multiline = true).apply { id = R.id.desktopKeyboardText }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val send = compactButton("发送文字") {
            if (text.text.isNotEmpty()) enqueue("input", JSONObject().put("text", text.text.toString()))
        }.apply { id = R.id.desktopKeyboardSend }
        activeControls.add(send); row.addView(send)
        val virtual = VirtualKeyboard(this) { names -> enqueue("input", JSONObject().put("keys", JSONArray(names))) }
            .apply { id = R.id.desktopVirtualKeyboard; visibility = View.GONE }
        activeControls.add(virtual)
        val expand = compactButton("展开虚拟键盘") {}.apply {
            id = R.id.desktopKeyboardExpand
            setOnClickListener {
                virtual.resetModifiers()
                val expanded = virtual.visibility != View.VISIBLE
                virtualKeyboardExpanded = expanded
                virtual.visibility = if (expanded) View.VISIBLE else View.GONE
                this.text = if (expanded) "收起虚拟键盘" else "展开虚拟键盘"
                ViewCompat.setStateDescription(this, if (expanded) "已展开" else "已收起")
                configureWorkspace()
                if (expanded) controlScroll.post { controlScroll.smoothScrollTo(0, virtual.top) }
            }
        }
        activeControls.add(expand); panel.addView(expand)
        panel.addView(virtual)
        panel.addView(row)
        panels.add(panel); panelHost.addView(panel)
    }

    private fun terminalPanel() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        terminalLog = label("命令输出显示在这里\n", 11f).apply {
            id = R.id.desktopTerminalLog
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        terminalScroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.status_bg); addView(terminalLog)
        }
        panel.addView(terminalScroll, LinearLayout.LayoutParams(-1, dp(92)))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        shell = Spinner(this).apply {
            adapter = ArrayAdapter(this@DesktopActivity, android.R.layout.simple_spinner_dropdown_item, listOf("CMD", "PowerShell"))
        }
        activeControls.add(shell)
        row.addView(shell, LinearLayout.LayoutParams(dp(108), dp(48)))
        commandText = field("输入命令…", limit = 8192).apply {
            id = R.id.desktopCommand
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { execute(); true } else false }
        }
        row.addView(commandText, LinearLayout.LayoutParams(0, -2, 1f))
        commandSend = compactButton("执行") { execute() }.apply { id = R.id.desktopExecute }
        activeControls.add(commandSend); row.addView(commandSend)
        panel.addView(row)
        panel.addView(compactButton("常用命令 · 点选填入") {
            CommandHints.show(this, shell.selectedItemPosition == 1) { line ->
                commandText.setText(line); commandText.setSelection(line.length)
            }
        })
        panel.addView(compactButton("命令列表 / 延迟执行") {
            startActivity(Intent(this, CommandsActivity::class.java).putExtra("line", commandText.text.toString())
                .putExtra("shell", if (shell.selectedItemPosition == 1) "powershell" else "cmd"))
        })
        panels.add(panel); panelHost.addView(panel)
    }

    private fun showMode() {
        panels.forEachIndexed { index, panel -> panel.visibility = if (!fullscreen && index == mode) View.VISIBLE else View.GONE }
        modeButtons.forEachIndexed { index, button ->
            val selected = index == mode
            button.setTextColor(color(if (selected) R.color.accent else R.color.dim))
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(color(if (selected) R.color.accent_dim else R.color.surface_variant))
            button.strokeColor = android.content.res.ColorStateList.valueOf(color(if (selected) R.color.accent else R.color.outline_soft))
        }
        configureWorkspace()
    }

    private fun setFullscreen(value: Boolean) {
        screen.cancelGesture()
        fullscreen = value
        toolbarView.visibility = if (value) View.GONE else View.VISIBLE
        body.setPadding(if (value) 0 else dp(16), 0, if (value) 0 else dp(16), if (value) 0 else dp(12))
        exitFullscreen.visibility = if (value) View.VISIBLE else View.GONE
        sessionRow.visibility = if (value) View.GONE else View.VISIBLE
        hideInlineFeedback(value)
        modeButtons.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        fullscreenButton.text = if (value) "退出全屏" else "全屏"
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        configureWorkspace()
        showMode()
    }

    private fun configureWorkspace() {
        val sideBySide = landscape && !fullscreen
        workspace.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        screenHost.layoutParams = LinearLayout.LayoutParams(if (sideBySide) 0 else -1, if (sideBySide) -1 else 0, 1f)
            .apply { topMargin = if (fullscreen) 0 else dp(8); bottomMargin = if (fullscreen) 0 else dp(8) }
        controlDock.visibility = if (fullscreen) View.GONE else View.VISIBLE
        controlDock.layoutParams = LinearLayout.LayoutParams(if (sideBySide) dp(300) else -1, if (sideBySide) -1 else -2)
        controlDock.setPadding(if (sideBySide) dp(8) else 0, 0, 0, 0)
        val keyboardHeight = if (mode == 1 && virtualKeyboardExpanded && !fullscreen)
            minOf(dp(432), maxOf(dp(240), (workspace.height * 0.75f).toInt())) else dp(240)
        controlScroll.layoutParams = if (sideBySide) LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(-1, keyboardHeight)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.cancelGesture()
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        configureWorkspace()
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

    private fun begin() {
        if (sessionJob?.isActive == true) return
        start.isEnabled = false; stop.isEnabled = true
        sessionJob = lifecycleScope.launch(feedbackContext()) {
            val owned = ArrayList<AgentClient>()
            var screenLease: RemoteLease? = null
            var inputLease: RemoteLease? = null
            val queue = Channel<InputCommand>(64)
            try {
                val source = ConnectionManager.sessionClient()
                origin = source
                val capabilities = source.call("capabilities").optJSONArray("features")
                if (capabilities == null || listOf("screen", "input", "pointer").any { required ->
                        (0 until capabilities.length()).none { capabilities.optString(it) == required }
                    }) error("请完整更新 Windows 1.6.0 接收端，新版本支持屏幕触控")
                status("正在连接画面、键鼠与终端…")
                suspend fun connection(): AgentClient {
                    val client = AgentClient(source.host, source.port)
                    owned.add(client) // Retain ownership before a cancellable connect can return.
                    ConnectionManager.connectFeatureClient(client, source)
                    return client
                }
                val frames = connection()
                val inputs = connection()
                val terminal = connection()
                val preview = RemoteLease("screen", frames).also { screenLease = it }
                val controls = RemoteLease("input", inputs).also { inputLease = it }
                preview.open(); controls.open()
                coroutineContext.ensureActive(); checkOrigin(source)
                terminalClient = terminal; commands = queue
                val hasLocalInput = (0 until capabilities.length()).any { capabilities.optString(it) == "local_input" }
                touchPauseSupported = (0 until capabilities.length()).any { capabilities.optString(it) == "touch_pause" }
                localLease = if (hasLocalInput) controls else null
                localPause.isEnabled = hasLocalInput && touchPauseSupported
                activeControls.forEach { it.isEnabled = true }
                screen.touchEnabled = touchSwitch.isChecked
                coroutineScope {
                    launch {
                        while (isActive) {
                            checkOrigin(source)
                            if(!hasLocalInput) { controls.keepAlive();delay(3000);continue }
                            val state = controls.call("local_input")
                            localPaused = state.optBoolean("paused")
                            localPause.text = if (localPaused) "恢复本机输入" else "暂停本机输入"
                            localStatus.text = if (localPaused) {
                                pauseSummary(state) +
                                    "\n本机解除：A R T E X + Ctrl + Delete，或 Ctrl+Alt+Del"
                            } else if(state.optString("warning").isNotBlank()) "已恢复本机输入：${state.optString("warning")}"
                            else if (touchPauseSupported) "本机输入可用 · 校验 HID 触屏实际状态，断线自动恢复" else "触屏暂停需完整更新 Windows 接收端"
                            delay(3000)
                        }
                    }
                    launch {
                        for (command in queue) {
                            checkOrigin(source)
                            controls.call(command.command, command.params)
                        }
                    }
                    while (isActive) {
                        checkOrigin(source)
                        if(!pageVisible) { preview.keepAlive();delay(1000);continue }
                        val result = preview.call("screen")
                        val bitmap = withContext(Dispatchers.Default) {
                            val bytes = android.util.Base64.decode(result.getString("data"), android.util.Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码电脑画面")
                        }
                        coroutineContext.ensureActive(); checkOrigin(source)
                        screen.showFrame(bitmap)
                        status("共享中 · ${bitmap.width} × ${bitmap.height} · 触控与终端可同时使用")
                        delay(350)
                    }
                }
            } catch (e: CancellationException) {
                status("共享已停止")
                throw e
            } catch (e: Exception) {
                status(e.message ?: "无法开始远程桌面", true)
            } finally {
                screen.touchEnabled = false
                commands = null; queue.cancel()
                terminalJob?.cancel(); terminalClient = null; origin = null
                localRequest?.cancel(); localLease = null; localPaused = false
                localPause.isEnabled = false; localPause.text = "暂停本机输入"
                localStatus.text = "共享已结束，本机输入将恢复"
                if (!isDestroyed) {
                    screen.clearFrame()
                    activeControls.forEach { it.isEnabled = false }
                }
                // End the input lease first so an interrupted drag cannot leave a button held.
                inputLease?.close(); screenLease?.close()
                owned.forEach { it.close() }
                if (!isDestroyed) { start.isEnabled = true; stop.isEnabled = false }
            }
        }
    }

    private fun changeLocalInput() {
        val lease = localLease ?: return
        if (localRequest?.isActive == true) return
        fun apply(action: String) {
            localPause.isEnabled = false
            localRequest = task {
                try {
                    // Explicit zero keeps manual mode when connected to a 1.7.0/1.7.1 receiver.
                    val state = lease.call("local_input", JSONObject().put("action", action).put("seconds", 0), 75_000)
                    localPaused = state.optBoolean("paused")
                    localPause.text = if (localPaused) "恢复本机输入" else "暂停本机输入"
                    localStatus.text = if (localPaused) pauseSummary(state)+" · 本机组合键或手机可恢复" else "本机输入已恢复"
                    status(localStatus.text.toString())
                } finally { localPause.isEnabled = localLease === lease && touchPauseSupported }
            }
        }
        if (localPaused) { apply("release"); return }
        AlertDialog.Builder(this).setTitle("暂停电脑本机输入")
            .setMessage("暂停键盘、鼠标及已识别的标准 HID 触屏，手机仍可控制。触屏暂停需要管理员权限，结果显示实际识别与禁用数量；未识别的厂商触控设备不能保证锁定。\n\n本机解除：A、R、T、E、X + Ctrl + Delete；或 Ctrl+Alt+Del。\n\n退出共享或心跳中断 12 秒恢复。独立恢复进程会在接收端退出或失去心跳后重新启用触屏；驱动恢复可能稍有延迟。")
            .setPositiveButton("开始暂停（手动解除）") { _, _ -> apply("pause") }
            .setNegativeButton("取消", null).show()
    }

    private fun pauseSummary(state:JSONObject):String {
        val disabled=state.optInt("touch_devices")
        val detected=state.optInt("touch_detected",disabled)
        val original=state.optInt("touch_already_disabled")
        return if(detected==0)"键鼠已暂停 · 未识别到标准 HID 触屏"
        else "键鼠已暂停 · 触屏确认禁用 ${disabled+original}/$detected（本次 $disabled，原已禁用 $original）"
    }

    private fun execute() {
        val client = terminalClient ?: return
        val source = origin ?: return
        if (terminalJob?.isActive == true) return
        val line = commandText.text.toString().trim()
        if (line.isEmpty()) return
        val selectedShell = if (shell.selectedItemPosition == 1) "powershell" else "cmd"
        commandText.setText(""); commandSend.isEnabled = false
        appendTerminal("\n$ $line\n")
        terminalJob = lifecycleScope.launch {
            try {
                checkOrigin(source)
                val result = client.call("exec", JSONObject().put("shell", selectedShell).put("line", line).put("timeout", 300), 310_000)
                if (!result.optBoolean("ok")) error(result.optString("error", "命令失败"))
                appendTerminal(result.optString("out") + result.optString("err") + "\n退出码 ${result.optInt("code")}\n")
            } catch (e: CancellationException) {
                appendTerminal("\n已停止等待命令结果\n"); throw e
            } catch (e: Exception) { appendTerminal("\n${e.message ?: "命令失败"}\n") }
            finally { if (!isDestroyed) commandSend.isEnabled = terminalClient != null }
        }
    }

    private fun appendTerminal(text: String) {
        if (isDestroyed) return
        terminalLog.text = (terminalLog.text.toString() + text).takeLast(50_000)
        terminalScroll.post { if (!isDestroyed) terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stopSession() {
        status("正在停止共享…")
        screen.cancelGesture()
        sessionJob?.cancel(); terminalJob?.cancel()
    }

    override fun onStart() {
        super.onStart()
        pageVisible=true
        ConnectionManager.addListener(this)
    }

    override fun onStatus(status: ConnectionManager.Status) {
        val source = origin ?: return
        if (runCatching { ConnectionManager.sessionClient() }.getOrNull() !== source) {
            sessionJob?.cancel(); terminalJob?.cancel()
        }
    }

    override fun onEvent(text: String, kind: ConnectionManager.LogKind) = Unit

    override fun onStop() {
        ConnectionManager.removeListener(this)
        pageVisible=false
        screen.cancelGesture()
        if(isFinishing) { sessionJob?.cancel();terminalJob?.cancel();localRequest?.cancel() }
        super.onStop()
    }
}

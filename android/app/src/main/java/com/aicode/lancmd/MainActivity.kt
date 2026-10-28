package com.aicode.lancmd

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.TCP_PORT
import com.aicode.lancmd.net.joinPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页：连接状态、工具入口与命令控制台。界面使用 miuix。 */
class MainActivity : MiuixActivity(), ConnectionManager.Listener {
    /** 首页的一个工具入口。 */
    internal class Tool(val label: String, val body: () -> Unit)

    private companion object {
        const val STATE_LOG = "console_log"
        const val STATE_HISTORY = "command_history"
        const val STATE_SHELL = "command_shell"
        const val REQ_NOTIFICATIONS = 100
        val CATEGORIES = listOf("命令控制台", "共享与控制", "文件与设备", "系统与应用", "录制与自动化")
    }

    internal var homeCategory by mutableStateOf("命令控制台")
    internal var consoleText by mutableStateOf("")
    internal var commandLine by mutableStateOf("")
    internal var shell by mutableStateOf("cmd")
    internal var connected by mutableStateOf(false)

    internal var deviceName by mutableStateOf("")
    internal var deviceSub by mutableStateOf("")
    internal var latencyText by mutableStateOf("")
    internal var overlayMessage by mutableStateOf("")
    internal var overlayBusy by mutableStateOf(true)
    internal var overlayVisible by mutableStateOf(true)
    internal var firewallHelpVisible by mutableStateOf(false)

    private val history = ArrayList<String>()
    private var uploadDir = ""
    private var transferJob: Job? = null
    private var transferBox: TransferBox? = null
    private var lastPath = ""
    private var firewallChecked = false
    /** 最近一次连接状态；界面不可见时也要保持最新。 */
    private var connection = Status()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return@registerForActivityResult
        }
        askUploadTarget(uris)
    }

    /** 分组后的工具入口，和旧版首页的分类一致。 */
    internal val toolGroups: LinkedHashMap<String, List<Tool>> by lazy {
        linkedMapOf(
            "共享与控制" to listOf(
                Tool("远程桌面") { openFeature(DesktopActivity::class.java) },
                Tool("摄像头") { openFeature(MediaActivity::class.java, "camera") },
                Tool("按键") { openFeature(ControlActivity::class.java, "input") },
                Tool("消息") { openFeature(ControlActivity::class.java, "message") },
                Tool("剪贴板") { showClipboard() }
            ),
            "文件与设备" to listOf(
                Tool("文件管理") { openFeature(StorageActivity::class.java) },
                Tool("文件搜索") { openFeature(FileSearchActivity::class.java) },
                Tool("U盘与设备") { openFeature(DevicesActivity::class.java) },
                Tool("隐私设备") { openFeature(PrivacyDevicesActivity::class.java) }
            ),
            "系统与应用" to listOf(
                Tool("任务管理器") { openFeature(TaskManagerActivity::class.java) },
                Tool("注册表编辑器") { openFeature(RegistryActivity::class.java) },
                Tool("浏览器") { openFeature(BrowserActivity::class.java) },
                Tool("应用中心") { openFeature(ApplicationsActivity::class.java) },
                Tool("应用锁") { openFeature(AppLocksActivity::class.java) },
                Tool("桌面背景") { openFeature(WallpaperActivity::class.java) },
                Tool("本地升级") { openFeature(UpgradeActivity::class.java) }
            ),
            "录制与自动化" to listOf(
                Tool("录音 / 录屏") { openFeature(RecordingsActivity::class.java) },
                Tool("手势回放") { openFeature(GesturesActivity::class.java) },
                Tool("延迟命令") { openFeature(CommandsActivity::class.java) },
                Tool("电脑音量") { AudioDialog(this).show() }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeCategory = savedInstanceState?.getString("homeCategory")?.takeIf { it in CATEGORIES }
            ?: "命令控制台"
        // 切换外观主题会重建页面：保留控制台、历史命令与终端类型
        savedInstanceState?.let { state ->
            consoleText = state.getString(STATE_LOG).orEmpty()
            state.getStringArrayList(STATE_HISTORY)?.let { history.clear(); history.addAll(it) }
            shell = state.getString(STATE_SHELL) ?: "cmd"
        }
        uploadDir = Prefs.uploadDir(this)
        content { Screen() }
        ConnectionManager.addListener(this)
        try { render(ConnectionManager.snapshot()) }
        catch (t: Throwable) { android.util.Log.e("LanCmd", "首帧刷新失败: " + t.message, t) }
        requestNotificationPermission()
    }

    @Composable
    private fun Screen() = Scaffold(topBar={ WorkspaceHeader("LanCmd",subtitle=if(connected)"已连接 · 远程工作区" else "等待连接 · 远程工作区") }) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            SectionCard {
                InfoItem(deviceName.ifBlank { getString(R.string.status_idle) }, deviceSub)
                if (latencyText.isNotBlank()) RowItem("延迟", latencyText) { pingOnce() }
                ActionRow {
                    Action("工具库", modifier = Modifier.weight(1f)) { ToolNavigator.show(this@MainActivity) }
                    Action("外观主题", modifier = Modifier.weight(1f)) { ThemeDialog(this@MainActivity).show() }
                    Action("更多", modifier = Modifier.weight(1f)) { showMenu() }
                }
            }
            if (overlayVisible) SectionCard {
                Note(overlayMessage)
                ActionRow {
                    if (!overlayBusy) Action("立即重连", primary = true, modifier = Modifier.weight(1f)) {
                        note("正在重连…")
                        ConnectionManager.retryNow()
                    }
                    if (firewallHelpVisible) Action("连不上？", modifier = Modifier.weight(1f)) {
                        showFirewallHelp()
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (homeCategory == "命令控制台") Console() else Tools()
            if(homeCategory=="命令控制台")SectionCard {
                Field(
                    label = "输入命令，回车发送",
                    value = commandLine,
                    onValueChange = { commandLine = it.take(8192) }
                )
                ActionRow {
                    Action("CMD", primary = shell == "cmd", modifier = Modifier.weight(1f)) { shell = "cmd" }
                    Action(
                        "PowerShell",
                        primary = shell == "powershell",
                        modifier = Modifier.weight(1f)
                    ) { shell = "powershell" }
                    Action("发送", enabled = connected, primary = true, modifier = Modifier.weight(1f)) {
                        sendTyped()
                    }
                }
                ActionRow {
                    Action("常用", modifier = Modifier.weight(1f)) {
                        CommandHints.show(this@MainActivity, shell == "powershell") { line ->
                            commandLine = line
                        }
                    }
                    Action("历史", modifier = Modifier.weight(1f)) { showHistory() }
                    Action("传文件", modifier = Modifier.weight(1f)) { startPickFiles() }
                    Action("延迟", modifier = Modifier.weight(1f)) {
                        startActivity(
                            Intent(this@MainActivity, CommandsActivity::class.java)
                                .putExtra("line", commandLine).putExtra("shell", shell)
                        )
                    }
                }
            }
            }
            HomeDock()
        }
    }

    @Composable private fun HomeDock() {
        val labels=listOf("控制台","共享","文件","系统","自动化")
        val icons=listOf(R.drawable.ic_terminal,R.drawable.ic_desktop,R.drawable.ic_folder,R.drawable.ic_process,R.drawable.ic_history)
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            CATEGORIES.forEachIndexed { index,category ->
                val selected=category==homeCategory
                Column(Modifier.weight(1f).background(if(selected)MiuixTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(18.dp))
                    .selectable(selected=selected,role=Role.Tab,onClick={homeCategory=category}).padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(painterResource(icons[index]),category,modifier=Modifier.size(21.dp),tint=if(selected)MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(labels[index],fontSize=11.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,modifier=Modifier.padding(top=5.dp))
                }
            }
        }
    }

    @Composable
    private fun Console() {
        SectionCard("命令控制台") {
            if (consoleText.isBlank()) EmptyNote("连接后在下方输入命令，输出显示在这里。")
            else Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Note(consoleText.takeLast(8000)) }
            }
            Action("清空控制台") { consoleText = "" }
        }
    }

    @Composable
    private fun Tools() {
        WorkspaceHeader(homeCategory,subtitle="工具与管理")
        toolGroups[homeCategory].orEmpty().chunked(2).forEach { group ->
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                group.forEach { tool ->
                    val icon=when(tool.label) {
                        "文件管理","文件搜索"->R.drawable.ic_folder
                        "远程桌面","桌面背景"->R.drawable.ic_desktop
                        "摄像头","隐私设备","录音 / 录屏"->R.drawable.ic_camera
                        "应用锁"->R.drawable.ic_lock
                        "浏览器"->R.drawable.ic_browser
                        "U盘与设备"->R.drawable.ic_usb
                        "按键","手势回放"->R.drawable.ic_keyboard
                        "消息"->R.drawable.ic_message
                        else->R.drawable.ic_process
                    }
                    Card(modifier=Modifier.weight(1f),cornerRadius=24.dp,insideMargin=PaddingValues(18.dp),onClick={openTool(tool.label)}) {
                        Icon(painterResource(icon),null,modifier=Modifier.size(28.dp),tint=MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(20.dp))
                        Text(tool.label,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        Text("打开工具  ›",fontSize=11.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,modifier=Modifier.padding(top=5.dp))
                    }
                }
                if(group.size==1)Spacer(Modifier.weight(1f))
            }
        }
    }

    /** 工具入口统一先确认已连接，和旧版磁贴的前置判断一致。 */
    internal fun openTool(label: String) {
        val tool = toolGroups.values.flatten().firstOrNull { it.label == label } ?: return
        if (ConnectionManager.isConnected()) tool.body()
        else { toast("未连接，正在重连…"); ConnectionManager.retryNow() }
    }

    internal fun openFeature(type: Class<*>, kind: String = "") {
        startActivity(Intent(this, type).putExtra("kind", kind))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        ConnectionManager.addListener(this)
        ConnectionManager.start(this)
        // 不要无条件用快照覆盖：快照可能还是初始空值，
        // 会把已经收到的有效状态（host/name）冲掉，
        // 导致随后的电源确认弹窗显示空白目标。
        val snap = ConnectionManager.snapshot()
        if (snap.state != State.IDLE || connection.state == State.IDLE) render(snap)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("homeCategory", homeCategory)
        outState.putString(STATE_LOG, consoleText.takeLast(60_000))
        outState.putStringArrayList(STATE_HISTORY, ArrayList(history))
        outState.putString(STATE_SHELL, shell)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        ConnectionManager.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        // 传输中的对话框持有 Activity，不清理会泄漏；job 交给 lifecycleScope 自动取消，
        // FileTransfer 在取消时会发 abort 让电脑删掉半截文件。
        transferBox?.dismiss()
        transferBox = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 命令

    internal fun sendTyped() {
        val line = commandLine.trim()
        if (line.isEmpty()) return
        commandLine = ""
        if (line.equals("cls", true) || line.equals("clear", true)) { consoleText = ""; return }
        remember(line)
        log("$ $line", LogKind.CMD)
        // 接收端最多执行 300 秒；另留 10 秒用于进程收尾和回包。
        run(
            "exec",
            JSONObject().put("shell", shell).put("line", line).put("timeout", 300),
            timeoutMs = 310_000
        ) { r ->
            val out = r.optString("out").trimEnd()
            val err = r.optString("err").trimEnd()
            val code = r.optInt("code", 0)
            if (out.isNotEmpty()) log(out, LogKind.PLAIN)
            if (err.isNotEmpty()) log(err, LogKind.ERROR)
            if (code != 0) log("[exit $code]", LogKind.ERROR)
            // start / & 这类会派生后台进程，命令本身已返回但输出可能不完整
            if (r.optBoolean("detached", false)) {
                log("（命令已启动，仍有进程在后台运行）", LogKind.INFO)
            }
        }
    }

    private fun remember(line: String) {
        history.remove(line)
        history.add(0, line)
        while (history.size > 50) history.removeAt(history.size - 1)
    }

    internal fun showHistory() {
        if (history.isEmpty()) { toast("还没有历史命令"); return }
        WorkspaceDialog.Builder(this).setTitle("历史命令")
            .setItems(history.toTypedArray()) { _, which -> commandLine = history[which] }
            .setNeutralButton("清空记录") { _, _ -> history.clear() }
            .setNegativeButton("关闭", null).show()
    }

    // ------------------------------------------------------------ 文件发送

    internal fun startPickFiles() {
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return
        }
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun askUploadTarget(uris: List<Uri>) {
        val preview = uris.take(3).joinToString("、") { FileTransfer.displayName(this, it) }
        val more = if (uris.size > 3) " 等 ${uris.size} 个文件" else ""
        val source = runCatching { ConnectionManager.sessionClient() }.getOrElse { return }
        RemoteDirectoryPicker(this).show(uploadDir.ifEmpty { lastPath }) { dir ->
            WorkspaceDialog.Builder(this).setTitle("发送 ${uris.size} 个文件到电脑")
                .setMessage("$preview$more\n\n目标：$dir\n同名文件会被替换。")
                .setPositiveButton("发送") { _, _ ->
                    if (runCatching { ConnectionManager.sessionClient() === source }.getOrDefault(false)) {
                        uploadDir = dir
                        Prefs.saveUploadDir(this, dir)
                        startUpload(uris, dir, source)
                    } else toast("连接已切换，请重新选择目标电脑")
                }.setNeutralButton("重新选择") { _, _ -> askUploadTarget(uris) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun startUpload(
        uris: List<Uri>,
        dir: String,
        source: AgentClient = ConnectionManager.sessionClient()
    ) {
        transferBox?.dismiss()
        val box = TransferBox(this, "准备发送…") { transferJob?.cancel() }
        transferBox = box
        transferJob = lifecycleScope.launch {
            var succeeded = 0
            var failed = 0
            try {
                FileDelay.waitLocal(this@MainActivity, source) { box.setTitle(it) }
                for ((i, uri) in uris.withIndex()) {
                    val name = FileTransfer.displayName(this@MainActivity, uri)
                    val remote = joinPath(dir, name)
                    box.setTitle("[${i + 1}/${uris.size}] $name")
                    try {
                        val r = FileTransfer.uploadTo(this@MainActivity, uri, remote, source) { p ->
                            if (!isFinishing && !isDestroyed) runOnUiThread { box.update(p) }
                        }
                        succeeded++
                        log("✓ 已发送 $name → ${r.optString("path", remote)}", LogKind.OK)
                    } catch (e: CancellationException) {
                        log("已取消发送（已完成 $succeeded 个）", LogKind.INFO)
                        throw e
                    } catch (e: Exception) {
                        failed++
                        log("✗ 发送失败 $name：${e.message}", LogKind.ERROR)
                    }
                }
                if (failed == 0) log("全部发送完成，共 $succeeded 个文件", LogKind.OK)
                else log("发送结束：成功 $succeeded，失败 $failed", LogKind.ERROR)
            } finally {
                box.dismiss()
                if (transferBox === box) transferBox = null
            }
        }
    }

    /** 传输进度对话框：标题 + 进度条 + 详情 + 取消。 */
    private inner class TransferBox(
        ctx: android.content.Context,
        initialTitle: String,
        private val onCancel: () -> Unit
    ) {
        private val title = android.widget.TextView(ctx).apply {
            text = initialTitle
            setTextColor(Themes.color(ctx, R.color.text))
            textSize = 14.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        private val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Themes.color(ctx, R.color.accent))
        }
        private val detail = android.widget.TextView(ctx).apply {
            text = "0%"
            setTextColor(Themes.color(ctx, R.color.dim))
            textSize = 12f
        }
        private val dialog: AlertDialog

        init {
            val box = dialogBox().apply { setPadding(px(22), px(18), px(22), px(6)) }
            box.addView(title)
            box.addView(
                bar,
                android.widget.LinearLayout.LayoutParams(-1, px(6)).apply { topMargin = px(14) }
            )
            box.addView(
                detail,
                android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
            )
            dialog = WorkspaceDialog.Builder(ctx).setView(box)
                .setNegativeButton("取消") { _, _ -> }
                .setCancelable(false).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    onCancel()
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    detail.text = "正在取消…"
                }
            }
            dialog.show()
        }

        /** inner class，直接看外层 Activity 的存活状态。 */
        private fun alive() = !isFinishing && !isDestroyed && dialog.isShowing

        fun setTitle(text: String) { if (alive()) title.text = text }

        fun update(p: FileTransfer.Progress) {
            if (!alive()) return
            bar.progress = p.percent
            val total = if (p.total > 0) FileTransfer.humanSize(p.total) else "未知大小"
            detail.text = "${FileTransfer.humanSize(p.done)} / $total" +
                "  ·  ${p.percent}%  ·  ${FileTransfer.humanSize(p.speedBps)}/s"
        }

        fun dismiss() {
            // Activity 已销毁时 dismiss 可能抛 IllegalArgumentException
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 请求封装

    private fun run(
        cmd: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
        block: (JSONObject) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                val r = if (cmd in setOf("files", "mkdir", "rm", "rename", "put")) {
                    FileDelay.call(
                        this@MainActivity, ConnectionManager.sessionClient(), cmd, params, timeoutMs
                    ) { log(it, LogKind.INFO) }
                } else ConnectionManager.send(cmd, params, timeoutMs)
                if (!r.optBoolean("ok")) { log(r.optString("error", "执行失败"), LogKind.ERROR); return@launch }
                block(r)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log(e.message ?: "请求失败", LogKind.ERROR) }
        }
    }

    internal fun pingOnce() {
        if (!ConnectionManager.isConnected()) { ConnectionManager.retryNow(); return }
        lifecycleScope.launch {
            try {
                val started = System.currentTimeMillis()
                ConnectionManager.send("ping", timeoutMs = 5000)
                log("延迟 ${System.currentTimeMillis() - started}ms", LogKind.INFO)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log(e.message ?: "ping 失败", LogKind.ERROR) }
        }
    }

    // ------------------------------------------------------------ 功能面板

    private fun showInfo() {
        run("info", JSONObject()) { r ->
            val text = buildString {
                append("主机  ").append(r.optString("host")).append('\n')
                append("用户  ").append(r.optString("user")).append('\n')
                append("系统  ").append(r.optString("os")).append(" / ").append(r.optString("arch")).append('\n')
                append("CPU   ").append(r.optInt("cpus")).append(" 核").append('\n')
                val up = r.optLong("uptime_s", -1)
                if (up >= 0) append("运行  ").append(uptime(up)).append('\n')
                val drives = r.optJSONArray("drives")
                if (drives != null) for (i in 0 until minOf(drives.length(), 6)) {
                    val d = drives.getJSONObject(i)
                    append(d.optString("path"))
                        .append("  剩 ").append(FileTransfer.humanSize(d.optLong("free")))
                        .append(" / ").append(FileTransfer.humanSize(d.optLong("total"))).append('\n')
                }
            }
            dialog("电脑信息", text.trimEnd())
        }
    }

    /**
     * 热点场景最常见的失败原因就是电脑防火墙没放行。
     * 连上之后顺手查一下，没放行就明确告诉用户怎么修。
     */
    private fun checkFirewall() {
        // 这一步在连接成功的回调里跑，任何异常都会变成闪退，全程兜底
        try { if (!ConnectionManager.onLocalOnlyWifi()) return }
        catch (t: Throwable) { android.util.Log.w("LanCmd", "网络状态检查失败: " + t.message, t); return }
        run("info", JSONObject()) { r ->
            if (r.optBoolean("firewall_ok", true)) return@run
            log("⚠ 电脑防火墙未放行 47823/47824", LogKind.ERROR)
            safeDialog {
                setTitle("电脑防火墙没放行")
                    .setMessage(
                        "手机连着电脑热点，但电脑防火墙拦住了连接端口。\n\n" +
                            "在电脑上打开 Start.exe，点「修复防火墙」即可（会请求管理员权限）。\n\n" +
                            "或者用管理员身份运行：\nLanCmdCtl.exe --fix-firewall"
                    )
                    .setPositiveButton("知道了", null)
            }
        }
    }

    internal fun showClipboard() {
        run("clip", JSONObject().put("action", "get")) { r ->
            val box = dialogInput(text = r.optString("text"), singleLine = false).apply {
                minLines = 3
                maxLines = 10
                contentDescription = "电脑剪贴板内容"
            }
            WorkspaceDialog.Builder(this).setTitle("电脑剪贴板").setView(box)
                .setPositiveButton("写回电脑") { _, _ ->
                    run("clip", JSONObject().put("action", "set").put("text", box.text.toString())) {
                        log("✓ 已写入电脑剪贴板", LogKind.OK)
                    }
                }.setNegativeButton("关闭", null).show()
        }
    }

    private fun doLock() {
        run("lock", JSONObject()) { log("✓ 电脑已锁屏", LogKind.OK) }
    }

    /**
     * 关机 / 重启 / 注销 / 睡眠的二次确认。
     *
     * 电源类操作不可撤销，所以做两级：
     *   第一级 —— 说明后果，按钮是「继续」
     *   第二级 —— 列出具体目标电脑和不可逆提示，按钮才是「确认执行」
     */
    internal fun confirmPower(action: String, label: String) {
        val irreversible = action in arrayOf("shutdown", "restart", "logoff")
        val warning = if (irreversible) "电脑上未保存的内容会丢失，该操作无法撤销。"
        else "电脑将进入睡眠，可正常唤醒。"
        // 目标信息在第一级就固定下来。status 会被后台状态回调覆盖，
        // 若等第二级现读，可能已经变成空值。
        val name = connection.name.ifBlank { "这台电脑" }
        val addr = if (connection.host.isBlank()) "未知地址" else "${connection.host}:${connection.port}"
        safeDialog {
            setTitle("${label}电脑？")
            setMessage("将向「" + name + "」发送" + label + "指令。\n\n" + warning)
            setPositiveButton("继续") { _, _ ->
                confirmPowerFinal(action, label, irreversible, name, addr)
            }
            setNegativeButton("取消", null)
        }
    }

    private fun confirmPowerFinal(
        action: String,
        label: String,
        irreversible: Boolean,
        name: String,
        addr: String
    ) {
        val consequence = if (irreversible) {
            "这是最后一步。点「确认${label}」后电脑会立即${label}，手机上的连接也会随之断开。"
        } else "确认让这台电脑进入睡眠？"
        safeDialog {
            setTitle("再次确认：${label}")
            setMessage("目标电脑：" + "\n" + name + "\n" + addr + "\n\n" + consequence)
            setPositiveButton("确认${label}") { _, _ ->
                log("正在发送" + label + "指令到 " + addr, LogKind.INFO)
                run("power", JSONObject().put("action", action)) { log("✓ 已发送${label}指令", LogKind.OK) }
            }
            setNegativeButton("取消", null)
        }
    }

    // ------------------------------------------------------------ 菜单

    internal fun showMenu() {
        val entries = linkedMapOf<String, () -> Unit>(
            "全部工具 · 搜索" to { ToolNavigator.show(this) },
            "立即重连" to { ConnectionManager.retryNow() },
            "手动输入电脑地址" to { manualDialog() },
            "断开连接" to {
                ConnectionManager.stop()
                log("已断开（不再自动重连）", LogKind.INFO)
            },
            "清除配对记录" to {
                ConnectionManager.forget(this)
                log("已清除配对记录，下次连接需在电脑端确认", LogKind.INFO)
            },
            "电脑信息" to { showInfo() },
            "外观主题" to { ThemeDialog(this).show() },
            (if (LanCmdService.isRunning) "关闭后台常驻" else "开启后台常驻") to { toggleResident() },
            "查看崩溃日志" to { showCrashLog() },
            "运行中的进程" to { showProcesses() },
            "锁定电脑" to { doLock() },
            "重启电脑" to { confirmPower("restart", "重启") },
            "关闭电脑" to { confirmPower("shutdown", "关机") },
            "关于" to { about() }
        )
        val labels = entries.keys.toList()
        WorkspaceDialog.Builder(this).setTitle("更多")
            .setItems(labels.toTypedArray()) { _, index -> entries.getValue(labels[index])() }
            .setNegativeButton("关闭", null).show()
    }

    internal fun showProcesses() {
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return
        }
        ProcessDialog(this, lifecycleScope) { text, kind -> log(text, kind) }.show()
    }

    private fun manualDialog() {
        val input = dialogInput(
            hint = "192.168.1.10 或 192.168.1.10:$TCP_PORT",
            text = Prefs.target(this)?.host ?: ""
        )
        WorkspaceDialog.Builder(this).setTitle("手动输入电脑地址").setView(input)
            .setPositiveButton("连接") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val host = raw.substringBefore(":")
                val port = raw.substringAfter(":", TCP_PORT.toString()).toIntOrNull() ?: TCP_PORT
                Prefs.saveTarget(this, Target(host, port, host, ""))
                ConnectionManager.retryNow()
            }.setNegativeButton("取消", null).show()
    }

    private fun toggleResident() {
        if (LanCmdService.isRunning) {
            LanCmdService.stop(this)
            log("已关闭后台常驻，App 退到后台后可能掉线", LogKind.INFO)
        } else {
            LanCmdService.start(this)
            log("已开启后台常驻，退到后台也保持连接", LogKind.INFO)
        }
    }

    /**
     * TCP 超时（不是拒绝连接）意味着包被静默丢弃，最常见就是电脑防火墙。
     * 这里把修复步骤直接写清楚，免得用户去翻文档。
     */
    internal fun showFirewallHelp() {
        safeDialog {
            setTitle("连不上？多半是防火墙")
            setMessage(
                "连接超时说明数据包发出去了但没有任何回应，" +
                    "这基本就是电脑防火墙把 47823 / 47824 端口拦掉了。" +
                    "\n\n电脑开热点时尤其容易这样：手机连的那个网络会被 Windows " +
                    "判为「公用网络」，而入站连接在这一类网络下默认是拦截的。" +
                    "\n\n修复步骤（在电脑上操作）：\n\n" +
                    "① 打开接收端所在文件夹，双击 Start.exe\n" +
                    "② 点「修复防火墙」按钮\n" +
                    "③ 弹出用户账户控制时点「是」\n" +
                    "④ 看到「防火墙已放行」后，回到手机点「立即重连」\n\n" +
                    "如果没有 Start.exe，就用管理员身份打开命令提示符执行：\n" +
                    "LanCmdCtl.exe --fix-firewall\n\n" +
                    "⚠ 还有一种情况：Windows 安全中心里当前网络的" +
                    "「阻止所有入站连接」被勾选了，那样即使加了放行规则也无效，" +
                    "需要把那个勾去掉。\n\n" +
                    "另外确认接收端确实在跑：Start.exe 上应显示「接收端运行中」。"
            ).setPositiveButton("知道了", null)
        }
    }

    /** 把最近一次崩溃连同设备信息展示出来，便于反馈问题。 */
    private fun showCrashLog() {
        val text = CrashLog.latest(this)
        if (text.isNullOrBlank()) {
            safeDialog {
                setTitle("崩溃日志")
                    .setMessage("没有记录到崩溃。\n\n如果 App 刚才异常退出过，重新打开后这里会出现记录。")
                    .setPositiveButton("好", null)
            }
            return
        }
        val view = android.widget.TextView(this).apply {
            this.text = text
            textSize = 10.5f
            setTextIsSelectable(true)
            setPadding(px(16), px(12), px(16), px(12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        safeDialog {
            setTitle("最近一次崩溃")
                .setView(android.widget.ScrollView(this@MainActivity).apply { addView(view) })
                .setPositiveButton("好", null)
                .setNegativeButton("清除记录") { _, _ -> CrashLog.clear(this@MainActivity) }
        }
    }

    private fun about() {
        WorkspaceDialog.Builder(this).setTitle("LanCmd ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "手机控制同一局域网内的电脑。\n\n" +
                    "· 电脑端常驻后台，手机断线不影响它\n" +
                    "· 掉线后自动重连，网络恢复立即重试\n" +
                    "· 配对后可自动重连\n" +
                    "· 屏幕、摄像头和按键需在电脑端授权，可随时停止\n\n" +
                    "电脑端：pythonw lan_command_agent.py"
            ).setPositiveButton("好", null).show()
    }

    // ------------------------------------------------------------ 状态渲染

    override fun onStatus(s: Status) = render(s)

    override fun onEvent(text: String, kind: LogKind) = log(text, kind)

    private fun render(s: Status) {
        // 先把状态存下来：界面不可见时也要保证 status 是最新的，
        // 否则后续 confirmPower 之类的动作会读到空 host/name。
        connection = s
        try { renderInner(s) }
        catch (t: Throwable) { android.util.Log.e("LanCmd", "界面刷新失败: " + t.message, t) }
    }

    private fun renderInner(s: Status) {
        if (!alive()) return
        connected = s.state == State.CONNECTED
        when (s.state) {
            State.CONNECTED -> {
                deviceName = s.name.ifEmpty { "已连接" }
                deviceSub = "${s.host}:${s.port}" + if (s.paired) "  ·  免确认直连" else "  ·  已配对"
                latencyText = if (s.latencyMs >= 0) "${s.latencyMs}ms" else "在线"
                overlayVisible = false
                firewallHelpVisible = false
                if (!firewallChecked) { firewallChecked = true; checkFirewall() }
            }
            State.CONNECTING -> {
                deviceName = s.name.ifEmpty { "连接中…" }
                deviceSub = "正在连接 ${s.host}:${s.port}"
                latencyText = ""
                showOverlay("正在连接 ${s.name.ifEmpty { s.host }}…", busy = true)
            }
            State.WAITING -> {
                deviceName = if (s.name.isEmpty()) "等待电脑" else s.name
                deviceSub = buildString {
                    append("已掉线，自动重连中")
                    if (s.attempt > 0) append(" · 第 ${s.attempt} 次")
                }
                latencyText = ""
                showOverlay(
                    buildString {
                        append(s.message.ifEmpty { "连接已断开，正在重连…" })
                        if (s.nextRetrySec > 0) append("\n${s.nextRetrySec} 秒后重试")
                        if (s.reconnects > 0) append("\n已成功重连 ${s.reconnects} 次")
                    },
                    busy = s.nextRetrySec == 0
                )
                firewallHelpVisible = s.needFirewallCheck
            }
            State.STOPPED -> {
                deviceName = "已断开"
                deviceSub = "点击「立即重连」或「更多」菜单恢复"
                latencyText = ""
                showOverlay("已手动断开，不会自动重连", busy = false)
            }
            State.IDLE -> {
                deviceName = getString(R.string.status_idle)
                deviceSub = getString(R.string.sub_idle)
                latencyText = ""
                showOverlay("正在查找局域网内的电脑…", busy = true)
            }
        }
    }

    private fun showOverlay(message: String, busy: Boolean) {
        overlayVisible = true
        overlayMessage = message
        overlayBusy = busy
        if (connection.state != State.WAITING) firewallHelpVisible = false
    }

    // ------------------------------------------------------------ 日志

    internal fun log(text: String, kind: LogKind) {
        if (text.isEmpty()) return
        val line = if (kind == LogKind.ERROR || kind == LogKind.INFO) {
            "${timeFmt.format(Date())} $text"
        } else text
        val next = if (consoleText.isEmpty()) line else consoleText + "\n" + line
        consoleText = if (next.length > 300_000) next.takeLast(200_000) else next
    }

    private fun note(text: String) = log(text, LogKind.INFO)

    /** Activity 还活着才能操作 UI / 弹窗，否则会抛 BadTokenException 直接闪退。 */
    private fun alive() = !isFinishing && !isDestroyed

    /**
     * 安全弹窗：生命周期已结束时静默丢弃，绝不抛异常。
     * 连接成功/失败的回调是异步的，用户完全可能在这期间退出界面。
     */
    private fun safeDialog(build: WorkspaceDialog.Builder.() -> WorkspaceDialog.Builder) {
        if (!alive()) return
        try { build(WorkspaceDialog.Builder(this)).show() }
        catch (t: Throwable) { android.util.Log.e("LanCmd", "弹窗失败: " + t.message, t) }
    }

    private fun dialog(title: String, message: String) {
        WorkspaceDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("好", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun uptime(sec: Long): String {
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return when {
            d > 0 -> "${d}天${h}小时"
            h > 0 -> "${h}小时${m}分"
            else -> "${m}分钟"
        }
    }
}

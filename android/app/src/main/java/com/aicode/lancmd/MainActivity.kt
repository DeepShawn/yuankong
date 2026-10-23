package com.aicode.lancmd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.TCP_PORT
import com.aicode.lancmd.net.joinPath
import com.aicode.lancmd.net.parentOf
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectionManager.Listener {

    private lateinit var tvName: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var scroll: ScrollView
    private lateinit var etCmd: EditText
    private lateinit var dot: View
    private lateinit var overlay: View
    private lateinit var pbOverlay: ProgressBar
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnFwHelp: MaterialButton
    private lateinit var btnSend: ImageButton
    private lateinit var toggleShell: MaterialButtonToggleGroup

    private val history = ArrayList<String>()
    private var uploadDir = ""
    private var transferJob: Job? = null
    private var transferBox: TransferBox? = null

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            if (!ConnectionManager.isConnected()) {
                toast("未连接，正在重连…")
                ConnectionManager.retryNow()
                return@registerForActivityResult
            }
            askUploadTarget(uris)
        }
    private val REQ_NOTIFICATIONS = 100
    private var lastPath = ""
    private var status = Status()
    private var firewallChecked = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            // 布局加载失败（主题/资源异常）是最典型的启动即崩原因
            android.util.Log.e("LanCmd", "布局加载失败", t)
            finish()
            return
        }

        tvName = findViewById(R.id.tvName)
        tvSub = findViewById(R.id.tvSub)
        tvLatency = findViewById(R.id.tvLatency)
        tvLog = findViewById(R.id.tvLog)
        tvOverlay = findViewById(R.id.tvOverlay)
        scroll = findViewById(R.id.scroll)
        etCmd = findViewById(R.id.etCmd)
        dot = findViewById(R.id.dot)
        overlay = findViewById(R.id.overlay)
        pbOverlay = findViewById(R.id.pbOverlay)
        btnRetry = findViewById(R.id.btnRetry)
        btnFwHelp = findViewById(R.id.btnFwHelp)
        btnSend = findViewById(R.id.btnSend)
        toggleShell = findViewById(R.id.toggleShell)

        toggleShell.check(R.id.btnShellCmd)
        toggleShell.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefsShell = if (checkedId == R.id.btnShellPs) "powershell" else "cmd"
        }

        try {
            buildActions()
            wireInput()
        } catch (t: Throwable) {
            android.util.Log.e("LanCmd", "初始化失败", t)
        }

        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        btnRetry.setOnClickListener {
            note("正在重连…")
            ConnectionManager.retryNow()
        }
        btnFwHelp.setOnClickListener { showFirewallHelp() }
        tvLatency.setOnClickListener { pingOnce() }
        findViewById<View>(R.id.btnUpload).setOnClickListener { startPickFiles() }

        uploadDir = Prefs.uploadDir(this)

        ConnectionManager.addListener(this)
        try {
            render(ConnectionManager.snapshot())
        } catch (t: Throwable) {
            android.util.Log.e("LanCmd", "首帧刷新失败", t)
        }
        requestNotificationPermission()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val padding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(padding[0] + bars.left, padding[1] + bars.top, padding[2] + bars.right,
                padding[3] + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATIONS
        )
    }

    override fun onStart() {
        super.onStart()
        ConnectionManager.addListener(this)
        ConnectionManager.start(this)
        // 不要无条件用快照覆盖：快照可能还是初始空值，
        // 会把已经收到的有效状态（host/name）冲掉，
        // 导致随后的电源确认弹窗显示空白目标。
        val snap = ConnectionManager.snapshot()
        if (snap.state != State.IDLE || status.state == State.IDLE) {
            render(snap)
        }
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

    private var prefsShell = "cmd"

    // ------------------------------------------------------------ 快捷操作

    private fun buildActions() {
        val row1 = findViewById<LinearLayout>(R.id.rowActions1)
        val row2 = findViewById<LinearLayout>(R.id.rowActions2)
        val actions = listOf(
            Action(R.drawable.ic_screenshot, "屏幕") { openFeature(MediaActivity::class.java, "screen") },
            Action(R.drawable.ic_camera, "摄像头") { openFeature(MediaActivity::class.java, "camera") },
            Action(R.drawable.ic_keyboard, "按键") { openFeature(ControlActivity::class.java, "input") },
            Action(R.drawable.ic_message, "消息") { openFeature(ControlActivity::class.java, "message") },
            Action(R.drawable.ic_folder, "存储") { openFeature(StorageActivity::class.java) },
            Action(R.drawable.ic_process, getString(R.string.q_ps)) { showProcesses() },
            Action(R.drawable.ic_clipboard, getString(R.string.q_clip)) { showClipboard() },
            Action(R.drawable.ic_info, getString(R.string.q_info)) { showInfo() }
        )
        actions.forEachIndexed { i, a ->
            val parent = if (i < 4) row1 else row2
            val item = layoutInflater.inflate(R.layout.view_action, parent, false)
            item.findViewById<ImageView>(R.id.ivIcon).setImageResource(a.icon)
            item.findViewById<TextView>(R.id.tvLabel).text = a.label
            item.contentDescription = a.label
            item.setOnClickListener {
                if (!ConnectionManager.isConnected()) {
                    toast("未连接，正在重连…")
                    ConnectionManager.retryNow()
                    return@setOnClickListener
                }
                a.body()
            }
            parent.addView(item)
        }
    }

    private class Action(val icon: Int, val label: String, val body: () -> Unit)

    private fun openFeature(type: Class<*>, kind: String = "") {
        startActivity(Intent(this, type).putExtra("kind", kind))
    }

    // ------------------------------------------------------------ 输入区

    private fun wireInput() {
        btnSend.setOnClickListener { sendTyped() }
        etCmd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        findViewById<View>(R.id.btnClear).setOnClickListener { tvLog.text = "" }
        findViewById<View>(R.id.btnHistory).setOnClickListener { showHistory() }
    }

    private fun sendTyped() {
        val line = etCmd.text.toString().trim()
        if (line.isEmpty()) return
        etCmd.setText("")
        if (line.equals("cls", true) || line.equals("clear", true)) {
            tvLog.text = ""
            return
        }
        remember(line)
        log("$ $line", LogKind.CMD)
        // 接收端最多执行 300 秒；另留 10 秒用于进程收尾和回包。
        run("exec", JSONObject().put("shell", prefsShell).put("line", line)
            .put("timeout", 300), timeoutMs = 310_000) { r ->
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

    private fun showHistory() {
        if (history.isEmpty()) {
            toast("还没有历史命令")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("历史命令")
            .setItems(history.toTypedArray()) { _, which ->
                etCmd.setText(history[which])
                etCmd.setSelection(etCmd.text.length)
            }
            .setNeutralButton("清空记录") { _, _ -> history.clear() }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------ 文件发送

    private fun startPickFiles() {
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return
        }
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun askUploadTarget(uris: List<Uri>) {
        val input = EditText(this)
        input.setPadding(dp(20), dp(12), dp(20), dp(12))
        input.setText(uploadDir.ifEmpty { lastPath })
        val preview = uris.take(3).joinToString("、") { FileTransfer.displayName(this, it) }
        val more = if (uris.size > 3) " 等 ${uris.size} 个文件" else ""
        AlertDialog.Builder(this)
            .setTitle("发送 ${uris.size} 个文件到电脑")
            .setMessage("$preview$more\n\n目标目录（可修改）：")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val dir = input.text.toString().trim().ifEmpty { "." }
                uploadDir = dir
                Prefs.saveUploadDir(this, dir)
                startUpload(uris, dir)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startUpload(uris: List<Uri>, dir: String) {
        transferBox?.dismiss()
        val box = TransferBox(this, "准备发送…") { transferJob?.cancel() }
        transferBox = box
        transferJob = lifecycleScope.launch {
            var succeeded = 0
            var failed = 0
            try {
                for ((i, uri) in uris.withIndex()) {
                    val name = FileTransfer.displayName(this@MainActivity, uri)
                    val remote = joinPath(dir, name)
                    box.setTitle("[${i + 1}/${uris.size}] $name")
                    try {
                        val r = FileTransfer.upload(this@MainActivity, uri, remote) { p ->
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
                if (failed == 0) {
                    log("全部发送完成，共 $succeeded 个文件", LogKind.OK)
                } else {
                    log("发送结束：成功 $succeeded，失败 $failed", LogKind.ERROR)
                }
            } finally {
                box.dismiss()
                if (transferBox === box) transferBox = null
            }
        }
    }

    /** 传输进度对话框：标题 + 进度条 + 详情 + 取消。 */
    private inner class TransferBox(
        private val ctx: Context,
        initialTitle: String,
        private val onCancel: () -> Unit
    ) {
        private val tvTitle = TextView(ctx).apply {
            text = initialTitle
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            textSize = 14.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.accent)
            )
        }
        private val tvDetail = TextView(ctx).apply {
            text = "0%"
            setTextColor(ContextCompat.getColor(ctx, R.color.dim))
            textSize = 12f
        }
        private val dialog: AlertDialog

        init {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(6))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            box.addView(tvTitle, lp)
            box.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(14) })
            box.addView(tvDetail, lp.apply { topMargin = dp(10) })

            dialog = AlertDialog.Builder(ctx)
                .setView(box)
                .setNegativeButton("取消") { _, _ -> }
                .setCancelable(false)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    onCancel()
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    tvDetail.text = "正在取消…"
                }
            }
            dialog.show()
        }

        /** inner class，直接看外层 Activity 的存活状态。 */
        private fun alive(): Boolean =
            !isFinishing && !isDestroyed && dialog.isShowing

        fun setTitle(t: String) {
            if (alive()) tvTitle.text = t
        }

        fun update(p: FileTransfer.Progress) {
            if (!alive()) return
            bar.progress = p.percent
            val total = if (p.total > 0) FileTransfer.humanSize(p.total) else "未知大小"
            tvDetail.text = "${FileTransfer.humanSize(p.done)} / $total" +
                "  ·  ${p.percent}%" +
                "  ·  ${FileTransfer.humanSize(p.speedBps)}/s"
        }

        fun dismiss() {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Exception) {
                // Activity 已销毁时 dismiss 可能抛 IllegalArgumentException
            }
        }
    }

    // ------------------------------------------------------------ 请求封装

    private fun run(
        cmd: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
        block: (JSONObject) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                val r = ConnectionManager.send(cmd, params, timeoutMs)
                if (!r.optBoolean("ok")) {
                    log(r.optString("error", "执行失败"), LogKind.ERROR)
                    return@launch
                }
                block(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e.message ?: "请求失败", LogKind.ERROR)
            }
        }
    }

    private fun pingOnce() {
        if (!ConnectionManager.isConnected()) {
            ConnectionManager.retryNow()
            return
        }
        lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                ConnectionManager.send("ping", timeoutMs = 5000)
                val ms = System.currentTimeMillis() - t0
                log("延迟 ${ms}ms", LogKind.INFO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e.message ?: "ping 失败", LogKind.ERROR)
            }
        }
    }

    // ------------------------------------------------------------ 功能面板

    private fun showInfo() {
        run("info", JSONObject()) { r ->
            val sb = StringBuilder()
            sb.append("主机  ").append(r.optString("host")).append('\n')
            sb.append("用户  ").append(r.optString("user")).append('\n')
            sb.append("系统  ").append(r.optString("os")).append(" / ").append(r.optString("arch")).append('\n')
            sb.append("CPU   ").append(r.optInt("cpus")).append(" 核").append('\n')
            val up = r.optLong("uptime_s", -1)
            if (up >= 0) sb.append("运行  ").append(uptime(up)).append('\n')
            val drives = r.optJSONArray("drives")
            if (drives != null) {
                for (i in 0 until minOf(drives.length(), 6)) {
                    val d = drives.getJSONObject(i)
                    sb.append(d.optString("path"))
                        .append("  剩 ").append(size(d.optLong("free")))
                        .append(" / ").append(size(d.optLong("total"))).append('\n')
                }
            }
            dialog("电脑信息", sb.toString().trimEnd())
        }
    }

    private fun showDirDialog() {
        val input = EditText(this)
        input.setPadding(48, 32, 48, 32)
        input.hint = "C:\\ 或 /home/user"
        input.setText(lastPath)
        AlertDialog.Builder(this)
            .setTitle("浏览目录")
            .setView(input)
            .setPositiveButton("打开") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isNotEmpty()) showListing(p)
            }
            .setNeutralButton("下载文件") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isNotEmpty()) confirmDownload(p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showListing(path: String) {
        run("ls", JSONObject().put("path", path)) { r ->
            val base = r.optString("path", path)
            lastPath = base
            val items = r.optJSONArray("items") ?: JSONArray()
            val labels = ArrayList<String>()
            val isDir = ArrayList<Boolean>()
            val full = ArrayList<String>()
            labels.add("⬆  上级目录")
            isDir.add(true)
            full.add(parentOf(base))
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val dir = it.optBoolean("dir", false)
                val name = it.optString("name")
                labels.add(if (dir) "📁  $name" else "📄  $name    " + size(it.optLong("size", 0)))
                isDir.add(dir)
                full.add(joinPath(base, name))
            }
            AlertDialog.Builder(this)
                .setTitle(base)
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> showListing(full[0])
                        isDir[which] -> showListing(full[which])
                        else -> fileActions(full[which])
                    }
                }
                .setPositiveButton(R.string.upload_here) { _, _ ->
                    uploadDir = base
                    Prefs.saveUploadDir(this, base)
                    pickFiles.launch(arrayOf("*/*"))
                }
                .setNeutralButton(R.string.menu_new_folder) { _, _ -> newFolderDialog(base) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun fileActions(path: String) {
        AlertDialog.Builder(this)
            .setTitle(baseName(path))
            .setItems(arrayOf("下载到手机", "重命名", "删除")) { _, which ->
                when (which) {
                    0 -> download(path)
                    1 -> renameDialog(path)
                    2 -> deleteConfirm(path)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun renameDialog(path: String) {
        val old = baseName(path)
        val input = EditText(this)
        input.setPadding(dp(20), dp(12), dp(20), dp(12))
        input.setText(old)
        input.setSelection(0, old.length)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == old) return@setPositiveButton
                val parent = parentOf(path)
                run("rename", JSONObject().put("path", path).put("to", joinPath(parent, newName))) {
                    log("✓ 已重命名为 $newName", LogKind.OK)
                    showListing(parent)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteConfirm(path: String) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除 ${baseName(path)}？\n删除后无法从手机恢复。")
            .setPositiveButton("删除") { _, _ ->
                run("rm", JSONObject().put("path", path)) {
                    log("✓ 已删除 $path", LogKind.OK)
                    showListing(parentOf(path))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun newFolderDialog(base: String) {
        val input = EditText(this)
        input.setPadding(dp(20), dp(12), dp(20), dp(12))
        input.hint = "文件夹名"
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                run("mkdir", JSONObject().put("path", joinPath(base, name))) {
                    log("✓ 已创建 $name", LogKind.OK)
                    showListing(base)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDownload(path: String) {
        AlertDialog.Builder(this)
            .setTitle("下载到手机")
            .setMessage(path)
            .setPositiveButton("下载") { _, _ -> download(path) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun download(path: String) {
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return
        }
        val name = baseName(path)
        val box = TransferBox(this, name) { transferJob?.cancel() }
        transferBox = box
        transferJob = lifecycleScope.launch {
            log("↓ 下载 $path", LogKind.INFO)
            try {
                val dir = getExternalFilesDir("downloads") ?: filesDir
                dir.mkdirs()
                val dest = java.io.File(dir, name)
                FileTransfer.download(path, dest) { p ->
                    if (!isFinishing && !isDestroyed) runOnUiThread { box.update(p) }
                }
                log("✓ 已保存 ${FileTransfer.humanSize(dest.length())} → ${dest.absolutePath}", LogKind.OK)
                toast("已保存到 ${dest.name}")
            } catch (e: CancellationException) {
                log("已取消下载", LogKind.INFO)
            } catch (e: Exception) {
                log("✗ 下载失败：${e.message}", LogKind.ERROR)
            } finally {
                box.dismiss()
                if (transferBox === box) transferBox = null
            }
        }
    }

    /**
     * 热点场景最常见的失败原因就是电脑防火墙没放行。
     * 连上之后顺手查一下，没放行就明确告诉用户怎么修。
     */
    private fun checkFirewall() {
        // 这一步在连接成功的回调里跑，任何异常都会变成闪退，全程兜底
        try {
            if (!ConnectionManager.onLocalOnlyWifi()) return
        } catch (t: Throwable) {
            android.util.Log.w("LanCmd", "网络状态检查失败: " + t.message, t)
            return
        }
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

    private fun showProcesses() {
        if (!ConnectionManager.isConnected()) {
            toast("未连接，正在重连…")
            ConnectionManager.retryNow()
            return
        }
        ProcessDialog(this, lifecycleScope) { text, kind -> log(text, kind) }.show()
    }

    private fun showClipboard() {
        run("clip", JSONObject().put("action", "get")) { r ->
            val box = EditText(this)
            box.setText(r.optString("text"))
            box.setPadding(48, 32, 48, 32)
            box.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            AlertDialog.Builder(this)
                .setTitle("电脑剪贴板")
                .setView(box)
                .setPositiveButton("写回电脑") { _, _ ->
                    run("clip", JSONObject().put("action", "set").put("text", box.text.toString())) {
                        log("✓ 已写入电脑剪贴板", LogKind.OK)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun doLock() {
        run("lock", JSONObject()) { log("✓ 电脑已锁屏", LogKind.OK) }
    }

    /**
     * 关机 / 重启 / 注销 / 睡眠的二次确认。
     *
     * 电源类操作不可撤销，且快捷键就排在快捷操作第二行，
     * 手指滑动时很容易误触，所以做两级：
     *   第一级 —— 说明后果，按钮是「继续」
     *   第二级 —— 列出具体目标电脑和不可逆提示，按钮才是「确认执行」
     * 第二级的按钮默认不是高亮的，避免连点两下直接穿透。
     */
    private fun confirmPower(action: String, label: String) {
        val irreversible = action in arrayOf("shutdown", "restart", "logoff")
        val warning = if (irreversible) {
            "电脑上未保存的内容会丢失，该操作无法撤销。"
        } else {
            "电脑将进入睡眠，可正常唤醒。"
        }
        // 目标信息在第一级就固定下来。status 会被后台状态回调覆盖，
        // 若等第二级现读，可能已经变成空值。
        val name = status.name.ifBlank { "这台电脑" }
        val addr = if (status.host.isBlank()) "未知地址" else "${status.host}:${status.port}"

        safeDialog {
            setTitle("${label}电脑？")
            setMessage(
                "将向「" + name + "」发送" + label + "指令。\n\n" + warning
            )
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
            "这是最后一步。点「确认${label}」后电脑会立即${label}，" +
                "手机上的连接也会随之断开。"
        } else {
            "确认让这台电脑进入睡眠？"
        }
        safeDialog {
            setTitle("再次确认：${label}")
            setMessage(
                "目标电脑：" + "\n" + name + "\n" + addr + "\n\n" + consequence
            )
            setPositiveButton("确认${label}") { _, _ ->
                log("正在发送" + label + "指令到 " + addr, LogKind.INFO)
                run("power", JSONObject().put("action", action)) {
                    log("✓ 已发送${label}指令", LogKind.OK)
                }
            }
            setNegativeButton("取消", null)
        }
    }

    // ------------------------------------------------------------ 菜单

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.menu_retry))
        popup.menu.add(0, 2, 1, getString(R.string.menu_manual))
        popup.menu.add(0, 3, 2, getString(R.string.menu_disconnect))
        popup.menu.add(0, 4, 3, getString(R.string.menu_forget))
        popup.menu.add(0, 5, 4, getString(R.string.menu_clear))
        popup.menu.add(0, 6, 5, getString(R.string.menu_about))
        popup.menu.add(0, 9, 6, "查看崩溃日志")
        popup.menu.add(0, 8, 7, getString(R.string.menu_send_file))
        popup.menu.add(0, 7, 7, if (LanCmdService.isRunning) "关闭后台常驻" else "开启后台常驻")
        popup.menu.add(0, 20, 8, "锁定电脑")
        popup.menu.add(0, 21, 9, "重启电脑")
        popup.menu.add(0, 22, 10, "关闭电脑")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> ConnectionManager.retryNow()
                2 -> manualDialog()
                3 -> {
                    ConnectionManager.stop()
                    log("已断开（不再自动重连）", LogKind.INFO)
                }
                4 -> {
                    ConnectionManager.forget(this)
                    log("已清除配对记录，下次连接需在电脑端确认", LogKind.INFO)
                }
                5 -> tvLog.text = ""
                6 -> about()
                7 -> toggleResident()
                8 -> startPickFiles()
                9 -> showCrashLog()
                20 -> doLock()
                21 -> confirmPower("restart", "重启")
                22 -> confirmPower("shutdown", "关机")
            }
            true
        }
        popup.show()
    }

    private fun manualDialog() {
        val input = EditText(this)
        input.setPadding(48, 32, 48, 32)
        input.hint = "192.168.1.10 或 192.168.1.10:$TCP_PORT"
        input.setText(Prefs.target(this)?.host ?: "")
        AlertDialog.Builder(this)
            .setTitle("手动输入电脑地址")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val host = raw.substringBefore(":")
                val port = raw.substringAfter(":", TCP_PORT.toString()).toIntOrNull() ?: TCP_PORT
                Prefs.saveTarget(this, Target(host, port, host, ""))
                ConnectionManager.retryNow()
            }
            .setNegativeButton("取消", null)
            .show()
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
    private fun showFirewallHelp() {
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
            )
                .setPositiveButton("知道了", null)
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
        val tv = TextView(this).apply {
            this.text = text
            textSize = 10.5f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        safeDialog {
            setTitle("最近一次崩溃")
                .setView(sv)
                .setPositiveButton("好", null)
                .setNegativeButton("清除记录") { _, _ -> CrashLog.clear(this@MainActivity) }
        }
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("LanCmd ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "手机控制同一局域网内的电脑。\n\n" +
                    "· 电脑端常驻后台，手机断线不影响它\n" +
                    "· 掉线后自动重连，网络恢复立即重试\n" +
                    "· 配对后可自动重连\n" +
                    "· 屏幕、摄像头和按键需在电脑端授权，可随时停止\n\n" +
                    "电脑端：pythonw lan_command_agent.py"
            )
            .setPositiveButton("好", null)
            .show()
    }

    // ------------------------------------------------------------ 状态渲染

    override fun onStatus(s: Status) {
        render(s)
    }

    override fun onEvent(text: String, kind: LogKind) {
        log(text, kind)
    }

    private fun render(s: Status) {
        // 先把状态存下来：界面不可见时也要保证 status 是最新的，
        // 否则后续 confirmPower 之类的动作会读到空 host/name。
        status = s
        try {
            renderInner(s)
        } catch (t: Throwable) {
            android.util.Log.e("LanCmd", "界面刷新失败: " + t.message, t)
        }
    }

    private fun renderInner(s: Status) {
        if (!alive()) return
        val connected = s.state == State.CONNECTED
        when (s.state) {
            State.CONNECTED -> {
                dot.setBackgroundResource(R.drawable.dot_on)
                tvName.text = s.name.ifEmpty { "已连接" }
                tvSub.text = "${s.host}:${s.port}" + if (s.paired) "  ·  免确认直连" else "  ·  已配对"
                tvLatency.visibility = View.VISIBLE
                tvLatency.text = if (s.latencyMs >= 0) "${s.latencyMs}ms" else "在线"
                tvLatency.setTextColor(
                    ContextCompat.getColor(this, if (s.latencyMs in 0..80) R.color.accent else R.color.text)
                )
                showOverlay(false)
                if (!firewallChecked) {
                    firewallChecked = true
                    checkFirewall()
                }
            }
            State.CONNECTING -> {
                dot.setBackgroundResource(R.drawable.dot_wait)
                tvName.text = s.name.ifEmpty { "连接中…" }
                tvSub.text = "正在连接 ${s.host}:${s.port}"
                tvLatency.visibility = View.GONE
                showOverlay(true, "正在连接 ${s.name.ifEmpty { s.host }}…", busy = true)
            }
            State.WAITING -> {
                dot.setBackgroundResource(R.drawable.dot_wait)
                tvName.text = if (s.name.isEmpty()) "等待电脑" else s.name
                tvSub.text = buildString {
                    append("已掉线，自动重连中")
                    if (s.attempt > 0) append(" · 第 ${s.attempt} 次")
                }
                tvLatency.visibility = View.GONE
                val msg = buildString {
                    append(s.message.ifEmpty { "连接已断开，正在重连…" })
                    if (s.nextRetrySec > 0) append("\n${s.nextRetrySec} 秒后重试")
                    if (s.reconnects > 0) append("\n已成功重连 ${s.reconnects} 次")
                }
                showOverlay(true, msg, busy = s.nextRetrySec == 0)
                btnFwHelp.visibility = if (s.needFirewallCheck) View.VISIBLE else View.GONE
            }
            State.STOPPED -> {
                dot.setBackgroundResource(R.drawable.dot_off)
                tvName.text = "已断开"
                tvSub.text = "点击「立即重连」或右上角菜单恢复"
                tvLatency.visibility = View.GONE
                showOverlay(true, "已手动断开，不会自动重连", busy = false)
            }
            State.IDLE -> {
                dot.setBackgroundResource(R.drawable.dot_off)
                tvName.text = getString(R.string.status_idle)
                tvSub.text = getString(R.string.sub_idle)
                tvLatency.visibility = View.GONE
                showOverlay(true, "正在查找局域网内的电脑…", busy = true)
            }
        }
        btnSend.isEnabled = connected
        btnSend.alpha = if (connected) 1f else 0.45f
    }

    private fun showOverlay(visible: Boolean, message: String = "", busy: Boolean = true) {
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            tvOverlay.text = message
            pbOverlay.visibility = if (busy) View.VISIBLE else View.GONE
            btnRetry.visibility = if (busy) View.GONE else View.VISIBLE
            if (status.state != State.WAITING) btnFwHelp.visibility = View.GONE
            btnRetry.text = if (status.state == State.STOPPED) "重新连接" else getString(R.string.retry_now)
        }
    }

    // ------------------------------------------------------------ 日志

    private fun log(text: String, kind: LogKind) {
        if (text.isEmpty()) return
        val color = when (kind) {
            LogKind.CMD -> R.color.accent
            LogKind.ERROR -> R.color.danger
            LogKind.OK -> R.color.ok
            LogKind.INFO -> R.color.warn
            else -> R.color.text
        }
        val line = if (kind == LogKind.ERROR || kind == LogKind.INFO) {
            "${timeFmt.format(Date())} $text"
        } else text
        val span = SpannableString(line)
        span.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, color)),
            0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (tvLog.length() > 0) tvLog.append("\n")
        tvLog.append(span)
        if (tvLog.length() > 300_000) {
            val all = tvLog.text.toString()
            tvLog.text = all.substring(all.length - 200_000)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun note(text: String) {
        log(text, LogKind.INFO)
    }

    /** Activity 还活着才能操作 UI / 弹窗，否则会抛 BadTokenException 直接闪退。 */
    private fun alive(): Boolean = !isFinishing && !isDestroyed

    /**
     * 安全弹窗：生命周期已结束时静默丢弃，绝不抛异常。
     * 连接成功/失败的回调是异步的，用户完全可能在这期间退出界面。
     */
    private fun safeDialog(build: AlertDialog.Builder.() -> AlertDialog.Builder) {
        if (!alive()) return
        try {
            build(AlertDialog.Builder(this)).show()
        } catch (t: Throwable) {
            android.util.Log.e("LanCmd", "弹窗失败: " + t.message, t)
        }
    }

    private fun dialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("好", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun baseName(path: String): String {
        val trimmed = path.trimEnd('/', '\\')
        val idx = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (idx >= 0) trimmed.substring(idx + 1) else trimmed
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun size(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

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

package com.aicode.lancmd

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Base64
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
        setContentView(R.layout.activity_main)

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
        btnSend = findViewById(R.id.btnSend)
        toggleShell = findViewById(R.id.toggleShell)

        toggleShell.check(R.id.btnShellCmd)
        toggleShell.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefsShell = if (checkedId == R.id.btnShellPs) "powershell" else "cmd"
        }

        buildActions()
        wireInput()

        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        btnRetry.setOnClickListener {
            note("正在重连…")
            ConnectionManager.retryNow()
        }
        tvLatency.setOnClickListener { pingOnce() }
        findViewById<View>(R.id.btnUpload).setOnClickListener { startPickFiles() }

        uploadDir = Prefs.uploadDir(this)

        ConnectionManager.addListener(this)
        render(ConnectionManager.snapshot())
        requestNotificationPermission()
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
        render(ConnectionManager.snapshot())
    }

    override fun onStop() {
        ConnectionManager.removeListener(this)
        super.onStop()
    }

    private var prefsShell = "cmd"

    // ------------------------------------------------------------ 快捷操作

    private fun buildActions() {
        val row1 = findViewById<LinearLayout>(R.id.rowActions1)
        val row2 = findViewById<LinearLayout>(R.id.rowActions2)
        val actions = listOf(
            Action(R.drawable.ic_info, getString(R.string.q_info)) { showInfo() },
            Action(R.drawable.ic_folder, getString(R.string.q_files)) { showDirDialog() },
            Action(R.drawable.ic_process, getString(R.string.q_ps)) { showProcesses() },
            Action(R.drawable.ic_clipboard, getString(R.string.q_clip)) { showClipboard() },
            Action(R.drawable.ic_screenshot, getString(R.string.q_shot)) { takeScreenshot() },
            Action(R.drawable.ic_lock, getString(R.string.q_lock)) { doLock() },
            Action(R.drawable.ic_restart, getString(R.string.q_restart)) { confirmPower("restart", "重启") },
            Action(R.drawable.ic_power, getString(R.string.q_shutdown)) { confirmPower("shutdown", "关机") }
        )
        actions.forEachIndexed { i, a ->
            val parent = if (i < 4) row1 else row2
            val item = layoutInflater.inflate(R.layout.view_action, parent, false)
            item.findViewById<ImageView>(R.id.ivIcon).setImageResource(a.icon)
            item.findViewById<TextView>(R.id.tvLabel).text = a.label
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
        run("exec", JSONObject().put("shell", prefsShell).put("line", line)) { r ->
            val out = r.optString("out").trimEnd()
            val err = r.optString("err").trimEnd()
            val code = r.optInt("code", 0)
            if (out.isNotEmpty()) log(out, LogKind.PLAIN)
            if (err.isNotEmpty()) log(err, LogKind.ERROR)
            if (code != 0) log("[exit $code]", LogKind.ERROR)
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
                            runOnUiThread { box.update(p) }
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
        ctx: Context,
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

        fun setTitle(t: String) {
            if (dialog.isShowing) tvTitle.text = t
        }

        fun update(p: FileTransfer.Progress) {
            if (!dialog.isShowing) return
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
                    runOnUiThread { box.update(p) }
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
        if (!ConnectionManager.onLocalOnlyWifi()) return
        run("info", JSONObject()) { r ->
            if (r.optBoolean("firewall_ok", true)) return@run
            log("⚠ 电脑防火墙未放行 47823/47824", LogKind.ERROR)
            AlertDialog.Builder(this)
                .setTitle("电脑防火墙没放行")
                .setMessage(
                    "手机连着电脑热点，但电脑防火墙拦住了连接端口。\n\n" +
                        "在电脑上打开 Start.exe，点「修复防火墙」即可（会请求管理员权限）。\n\n" +
                        "或者用管理员身份运行：\nLanCmdCtl.exe --fix-firewall"
                )
                .setPositiveButton("知道了", null)
                .show()
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

    private fun takeScreenshot() {
        note("正在截取电脑屏幕…")
        run("screenshot", JSONObject(), timeoutMs = 60_000) { r ->
            val bytes = Base64.decode(r.optString("data"), Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                log("截图解码失败", LogKind.ERROR)
                return@run
            }
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            AlertDialog.Builder(this)
                .setTitle("${r.optInt("w")} × ${r.optInt("h")}")
                .setView(iv)
                .setPositiveButton("好", null)
                .show()
            log("✓ 已获取截图 ${r.optInt("w")}×${r.optInt("h")}", LogKind.OK)
        }
    }

    private fun doLock() {
        run("lock", JSONObject()) { log("✓ 电脑已锁屏", LogKind.OK) }
    }

    private fun confirmPower(action: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("确认${label}")
            .setMessage("将向 ${status.name} 发送${label}指令，继续？")
            .setPositiveButton("执行") { _, _ ->
                run("power", JSONObject().put("action", action)) {
                    log("✓ 已发送${label}指令", LogKind.OK)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        popup.menu.add(0, 8, 6, getString(R.string.menu_send_file))
        popup.menu.add(0, 7, 7, if (LanCmdService.isRunning) "关闭后台常驻" else "开启后台常驻")
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

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("LanCmd 1.0")
            .setMessage(
                "手机控制同一局域网内的电脑。\n\n" +
                    "· 电脑端常驻后台，手机断线不影响它\n" +
                    "· 掉线后自动重连，网络恢复立即重试\n" +
                    "· 配对一次后无需再确认\n\n" +
                    "电脑端：pythonw lan_command_agent.py"
            )
            .setPositiveButton("好", null)
            .show()
    }

    // ------------------------------------------------------------ 状态渲染

    override fun onStatus(s: Status) {
        status = s
        render(s)
    }

    override fun onEvent(text: String, kind: LogKind) {
        log(text, kind)
    }

    private fun render(s: Status) {
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

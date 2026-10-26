package com.aicode.lancmd

import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.joinPath
import com.aicode.lancmd.net.parentOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

class StorageActivity : FeatureActivity() {
    private var path = ""
    private var pendingDownload = ""
    private var uploadTarget = ""
    private var entries = listOf<JSONObject>()
    private var visible = listOf<JSONObject>()
    private var loading: Job? = null
    private var transfer: Job? = null
    private lateinit var breadcrumbs: LinearLayout
    private lateinit var disks: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var transferInfo: TextView
    private lateinit var cancelTransfer: com.google.android.material.button.MaterialButton
    private val adapter = FileAdapter()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val target = uploadTarget
            val names = uris.map { FileTransfer.displayName(this, it) }
            if (names.any { !validName(it) }) {
                status("所选文件包含 Windows 无法使用的文件名")
            } else {
                AlertDialog.Builder(this).setTitle("上传 ${uris.size} 个文件？")
                    .setMessage("目标：$target\n\n${names.take(8).joinToString("\n")}${if (names.size > 8) "\n…" else ""}\n\n目标目录中同名文件会被替换。")
                    .setNegativeButton("取消", null).setPositiveButton("上传") { _, _ ->
                        startTransfer("上传 ${uris.size} 个文件") { progress ->
                            uris.zip(names).forEach { (uri, name) ->
                                FileTransfer.upload(this, uri, joinPath(target, name), progress)
                            }
                        }
                    }.show()
            }
        }
    }

    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingDownload
        pendingDownload = ""
        if (uri != null && source.isNotEmpty()) startTransfer("下载 ${source.substringAfterLast('\\').substringAfterLast('/')}") { progress ->
            val cached = File.createTempFile("lancmd-download-", ".tmp", cacheDir)
            try {
                FileTransfer.download(source, cached, progress)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        cached.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("无法写入选择的位置")
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                }
                throw e
            } finally { cached.delete() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = savedInstanceState?.getString("path") ?: ""
        pendingDownload = savedInstanceState?.getString("download") ?: ""
        uploadTarget = savedInstanceState?.getString("upload") ?: ""
        setup("电脑存储", "查看磁盘空间、浏览目录，或选择手机中的文件上传")
        disks = LinearLayout(this)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(disks) })
        breadcrumbs = LinearLayout(this)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(breadcrumbs) })
        val actions = LinearLayout(this)
        listOf("上一级" to { load(parentOf(path)) }, "刷新" to { load(path) }, "新建" to { renameOrCreate(null) },
            "上传" to {
                if (transfer?.isCompleted != false && path.isNotBlank()) {
                    uploadTarget = path
                    pick.launch(arrayOf("*/*"))
                }
            }).forEach { (name, callback) ->
            actions.addView(button(name, callback).apply { minWidth = 0; textSize = 12f }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(actions)
        search = EditText(this).apply {
            hint = "搜索当前目录"
            setSingleLine(true)
            textSize = 14f
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.dim))
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "搜索文件名"
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        transferInfo = label("", 12f, R.color.accent).apply { visibility = View.GONE }
        cancelTransfer = button("取消传输") { transfer?.cancel() }.apply { visibility = View.GONE }
        body.addView(transferInfo)
        body.addView(cancelTransfer)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StorageActivity)
            adapter = this@StorageActivity.adapter
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        task {
            val storage = request("storage")
            val drives = storage.getJSONArray("drives")
            for (i in 0 until drives.length()) disk(drives.getJSONObject(i))
            load(path.ifBlank { storage.optString("home") })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("path", path)
        outState.putString("download", pendingDownload)
        outState.putString("upload", uploadTarget)
        super.onSaveInstanceState(outState)
    }

    private fun disk(drive: JSONObject) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.action_bg)
            contentDescription = "磁盘 ${drive.optString("path")}"
            setOnClickListener { load(drive.getString("path")) }
        }
        box.addView(label(drive.optString("path"), 16f))
        box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = drive.optDouble("percent").toInt().coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(color(if (progress > 90) R.color.warn else R.color.accent))
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        box.addView(label("${FileTransfer.humanSize(drive.optLong("free"))} 可用 / ${FileTransfer.humanSize(drive.optLong("total"))}", 11f, R.color.dim))
        disks.addView(box, LinearLayout.LayoutParams(dp(208), -2).apply { marginEnd = dp(10) })
    }

    private fun load(target: String) {
        loading?.cancel()
        loading = task {
            status("正在读取目录…")
            val result = request("ls", JSONObject().put("path", target))
            path = result.getString("path")
            val items = result.getJSONArray("items")
            entries = (0 until items.length()).map { items.getJSONObject(it) }
                .sortedWith(compareByDescending<JSONObject> { it.optBoolean("dir") }.thenBy { it.optString("name").lowercase() })
            search.setText("")
            crumbs()
            filter()
            status(if (entries.isEmpty()) "此目录为空" else "${entries.size} 项 · 长按目录可管理")
        }
    }

    private fun crumbs() {
        breadcrumbs.removeAllViews()
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val unc = path.startsWith("\\\\")
        var current = if (path.startsWith('/')) "/" else ""
        breadcrumbs.addView(button("主目录") { load("") }.apply { textSize = 12f })
        parts.forEachIndexed { index, part ->
            if (unc && index == 0) {
                current = "\\\\$part"
                return@forEachIndexed
            }
            current = if (current.isEmpty()) "$part\\" else joinPath(current, part)
            val target = current
            breadcrumbs.addView(button(if (unc && index == 1) "${parts[0]} / $part" else part) { load(target) }.apply { textSize = 12f })
        }
    }

    private fun filter() {
        val query = search.text.toString().trim()
        visible = entries.filter { it.optString("name").contains(query, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun fileActions(item: JSONObject, anchor: View) {
        val menu = PopupMenu(this, anchor)
        val client = runCatching { ConnectionManager.sessionClient() }.getOrNull()
        menu.menu.add("在电脑打开")
        if (!item.optBoolean("dir")) menu.menu.add("预览")
        if (!item.optBoolean("dir") && item.optString("name").endsWith(".zip", true)) menu.menu.add("解压 ZIP")
        if (!item.optBoolean("dir")) menu.menu.add("下载到手机")
        menu.menu.add("重命名")
        menu.menu.add("删除")
        val selected = joinPath(path, item.getString("name"))
        menu.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "在电脑打开" -> AlertDialog.Builder(this).setTitle("在电脑打开？")
                    .setMessage("$selected\n\n应用程序会在电脑启动，文件将使用电脑默认应用打开。")
                    .setNegativeButton("取消", null).setPositiveButton("打开") { _, _ -> task {
                        pinnedCall(client, "files", JSONObject().put("action", "open").put("path", selected))
                        status("已向电脑发送打开请求")
                    } }.show()
                "预览" -> FilePreview.show(this, client, selected)
                "解压 ZIP" -> {
                    val destination = EditText(this).apply {
                        setSingleLine(); setText(joinPath(path, item.getString("name").dropLast(4) + "_extracted"))
                    }
                    val dialog = AlertDialog.Builder(this).setTitle("解压到新文件夹")
                        .setMessage("支持 ZIP；最多 2000 项、512 MB。目标必须不存在，不覆盖已有文件。")
                        .setView(destination).setNegativeButton("取消", null).setPositiveButton("解压", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val target = destination.text.toString().trim()
                            if (target.isEmpty()) { destination.error = "请输入目标路径"; return@setOnClickListener }
                            dialog.dismiss()
                            task {
                                status("正在解压 ZIP…")
                                val result = pinnedCall(client, "files", JSONObject().put("action", "extract")
                                    .put("path", selected).put("destination", target), 120_000)
                                status("已解压 ${result.optInt("count")} 项")
                                load(result.getString("path"))
                            }
                        }
                    }
                    dialog.show()
                }
                "下载到手机" -> {
                    if (transfer?.isCompleted != false) { pendingDownload = selected; save.launch(item.getString("name")) }
                }
                "重命名" -> renameOrCreate(item)
                "删除" -> AlertDialog.Builder(this).setTitle("删除${if (item.optBoolean("dir")) "目录" else "文件"}？")
                    .setMessage("$selected\n\n${if (item.optBoolean("dir")) "目录中的全部内容也会删除。" else ""}此操作无法撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> task {
                        request("rm", JSONObject().put("path", selected)); load(path)
                    } }.show()
            }
            true
        }
        menu.show()
    }

    private fun renameOrCreate(item: JSONObject?) {
        val original = item?.optString("name")
        val base = path
        val text = EditText(this).apply { setSingleLine(); setText(original ?: "") }
        val dialog = AlertDialog.Builder(this).setTitle(if (item == null) "新建文件夹" else "重命名")
            .setView(text).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = text.text.toString().trim()
                if (!validName(name)) { text.error = "文件名不能包含 \\ / : * ? \" < > | 或控制字符"; return@setOnClickListener }
                dialog.dismiss()
                task {
                    if (item == null) request("mkdir", JSONObject().put("path", joinPath(base, name)))
                    else request("rename", JSONObject().put("path", joinPath(base, original!!)).put("to", joinPath(base, name)))
                    load(base)
                }
            }
        }
        dialog.show()
    }

    private fun validName(name: String) = name.isNotBlank() && name !in listOf(".", "..") &&
        !name.endsWith('.') && !name.endsWith(' ') && name.none { it.code < 32 || it in "\\/:*?\"<>|" }

    private fun startTransfer(title: String, block: suspend ((FileTransfer.Progress) -> Unit) -> Unit) {
        if (transfer?.isCompleted == false) return
        transferInfo.visibility = View.VISIBLE
        cancelTransfer.visibility = View.VISIBLE
        transferInfo.text = title
        transfer = task {
            try {
                block { p -> runOnUiThread {
                    if (!isDestroyed) transferInfo.text = "$title · ${p.percent}% · ${FileTransfer.humanSize(p.done)} / ${FileTransfer.humanSize(p.total)}"
                } }
                status("$title：完成")
                load(path)
            } catch (e: CancellationException) { status("传输已取消"); throw e }
            finally {
                if (!isDestroyed) { transferInfo.visibility = View.GONE; cancelTransfer.visibility = View.GONE }
            }
        }
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val name: TextView, val detail: TextView, val icon: ImageView, val more: TextView) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = visible.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@StorageActivity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(6), dp(10))
                minimumHeight = dp(72)
                setBackgroundResource(R.drawable.action_bg)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
            }
            val icon = ImageView(this@StorageActivity).apply { imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent)) }
            row.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
            val labels = LinearLayout(this@StorageActivity).apply { orientation = LinearLayout.VERTICAL }
            val name = label("", 14f).apply { maxLines = 2 }
            val detail = label("", 11f, R.color.dim)
            labels.addView(name); labels.addView(detail)
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val more = label("⋮", 24f).apply { gravity = android.view.Gravity.CENTER; contentDescription = "更多文件操作" }
            row.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            return Holder(row, name, detail, icon, more)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = visible[position]
            holder.name.text = item.optString("name")
            holder.icon.setImageResource(if (item.optBoolean("dir")) R.drawable.ic_folder else R.drawable.ic_file)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.optLong("mtime") * 1000))
            holder.detail.text = (if (item.optBoolean("dir")) "文件夹" else FileTransfer.humanSize(item.optLong("size"))) + " · $date"
            holder.row.setOnClickListener { if (item.optBoolean("dir")) load(joinPath(path, item.getString("name"))) else fileActions(item, holder.more) }
            holder.row.setOnLongClickListener { fileActions(item, holder.more); true }
            holder.more.setOnClickListener { fileActions(item, holder.more) }
        }
    }
}

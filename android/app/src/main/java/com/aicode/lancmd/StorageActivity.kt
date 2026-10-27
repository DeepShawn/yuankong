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
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val sourceKey by lazy { Prefs.target(this)?.agentId?.takeIf { it.isNotBlank() } ?: "${origin?.host}:${origin?.port}" }
    private var sourceChanged = false
    private fun checkSource() {
        if (sourceChanged || origin == null || origin?.closed == true || runCatching { ConnectionManager.sessionClient() }.getOrNull() !== origin)
            throw java.io.IOException("电脑连接已改变，请重新打开文件管理；未向新电脑发送操作")
    }
    private suspend fun storageCall(command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
        checkSource()
        return pinnedCall(origin, command, params, timeout)
    }
    private fun sourceReady(): Boolean = try { checkSource(); true } catch(e: Exception) { status(e.message ?: "连接已改变",true); false }

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
                            checkSource()
                            uris.zip(names).forEach { (uri, name) ->
                                FileTransfer.uploadTo(this, uri, joinPath(target, name), origin, progress)
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
                checkSource()
                FileTransfer.downloadFrom(source, cached, origin, progress)
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
        origin; sourceKey
        sourceChanged = savedInstanceState?.getString("sourceKey")?.let { it != sourceKey } ?: false
        path = savedInstanceState?.getString("path") ?: intent.getStringExtra("path") ?: ""
        pendingDownload = savedInstanceState?.getString("download") ?: ""
        uploadTarget = savedInstanceState?.getString("upload") ?: ""
        setup("电脑存储", "查看磁盘空间、浏览目录，或选择手机中的文件上传")
        disks = LinearLayout(this)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(disks) })
        breadcrumbs = LinearLayout(this)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(breadcrumbs) })
        val actions = LinearLayout(this)
        listOf("上一级" to { load(parentOf(path)) }, "刷新" to { load(path) }, "新建" to { newItem() },
            "上传" to {
                if (transfer?.isCompleted != false && path.isNotBlank()) {
                    uploadTarget = path
                    pick.launch(arrayOf("*/*"))
                }
            }).forEach { (name, callback) ->
            actions.addView(button(name, callback).apply { minWidth = 0; textSize = 12f }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(actions)
        val tools = LinearLayout(this)
        tools.addView(button("脚本任务 · 运行记录") { if(sourceReady())ScriptJobsDialog.show(this,origin) }.apply { textSize=12f;minWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(button("U盘弹出 / 断开") { usbActions() }.apply { textSize=12f;minWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
        body.addView(tools)
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
            val storage = storageCall("storage")
            val drives = storage.getJSONArray("drives")
            for (i in 0 until drives.length()) disk(drives.getJSONObject(i))
            load(path.ifBlank { storage.optString("home") })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sourceKey", sourceKey)
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
            val result = storageCall("ls", JSONObject().put("path", target))
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
        if (!sourceReady()) return
        val menu = PopupMenu(this, anchor)
        val client = origin
        menu.menu.add("在电脑打开")
        if (!item.optBoolean("dir")) menu.menu.add("预览")
        if (!item.optBoolean("dir")) menu.menu.add("编辑文本")
        val scriptType=item.optString("name").substringAfterLast('.',"").lowercase()
        if (!item.optBoolean("dir") && scriptType in listOf("bat","cmd","vbs")) menu.menu.add("运行 ${scriptType.uppercase()}")
        if (!item.optBoolean("dir") && item.optString("name").endsWith(".exe",true)) menu.menu.add("应用锁")
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
                "编辑文本" -> task { checkSource(); startActivity(android.content.Intent(this@StorageActivity,TextEditorActivity::class.java).putExtra("path",selected)) }
                "运行 BAT","运行 CMD","运行 VBS" -> ScriptJobsDialog.show(this,client,selected)
                "应用锁" -> task { checkSource(); startActivity(android.content.Intent(this@StorageActivity,AppLocksActivity::class.java).putExtra("path",selected)) }
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
                        storageCall("rm", JSONObject().put("path", selected)); load(path)
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
                    if (item == null) storageCall("mkdir", JSONObject().put("path", joinPath(base, name)))
                    else storageCall("rename", JSONObject().put("path", joinPath(base, original!!)).put("to", joinPath(base, name)))
                    load(base)
                }
            }
        }
        dialog.show()
    }

    private fun newItem() {
        if (!sourceReady()) return
        val names = listOf("文件夹","文本文档 (.txt)","批处理 (.bat)","VBScript (.vbs)","命令脚本 (.cmd)","PowerShell (.ps1)","Markdown (.md)","JSON (.json)","配置文件 (.ini)","其他文本文件","隐藏系统占位文件 · 0–500 GB")
        val suffixes = listOf("","txt","bat","vbs","cmd","ps1","md","json","ini","")
        AlertDialog.Builder(this).setTitle("新建").setItems(names.toTypedArray()) { _, index ->
            if (index == 0) { renameOrCreate(null); return@setItems }
            if (index == names.lastIndex) {
                if(sourceReady())startActivity(android.content.Intent(this,PlaceholderFilesActivity::class.java).putExtra("directory",path))
                return@setItems
            }
            val client = origin
            val base = path
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
            val name = EditText(this).apply { setSingleLine(); setText(if (suffixes[index].isEmpty()) "新文件.txt" else "新建文件.${suffixes[index]}"); contentDescription = "新文件名称" }
            val format = android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(this@StorageActivity,android.R.layout.simple_spinner_dropdown_item,TextEditorActivity.ENCODING_NAMES)
                setSelection(when(suffixes[index]) { "bat","cmd" -> 2; "vbs" -> 4; else -> 0 })
            }
            content.addView(name); content.addView(label("编码（可在编辑页调整）",12f,R.color.dim)); content.addView(format)
            val dialog = AlertDialog.Builder(this).setTitle("新建文本文件").setMessage("创建后直接进入编辑。BAT/CMD 中文通常使用 GBK，VBS 建议 UTF-16 LE；不会自动运行脚本。").setView(content)
                .setNegativeButton("取消",null).setPositiveButton("创建并编辑",null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val filename = name.text.toString().trim()
                if (!validName(filename)) { name.error = "请输入兼容 Windows 的文件名"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                task { try {
                    checkSource()
                    val result = pinnedCall(client,"files",JSONObject().put("action","text_create").put("path",joinPath(base,filename)).put("text","")
                        .put("encoding",TextEditorActivity.ENCODINGS[format.selectedItemPosition]).put("newline","crlf"))
                    checkSource()
                    dialog.dismiss(); load(base)
                    startActivity(android.content.Intent(this@StorageActivity,TextEditorActivity::class.java).putExtra("path",result.getString("path")).putExtra("encoding",TextEditorActivity.ENCODINGS[format.selectedItemPosition]))
                } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true } }
            } }
            dialog.show()
        }.show()
    }

    private fun usbActions() {
        if(!sourceReady())return
        AlertDialog.Builder(this).setTitle("U盘连接管理")
            .setItems(arrayOf("安全弹出 U盘","强制断开 U盘（禁用设备）","恢复已断开的设备")) { _,index->
                if(index==2)startActivity(android.content.Intent(this,DevicesActivity::class.java))
                else ejectUsb(index==1)
            }.setNegativeButton("取消",null).show()
    }

    private fun ejectUsb(force:Boolean=false) {
        if (!sourceReady()) return
        if (transfer?.isCompleted == false) { status("请先等待文件传输完成或取消传输",true);return }
        val client=origin
        task {
            status("正在读取 USB 存储…")
            val result=pinnedCall(client,"devices",timeout=45_000)
            if (!result.optBoolean("admin")) { status("安全弹出需要管理员接收端，请在电脑 Start.exe 选择管理员启动 / 重启",true);return@task }
            val list=result.getJSONArray("devices")
            val items=(0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("kind")=="storage" && it.optBoolean("ejectable") && !it.optBoolean("protected") }
            if (items.isEmpty()) { status("没有可安全弹出的 USB 存储设备");return@task }
            val labels=items.map { device ->
                val paths=device.optJSONArray("paths")
                device.optString("name","U盘")+if(paths==null) "" else " · "+(0 until paths.length()).joinToString(" / ") { paths.optString(it) }
            }
            status("请选择要弹出的 U盘；同一设备的所有分区会一起弹出")
            AlertDialog.Builder(this@StorageActivity).setTitle(if(force)"选择要强制断开的 U盘" else "安全弹出 U盘").setItems(labels.toTypedArray()) { _, index ->
                val selected=items[index]
                AlertDialog.Builder(this@StorageActivity).setTitle(if(force)"强制断开 ${labels[index]}？" else "安全弹出 ${labels[index]}？")
                    .setMessage(if(force)"强制禁用整个 USB 存储设备，会中断所有分区访问。正在写入时可能丢失数据、损坏文件或文件系统。设备可能仍有供电；之后可在设备管理恢复启用。建议先使用安全弹出。" else "请保存文件并关闭使用此设备的程序，等待占位文件创建和文件传输结束。此设备的所有分区都会弹出；设备忙时在安卓返回错误，不强制卸载。")
                    .setNegativeButton("取消",null).setPositiveButton(if(force)"仍然强制断开" else "弹出") { _,_->
                        task {
                            checkSource()
                            if(transfer?.isCompleted==false)throw java.io.IOException("文件正在传输，请先完成或取消")
                            val response=pinnedCall(client,"devices",JSONObject().put("action",if(force)"force_disconnect" else "eject").put("device_id",selected.getString("id")).apply {
                                if(force)put("confirm_data_loss",true)
                            },45_000)
                            val storage=pinnedCall(client,"storage")
                            disks.removeAllViews()
                            val drives=storage.getJSONArray("drives")
                            for(i in 0 until drives.length())disk(drives.getJSONObject(i))
                            load(storage.optString("home"))
                            status(response.optString("notice","设备已安全弹出"))
                        }
                    }.show()
            }.setNegativeButton("取消",null).show()
        }
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
            if(item.optBoolean("hidden"))holder.detail.append(" · 隐藏")
            if(item.optBoolean("system"))holder.detail.append(" · 系统")
            holder.row.setOnClickListener { if (item.optBoolean("dir")) load(joinPath(path, item.getString("name"))) else fileActions(item, holder.more) }
            holder.row.setOnLongClickListener { fileActions(item, holder.more); true }
            holder.more.setOnClickListener { fileActions(item, holder.more) }
        }
    }
}

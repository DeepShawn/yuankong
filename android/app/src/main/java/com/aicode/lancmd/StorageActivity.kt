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
import android.widget.CheckBox
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
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

class StorageActivity : FeatureActivity() {
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val sourceKey by lazy { Prefs.target(this)?.agentId?.takeIf { it.isNotBlank() } ?: "${origin?.host}:${origin?.port}" }
    private var sourceChanged = false
    private fun checkSource() {
        if (sourceChanged || origin == null || origin?.closed == true || runCatching { ConnectionManager.sessionClient() }.getOrNull() !== origin)
            throw java.io.IOException("电脑连接已改变，请重新打开文件管理；未向新电脑发送操作")
    }
    private suspend fun storageCall(command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
        checkSource()
        return if(command in setOf("files","rm","rename","mkdir","put") || command in setOf("file_jobs","placeholders") && params.optString("action")=="create")
            FileDelay.call(this,origin,command,params) { status(it) }
        else pinnedCall(origin, command, params, timeout)
    }
    private fun sourceReady(): Boolean = try { checkSource(); true } catch(e: Exception) { status(e.message ?: "连接已改变",true); false }

    private var path = ""
    private var pendingDownload = ""
    private var uploadTarget = ""
    private var entries = listOf<JSONObject>()
    private var visible = listOf<JSONObject>()
    private var loading: Job? = null
    private var transfer: Job? = null
    private var operationsBusy=false
    private var remoteJob:String?=null
    private var lastResult:String?=null
    private var lastError=false
    private var multi=false
    private val selection=linkedMapOf<String,JSONObject>()
    private var restoreKeys=setOf<String>()
    private lateinit var selectionBar:LinearLayout
    private lateinit var selectionCount:TextView
    private lateinit var multiButton:com.google.android.material.button.MaterialButton
    private val selectionActions=ArrayList<com.google.android.material.button.MaterialButton>()
    private var pendingTreeItems=listOf<JSONObject>()
    private var pendingTreeBase=""
    private lateinit var breadcrumbs: LinearLayout
    private lateinit var disks: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var transferInfo: TextView
    private lateinit var cancelTransfer: com.google.android.material.button.MaterialButton
    private lateinit var transferRow:LinearLayout
    private val adapter = FileAdapter()
    private fun itemKey(item:JSONObject)=item.optString("name")+"\u0000"+item.optString("file_id",item.optString("mtime")+":"+item.optLong("size"))
    private val pickTree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri->
        val targets=pendingTreeItems;val base=pendingTreeBase
        pendingTreeItems=emptyList();pendingTreeBase=""
        if(uri!=null && targets.isNotEmpty() && sourceReady())downloadSelected(base,targets,uri)
    }

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
        if(!sourceChanged) {
            restoreKeys=savedInstanceState?.getStringArrayList("selection").orEmpty().toSet()
            multi=savedInstanceState?.getBoolean("multi") ?: false
            pendingTreeBase=savedInstanceState?.getString("treeBase").orEmpty()
            val pending=runCatching { JSONArray(savedInstanceState?.getString("treeItems") ?: "[]") }.getOrElse { JSONArray() }
            pendingTreeItems=(0 until pending.length()).map { pending.getJSONObject(it) }
        }
        path = savedInstanceState?.getString("path") ?: intent.getStringExtra("path") ?: ""
        pendingDownload = savedInstanceState?.getString("download") ?: ""
        uploadTarget = savedInstanceState?.getString("upload") ?: ""
        setup("电脑存储", "查看磁盘空间、浏览目录，或选择手机中的文件上传")
        statusText.maxLines=5;statusText.ellipsize=android.text.TextUtils.TruncateAt.END
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
        tools.addView(button("占位文件") { openPlaceholder() }.apply { textSize=12f;minWidth=0;setPadding(dp(6),paddingTop,dp(6),paddingBottom);contentDescription="创建 0–500 GB 隐藏系统占位文件" },LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(button("文件任务") { showFileJobs() }.apply { textSize=12f;minWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(button("延迟计划") { showFilePlans() }.apply { textSize=12f;minWidth=0 },LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(button("U盘管理") { usbActions() }.apply { textSize=12f;minWidth=0;setPadding(dp(6),paddingTop,dp(6),paddingBottom);contentDescription="U盘弹出 / 断开" },LinearLayout.LayoutParams(0,-2,1f))
        for(i in 0 until tools.childCount)(tools.getChildAt(i) as? com.google.android.material.button.MaterialButton)?.apply { setPadding(dp(6),paddingTop,dp(6),paddingBottom);setSingleLine() }
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
        val searchRow=LinearLayout(this)
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(48),1f))
        multiButton=button("多选") { if(!operationsBusy){multi=!multi;if(!multi)selection.clear();updateSelection();adapter.notifyDataSetChanged()} }.apply { minWidth=0;textSize=12f;setPadding(dp(4),0,dp(4),0);setSingleLine() }
        searchRow.addView(button("延迟 ${FileDelay.seconds(this)}s") { FileDelay.choose(this) { (searchRow.getChildAt(1) as? android.widget.TextView)?.text="延迟 ${FileDelay.seconds(this)}s" } }.apply { minWidth=0;textSize=11f;setPadding(dp(4),0,dp(4),0);contentDescription="设置文件操作延迟" },LinearLayout.LayoutParams(dp(80),dp(48)))
        searchRow.addView(multiButton,LinearLayout.LayoutParams(dp(84),dp(48)))
        body.addView(searchRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) })
        selectionBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        selectionCount=label("",12f,R.color.accent);selectionBar.addView(selectionCount)
        val selectRow=LinearLayout(this)
        listOf("全选结果" to { chooseAll(false) },"反选" to { chooseAll(true) },"清空" to { selection.clear();updateSelection();adapter.notifyDataSetChanged() }).forEach { (name,callback)->
            val key=button(name) { if(!operationsBusy)callback() }.apply { minWidth=0;textSize=12f };selectionActions.add(key);selectRow.addView(key,LinearLayout.LayoutParams(0,-2,1f))
        };selectionBar.addView(selectRow)
        val batchRow=LinearLayout(this)
        listOf("复制" to "copy","移动" to "move","删除" to "delete","下载" to "download").forEach { (name,operation)->
            val key=button(name) { beginBatch(operation) }.apply { minWidth=0;textSize=12f;contentDescription="批量$name" };selectionActions.add(key);batchRow.addView(key,LinearLayout.LayoutParams(0,-2,1f))
        };selectionBar.addView(batchRow);body.addView(selectionBar)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        transferInfo = label("", 12f, R.color.accent).apply { visibility = View.GONE }
        cancelTransfer = button("取消操作") {
            val job=remoteJob
            if(job==null)transfer?.cancel() else task {
                cancelTransfer.isEnabled=false
                try { storageCall("file_jobs",JSONObject().put("action","cancel").put("job_id",job));status("已请求停止，等待当前文件清理；已完成操作保留") }
                catch(e:Exception){cancelTransfer.isEnabled=true;throw e}
            }
        }.apply { visibility = View.GONE;minWidth=0;textSize=12f }
        transferRow=LinearLayout(this).apply { visibility=View.GONE;gravity=android.view.Gravity.CENTER_VERTICAL }
        transferRow.addView(transferInfo,LinearLayout.LayoutParams(0,-2,1f));transferRow.addView(cancelTransfer,LinearLayout.LayoutParams(dp(100),-2));body.addView(transferRow,1)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StorageActivity)
            adapter = this@StorageActivity.adapter
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        updateSelection()
        task {
            val storage = storageCall("storage")
            val drives = storage.getJSONArray("drives")
            for (i in 0 until drives.length()) disk(drives.getJSONObject(i))
            load(path.ifBlank { storage.optString("home") })
            val jobs=runCatching { storageCall("file_jobs") }.getOrNull()?.optJSONArray("jobs")
            if(jobs!=null && !operationsBusy) {
                val active=(0 until jobs.length()).map { jobs.getJSONObject(it) }.firstOrNull { !it.has("finished") }
                if(active!=null)trackFileJob(active.getString("id"),active.optString("operation"),path)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sourceKey", sourceKey)
        outState.putString("path", path)
        outState.putString("download", pendingDownload)
        outState.putString("upload", uploadTarget)
        outState.putBoolean("multi",multi);outState.putStringArrayList("selection",ArrayList(selection.keys+restoreKeys))
        outState.putString("treeBase",pendingTreeBase);outState.putString("treeItems",JSONArray(pendingTreeItems).toString())
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

    private fun load(target: String, notice: String? = null) {
        loading?.cancel()
        loading = task {
            status("正在读取目录…")
            val result = storageCall("ls", JSONObject().put("path", target))
            val changed=path!=result.getString("path")
            if(changed && restoreKeys.isEmpty()) { selection.clear();multi=false;lastResult=null;lastError=false;clearTopResults() }
            path = result.getString("path")
            val items = result.getJSONArray("items")
            entries = (0 until items.length()).map { items.getJSONObject(it) }
                .sortedWith(compareByDescending<JSONObject> { it.optBoolean("dir") }.thenBy { it.optString("name").lowercase() })
            val current=entries.associateBy { itemKey(it) }
            selection.keys.retainAll(current.keys)
            for(id in selection.keys.toList())selection[id]=current.getValue(id)
            for(id in restoreKeys)current[id]?.let { selection[id]=it };restoreKeys=emptySet()
            search.setText("")
            crumbs()
            filter()
            if(notice!=null)lastResult=notice
            status(lastResult ?: if(entries.isEmpty())"此目录为空" else "${entries.size} 项 · 长按多选，⋮ 单项操作",lastError)
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
        if(::selectionBar.isInitialized)updateSelection()
    }

    private fun fileActions(item: JSONObject, anchor: View) {
        if (!sourceReady()) return
        if(operationsBusy){status("文件操作进行中，请等待完成或取消",true);return}
        val menu = PopupMenu(this, anchor)
        val client = origin
        menu.menu.add("在电脑打开")
        if (!item.optBoolean("dir")) menu.menu.add("预览")
        val office=OfficeDocumentActivity.supports(item.optString("name"))
        if (!item.optBoolean("dir")) menu.menu.add(if(office)"手机 WPS 预览 / 编辑" else "编辑文本")
        if(office)menu.menu.add("电脑 WPS 打开")
        menu.menu.add("在当前窗口下方打开")
        menu.menu.add("修改日期")
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
                        FileDelay.call(this@StorageActivity,client, "files", JSONObject().put("action", "open").put("path", selected))
                        status("已向电脑发送打开请求")
                    } }.show()
                "预览" -> if(office)openOffice(selected,item.optString("file_id")) else FilePreview.show(this, client, selected)
                "手机 WPS 预览 / 编辑" -> openOffice(selected,item.optString("file_id"))
                "电脑 WPS 打开","在当前窗口下方打开" -> task {
                    val response=FileDelay.call(this@StorageActivity,client,"files",JSONObject().put("action","open").put("path",selected).put("application",if(it.title=="电脑 WPS 打开")"wps" else "default").put("background",it.title=="在当前窗口下方打开")) { status(it) }
                    status(response.optString("notice","已向电脑发送打开请求"))
                }
                "修改日期" -> modifyDate(item,selected)
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
                                val result = FileDelay.call(this@StorageActivity,client, "files", JSONObject().put("action", "extract")
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
                "删除" -> deleteItem(item,selected)
            }
            true
        }
        menu.show()
    }

    private fun deleteItem(item:JSONObject,selected:String) {
        val base=path
        AlertDialog.Builder(this).setTitle("删除${if(item.optBoolean("dir"))"目录" else "文件"}？")
            .setMessage("$selected\n\n${if(item.optBoolean("dir"))"目录中的全部内容也会删除。" else ""}此操作无法撤销。结果显示在页面顶部。")
            .setNegativeButton("取消",null).setPositiveButton("删除") { _,_->
                if(operationsBusy)return@setPositiveButton
                operationsBusy=true;clearTopResults();updateSelection()
                task {
                    try {
                        storageCall("rm",JSONObject().put("path",selected).apply { item.optString("file_id").takeIf { it.isNotEmpty() }?.let { put("file_id",it) } })
                        lastResult="删除完成：${item.optString("name")}";lastError=false
                        reportTopResults(lastResult!!,listOf("成功 · $selected"))
                        if(path==base)load(base,lastResult)
                    } catch(e:CancellationException){throw e}
                    catch(e:Exception){lastResult="删除失败：${e.message}";lastError=true;reportTopResults(lastResult!!,listOf("失败 · $selected：${e.message}"),true)}
                    finally { operationsBusy=false;updateSelection() }
                }
            }.show()
    }

    private fun openPlaceholder() {
        if(!sourceReady())return
        if(path.isBlank()){status("请先选择电脑文件夹",true);return}
        if(operationsBusy){status("请等待当前文件操作完成或取消",true);return}
        startActivity(android.content.Intent(this,PlaceholderFilesActivity::class.java).putExtra("directory",path))
    }
    private fun toggleSelection(item:JSONObject) {
        if(operationsBusy)return
        val key=itemKey(item)
        if(selection.containsKey(key))selection.remove(key)
        else if(selection.size>=100){status("每批最多选择 100 项，请先筛选",true);adapter.notifyDataSetChanged();return}
        else selection[key]=item
        updateSelection();adapter.notifyDataSetChanged()
    }
    private fun chooseAll(invert:Boolean) {
        val next=selection.toMutableMap()
        visible.forEach { if(invert && next.containsKey(itemKey(it)))next.remove(itemKey(it)) else next[itemKey(it)]=it }
        if(next.size>100){status("选择结果超过 100 项，请筛选或分批处理",true);return}
        selection.clear();selection.putAll(next);updateSelection();adapter.notifyDataSetChanged()
    }
    private fun updateSelection() {
        if(!::selectionBar.isInitialized)return
        selectionBar.visibility=if(multi)View.VISIBLE else View.GONE
        multiButton.text=if(multi)"退出多选" else "多选";multiButton.isEnabled=!operationsBusy
        selectionCount.text="已选 ${selection.size} 项 · 当前显示 ${visible.size} 项（搜索外的已选项仍保留）"
        selectionActions.forEach { it.isEnabled=!operationsBusy && (it.contentDescription?.startsWith("批量")!=true || selection.isNotEmpty()) }
        (disks.parent as? View)?.visibility=if(multi || operationsBusy)View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }
    private fun beginBatch(operation:String) {
        if(operationsBusy || selection.isEmpty() || !sourceReady())return
        val targets=selection.values.map { JSONObject(it.toString()) };val base=path
        if(operation=="download") {
            pendingTreeBase=base;pendingTreeItems=targets
            pickTree.launch(null);return
        }
        if(targets.any { it.optString("file_id").length!=64 }){status("批量操作需要文件版本，请更新 Windows 接收端并刷新目录",true);return}
        fun confirm(destination:String="") {
            val title=when(operation){"copy"->"复制";"move"->"移动";else->"删除"}
            AlertDialog.Builder(this).setTitle("$title 所选 ${targets.size} 项？")
                .setMessage(targets.take(8).joinToString("\n") { it.optString("name") }+(if(targets.size>8)"\n…" else "")+
                    (if(destination.isNotBlank())"\n\n目标：$destination\n目标必须不存在，不覆盖同名文件或目录。" else "\n\n文件夹中的内容也会删除，无法撤销。")+
                    "\n停止操作只停止后续步骤，已完成的复制、移动或删除不会撤销。结果显示在页面顶部。")
                .setNegativeButton("取消",null).setPositiveButton(title) { _,_->
                    task {
                        checkSource()
                        if(operationsBusy)return@task
                        operationsBusy=true;lastResult=null;lastError=false;clearTopResults();updateSelection();status("正在提交$title 文件任务…")
                        val specs=JSONArray(targets.map { JSONObject().put("path",joinPath(base,it.getString("name"))).put("file_id",it.getString("file_id")) })
                        val result=try { storageCall("file_jobs",JSONObject().put("action","create").put("operation",operation).put("items",specs).apply { if(destination.isNotBlank())put("destination",destination) }) }
                            finally { operationsBusy=false;updateSelection() }
                        trackFileJob(result.getJSONObject("job").getString("id"),operation,base)
                    }
                }.show()
        }
        if(operation=="delete")confirm() else RemoteDirectoryPicker(this,origin).show(base) { destination->if(sourceReady())confirm(destination) }
    }
    private fun operationName(operation:String)=when(operation){"copy"->"复制";"move"->"移动";"delete"->"删除";else->operation}
    private fun fileResults(job:JSONObject):Pair<String,List<String>> {
        val list=job.getJSONArray("items");var success=0;var failed=0;var cancelled=0;var warnings=0
        val lines=(0 until list.length()).map { i->
            val item=list.getJSONObject(i);val label=item.getString("path")
            val line=when(item.optString("status")) {
                "completed"->{success++;"成功 · $label"+(if(item.has("destination"))" → ${item.getString("destination")}" else "")}
                "failed"->{failed++;"失败 · $label：${item.optString("error")}"}
                else->{cancelled++;"取消/未执行 · $label"}
            }
            if(item.has("cleanup_warning")){warnings++;line+"；清理提示：${item.optString("cleanup_warning")}"}else line
        }
        lastError=failed>0 || warnings>0
        return "${operationName(job.optString("operation"))}：成功 $success，失败 $failed，取消/未执行 $cancelled"+(if(warnings>0)"，清理提示 $warnings" else "") to lines
    }
    private fun showFileJobs() {
        if(!sourceReady())return
        if(operationsBusy){status("当前文件任务仍在运行，可用上方取消按钮停止后续操作");return}
        task {
            val response=storageCall("file_jobs");val jobs=response.getJSONArray("jobs")
            val active=(0 until jobs.length()).map { jobs.getJSONObject(it) }.firstOrNull { !it.has("finished") }
            if(active!=null){trackFileJob(active.getString("id"),active.optString("operation"),path);return@task}
            val lines=ArrayList<String>();var failures=false
            for(i in 0 until jobs.length()) {
                val result=fileResults(jobs.getJSONObject(i));lines.add(result.first);lines.addAll(result.second);failures=failures || lastError
            }
            lastResult=if(jobs.length()==0)"暂无文件任务" else "最近 ${jobs.length()} 个文件任务（逐项结果见下方）";lastError=failures
            if(response.optString("warning").isNotBlank()){lines.add(response.optString("warning"));lastError=true}
            reportTopResults(lastResult!!,lines,lastError)
        }
    }
    private fun trackFileJob(id:String,operation:String,base:String) {
        if(operationsBusy)return
        operationsBusy=true;remoteJob=id;lastResult=null;lastError=false;clearTopResults();updateSelection()
        transferRow.visibility=View.VISIBLE;transferInfo.visibility=View.VISIBLE;cancelTransfer.visibility=View.VISIBLE;cancelTransfer.isEnabled=true
        transfer=task {
            try {
                while(true) {
                    val response=storageCall("file_jobs");val jobs=response.getJSONArray("jobs")
                    val job=(0 until jobs.length()).map { jobs.getJSONObject(it) }.firstOrNull { it.optString("id")==id }
                        ?: throw java.io.IOException("文件任务记录已失效，请刷新任务列表")
                    val children=job.getJSONArray("items")
                    if(job.has("finished")) {
                        val result=fileResults(job);lastResult=result.first;reportTopResults(result.first,result.second,lastError)
                        if(path==base) {
                            for(i in 0 until children.length()) {
                                val item=children.getJSONObject(i)
                                if(item.optString("status")=="completed") {
                                    val name=item.getString("path").substringAfterLast('\\').substringAfterLast('/')
                                    selection.remove(name+"\u0000"+item.optString("file_id"))
                                }
                            }
                            load(base,lastResult)
                        }
                        break
                    }
                    val done=(0 until children.length()).count { children.getJSONObject(it).optString("status") in listOf("completed","failed","cancelled") }
                    val running=(0 until children.length()).map { children.getJSONObject(it) }.firstOrNull { it.optString("status")=="running" }
                    val text="正在${operationName(operation)} · 已处理 $done/${children.length()}"+(running?.let { "\n${it.optString("path")} · ${FileTransfer.humanSize(it.optLong("bytes"))} / ${FileTransfer.humanSize(it.optLong("total_bytes"))}" } ?: "")
                    status(text);transferInfo.text=text;delay(800)
                }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { lastResult="文件任务反馈失败：${e.message}；可重新打开文件任务查看电脑上的状态";lastError=true;status(lastResult!!,true) }
            finally {
                remoteJob=null;operationsBusy=false;transferRow.visibility=View.GONE;transferInfo.visibility=View.GONE;cancelTransfer.visibility=View.GONE;updateSelection()
            }
        }
    }
    private fun downloadSelected(base:String,targets:List<JSONObject>,tree:android.net.Uri) {
        startTransfer("批量下载") { progress->
            val results=ArrayList<String>();var success=0;var failed=0;var processed=0
            val client=origin ?: throw java.io.IOException("未连接")
            try {
                for((index,item) in targets.withIndex()) {
                    checkSource();val source=joinPath(base,item.getString("name"))
                    try {
                        status("下载 ${index+1}/${targets.size}：${item.optString("name")}")
                        com.aicode.lancmd.net.TreeDownloads(this,client).download(source,item,tree) { file,p->
                            progress(p);runOnUiThread { if(!isDestroyed)status("下载 ${index+1}/${targets.size} · ${file.substringAfterLast('\\').substringAfterLast('/')} · ${p.percent}%") }
                        }
                        success++;if(path==base)selection.remove(itemKey(item));results.add("成功 · $source")
                    } catch(e:CancellationException){throw e}
                    catch(e:Exception){failed++;results.add("失败 · $source：${e.message}"+e.suppressed.joinToString("") { "；${it.message}" })}
                    processed++
                    if(client.closed || runCatching { ConnectionManager.sessionClient() }.getOrNull()!==client)break
                }
            } finally {
                val remaining=targets.size-processed
                lastResult="下载：成功 $success，失败 $failed，未执行 $remaining";lastError=failed>0
                reportTopResults(lastResult!!,results,lastError)
            }
        }
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
        if(operationsBusy){status("请先等待或取消文件操作",true);return}
        val names = listOf("文件夹","文本文档 (.txt)","批处理 (.bat)","VBScript (.vbs)","命令脚本 (.cmd)","PowerShell (.ps1)","Markdown (.md)","JSON (.json)","配置文件 (.ini)","其他文本文件","隐藏系统占位文件 · 0–500 GB")
        val suffixes = listOf("","txt","bat","vbs","cmd","ps1","md","json","ini","")
        AlertDialog.Builder(this).setTitle("新建").setItems(names.toTypedArray()) { _, index ->
            if (index == 0) { renameOrCreate(null); return@setItems }
            if (index == names.lastIndex) {
                openPlaceholder()
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
                    val result = FileDelay.call(this@StorageActivity,client,"files",JSONObject().put("action","text_create").put("path",joinPath(base,filename)).put("text","")
                        .put("encoding",TextEditorActivity.ENCODINGS[format.selectedItemPosition]).put("newline","crlf"))
                    checkSource()
                    dialog.dismiss(); load(base)
                    startActivity(android.content.Intent(this@StorageActivity,TextEditorActivity::class.java).putExtra("path",result.getString("path")).putExtra("encoding",TextEditorActivity.ENCODINGS[format.selectedItemPosition]))
                } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true } }
            } }
            dialog.show()
        }.show()
    }

    private fun openOffice(selected:String,version:String) {
        if(sourceReady())startActivity(android.content.Intent(this,OfficeDocumentActivity::class.java).putExtra("path",selected).putExtra("file_id",version))
    }
    private fun modifyDate(item:JSONObject,selected:String) {
        val format=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.getDefault()).apply { isLenient=false }
        val value=styleInput(EditText(this).apply { setSingleLine();setText(format.format(Date(item.optLong("mtime")*1000)));contentDescription="文件修改日期" })
        val dialog=AlertDialog.Builder(this).setTitle("修改文件日期").setMessage("电脑文件的最后修改时间，按手机本地时区输入。格式 yyyy-MM-dd HH:mm:ss。操作延迟 ${FileDelay.seconds(this)} 秒。")
            .setView(value).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val date=runCatching { val pos=java.text.ParsePosition(0);val d=format.parse(value.text.toString(),pos);check(pos.index==value.length());d }.getOrNull()
            if(date==null || date.time !in 0L..4_102_444_800_000L){value.error="请输入 1970–2100 年的有效日期";return@setOnClickListener}
            dialog.dismiss();task {
                storageCall("files",JSONObject().put("action","mtime").put("path",selected).put("modified",date.time/1000.0).apply { item.optString("file_id").takeIf { it.isNotBlank() }?.let { put("file_id",it) } })
                load(path,"修改日期已保存：${item.optString("name")}")
            }
        } };dialog.show()
    }
    private fun showFilePlans() {
        if(!sourceReady())return
        task {
            val response=pinnedCall(origin,"file_schedule");val jobs=response.getJSONArray("jobs")
            val items=(0 until jobs.length()).map { jobs.getJSONObject(it) }
            val names=mapOf("waiting" to "等待执行","running" to "执行中","completed" to "已完成","failed" to "失败","cancelled" to "已取消")
            reportTopResults("${items.size} 个延迟文件计划",items.map { "${names[it.optString("status")] ?: it.optString("status")} · ${it.optString("command")} · ${it.optString("path")} ${it.optString("error")}" },items.any { it.optString("status")=="failed" })
            val pending=items.filter { it.optString("status")=="waiting" }
            if(pending.isNotEmpty())AlertDialog.Builder(this@StorageActivity).setTitle("选择要取消的文件计划")
                .setItems(pending.map { it.optString("command")+" · "+it.optString("path") }.toTypedArray()) { _,index-> task {
                    pinnedCall(origin,"file_schedule",JSONObject().put("action","cancel").put("job_id",pending[index].getString("id")));status("已取消尚未执行的文件计划")
                } }.setNegativeButton("关闭",null).show()
        }
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
            var receiverVersion=""
            if(force) {
                val caps=pinnedCall(client,"capabilities")
                receiverVersion=caps.optString("version")
                if(!supportsUsbConnection(caps)){status(usbUpgradeMessage(caps.optString("version")),true);return@task}
            }
            val result=pinnedCall(client,"devices",timeout=45_000)
            if(force && result.optJSONArray("actions")?.let { actions->(0 until actions.length()).none { actions.optString(it)=="force_disconnect" } }==true) { status(usbUpgradeMessage(receiverVersion),true);return@task }
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
                            val response=try { pinnedCall(client,"devices",JSONObject().put("action",if(force)"force_disconnect" else "eject").put("device_id",selected.getString("id")).apply {
                                if(force)put("confirm_data_loss",true)
                            },if(force)120_000 else 45_000) } catch(e:Exception) {
                                if(force && (e.message.orEmpty().contains("无效的设备管理操作") || e.message.orEmpty().contains("未知命令") || e.message.orEmpty().contains("请完整更新 Windows")))throw java.io.IOException(usbUpgradeMessage(receiverVersion))
                                throw e
                            }
                            val storage=pinnedCall(client,"storage")
                            disks.removeAllViews()
                            val drives=storage.getJSONArray("drives")
                            for(i in 0 until drives.length())disk(drives.getJSONObject(i))
                            load(storage.optString("home"),response.optString("notice","设备已安全弹出"))
                        }
                    }.show()
            }.setNegativeButton("取消",null).show()
        }
    }

    private fun validName(name: String) = name.isNotBlank() && name !in listOf(".", "..") &&
        !name.endsWith('.') && !name.endsWith(' ') && name.none { it.code < 32 || it in "\\/:*?\"<>|" }

    private fun startTransfer(title: String, block: suspend ((FileTransfer.Progress) -> Unit) -> Unit) {
        if (operationsBusy || transfer?.isCompleted == false) return
        operationsBusy=true;lastResult=null;lastError=false;clearTopResults();updateSelection()
        transferRow.visibility=View.VISIBLE;cancelTransfer.isEnabled=true
        transferInfo.visibility = View.VISIBLE
        cancelTransfer.visibility = View.VISIBLE
        transferInfo.text = title
        transfer = task {
            try {
                FileDelay.waitLocal(this@StorageActivity,origin) { status(it) }
                block { p -> runOnUiThread {
                    if (!isDestroyed) transferInfo.text = "$title · ${p.percent}% · ${FileTransfer.humanSize(p.done)} / ${FileTransfer.humanSize(p.total)}"
                } }
                status(lastResult ?: "$title：完成",lastError)
                load(path,lastResult ?: "$title：完成")
            } catch (e: CancellationException) { status(lastResult ?: "传输已取消，当前未完成手机文件已请求清理"); throw e }
            catch(e:Exception){lastResult="$title：${e.message}";lastError=true;status(lastResult!!,true)}
            finally {
                operationsBusy=false
                if (!isDestroyed) { transferRow.visibility=View.GONE;transferInfo.visibility = View.GONE; cancelTransfer.visibility = View.GONE;updateSelection() }
            }
        }
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val name: TextView, val detail: TextView, val icon: ImageView, val more: TextView,val check:CheckBox) : RecyclerView.ViewHolder(row)
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
            val check=styleCheck(CheckBox(this@StorageActivity));row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
            val more = label("⋮", 24f).apply { gravity = android.view.Gravity.CENTER; contentDescription = "更多文件操作" }
            row.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            return Holder(row, name, detail, icon, more,check)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = visible[position]
            holder.name.text = item.optString("name")
            holder.icon.setImageResource(if (item.optBoolean("dir")) R.drawable.ic_folder else R.drawable.ic_file)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.optLong("mtime") * 1000))
            holder.detail.text = (if (item.optBoolean("dir")) "文件夹" else FileTransfer.humanSize(item.optLong("size"))) + " · $date"
            if(item.optBoolean("hidden"))holder.detail.append(" · 隐藏")
            if(item.optBoolean("system"))holder.detail.append(" · 系统")
            holder.check.setOnCheckedChangeListener(null);holder.check.visibility=if(multi)View.VISIBLE else View.GONE;holder.check.isChecked=selection.containsKey(itemKey(item));holder.check.isEnabled=!operationsBusy
            holder.check.contentDescription="选择文件 ${item.optString("name")}";holder.check.setOnCheckedChangeListener { _,_->toggleSelection(item) }
            holder.more.visibility=if(multi)View.GONE else View.VISIBLE
            holder.row.setOnClickListener { if(!operationsBusy){if(multi)toggleSelection(item) else if (item.optBoolean("dir")) load(joinPath(path, item.getString("name"))) else fileActions(item, holder.more)} }
            holder.row.setOnLongClickListener { if(!operationsBusy){multi=true;toggleSelection(item)};true }
            holder.more.setOnClickListener { fileActions(item, holder.more) }
        }
    }
}

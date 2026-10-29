package com.aicode.lancmd

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.ui.unit.dp
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.joinPath
import com.aicode.lancmd.net.parentOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperCheckbox
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

/** 电脑磁盘、目录浏览与文件传输。界面使用 miuix。 */
class StorageActivity : MiuixActivity() {
    /** 一块磁盘在界面上的样子。 */
    internal class Disk(val path: String, val percent: Int, val detail: String, val tight: Boolean)

    /** 面包屑的一级。 */
    internal class Crumb(val label: String, val target: String)

    private val sourceKey by lazy {
        Prefs.target(this)?.agentId?.takeIf { it.isNotBlank() } ?: "${origin?.host}:${origin?.port}"
    }
    private var sourceChanged = false

    internal var path by mutableStateOf("")
    internal var disks by mutableStateOf<List<Disk>>(emptyList())
    internal var crumbs by mutableStateOf<List<Crumb>>(emptyList())
    internal var query by mutableStateOf("")
    internal var visible by mutableStateOf<List<JSONObject>>(emptyList())
    internal var multi by mutableStateOf(false)
    internal var selectionSummary by mutableStateOf("")
    internal var listingBusy by mutableStateOf(false)
    private var listingMessage by mutableStateOf("正在读取磁盘与目录…")
    internal var operationsBusy by mutableStateOf(false)
    internal var transferText by mutableStateOf("")
    /** 批量操作的逐项结果，和旧版顶部的「展开逐项结果」一致。 */
    internal var results by mutableStateOf<List<String>>(emptyList())
    private var resultsExpanded by mutableStateOf(false)
    private var delaySeconds by mutableStateOf(0)
    private var toolsExpanded by mutableStateOf(false)
    private var disksExpanded by mutableStateOf(false)

    private val highlightName by lazy { intent.getStringExtra("highlight").orEmpty() }
    private val highlightFolder by lazy { intent.getStringExtra("path").orEmpty() }

    private var entries = listOf<JSONObject>()
    private var pendingDownload = ""
    private var uploadTarget = ""
    private var loading: Job? = null
    private var listingSequence=0
    private var transfer: Job? = null
    private var remoteJob: String? = null
    private var forceConfirmation by mutableStateOf<JSONObject?>(null)
    private var forceConfirmSending by mutableStateOf(false)
    private var lastResult: String? = null
    private var lastError = false
    private val selection = mutableStateMapOf<String, JSONObject>()
    private var restoreKeys = setOf<String>()
    private var pendingTreeItems = listOf<JSONObject>()
    private var pendingTreeBase = ""

    private fun itemKey(item: JSONObject) =
        item.optString("name") + "\u0000" + item.optString("file_id", item.optString("mtime") + ":" + item.optLong("size"))

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val targets = pendingTreeItems
        val base = pendingTreeBase
        pendingTreeItems = emptyList(); pendingTreeBase = ""
        if (uri != null && targets.isNotEmpty() && sourceReady()) downloadSelected(base, targets, uri)
    }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val target = uploadTarget
            val names = uris.map { FileTransfer.displayName(this, it) }
            if (names.any { !validName(it) }) {
                status("所选文件包含 Windows 无法使用的文件名")
            } else {
                WorkspaceDialog.Builder(this).setTitle("上传 ${uris.size} 个文件？")
                    .setMessage(
                        "目标：$target\n\n${names.take(8).joinToString("\n")}" +
                            "${if (names.size > 8) "\n…" else ""}\n\n目标目录中同名文件会被替换。"
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("上传") { _, _ ->
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

    private val save = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val source = pendingDownload
        pendingDownload = ""
        if (uri != null && source.isNotEmpty()) startTransfer(
            "下载 ${source.substringAfterLast('\\').substringAfterLast('/')}"
        ) { progress ->
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
        sourceKey
        sourceChanged = savedInstanceState?.getString("sourceKey")?.let { it != sourceKey } ?: false
        if (!sourceChanged) {
            restoreKeys = savedInstanceState?.getStringArrayList("selection").orEmpty().toSet()
            multi = savedInstanceState?.getBoolean("multi") ?: false
            pendingTreeBase = savedInstanceState?.getString("treeBase").orEmpty()
            val pending = runCatching { JSONArray(savedInstanceState?.getString("treeItems") ?: "[]") }
                .getOrElse { JSONArray() }
            pendingTreeItems = (0 until pending.length()).map { pending.getJSONObject(it) }
        }
        path = savedInstanceState?.getString("path") ?: intent.getStringExtra("path") ?: ""
        pendingDownload = savedInstanceState?.getString("download") ?: ""
        uploadTarget = savedInstanceState?.getString("upload") ?: ""
        delaySeconds = FileDelay.seconds(this)
        status("查看磁盘空间、浏览目录，或选择手机中的文件上传")
        content { Screen() }
        updateSelection()
        task {
            if(path.isNotBlank())load(path)
            val storage = try {storageCall("storage",timeout=6000)}
            catch(e:CancellationException){throw e}
            catch(e:Exception) {
                if(path.isBlank())load("")
                if(disks.isEmpty())status("磁盘容量暂不可用，仍可输入目录重试；慢盘占位任务可在更多工具中取消",true)
                return@task
            }
            renderDisks(storage.getJSONArray("drives"))
            if(path.isBlank())load(storage.optString("home"))
            val jobs = runCatching { storageCall("file_jobs") }.getOrNull()?.optJSONArray("jobs")
            if (jobs != null && !operationsBusy) {
                val active = (0 until jobs.length()).map { jobs.getJSONObject(it) }
                    .firstOrNull { !it.has("finished") }
                if (active != null) trackFileJob(active.getString("id"), active.optString("operation"), path)
            }
        }
    }

    @Composable
    private fun Screen() = FixedPage("电脑存储") {
        if (transferText.isNotBlank()) SectionCard {
            Note(transferText)
            if (forceConfirmation != null) Action("查看占用并确认", enabled = !forceConfirmSending) { confirmForceDelete() }
            Action("取消操作") { cancelTransfer() }
        }
        if (results.isNotEmpty()) SectionCard("逐项结果") {
            RowItem(if (resultsExpanded) "收起逐项结果" else "展开逐项结果（${results.size}）") {
                resultsExpanded = !resultsExpanded
            }
            if (resultsExpanded) Column(Modifier.heightIn(max=144.dp).verticalScroll(rememberScrollState())) { Note(results.joinToString("\n").take(100_000)) }
        }
        if (disksExpanded && !multi && !operationsBusy) Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            disks.forEach { disk ->
                Column(modifier = Modifier.width(208.dp).padding(end = 10.dp)) {
                    RowItem(disk.path, disk.detail) { load(disk.path) }
                    Progress(disk.percent / 100f)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            crumbs.forEach { crumb -> SelectionAction(crumb.label) { load(crumb.target) } }
        }
        SectionCard {
            ActionRow {
                Action("上一级", modifier = Modifier.weight(1f)) { load(parentOf(path)) }
                Action("刷新", modifier = Modifier.weight(1f)) { load(path) }
                Action("新建", modifier = Modifier.weight(1f)) { newItem() }
                Action("上传", modifier = Modifier.weight(1f)) { upload() }
            }
            if(toolsExpanded)ActionRow {
                Action("占位文件", modifier = Modifier.weight(1f)) { openPlaceholder() }
                Action("文件任务", modifier = Modifier.weight(1f)) { showFileJobs() }
                Action("延迟计划", modifier = Modifier.weight(1f)) { showFilePlans() }
                Action("U盘管理", modifier = Modifier.weight(1f)) { usbActions() }
            }
            Field(label = "搜索当前目录", value = query, onValueChange = { query = it; filter() })
            ActionRow {
                SelectionAction("磁盘",selected=disksExpanded,modifier=Modifier.weight(1f)){disksExpanded=!disksExpanded}
                SelectionAction("更多工具",selected=toolsExpanded,modifier=Modifier.weight(1f)){toolsExpanded=!toolsExpanded}
                SelectionAction("延迟 ${delaySeconds}s", modifier = Modifier.weight(1f)) {
                    FileDelay.choose(this@StorageActivity) { delaySeconds = FileDelay.seconds(this@StorageActivity) }
                }
                Action(
                    if (multi) "退出多选" else "多选",
                    enabled = !operationsBusy,
                    modifier = Modifier.weight(1f)
                ) { toggleMulti() }
            }
        }
        if (multi) SectionCard("批量操作") {
            Note(selectionSummary)
            ActionRow {
                Action("全选结果", enabled = !operationsBusy, modifier = Modifier.weight(1f)) { chooseAll(false) }
                Action("反选", enabled = !operationsBusy, modifier = Modifier.weight(1f)) { chooseAll(true) }
                Action("清空", enabled = !operationsBusy, modifier = Modifier.weight(1f)) { clearSelection() }
            }
            ActionRow {
                listOf("复制" to "copy", "移动" to "move", "删除" to "delete", "下载" to "download")
                    .forEach { (name, operation) ->
                        Action(
                            name,
                            enabled = !operationsBusy && selection.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { beginBatch(operation) }
                    }
            }
            Action("强制删除", enabled = !operationsBusy && selection.isNotEmpty()) { beginBatch("force_delete") }
        }
        if(listingBusy) {Note("目录加载中，返回和其他操作仍可使用…")}
        else if(visible.isEmpty())EmptyNote(listingMessage)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(visible) { item ->
                val name = item.optString("name")
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(item.optLong("mtime") * 1000))
                val summary = buildString {
                    append(if (item.optBoolean("dir")) "文件夹" else FileTransfer.humanSize(item.optLong("size")))
                    append(" · ").append(date)
                    if (item.optBoolean("hidden")) append(" · 隐藏")
                    if (item.optBoolean("system")) append(" · 系统")
                }
                if (multi) SelectionRow(
                    title = name,
                    summary = summary,
                    checked = selection.containsKey(itemKey(item)),
                    onCheckedChange = { toggleSelection(item) },
                    enabled = !operationsBusy
                ) else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowItem(name,summary,enabled=!operationsBusy,onLongClick={fileActions(item)}) {
                            if(item.optBoolean("dir"))load(joinPath(path,name)) else fileActions(item)
                        }
                    }
                    IconButton(enabled=!operationsBusy,onClick={fileActions(item)}) {
                        Icon(painterResource(R.drawable.ic_more),"操作：$name")
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sourceKey", sourceKey)
        outState.putString("path", path)
        outState.putString("download", pendingDownload)
        outState.putString("upload", uploadTarget)
        outState.putBoolean("multi", multi)
        outState.putStringArrayList("selection", ArrayList(selection.keys + restoreKeys))
        outState.putString("treeBase", pendingTreeBase)
        outState.putString("treeItems", JSONArray(pendingTreeItems).toString())
        super.onSaveInstanceState(outState)
    }

    private fun checkSource() {
        if (sourceChanged || origin == null || origin?.closed == true ||
            runCatching { ConnectionManager.sessionClient() }.getOrNull() !== origin
        ) throw IOException("电脑连接已改变，请重新打开文件管理；未向新电脑发送操作")
    }

    private suspend fun storageCall(
        command: String,
        params: JSONObject = JSONObject(),
        timeout: Long = 30_000
    ): JSONObject {
        checkSource()
        val delayed = command in setOf("files", "rm", "rename", "mkdir", "put") ||
            command in setOf("file_jobs", "placeholders") && params.optString("action") == "create"
        return if (delayed) FileDelay.call(this, origin, command, params) { status(it) }
        else pinnedCall(origin, command, params, timeout)
    }

    private fun sourceReady(): Boolean =
        try { checkSource(); true } catch (e: Exception) { status(e.message ?: "连接已改变", true); false }

    private fun renderDisks(drives: JSONArray) {
        disks = (0 until drives.length()).map { index ->
            val drive = drives.getJSONObject(index)
            val percent = drive.optDouble("percent").toInt().coerceIn(0, 100)
            Disk(
                path = drive.optString("path"),
                percent = percent,
                detail = if(drive.has("ready") && !drive.optBoolean("ready"))"磁盘忙碌或暂不可用 · 仍可尝试打开"
                else "${FileTransfer.humanSize(drive.optLong("free"))} 可用 / " +
                    FileTransfer.humanSize(drive.optLong("total")) + if(drive.optBoolean("pending") || drive.optBoolean("stale"))" · 上次容量" else "",
                tight = percent > 90
            )
        }
    }

    internal fun load(target: String, notice: String? = null) {
        loading?.cancel()
        val sequence=++listingSequence
        loading = task {
            listingBusy=true
            status("正在读取目录…")
            try {
            val result = storageCall("ls", JSONObject().put("path", target),timeout=8000)
            val changed = path != result.getString("path")
            if (changed && restoreKeys.isEmpty()) {
                selection.clear(); multi = false; lastResult = null; lastError = false; results = emptyList()
            }
            path = result.getString("path")
            val items = result.getJSONArray("items")
            entries = withContext(Dispatchers.Default) { (0 until items.length()).map { items.getJSONObject(it) }
                .sortedWith(
                    compareByDescending<JSONObject> { it.optBoolean("dir") }
                        .thenBy { it.optString("name").lowercase() }
                ) }
            val current = entries.associateBy { itemKey(it) }
            selection.keys.retainAll(current.keys)
            for (id in selection.keys.toList()) selection[id] = current.getValue(id)
            for (id in restoreKeys) current[id]?.let { selection[id] = it }
            restoreKeys = emptySet()
            query = ""
            buildCrumbs()
            filter()
            if (path == highlightFolder && highlightName.isNotBlank()) {
                val found = visible.any { it.optString("name") == highlightName }
                if (lastResult == null && notice == null) {
                    lastResult = if (found) "已定位：$highlightName"
                    else "所选文件可能已移动或删除，请更新搜索索引"
                }
            }
            if (notice != null) lastResult = notice
            status(
                lastResult ?: if (entries.isEmpty()) "此目录为空" else "${entries.size} 项 · 长按多选，点击单项操作",
                lastError
            )
            listingMessage=if(entries.isEmpty())"此目录为空" else "没有匹配名称；可以清空搜索后查看"
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                listingMessage="目录暂未加载：${e.message ?: "磁盘无响应"}。点击刷新可重试；也可在更多工具中取消占位任务。"
                status(listingMessage,true)
            } finally {if(sequence==listingSequence)listingBusy=false}
        }
    }

    private fun buildCrumbs() {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val unc = path.startsWith("\\\\")
        var current = if (path.startsWith('/')) "/" else ""
        val list = mutableListOf(Crumb("主目录", ""))
        parts.forEachIndexed { index, part ->
            if (unc && index == 0) { current = "\\\\$part"; return@forEachIndexed }
            current = if (current.isEmpty()) "$part\\" else joinPath(current, part)
            list.add(Crumb(if (unc && index == 1) "${parts[0]} / $part" else part, current))
        }
        crumbs = list
    }

    /** 按 [query] 过滤当前目录；测试改完 query 后调用它。 */
    internal fun search() = filter()

    private fun filter() {
        val text = query.trim()
        visible = entries.filter { it.optString("name").contains(text, ignoreCase = true) }
        updateSelection()
    }

    internal fun upload() {
        if (transfer?.isCompleted != false && path.isNotBlank()) {
            uploadTarget = path
            pick.launch(arrayOf("*/*"))
        }
    }

    internal fun toggleMulti() {
        if (operationsBusy) return
        multi = !multi
        if (!multi) selection.clear()
        updateSelection()
    }

    internal fun toggleSelection(item: JSONObject) {
        if (operationsBusy) return
        val key = itemKey(item)
        if (selection.containsKey(key)) selection.remove(key)
        else if (selection.size >= 100) { status("每批最多选择 100 项，请先筛选", true); return }
        else selection[key] = item
        updateSelection()
    }

    internal fun chooseAll(invert: Boolean) {
        val next = selection.toMutableMap()
        visible.forEach {
            if (invert && next.containsKey(itemKey(it))) next.remove(itemKey(it)) else next[itemKey(it)] = it
        }
        if (next.size > 100) { status("选择结果超过 100 项，请筛选或分批处理", true); return }
        selection.clear(); selection.putAll(next); updateSelection()
    }

    internal fun clearSelection() {
        selection.clear(); updateSelection()
    }

    private fun updateSelection() {
        selectionSummary = "已选 ${selection.size} 项 · 当前显示 ${visible.size} 项（搜索外的已选项仍保留）"
    }

    /**
     * 单项操作菜单。
     *
     * 旧版用挂在列表行上的 PopupMenu；Compose 页面没有稳定的锚点 View，
     * 改成两级系统列表弹窗，分组保持不变。
     */
    internal fun fileActions(item: JSONObject) {
        if (!sourceReady()) return
        if (operationsBusy) { status("文件操作进行中，请等待完成或取消", true); return }
        val groups = menuGroups(item)
        WorkspaceDialog.Builder(this).setTitle(item.optString("name"))
            .setItems(groups.keys.toTypedArray()) { _, index ->
                val group = groups.keys.toList()[index]
                val entries = groups.getValue(group)
                if (entries.size == 1 && entries[0] == group) { act(group, item); return@setItems }
                WorkspaceDialog.Builder(this).setTitle(group)
                    .setItems(entries.toTypedArray()) { _, choice -> act(entries[choice], item) }
                    .setNegativeButton("返回", null).show()
            }.setNegativeButton("取消", null).show()
    }

    /** 菜单分组，和旧版 PopupMenu 的子菜单一致。 */
    internal fun menuGroups(item: JSONObject): LinkedHashMap<String, List<String>> {
        val dir = item.optBoolean("dir")
        val name = item.optString("name")
        val office = OfficeDocumentActivity.supports(name)
        val groups = linkedMapOf<String, List<String>>()
        groups["打开与预览"] = buildList {
            add("在电脑打开")
            if (!dir) add("预览")
            if (office) add("电脑 WPS 打开")
            add("在当前窗口下方打开")
        }
        groups["编辑与属性"] = buildList {
            if (!dir) add(if (office) "手机 WPS 预览 / 编辑" else "编辑文本")
            if (!dir && name.substringAfterLast('.', "").lowercase() in listOf("jpg","jpeg","png","bmp")) add("设为桌面背景")
            add("最后修改日期"); add("文件属性")
        }
        groups["传输与整理"] = buildList {
            if (!dir) add("下载到手机")
            add("重命名")
            if (!dir && name.endsWith(".zip", true)) add("解压 ZIP")
        }
        val scriptType = name.substringAfterLast('.', "").lowercase()
        if (!dir && (scriptType in listOf("bat", "cmd", "vbs") || scriptType == "exe")) {
            groups["高级操作"] = buildList {
                if (scriptType in listOf("bat", "cmd", "vbs")) add("运行 ${scriptType.uppercase()}")
                if (scriptType == "exe") add("应用锁")
            }
        }
        groups["删除"] = if (dir) listOf("删除") else listOf("删除", "强制删除")
        return groups
    }

    /** 执行一个菜单项，测试直接调用它。 */
    internal fun act(label: String, item: JSONObject) {
        val client = origin
        val selected = joinPath(path, item.getString("name"))
        val office = OfficeDocumentActivity.supports(item.optString("name"))
        when (label) {
            "设为桌面背景" -> if(sourceReady())startActivity(Intent(this,WallpaperActivity::class.java).putExtra("path",selected))
            "在电脑打开" -> WorkspaceDialog.Builder(this).setTitle("在电脑打开？")
                .setMessage("$selected\n\n应用程序会在电脑启动，文件将使用电脑默认应用打开。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开") { _, _ ->
                    task {
                        FileDelay.call(
                            this@StorageActivity, client, "files",
                            JSONObject().put("action", "open").put("path", selected)
                        )
                        status("已向电脑发送打开请求")
                    }
                }.show()
            "预览" -> if (office) openOffice(selected, item.optString("file_id"))
            else FilePreview.show(this, client, selected)
            "手机 WPS 预览 / 编辑" -> openOffice(selected, item.optString("file_id"))
            "电脑 WPS 打开", "在当前窗口下方打开" -> task {
                val response = FileDelay.call(
                    this@StorageActivity, client, "files",
                    JSONObject().put("action", "open").put("path", selected)
                        .put("application", if (label == "电脑 WPS 打开") "wps" else "default")
                        .put("background", label == "在当前窗口下方打开")
                ) { status(it) }
                status(response.optString("notice", "已向电脑发送打开请求"))
            }
            "最后修改日期" -> modifyDate(item, selected)
            "文件属性" -> WorkspaceDialog.Builder(this).setTitle(item.optString("name")).setMessage(
                "$selected\n\n${if (item.optBoolean("dir")) "文件夹" else FileTransfer.humanSize(item.optLong("size"))}\n" +
                    "最后修改：${DateFormat.getDateTimeInstance().format(Date(item.optLong("mtime") * 1000))}\n" +
                    "隐藏：${item.optBoolean("hidden")} · 系统：${item.optBoolean("system")}"
            ).setPositiveButton("修改最后修改日期") { _, _ -> modifyDate(item, selected) }
                .setNegativeButton("关闭", null).show()
            "编辑文本" -> task {
                checkSource()
                startActivity(Intent(this@StorageActivity, TextEditorActivity::class.java).putExtra("path", selected))
            }
            "运行 BAT", "运行 CMD", "运行 VBS" -> ScriptJobsDialog.show(this, client, selected)
            "应用锁" -> task {
                checkSource()
                startActivity(Intent(this@StorageActivity, AppLocksActivity::class.java).putExtra("path", selected))
            }
            "解压 ZIP" -> extractZip(item, selected, client)
            "下载到手机" -> if (transfer?.isCompleted != false) {
                pendingDownload = selected
                save.launch(item.getString("name"))
            }
            "重命名" -> renameOrCreate(item)
            "删除" -> deleteItem(item, selected)
            "强制删除" -> beginFileOperation("force_delete", listOf(JSONObject(item.toString())))
        }
    }

    private fun extractZip(item: JSONObject, selected: String, client: com.aicode.lancmd.net.AgentClient?) {
        val destination = dialogInput(
            text = joinPath(path, item.getString("name").dropLast(4) + "_extracted")
        )
        val dialog = WorkspaceDialog.Builder(this).setTitle("解压到新文件夹")
            .setMessage("支持 ZIP；最多 2000 项、512 MB。目标必须不存在，不覆盖已有文件。")
            .setView(destination).setNegativeButton("取消", null)
            .setPositiveButton("解压", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = destination.text.toString().trim()
                if (target.isEmpty()) { destination.error = "请输入目标路径"; return@setOnClickListener }
                dialog.dismiss()
                task {
                    status("正在解压 ZIP…")
                    val result = FileDelay.call(
                        this@StorageActivity, client, "files",
                        JSONObject().put("action", "extract").put("path", selected)
                            .put("destination", target), 120_000
                    )
                    status("已解压 ${result.optInt("count")} 项")
                    load(result.getString("path"))
                }
            }
        }
        dialog.show()
    }

    internal fun deleteItem(item: JSONObject, selected: String) {
        val base = path
        WorkspaceDialog.Builder(this).setTitle("删除${if (item.optBoolean("dir")) "目录" else "文件"}？")
            .setMessage(
                "$selected\n\n${if (item.optBoolean("dir")) "目录中的全部内容也会删除。" else ""}" +
                    "此操作无法撤销。结果显示在页面顶部。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (operationsBusy) return@setPositiveButton
                operationsBusy = true
                results = emptyList()
                task {
                    try {
                        storageCall(
                            "rm",
                            JSONObject().put("path", selected).apply {
                                item.optString("file_id").takeIf { it.isNotEmpty() }?.let { put("file_id", it) }
                            }
                        )
                        lastResult = "删除完成：${item.optString("name")}"
                        lastError = false
                        report(lastResult!!, listOf("成功 · $selected"))
                        if (path == base) load(base, lastResult)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        lastResult = "删除失败：${e.message}"
                        lastError = true
                        report(lastResult!!, listOf("失败 · $selected：${e.message}"), true)
                    }
                    finally { operationsBusy = false }
                }
            }.show()
    }

    /** 顶部摘要 + 逐项结果，和旧版的「展开逐项结果」一致。 */
    private fun report(summary: String, lines: List<String>, failed: Boolean = false) {
        status(summary, failed)
        results = lines
        resultsExpanded = failed
    }

    internal fun openPlaceholder() {
        if (!sourceReady()) return
        if (path.isBlank()) { status("请先选择电脑文件夹", true); return }
        if (operationsBusy) { status("请等待当前文件操作完成或取消", true); return }
        startActivity(Intent(this, PlaceholderFilesActivity::class.java).putExtra("directory", path))
    }

    internal fun beginBatch(operation: String) {
        if (operationsBusy || selection.isEmpty() || !sourceReady()) return
        beginFileOperation(operation, selection.values.map { JSONObject(it.toString()) })
    }

    private fun beginFileOperation(operation: String, targets: List<JSONObject>) {
        if (operationsBusy || targets.isEmpty() || !sourceReady()) return
        if (operation == "force_delete" && targets.any { it.optBoolean("dir") }) {
            status("强制删除仅支持文件，请取消选择文件夹；文件夹仍可使用普通删除", true); return
        }
        val base = path
        if (operation == "download") {
            pendingTreeBase = base; pendingTreeItems = targets
            pickTree.launch(null); return
        }
        if (targets.any { it.optString("file_id").length != 64 }) {
            status("批量操作需要文件版本，请更新 Windows 接收端并刷新目录", true); return
        }
        fun confirm(destination: String = "") {
            val title = operationName(operation)
            WorkspaceDialog.Builder(this).setTitle("$title 所选 ${targets.size} 项？")
                .setMessage(
                    targets.take(8).joinToString("\n") { it.optString("name") } +
                        (if (targets.size > 8) "\n…" else "") +
                        (
                            if (destination.isNotBlank())
                                "\n\n目标：$destination\n目标必须不存在，不覆盖同名文件或目录。"
                            else if (operation == "force_delete") "\n\n先尝试直接删除。若文件被独占，将列出占用应用，再次确认后结束所列应用并删除；未保存内容可能丢失。文件删除无法撤销。"
                            else "\n\n文件夹中的内容也会删除，无法撤销。"
                            ) +
                        "\n停止操作只停止后续步骤，已完成的复制、移动或删除不会撤销。结果显示在页面顶部。"
                )
                .setNegativeButton("取消", null)
                .setPositiveButton(title) { _, _ ->
                    task {
                        checkSource()
                        if (operationsBusy) return@task
                        if (operation == "force_delete") {
                            val features = storageCall("capabilities").optJSONArray("features")
                            if (features == null || (0 until features.length()).none { features.optString(it) == "force_delete" }) {
                                status("当前接收端不支持强制删除，请完整更新 Windows 包后重新启动并刷新目录", true)
                                return@task
                            }
                        }
                        operationsBusy = true
                        lastResult = null; lastError = false; results = emptyList()
                        status("正在提交$title 文件任务…")
                        val specs = JSONArray(
                            targets.map {
                                JSONObject().put("path", joinPath(base, it.getString("name")))
                                    .put("file_id", it.getString("file_id"))
                            }
                        )
                        val result = try {
                            storageCall(
                                "file_jobs",
                                JSONObject().put("action", "create").put("operation", operation)
                                    .put("items", specs)
                                    .apply { if (destination.isNotBlank()) put("destination", destination) }
                            )
                        } finally { operationsBusy = false }
                        trackFileJob(result.getJSONObject("job").getString("id"), operation, base)
                    }
                }.show()
        }
        if (operation in listOf("delete", "force_delete")) confirm()
        else RemoteDirectoryPicker(this, origin).show(base) { destination ->
            if (sourceReady()) confirm(destination)
        }
    }

    private fun operationName(operation: String) =
        when (operation) { "copy" -> "复制"; "move" -> "移动"; "delete" -> "删除"; "force_delete" -> "强制删除"; else -> operation }

    internal fun confirmForceDelete() {
        val job = forceConfirmation?.let { JSONObject(it.toString()) } ?: return
        if (!sourceReady() || forceConfirmSending) return
        val list = job.getJSONArray("items")
        val lines = buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (item.optString("status") != "needs_confirmation") continue
                add(item.optString("path"))
                val blockers = item.optJSONArray("blockers") ?: continue
                for (index in 0 until blockers.length()) {
                    val process = blockers.getJSONObject(index)
                    val created = process.optString("created").toLongOrNull()
                        ?.let { DateFormat.getDateTimeInstance().format(Date(it / 10000 - 11644473600000L)) }
                    add("${process.optString("application")} · PID ${process.optInt("pid")}\n${process.optString("path")}\n进程启动：${created ?: "未知"}")
                }
            }
        }
        WorkspaceDialog.Builder(this).setTitle("结束占用应用后删除？")
            .setMessage(lines.joinToString("\n\n") + "\n\n仅结束以上经过身份验证的应用。未保存内容可能丢失；程序可能重新创建同名文件。若占用程序变化，会要求重新确认。")
            .setNegativeButton("暂不结束", null)
            .setPositiveButton("结束所列应用并删除") { _, _ ->
                task {
                    forceConfirmSending = true
                    try {
                        storageCall("file_jobs", JSONObject().put("action", "confirm_force_delete")
                            .put("job_id", job.getString("id"))
                            .put("confirmation_id", job.getString("confirmation_id"))
                            .put("confirm_data_loss", true))
                        forceConfirmation = null
                        status("已确认，正在结束所列占用应用并删除文件…")
                    } finally { forceConfirmSending = false }
                }
            }.show()
    }

    private fun fileResults(job: JSONObject): Pair<String, List<String>> {
        val list = job.getJSONArray("items")
        var success = 0; var failed = 0; var cancelled = 0; var warnings = 0; var pending = 0
        val lines = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val label = item.getString("path")
            val line = when (item.optString("status")) {
                "completed" -> {
                    success++
                    "成功 · $label" + (if (item.has("destination")) " → ${item.getString("destination")}" else "")
                }
                "failed" -> { failed++; "失败 · $label：${item.optString("error")}" }
                "pending_release" -> { pending++; "待句柄释放 · $label：${item.optString("notice")}" }
                else -> { cancelled++; "取消/未执行 · $label" }
            }
            val described = line + if (item.optString("notice").isNotBlank() && item.optString("status") != "pending_release") "；${item.optString("notice")}" else ""
            if (item.has("cleanup_warning")) {
                warnings++
                described + "；清理提示：${item.optString("cleanup_warning")}"
            } else described
        }
        lastError = failed > 0 || warnings > 0 || pending > 0
        return "${operationName(job.optString("operation"))}：成功 $success，失败 $failed，取消/未执行 $cancelled" +
            (if (warnings > 0) "，清理提示 $warnings" else "") +
            (if (pending > 0) "，待句柄释放 $pending（未确认立即删除）" else "") to lines
    }

    internal fun showFileJobs() {
        if (!sourceReady()) return
        if (operationsBusy) { status("当前文件任务仍在运行，可用上方取消按钮停止后续操作"); return }
        task {
            val response = storageCall("file_jobs")
            val jobs = response.getJSONArray("jobs")
            val active = (0 until jobs.length()).map { jobs.getJSONObject(it) }
                .firstOrNull { !it.has("finished") }
            if (active != null) {
                trackFileJob(active.getString("id"), active.optString("operation"), path)
                return@task
            }
            val lines = ArrayList<String>()
            var failures = false
            for (i in 0 until jobs.length()) {
                val result = fileResults(jobs.getJSONObject(i))
                lines.add(result.first); lines.addAll(result.second)
                failures = failures || lastError
            }
            lastResult = if (jobs.length() == 0) "暂无文件任务"
            else "最近 ${jobs.length()} 个文件任务（逐项结果见下方）"
            lastError = failures
            if (response.optString("warning").isNotBlank()) {
                lines.add(response.optString("warning")); lastError = true
            }
            report(lastResult!!, lines, lastError)
        }
    }

    internal fun cancelTransfer() {
        val job = remoteJob
        if (job == null) transfer?.cancel() else task {
            storageCall("file_jobs", JSONObject().put("action", "cancel").put("job_id", job))
            status("已请求停止，等待当前文件清理；已完成操作保留")
        }
    }

    private fun trackFileJob(id: String, operation: String, base: String) {
        if (operationsBusy) return
        operationsBusy = true
        remoteJob = id
        lastResult = null; lastError = false; results = emptyList()
        transferText = "正在${operationName(operation)}…"
        transfer = task {
            try {
                while (true) {
                    val response = storageCall("file_jobs")
                    val jobs = response.getJSONArray("jobs")
                    val job = (0 until jobs.length()).map { jobs.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == id }
                        ?: throw IOException("文件任务记录已失效，请刷新任务列表")
                    val children = job.getJSONArray("items")
                    if (job.optString("status") == "needs_confirmation") {
                        forceConfirmation = JSONObject(job.toString())
                        transferText = "文件被应用占用，等待在手机上确认；尚未删除占用文件"
                        status(transferText)
                        delay(800)
                        continue
                    }
                    forceConfirmation = null
                    if (job.has("finished")) {
                        val result = fileResults(job)
                        lastResult = result.first
                        report(result.first, result.second, lastError)
                        if (path == base) {
                            for (i in 0 until children.length()) {
                                val item = children.getJSONObject(i)
                                if (item.optString("status") == "completed") {
                                    val name = item.getString("path")
                                        .substringAfterLast('\\').substringAfterLast('/')
                                    selection.remove(name + "\u0000" + item.optString("file_id"))
                                }
                            }
                            load(base, lastResult)
                        }
                        break
                    }
                    val done = (0 until children.length()).count {
                        children.getJSONObject(it).optString("status") in
                            listOf("completed", "failed", "cancelled")
                    }
                    val running = (0 until children.length()).map { children.getJSONObject(it) }
                        .firstOrNull { it.optString("status") == "running" }
                    val text = "正在${operationName(operation)} · 已处理 $done/${children.length()}" +
                        (
                            running?.let {
                                "\n${it.optString("path")} · ${FileTransfer.humanSize(it.optLong("bytes"))} / " +
                                    FileTransfer.humanSize(it.optLong("total_bytes"))
                            } ?: ""
                            )
                    status(text)
                    transferText = text
                    delay(800)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastResult = "文件任务反馈失败：${e.message}；可重新打开文件任务查看电脑上的状态"
                lastError = true
                status(lastResult!!, true)
            }
            finally { remoteJob = null; operationsBusy = false; transferText = "" }
        }
    }

    private fun downloadSelected(base: String, targets: List<JSONObject>, tree: Uri) {
        startTransfer("批量下载") { progress ->
            val lines = ArrayList<String>()
            var success = 0; var failed = 0; var processed = 0
            val client = origin ?: throw IOException("未连接")
            try {
                for ((index, item) in targets.withIndex()) {
                    checkSource()
                    val source = joinPath(base, item.getString("name"))
                    try {
                        status("下载 ${index + 1}/${targets.size}：${item.optString("name")}")
                        com.aicode.lancmd.net.TreeDownloads(this, client).download(source, item, tree) { file, p ->
                            progress(p)
                            runOnUiThread {
                                if (!isDestroyed) status(
                                    "下载 ${index + 1}/${targets.size} · " +
                                        "${file.substringAfterLast('\\').substringAfterLast('/')} · ${p.percent}%"
                                )
                            }
                        }
                        success++
                        if (path == base) selection.remove(itemKey(item))
                        lines.add("成功 · $source")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed++
                        lines.add(
                            "失败 · $source：${e.message}" +
                                e.suppressed.joinToString("") { "；${it.message}" }
                        )
                    }
                    processed++
                    if (client.closed ||
                        runCatching { ConnectionManager.sessionClient() }.getOrNull() !== client
                    ) break
                }
            } finally {
                val remaining = targets.size - processed
                lastResult = "下载：成功 $success，失败 $failed，未执行 $remaining"
                lastError = failed > 0
                report(lastResult!!, lines, lastError)
            }
        }
    }

    internal fun renameOrCreate(item: JSONObject?) {
        val original = item?.optString("name")
        val base = path
        val text = dialogInput(text = original ?: "")
        val dialog = WorkspaceDialog.Builder(this).setTitle(if (item == null) "新建文件夹" else "重命名")
            .setView(text).focusInput(text,selectAll=item!=null).setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = text.text.toString().trim()
                if (!validName(name)) {
                    text.error = "文件名不能包含 \\ / : * ? \" < > | 或控制字符"
                    return@setOnClickListener
                }
                dialog.dismiss()
                task {
                    if (item == null) storageCall("mkdir", JSONObject().put("path", joinPath(base, name)))
                    else storageCall(
                        "rename",
                        JSONObject().put("path", joinPath(base, original!!)).put("to", joinPath(base, name))
                    )
                    load(base,if(item==null)"文件夹已创建：$name" else "已重命名：$original → $name")
                }
            }
        }
        dialog.show()
    }

    internal fun newItem() {
        if (!sourceReady()) return
        if (operationsBusy) { status("请先等待或取消文件操作", true); return }
        WorkspaceDialog.Builder(this).setTitle("新建")
            .setItems(arrayOf("文件夹", "文本与配置文件", "脚本文件")) { _, kind ->
                if (kind == 0) { renameOrCreate(null); return@setItems }
                val choices = if (kind == 1)
                    listOf("文本文档 (.txt)", "Markdown (.md)", "JSON (.json)", "配置文件 (.ini)", "其他文本文件")
                else listOf("批处理 (.bat)", "VBScript (.vbs)", "命令脚本 (.cmd)", "PowerShell (.ps1)")
                val suffixes = if (kind == 1) listOf("txt", "md", "json", "ini", "")
                else listOf("bat", "vbs", "cmd", "ps1")
                WorkspaceDialog.Builder(this)
                    .setTitle(if (kind == 1) "新建文本文件" else "新建脚本文件")
                    .setItems(choices.toTypedArray()) { _, index -> newTextFile(suffixes[index]) }
                    .setNegativeButton("取消", null).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun newTextFile(suffix: String) {
        val client = origin
        val base = path
        val box = dialogBox()
        val name = dialogInput(
            text = if (suffix.isEmpty()) "新文件.txt" else "新建文件.$suffix"
        ).apply { contentDescription = "新文件名称" }
        val format = dialogSpinner(
            TextEditorActivity.ENCODING_NAMES,
            when (suffix) { "bat", "cmd" -> 2; "vbs" -> 4; else -> 0 }
        )
        box.addView(name); box.addView(format)
        val dialog = WorkspaceDialog.Builder(this).setTitle("新建文本文件")
            .setMessage(
                "创建后直接进入编辑。BAT/CMD 中文通常使用 GBK，VBS 建议 UTF-16 LE；不会自动运行脚本。" +
                    "\n编码可在编辑页调整。"
            )
            .setView(box).focusInput(name,selectAll=true).setNegativeButton("取消", null)
            .setPositiveButton("创建并编辑", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val filename = name.text.toString().trim()
                if (!validName(filename)) {
                    name.error = "请输入兼容 Windows 的文件名"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                task {
                    try {
                        checkSource()
                        val encoding = TextEditorActivity.ENCODINGS[format.selectedItemPosition]
                        val result = FileDelay.call(
                            this@StorageActivity, client, "files",
                            JSONObject().put("action", "text_create")
                                .put("path", joinPath(base, filename)).put("text", "")
                                .put("encoding", encoding).put("newline", "crlf")
                        )
                        checkSource()
                        dialog.dismiss()
                        load(base)
                        startActivity(
                            Intent(this@StorageActivity, TextEditorActivity::class.java)
                                .putExtra("path", result.getString("path"))
                                .putExtra("encoding", encoding)
                        )
                    } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }

    private fun openOffice(selected: String, version: String) {
        if (sourceReady()) startActivity(
            Intent(this, OfficeDocumentActivity::class.java)
                .putExtra("path", selected).putExtra("file_id", version)
        )
    }

    internal fun modifyDate(item: JSONObject, selected: String) {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .apply { isLenient = false }
        val value = dialogInput(text = format.format(Date(item.optLong("mtime") * 1000)))
            .apply { contentDescription = "文件修改日期" }
        fun calendar() = java.util.Calendar.getInstance().apply {
            time = runCatching { format.parse(value.text.toString()) }.getOrNull()
                ?: Date(item.optLong("mtime") * 1000)
        }
        val box = dialogBox()
        box.addView(value)
        box.addView(
            dialogButton("选择日期") {
                val current = calendar()
                android.app.DatePickerDialog(
                    this,
                    { _, year, month, day ->
                        current.set(year, month, day)
                        value.setText(format.format(current.time))
                    },
                    current.get(java.util.Calendar.YEAR),
                    current.get(java.util.Calendar.MONTH),
                    current.get(java.util.Calendar.DAY_OF_MONTH)
                ).apply { datePicker.minDate = 0; datePicker.maxDate = 4_102_444_800_000L }.show()
            }
        )
        box.addView(
            dialogButton("选择时间") {
                val current = calendar()
                android.app.TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        current.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        current.set(java.util.Calendar.MINUTE, minute)
                        value.setText(format.format(current.time))
                    },
                    current.get(java.util.Calendar.HOUR_OF_DAY),
                    current.get(java.util.Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this)
                ).show()
            }
        )
        val dialog = WorkspaceDialog.Builder(this).setTitle("最后修改日期")
            .setMessage(
                "电脑文件的最后修改时间，按手机本地时区输入。格式 yyyy-MM-dd HH:mm:ss。" +
                    "操作延迟 ${FileDelay.seconds(this)} 秒。"
            )
            .setView(box).setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val date = runCatching {
                    val pos = java.text.ParsePosition(0)
                    val parsed = format.parse(value.text.toString(), pos)
                    check(pos.index == value.length())
                    parsed
                }.getOrNull()
                if (date == null || date.time !in 0L..4_102_444_800_000L) {
                    value.error = "请输入 1970–2100 年的有效日期"
                    return@setOnClickListener
                }
                dialog.dismiss()
                task {
                    storageCall(
                        "files",
                        JSONObject().put("action", "mtime").put("path", selected)
                            .put("modified", date.time / 1000.0).apply {
                                item.optString("file_id").takeIf { it.isNotBlank() }?.let { put("file_id", it) }
                            }
                    )
                    load(path, "修改日期已保存：${item.optString("name")}")
                }
            }
        }
        dialog.show()
    }

    internal fun showFilePlans() {
        if (!sourceReady()) return
        task {
            val response = pinnedCall(origin, "file_schedule")
            val jobs = response.getJSONArray("jobs")
            val items = (0 until jobs.length()).map { jobs.getJSONObject(it) }
            val names = mapOf(
                "waiting" to "等待执行", "running" to "执行中", "completed" to "已完成",
                "failed" to "失败", "cancelled" to "已取消"
            )
            report(
                "${items.size} 个延迟文件计划",
                items.map {
                    "${names[it.optString("status")] ?: it.optString("status")} · " +
                        "${it.optString("command")} · ${it.optString("path")} ${it.optString("error")}"
                },
                items.any { it.optString("status") == "failed" }
            )
            val pending = items.filter { it.optString("status") == "waiting" }
            if (pending.isNotEmpty()) WorkspaceDialog.Builder(this@StorageActivity)
                .setTitle("选择要取消的文件计划")
                .setItems(
                    pending.map { it.optString("command") + " · " + it.optString("path") }.toTypedArray()
                ) { _, index ->
                    task {
                        pinnedCall(
                            origin, "file_schedule",
                            JSONObject().put("action", "cancel").put("job_id", pending[index].getString("id"))
                        )
                        status("已取消尚未执行的文件计划")
                    }
                }.setNegativeButton("关闭", null).show()
        }
    }

    internal fun usbActions() {
        if (!sourceReady()) return
        WorkspaceDialog.Builder(this).setTitle("U盘连接管理")
            .setItems(arrayOf("安全弹出 U盘", "强制断开 U盘（禁用设备）", "恢复已断开的设备")) { _, index ->
                if (index == 2) startActivity(Intent(this, DevicesActivity::class.java))
                else ejectUsb(index == 1)
            }.setNegativeButton("取消", null).show()
    }

    internal fun ejectUsb(force: Boolean = false) {
        if (!sourceReady()) return
        if (transfer?.isCompleted == false) { status("请先等待文件传输完成或取消传输", true); return }
        val client = origin
        task {
            status("正在读取 USB 存储…")
            var receiverVersion = ""
            if (force) {
                val caps = pinnedCall(client, "capabilities")
                receiverVersion = caps.optString("version")
                if (!supportsUsbConnection(caps)) {
                    status(usbUpgradeMessage(caps.optString("version")), true); return@task
                }
            }
            val result = pinnedCall(client, "devices", timeout = 45_000)
            if (force && result.optJSONArray("actions")?.let { actions ->
                    (0 until actions.length()).none { actions.optString(it) == "force_disconnect" }
                } == true
            ) { status(usbUpgradeMessage(receiverVersion), true); return@task }
            if (!result.optBoolean("admin")) {
                status("安全弹出需要管理员接收端，请在电脑 Start.exe 选择管理员启动 / 重启", true); return@task
            }
            val list = result.getJSONArray("devices")
            val items = (0 until list.length()).map { list.getJSONObject(it) }
                .filter {
                    it.optString("kind") == "storage" && it.optBoolean("ejectable") && !it.optBoolean("protected")
                }
            if (items.isEmpty()) { status("没有可安全弹出的 USB 存储设备"); return@task }
            val labels = items.map { device ->
                val paths = device.optJSONArray("paths")
                device.optString("name", "U盘") +
                    if (paths == null) "" else " · " +
                        (0 until paths.length()).joinToString(" / ") { paths.optString(it) }
            }
            status("请选择要弹出的 U盘；同一设备的所有分区会一起弹出")
            WorkspaceDialog.Builder(this@StorageActivity)
                .setTitle(if (force) "选择要强制断开的 U盘" else "安全弹出 U盘")
                .setItems(labels.toTypedArray()) { _, index ->
                    confirmEject(force, items[index], labels[index], client, receiverVersion)
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun confirmEject(
        force: Boolean,
        selected: JSONObject,
        label: String,
        client: com.aicode.lancmd.net.AgentClient?,
        receiverVersion: String
    ) {
        WorkspaceDialog.Builder(this)
            .setTitle(if (force) "强制断开 $label？" else "安全弹出 $label？")
            .setMessage(
                if (force)
                    "强制禁用整个 USB 存储设备，会中断所有分区访问。正在写入时可能丢失数据、损坏文件或文件系统。" +
                        "设备可能仍有供电；之后可在设备管理恢复启用。建议先使用安全弹出。"
                else "请保存文件并关闭使用此设备的程序，等待占位文件创建和文件传输结束。" +
                    "此设备的所有分区都会弹出；设备忙时在安卓返回错误，不强制卸载。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(if (force) "仍然强制断开" else "弹出") { _, _ ->
                task {
                    checkSource()
                    if (transfer?.isCompleted == false) throw IOException("文件正在传输，请先完成或取消")
                    val response = try {
                        pinnedCall(
                            client, "devices",
                            JSONObject().put("action", if (force) "force_disconnect" else "eject")
                                .put("device_id", selected.getString("id"))
                                .apply { if (force) put("confirm_data_loss", true) },
                            if (force) 120_000 else 45_000
                        )
                    } catch (e: Exception) {
                        val text = e.message.orEmpty()
                        if (force && (
                                text.contains("无效的设备管理操作") || text.contains("未知命令") ||
                                    text.contains("请完整更新 Windows")
                                )
                        ) throw IOException(usbUpgradeMessage(receiverVersion))
                        throw e
                    }
                    val storage = pinnedCall(client, "storage")
                    renderDisks(storage.getJSONArray("drives"))
                    load(storage.optString("home"), response.optString("notice", "设备已安全弹出"))
                }
            }.show()
    }

    private fun validName(name: String) = name.isNotBlank() && name !in listOf(".", "..") &&
        !name.endsWith('.') && !name.endsWith(' ') && name.none { it.code < 32 || it in "\\/:*?\"<>|" }

    private fun startTransfer(
        title: String,
        block: suspend ((FileTransfer.Progress) -> Unit) -> Unit
    ) {
        if (operationsBusy || transfer?.isCompleted == false) return
        operationsBusy = true
        lastResult = null; lastError = false; results = emptyList()
        transferText = title
        transfer = task {
            try {
                FileDelay.waitLocal(this@StorageActivity, origin) { status(it) }
                block { p ->
                    runOnUiThread {
                        if (!isDestroyed) transferText =
                            "$title · ${p.percent}% · ${FileTransfer.humanSize(p.done)} / " +
                                FileTransfer.humanSize(p.total)
                    }
                }
                status(lastResult ?: "$title：完成", lastError)
                load(path, lastResult ?: "$title：完成")
            } catch (e: CancellationException) {
                status(lastResult ?: "传输已取消，当前未完成手机文件已请求清理"); throw e
            }
            catch (e: Exception) { lastResult = "$title：${e.message}"; lastError = true; status(lastResult!!, true) }
            finally { operationsBusy = false; transferText = "" }
        }
    }
}

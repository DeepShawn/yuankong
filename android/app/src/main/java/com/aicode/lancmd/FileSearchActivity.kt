package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** 全盘文件名与路径搜索，依赖电脑端索引。界面使用 miuix。 */
class FileSearchActivity : MiuixActivity() {
    private val scopes = listOf("文件名", "完整路径")
    private val kinds = listOf("全部类型", "仅文件", "仅文件夹")
    private val sorts = listOf("名称排序", "路径排序", "日期排序", "大小排序")

    internal class Item(
        val name: String,
        val parent: String,
        val path: String,
        val detail: String,
        val dir: Boolean,
        val fileId: String
    )

    internal var query by mutableStateOf("")
    internal var scopeIndex by mutableIntStateOf(0)
    internal var kindIndex by mutableIntStateOf(0)
    internal var sortIndex by mutableIntStateOf(0)
    internal var items by mutableStateOf<List<Item>>(emptyList())
    internal var indexInfo by mutableStateOf("读取索引状态…")
    internal var buildLabel by mutableStateOf("建立索引")
    internal var canBuild by mutableStateOf(true)
    internal var canCancel by mutableStateOf(false)
    internal var pageLabel by mutableStateOf("0 项")
    internal var hasPrevious by mutableStateOf(false)
    internal var hasNext by mutableStateOf(false)

    private var roots = listOf<String>()
    private var drives = listOf<String>()
    private var offset = 0
    private var total = 0
    private var updated = 0.0
    private var jobId = ""
    private var poll: Job? = null
    private var searching: Job? = null
    private var querySequence = 0
    private var statusBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = savedInstanceState?.getString("query").orEmpty()
        scopeIndex = savedInstanceState?.getInt("scope", 0) ?: 0
        kindIndex = savedInstanceState?.getInt("filter", 0) ?: 0
        sortIndex = savedInstanceState?.getInt("sort", 0) ?: 0
        status("首次建立索引后可快速搜索文件名和路径；文件变动后请更新索引。")
        content { Screen() }
    }

    @Composable
    private fun Screen() = Page("文件搜索") {
        SectionCard {
            Field(label = "文件名、关键词或 *.docx", value = query, onValueChange = { typed(it) })
            ChoiceField(
                items = scopes, title = "搜索范围",
                selectedIndex = scopeIndex,
                onSelectedIndexChange = { scopeIndex = it; search(true) }
            )
            ChoiceField(
                items = kinds, title = "结果类型",
                selectedIndex = kindIndex,
                onSelectedIndexChange = { kindIndex = it; search(true) }
            )
            ChoiceField(
                items = sorts, title = "排序方式",
                selectedIndex = sortIndex,
                onSelectedIndexChange = { sortIndex = it; search(true) }
            )
        }
        SectionCard("索引") {
            Note(indexInfo)
            ActionRow {
                Action("索引范围", modifier = Modifier.weight(1f)) { chooseRoots() }
                Action(buildLabel, enabled = canBuild, primary = true, modifier = Modifier.weight(1f)) { build() }
                Action("取消建立", enabled = canCancel, modifier = Modifier.weight(1f)) { cancelBuild() }
            }
        }
        SectionCard("结果 · $pageLabel") {
            if (items.isEmpty()) EmptyNote("没有匹配结果；先建立索引，或在上方修改关键词。")
            items.forEach { item ->
                RowItem(
                    title = (if (item.dir) "📁 " else "") + item.name,
                    summary = "${item.parent}\n${item.detail}"
                ) { actions(item) }
            }
            ActionRow {
                Action("上一页", enabled = hasPrevious, modifier = Modifier.weight(1f)) { offsetBy(-100) }
                Action("下一页", enabled = hasNext, modifier = Modifier.weight(1f)) { offsetBy(100) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        outState.putInt("scope", scopeIndex)
        outState.putInt("filter", kindIndex)
        outState.putInt("sort", sortIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        poll = lifecycleScope.launch { while (isActive) { refreshStatus(); delay(1500) } }
    }

    override fun onStop() {
        poll?.cancel(); super.onStop()
    }

    private fun checkSource() = check(
        origin != null && origin?.closed == false &&
            runCatching { ConnectionManager.sessionClient() }.getOrNull() === origin
    ) { "电脑连接已改变，请重新打开文件搜索" }

    private suspend fun call(params: JSONObject = JSONObject()): JSONObject {
        checkSource()
        return pinnedCall(origin, "file_search", params, 45_000).also { checkSource() }
    }

    /** 输入即搜索，和旧版的文本监听一致。 */
    internal fun typed(text: String) {
        query = text
        search(true)
    }

    private suspend fun refreshStatus() {
        if (statusBusy) return
        statusBusy = true
        try {
            val response = call()
            val index = response.getJSONObject("index")
            val job = response.optJSONObject("job")
            val disks = response.optJSONArray("drives")
            drives = if (disks == null) emptyList() else (0 until disks.length()).map { disks.getString(it) }
            val saved = index.optJSONArray("roots")
            roots = if (saved == null) emptyList() else (0 until saved.length()).map { saved.getString(it) }
            val active = job?.optString("status") == "building"
            jobId = job?.optString("id").orEmpty()
            canBuild = !active
            canCancel = active && response.optBoolean("can_cancel")
            buildLabel = if (index.optBoolean("available")) "更新索引" else "建立索引"
            indexInfo = when {
                active -> (
                    if (job?.optString("phase") == "optimizing") "正在整理索引"
                    else "正在扫描 · ${job?.optLong("scanned")} 项 · 跳过 ${job?.optLong("skipped")} 项"
                    ) + "\n" + job?.optString("current").orEmpty()
                job?.optString("status") == "failed" -> "索引失败：${job.optString("error")}；旧索引保留"
                index.optBoolean("available") ->
                    "已索引 ${index.optLong("count")} 项 · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date((index.optDouble("updated") * 1000).toLong())) +
                        (if (index.optBoolean("incomplete")) " · 达到收录上限，请缩小范围" else "") +
                        "\n范围：" + roots.joinToString(" · ")
                else -> "尚未建立索引。先选择全部本地磁盘或指定文件夹；仅收录文件名与路径，不读取内容。"
            }
            val generation = index.optDouble("updated", 0.0)
            if (generation != updated || index.optBoolean("available") && searching == null) {
                updated = generation
                search(true)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { indexInfo = e.message.orEmpty(); status(e.message ?: "无法读取搜索状态", true) }
        finally { statusBusy = false }
    }

    /** 「建立 / 更新索引」入口：没有范围时先选范围。 */
    internal fun build() {
        if (roots.isEmpty()) chooseRoots() else confirmBuild(roots)
    }

    /** 翻页；[delta] 为一页的偏移量。 */
    internal fun offsetBy(delta: Int) {
        offset = (offset + delta).coerceAtLeast(0)
        search(false)
    }

    internal fun cancelBuild() = task {
        call(JSONObject().put("action", "cancel").put("job_id", jobId))
        status("已请求取消；旧索引保留")
    }

    internal fun chooseRoots() {
        WorkspaceDialog.Builder(this).setTitle("选择索引范围")
            .setItems(arrayOf("全部本地固定磁盘", "选择一个文件夹（含子文件夹）", "沿用上次范围")) { _, index ->
                when (index) {
                    0 -> if (drives.isEmpty()) status("未找到本地固定磁盘，请选择文件夹", true)
                    else confirmBuild(drives)
                    1 -> RemoteDirectoryPicker(this, origin)
                        .show(roots.firstOrNull().orEmpty()) { confirmBuild(listOf(it)) }
                    else -> if (roots.isEmpty()) status("没有上次范围，请先选择磁盘或文件夹", true)
                    else confirmBuild(roots)
                }
            }.setNegativeButton("取消", null).show()
    }

    internal fun confirmBuild(selected: List<String>) {
        WorkspaceDialog.Builder(this).setTitle("建立 / 更新文件索引？")
            .setMessage(
                selected.joinToString("\n") +
                    "\n\n首次扫描可能需要较长时间；无权限目录和链接会跳过。最多收录 200 万项，至少保留 1 GB 空闲。" +
                    "手机断线后继续，取消或失败保留旧索引。文件变动后请手动更新。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                task {
                    call(JSONObject().put("action", "build").put("roots", JSONArray(selected)))
                    status("索引已开始，电脑端无需确认弹窗")
                    refreshStatus()
                }
            }.show()
    }

    internal fun search(reset: Boolean) {
        if (reset) offset = 0
        val sequence = ++querySequence
        val text = query
        val start = offset
        searching?.cancel()
        searching = lifecycleScope.launch {
            delay(250)
            try {
                val result = call(
                    JSONObject().put("action", "query").put("query", text)
                        .put("scope", if (scopeIndex == 1) "path" else "name")
                        .put("kind", listOf("all", "files", "folders")[kindIndex.coerceAtLeast(0)])
                        .put("sort", listOf("name", "path", "modified", "size")[sortIndex.coerceAtLeast(0)])
                        .put("offset", start).put("limit", 100)
                )
                if (sequence != querySequence) return@launch
                total = result.getInt("total")
                val list = result.getJSONArray("items")
                items = (0 until list.length()).map { index ->
                    val item = list.getJSONObject(index)
                    Item(
                        name = item.getString("name"),
                        parent = item.getString("parent"),
                        path = item.getString("path"),
                        detail = (
                            if (item.optBoolean("dir")) "文件夹"
                            else FileTransfer.humanSize(item.optLong("size"))
                            ) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date((item.optDouble("mtime") * 1000).toLong())),
                        dir = item.optBoolean("dir"),
                        fileId = item.getString("file_id")
                    )
                }
                pageLabel = if (total == 0) "0 项" else "${start + 1}–${start + items.size} / $total"
                hasPrevious = start > 0
                hasNext = start + items.size < total
                status(
                    if (!result.getJSONObject("index").optBoolean("available")) "请先建立文件索引"
                    else if (total == 0) "没有匹配结果；文件变动后请更新索引"
                    else "找到 $total 项 · 点击结果打开操作菜单"
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status(e.message ?: "搜索失败", true) }
        }
    }

    /** 结果操作菜单；旧版用 PopupMenu，Compose 页面用系统列表弹窗。 */
    internal fun actions(item: Item) {
        try { checkSource() } catch (e: Exception) { status(e.message.orEmpty(), true); return }
        val office = OfficeDocumentActivity.supports(item.path)
        val labels = buildList {
            add("在文件管理中定位")
            add("在电脑打开")
            if (!item.dir) { add("预览"); add(if (office) "手机 WPS 编辑" else "编辑文本") }
        }
        WorkspaceDialog.Builder(this).setTitle(item.name)
            .setItems(labels.toTypedArray()) { _, index -> act(labels[index], item, office) }
            .setNegativeButton("取消", null).show()
    }

    internal fun act(label: String, item: Item, office: Boolean = OfficeDocumentActivity.supports(item.path)) {
        when (label) {
            "在文件管理中定位" -> startActivity(
                Intent(this, StorageActivity::class.java)
                    .putExtra("path", item.parent).putExtra("highlight", item.name)
            )
            "在电脑打开" -> WorkspaceDialog.Builder(this).setTitle("在电脑打开？").setMessage(item.path)
                .setNegativeButton("取消", null)
                .setPositiveButton("打开") { _, _ ->
                    task {
                        val response = FileDelay.call(
                            this@FileSearchActivity, origin, "files",
                            JSONObject().put("action", "open").put("path", item.path)
                                .put("file_id", item.fileId)
                        )
                        status(response.optString("notice", "打开请求已发送"))
                    }
                }.show()
            "预览", "手机 WPS 编辑" ->
                if (office) startActivity(
                    Intent(this, OfficeDocumentActivity::class.java)
                        .putExtra("path", item.path).putExtra("file_id", item.fileId)
                ) else FilePreview.show(this, origin, item.path)
            "编辑文本" -> startActivity(
                Intent(this, TextEditorActivity::class.java).putExtra("path", item.path)
            )
        }
    }
}

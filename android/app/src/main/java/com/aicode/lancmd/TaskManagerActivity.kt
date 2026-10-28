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
import com.aicode.lancmd.net.parentOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperCheckbox
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.util.Locale

/** 电脑进程列表与批量结束。界面使用 miuix。 */
class TaskManagerActivity : MiuixActivity() {
    private val sorts = listOf("内存排序", "CPU 排序", "名称排序")

    private val sourceKey by lazy { Prefs.target(this)?.agentId.orEmpty() + "@${origin?.host}:${origin?.port}" }

    internal var query by mutableStateOf("")
    internal var sortIndex by mutableIntStateOf(0)
    internal var automatic by mutableStateOf(true)
    internal var multi by mutableStateOf(false)
    internal var shown by mutableStateOf<List<JSONObject>>(emptyList())
    internal var selectionSummary by mutableStateOf("")
    /** 批量操作的逐项结果，和旧版顶部的「展开逐项结果」一致。 */
    internal var results by mutableStateOf<List<String>>(emptyList())
    private var resultsExpanded by mutableStateOf(false)

    private val selection = linkedMapOf<String, JSONObject>()
    private var restoreKeys = setOf<String>()
    private var busy = false
    internal var batchBusy by mutableStateOf(false)
    private var cancelRequested = false
    private var poll: Job? = null
    private var lastResult: String? = null
    private var lastError = false
    private var items = listOf<JSONObject>()

    private fun key(item: JSONObject) = "${item.optInt("pid")}:${item.optString("created")}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getString("source") == sourceKey) {
            restoreKeys = savedInstanceState.getStringArrayList("selection").orEmpty().toSet()
            multi = savedInstanceState.getBoolean("multi")
        }
        status("点击查看详情，长按或点击多选可批量结束；关键进程和 LanCmd 自身将跳过。")
        content { Screen() }
        updateSelection()
    }

    @Composable
    private fun Screen() = Page("任务管理器") {
        if (results.isNotEmpty()) SectionCard("逐项结果") {
            RowItem(
                title = if (resultsExpanded) "收起逐项结果" else "展开逐项结果（${results.size}）"
            ) { resultsExpanded = !resultsExpanded }
            if (resultsExpanded) Note(results.joinToString("\n").take(100_000))
        }
        SectionCard {
            Field(label = "搜索名称、PID 或路径", value = query, onValueChange = { query = it; filter() })
            ChoiceField(
                items = sorts, title = "排序",
                selectedIndex = sortIndex,
                onSelectedIndexChange = { sortIndex = it; filter() }
            )
            SuperSwitch(
                checked = automatic,
                onCheckedChange = { automatic = it },
                title = "每 3 秒刷新",
                summary = "选择按 PID 和创建时间保留"
            )
            ActionRow {
                Action("刷新", modifier = Modifier.weight(1f)) { refresh() }
                Action(
                    if (multi) "退出多选" else "多选",
                    enabled = !batchBusy,
                    modifier = Modifier.weight(1f)
                ) { toggleMulti() }
            }
        }
        if (multi || batchBusy) SectionCard("批量操作") {
            Note(selectionSummary)
            ActionRow {
                Action("全选结果", enabled = !batchBusy, modifier = Modifier.weight(1f)) { chooseAll() }
                Action("反选", enabled = !batchBusy, modifier = Modifier.weight(1f)) { invert() }
                Action("清空", enabled = !batchBusy, modifier = Modifier.weight(1f)) { clearSelection() }
            }
            Action(
                "结束所选",
                enabled = !batchBusy && selection.values.any { it.optBoolean("can_terminate") }
            ) { confirmTerminateMany(selection.values.map { JSONObject(it.toString()) }) }
            if (batchBusy) Action("停止后续操作") {
                cancelRequested = true
                status("已请求停止，当前请求返回后不再结束后续进程")
            }
        }
        SectionCard("进程") {
            if (shown.isEmpty()) EmptyNote("没有匹配的进程。")
            shown.forEach { item ->
                val cpu = if (item.isNull("cpu")) "采样中"
                else String.format(Locale.US, "%.1f%%", item.optDouble("cpu"))
                val title = "${item.optString("name")} · ${item.optInt("pid")}"
                val summary = "CPU $cpu · ${FileTransfer.humanSize(item.optLong("memory"))}" +
                    if (!item.optBoolean("can_terminate")) " · 受保护 / 受限" else ""
                if (multi) SuperCheckbox(
                    title = title,
                    summary = summary,
                    checked = selection.containsKey(key(item)),
                    onCheckedChange = { toggle(item) },
                    enabled = !batchBusy
                ) else RowItem(title, summary, enabled = !batchBusy) { details(item) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", sourceKey)
        outState.putBoolean("multi", multi)
        outState.putStringArrayList("selection", ArrayList(selection.keys + restoreKeys))
        super.onSaveInstanceState(outState)
    }

    internal fun toggleMulti() {
        if (batchBusy) return
        multi = !multi
        if (!multi) selection.clear()
        updateSelection()
    }

    internal fun refresh() {
        if (busy || batchBusy) return
        busy = true
        task {
            try {
                val data = pinnedCall(origin, "tasks")
                val list = data.getJSONArray("items")
                items = (0 until list.length()).map { list.getJSONObject(it) }
                val current = items.associateBy { key(it) }
                selection.keys.retainAll(current.keys)
                for (id in selection.keys.toList()) selection[id] = current.getValue(id)
                for (id in restoreKeys) current[id]?.let { selection[id] = it }
                restoreKeys = emptySet()
                filter()
                status(lastResult ?: "${items.size} 个进程 · 点击详情，长按多选", lastError)
            } finally { busy = false }
        }
    }

    private fun filter() {
        val text = query.trim()
        val matches = items.filter {
            (it.optString("name") + " " + it.optInt("pid") + " " + it.optString("path")).contains(text, true)
        }
        shown = when (sortIndex) {
            1 -> matches.sortedByDescending { it.optDouble("cpu", -1.0) }
            2 -> matches.sortedBy { it.optString("name").lowercase() }
            else -> matches.sortedByDescending { it.optLong("memory") }
        }
        updateSelection()
    }

    internal fun toggle(item: JSONObject) {
        if (batchBusy) return
        val id = key(item)
        if (selection.containsKey(id)) selection.remove(id)
        else if (selection.size >= 100) { status("每批最多选择 100 个进程，请先筛选", true); return }
        else selection[id] = item
        updateSelection()
    }

    internal fun chooseAll() {
        if ((selection.keys + shown.map { key(it) }).toSet().size > 100) {
            status("超过 100 个进程，请先筛选或分批选择", true); return
        }
        shown.forEach { selection[key(it)] = it }
        updateSelection()
    }

    internal fun invert() {
        val next = selection.toMutableMap()
        shown.forEach { if (next.containsKey(key(it))) next.remove(key(it)) else next[key(it)] = it }
        if (next.size > 100) { status("反选后超过 100 个进程，请先筛选", true); return }
        selection.clear()
        selection.putAll(next)
        updateSelection()
    }

    internal fun clearSelection() {
        selection.clear()
        updateSelection()
    }

    private fun updateSelection() {
        val protectedCount = selection.values.count { !it.optBoolean("can_terminate") }
        selectionSummary = "已选 ${selection.size} 项 · $protectedCount 项受保护/受限 · 当前显示 ${shown.size} 项"
    }

    internal fun details(item: JSONObject) {
        val path = item.optString("path")
        val message = "PID ${item.getInt("pid")} · 父进程 ${item.optInt("parent")}\n" +
            "内存 ${FileTransfer.humanSize(item.optLong("memory"))}\n$path\n\n" +
            if (item.optBoolean("can_terminate")) "结束会丢失未保存内容。" else "此进程受到保护或当前权限不足。"
        val dialog = WorkspaceDialog.Builder(this).setTitle(item.optString("name")).setMessage(message)
            .setNegativeButton("关闭", null)
            .setNeutralButton("文件 / 应用锁") { _, _ ->
                if (path.isNotEmpty()) WorkspaceDialog.Builder(this)
                    .setItems(arrayOf("在文件管理中定位", "应用锁")) { _, index ->
                        task {
                            pinnedCall(origin, "capabilities")
                            startActivity(
                                Intent(
                                    this@TaskManagerActivity,
                                    if (index == 0) StorageActivity::class.java else AppLocksActivity::class.java
                                ).putExtra("path", if (index == 0) parentOf(path) else path)
                            )
                        }
                    }.show()
            }
            .setPositiveButton("结束进程") { _, _ -> confirmTerminate(item) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = item.optBoolean("can_terminate")
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = path.isNotEmpty()
        }
        dialog.show()
    }

    internal fun confirmTerminate(item: JSONObject) = confirmTerminateMany(listOf(JSONObject(item.toString())))

    internal fun confirmTerminateMany(targets: List<JSONObject>) {
        if (batchBusy || targets.isEmpty()) return
        WorkspaceDialog.Builder(this)
            .setTitle(if (targets.size == 1) "结束 ${targets[0].optString("name")}？" else "结束所选 ${targets.size} 项？")
            .setMessage(
                targets.take(8).joinToString("\n") { "${it.optString("name")} · PID ${it.optInt("pid")}" } +
                    "\n\n受保护进程自动跳过。未保存内容可能丢失；停止批量操作只停止后续请求，" +
                    "不能撤销已结束的进程。结果直接显示在页面顶部。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("结束") { _, _ -> runTerminate(targets) }.show()
    }

    private fun runTerminate(targets: List<JSONObject>) {
        if (batchBusy) return
        batchBusy = true
        cancelRequested = false
        updateSelection()
        results = emptyList()
        task {
            val lines = ArrayList<String>()
            var success = 0
            var failed = 0
            var skipped = 0
            try {
                for ((index, item) in targets.withIndex()) {
                    val label = "${item.optString("name")} (PID ${item.optInt("pid")})"
                    if (cancelRequested) { skipped++; lines.add("未执行 · $label"); continue }
                    if (!item.optBoolean("can_terminate")) {
                        skipped++; lines.add("跳过 · $label：受保护或权限不足"); continue
                    }
                    status("正在结束 ${index + 1}/${targets.size} · $label")
                    try {
                        pinnedCall(
                            origin, "tasks",
                            JSONObject().put("action", "terminate").put("pid", item.getInt("pid"))
                                .put("created", item.getString("created"))
                        )
                        success++
                        selection.remove(key(item))
                        lines.add("成功 · 已结束 $label")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed++
                        lines.add("失败 · $label：${e.message}")
                        if (origin?.closed != false ||
                            runCatching { ConnectionManager.sessionClient() }.getOrNull() !== origin
                        ) cancelRequested = true
                    }
                }
                lastResult = "结束进程：成功 $success，失败 $failed，跳过/未执行 $skipped"
                lastError = failed > 0
                results = lines
                resultsExpanded = lastError
                status(lastResult!!, lastError)
                if (failed > 0) automatic = false
            } finally { batchBusy = false; updateSelection() }
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        poll = lifecycleScope.launch {
            refresh()
            while (isActive) { delay(3000); if (automatic) refresh() }
        }
    }

    override fun onStop() {
        poll?.cancel(); super.onStop()
    }
}

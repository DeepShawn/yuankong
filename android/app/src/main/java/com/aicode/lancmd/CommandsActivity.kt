package com.aicode.lancmd

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** 本地命令列表与电脑端延迟执行计划。界面使用 miuix。 */
class CommandsActivity : MiuixActivity() {
    private val stateNames = mapOf(
        "pending" to "待执行", "waiting" to "等待中", "running" to "执行中",
        "completed" to "已完成", "failed" to "失败", "cancelled" to "已取消"
    )

    /** 列表里的一条命令。 */
    internal class Step(val line: String, val detail: String)

    /** 电脑上的一个计划。 */
    internal class Batch(val id: String, val name: String, val detail: String, val output: String, val pending: Boolean)

    private val prefs by lazy { getSharedPreferences("command_lists", MODE_PRIVATE) }
    private var recipes = JSONArray()

    internal var selected by mutableIntStateOf(0)
    internal var names by mutableStateOf<List<String>>(emptyList())
    internal var steps by mutableStateOf<List<Step>>(emptyList())
    internal var batches by mutableStateOf<List<Batch>>(emptyList())

    private var poll: Job? = null
    private var refreshing = false
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recipes = runCatching { JSONArray(prefs.getString("lists", "[]")) }.getOrElse { JSONArray() }
        if (recipes.length() == 0) recipes.put(newRecipe("命令列表 1"))
        status("顺序执行：第一条从启动计时，后续每条在上一条结束后再等待指定秒数。")
        content { Screen() }
        refreshLists()
        intent.getStringExtra("line").orEmpty().takeIf { it.isNotBlank() }?.let { line ->
            editCommand(null, line, intent.getStringExtra("shell") ?: "cmd")
        }
    }

    @Composable
    private fun Screen() = Page("命令列表 / 延迟执行") {
        SectionCard("命令列表") {
            ChoiceField(
                items = names, title = "当前列表",
                selectedIndex = selected.coerceIn(0, (names.size - 1).coerceAtLeast(0)),
                onSelectedIndexChange = { selected = it; refreshLists() }
            )
            ActionRow {
                Action("新列表", modifier = Modifier.weight(1f)) { nameDialog(false) }
                Action("重命名", modifier = Modifier.weight(1f)) { nameDialog(true) }
                Action("删除列表", modifier = Modifier.weight(1f)) { deleteRecipe() }
            }
        }
        SectionCard("执行顺序") {
            if (steps.isEmpty()) EmptyNote("列表为空，添加命令并设置等待秒数。")
            steps.forEachIndexed { index, step ->
                InfoItem(step.line, step.detail)
                ActionRow {
                    Action("编辑", modifier = Modifier.weight(1f)) { editCommand(index) }
                    Action("上移", enabled = index > 0, modifier = Modifier.weight(1f)) { moveUp(index) }
                    Action("删除", modifier = Modifier.weight(1f)) { removeStep(index) }
                }
            }
            ActionRow {
                Action("添加命令", modifier = Modifier.weight(1f)) { editCommand(null) }
                Action("刷新电脑任务", modifier = Modifier.weight(1f)) { refreshJobs() }
            }
            Action("启动当前命令列表", primary = true) { submit() }
            Note("电脑端负责计时，手机断线仍继续；接收端退出会丢弃未完成计划。单条最长运行 300 秒，失败后继续下一条。")
        }
        SectionCard("电脑上的任务") {
            if (batches.isEmpty()) EmptyNote("当前没有命令计划")
            batches.forEach { batch ->
                InfoItem(batch.name, batch.detail)
                ActionRow {
                    Action("查看执行结果", modifier = Modifier.weight(1f)) { showOutput(batch) }
                    if (batch.pending) Action("取消尚未执行的命令", modifier = Modifier.weight(1f)) {
                        confirmCancel(batch)
                    }
                }
            }
        }
    }

    private fun newRecipe(name: String) = JSONObject()
        .put("id", UUID.randomUUID().toString()).put("name", name).put("items", JSONArray())

    private fun recipe() = recipes.getJSONObject(selected.coerceIn(0, recipes.length() - 1))

    private fun save() {
        prefs.edit().putString("lists", recipes.toString()).apply()
    }

    /** 列表名、当前列表内容与本地存档都从 [recipes] 重算。 */
    private fun refreshLists() {
        names = (0 until recipes.length()).map { recipes.getJSONObject(it).optString("name") }
        selected = selected.coerceIn(0, recipes.length() - 1)
        val items = recipe().getJSONArray("items")
        steps = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            Step(
                line = item.optString("line"),
                detail = "第 ${index + 1} 条 · ${item.optString("shell").uppercase()} · 等待 ${item.optInt("delay")} 秒"
            )
        }
        save()
    }

    internal fun moveUp(index: Int) {
        if (index <= 0) return
        val items = recipe().getJSONArray("items")
        val previous = items.get(index - 1)
        val current = items.get(index)
        items.put(index - 1, current)
        items.put(index, previous)
        refreshLists()
    }

    internal fun removeStep(index: Int) {
        recipe().getJSONArray("items").remove(index)
        refreshLists()
    }

    internal fun nameDialog(rename: Boolean) {
        if (!rename && recipes.length() >= 30) { status("最多保存 30 个列表", true); return }
        val input = dialogInput(text = if (rename) recipe().optString("name") else "").apply {
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (rename) "列表名称" else "新命令列表").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "请输入名称"; return@setOnClickListener }
                if (rename) recipe().put("name", name)
                else { recipes.put(newRecipe(name)); selected = recipes.length() - 1 }
                refreshLists()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    internal fun deleteRecipe() {
        AlertDialog.Builder(this).setTitle("删除本地命令列表？")
            .setMessage("已经提交到电脑的任务不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                recipes.remove(selected)
                if (recipes.length() == 0) recipes.put(newRecipe("命令列表 1"))
                selected = selected.coerceAtMost(recipes.length() - 1)
                refreshLists()
            }.show()
    }

    internal fun editCommand(index: Int?, seed: String = "", seedShell: String = "cmd") {
        val target = recipe()
        val items = target.getJSONArray("items")
        if (index == null && items.length() >= 50) { status("每个列表最多 50 条命令", true); return }
        val existing = index?.let { items.getJSONObject(it) }
        val box = dialogBox()
        val shell = dialogSpinner(
            listOf("CMD", "PowerShell"),
            if ((existing?.optString("shell") ?: seedShell) == "powershell") 1 else 0
        )
        val line = dialogInput(hint = "输入命令", text = existing?.optString("line") ?: seed, singleLine = false).apply {
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(8192))
            contentDescription = "命令内容"
        }
        val seconds = dialogInput(text = (existing?.optInt("delay") ?: 0).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            contentDescription = "等待秒数"
        }
        box.addView(shell); box.addView(line); box.addView(seconds)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index == null) "添加命令" else "编辑命令")
            .setMessage("第一条从启动计时，后续从上一条结束计时。")
            .setView(box)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val command = line.text.toString().trim()
                val wait = seconds.text.toString().toIntOrNull()
                if (command.isEmpty()) { line.error = "请输入命令"; return@setOnClickListener }
                if (wait == null || wait !in 0..86400) { seconds.error = "请输入 0 到 86400 秒"; return@setOnClickListener }
                val value = JSONObject().put("line", command).put("delay", wait)
                    .put("shell", if (shell.selectedItemPosition == 1) "powershell" else "cmd")
                if (index == null) items.put(value) else items.put(index, value)
                target.put("items", items)
                for (i in 0 until recipes.length()) {
                    if (recipes.getJSONObject(i).optString("id") == target.optString("id")) recipes.put(i, target)
                }
                refreshLists()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    internal fun submit() {
        if (submitting) return
        val value = JSONObject(recipe().toString())
        val items = value.getJSONArray("items")
        if (items.length() == 0) { status("请先添加命令", true); return }
        AlertDialog.Builder(this).setTitle("启动 ${value.optString("name")}？")
            .setMessage(
                "共 ${items.length()} 条，按列表顺序执行。每条先等待设定秒数，上一条结束后才开始下一条的等待。\n\n" +
                    "手机断线仍继续；接收端退出则取消。已开始的命令不能通过取消计划撤销。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("启动") { _, _ ->
                if (!submitting) {
                    submitting = true
                    task {
                        try {
                            pinnedCall(
                                origin, "commands",
                                JSONObject().put("action", "add").put("name", value.optString("name"))
                                    .put("items", items)
                            )
                            status("计划已提交，电脑端负责等待和执行")
                            refreshJobs()
                        } finally { submitting = false }
                    }
                }
            }.show()
    }

    private fun showOutput(batch: Batch) {
        val text = android.widget.TextView(this).apply {
            this.text = batch.output
            setTextIsSelectable(true)
            setPadding(px(16), px(12), px(16), px(12))
        }
        AlertDialog.Builder(this).setTitle("执行结果（刷新后更新）")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("关闭", null).show()
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    internal fun confirmCancel(batch: Batch) {
        AlertDialog.Builder(this).setTitle("取消此计划的后续命令？")
            .setMessage("已经开始的命令继续运行，其余命令取消。")
            .setNegativeButton("返回", null)
            .setPositiveButton("取消计划") { _, _ ->
                task {
                    pinnedCall(origin, "commands", JSONObject().put("action", "cancel").put("batch_id", batch.id))
                    refreshJobs()
                }
            }.show()
    }

    internal fun refreshJobs() {
        if (refreshing) return
        refreshing = true
        task {
            try {
                val list = pinnedCall(origin, "commands").getJSONArray("batches")
                batches = (0 until list.length()).map { index ->
                    val batch = list.getJSONObject(index)
                    val items = batch.getJSONArray("items")
                    var pending = false
                    val detail = (0 until items.length()).joinToString("\n") { n ->
                        val item = items.getJSONObject(n)
                        val state = item.optString("status")
                        if (state in listOf("pending", "waiting")) pending = true
                        val due = if (state == "waiting") {
                            " · 至 " + DateFormat.getTimeInstance()
                                .format(Date((item.optDouble("due") * 1000).toLong()))
                        } else ""
                        "${n + 1}. ${stateNames[state] ?: state}$due · ${item.optString("line").take(120)}"
                    }
                    val output = (0 until items.length()).joinToString("\n\n") { n ->
                        val item = items.getJSONObject(n)
                        val result = item.optJSONObject("result")
                        "${n + 1}. ${item.optString("line")}\n${stateNames[item.optString("status")]}\n" +
                            (
                                result?.let { "退出码 ${it.optInt("code")}\n${it.optString("out")}${it.optString("err")}" }
                                    ?: item.optString("error")
                                )
                    }
                    Batch(
                        id = batch.getString("id"),
                        name = batch.optString("name"),
                        detail = detail,
                        output = output,
                        pending = pending
                    )
                }
            } finally { refreshing = false }
        }
    }

    override fun onStart() {
        super.onStart()
        poll = lifecycleScope.launch {
            while (isActive) { if (origin?.closed == false) refreshJobs(); delay(3000) }
        }
    }

    override fun onStop() {
        poll?.cancel(); super.onStop()
    }
}

package com.aicode.lancmd

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class CommandsActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val prefs by lazy { getSharedPreferences("command_lists", MODE_PRIVATE) }
    private var recipes = JSONArray()
    private var selected = 0
    private lateinit var selector: Spinner
    private lateinit var editor: LinearLayout
    private lateinit var jobs: LinearLayout
    private var poll: Job? = null
    private var refreshing = false
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("命令列表 / 延迟执行", "顺序执行：第一条从启动计时，后续每条在上一条结束后再等待指定秒数。", scrollable = true)
        origin
        recipes = runCatching { JSONArray(prefs.getString("lists", "[]")) }.getOrElse { JSONArray() }
        if (recipes.length() == 0) recipes.put(newRecipe("命令列表 1"))
        val saved = panel("命令列表")
        selector = styleSpinner(Spinner(this))
        saved.addView(fieldRow("当前列表", selector))
        val manage = listOf("新列表" to { nameDialog(false) }, "重命名" to { nameDialog(true) }, "删除列表" to { deleteRecipe() })
            .map { (name, run) -> button(name, run).apply { textSize = Ui.TEXT_CAPTION } }
        saved.addView(actions(*manage.toTypedArray()))
        editor = panel()
        body.addView(actions(
            button("添加命令") { editCommand(null) },
            button("刷新电脑任务") { refreshJobs() }))
        body.addView(primary(button("启动当前命令列表") { submit() }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(caption("电脑端负责计时，手机断线仍继续；接收端退出会丢弃未完成计划。单条最长运行 300 秒，失败后继续下一条。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(Ui.GAP) })
        jobs = panel()
        updateSelector()
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = position; renderEditor()
            }
        }
        if (intent.getStringExtra("line").orEmpty().isNotBlank()) {
            editCommand(null, intent.getStringExtra("line").orEmpty(), intent.getStringExtra("shell") ?: "cmd")
        }
    }

    private fun newRecipe(name: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("items", JSONArray())
    private fun recipe() = recipes.getJSONObject(selected.coerceIn(0, recipes.length() - 1))
    private fun save() { prefs.edit().putString("lists", recipes.toString()).apply() }

    private fun updateSelector() {
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            (0 until recipes.length()).map { recipes.getJSONObject(it).optString("name") })
        selector.setSelection(selected.coerceIn(0, recipes.length() - 1))
        renderEditor(); save()
    }

    private fun nameDialog(rename: Boolean) {
        if (!rename && recipes.length() >= 30) { status("最多保存 30 个列表", true); return }
        val input = styleInput(EditText(this)).apply { setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(60)); if (rename) setText(recipe().optString("name")) }
        val dialog = AlertDialog.Builder(this).setTitle(if (rename) "列表名称" else "新命令列表").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isBlank()) { input.error = "请输入名称"; return@setOnClickListener }
            if (rename) recipe().put("name", name)
            else { recipes.put(newRecipe(name)); selected = recipes.length() - 1 }
            updateSelector(); dialog.dismiss()
        } }
        dialog.show()
    }

    private fun deleteRecipe() {
        AlertDialog.Builder(this).setTitle("删除本地命令列表？").setMessage("已经提交到电脑的任务不受影响。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                recipes.remove(selected)
                if (recipes.length() == 0) recipes.put(newRecipe("命令列表 1"))
                selected = selected.coerceAtMost(recipes.length() - 1); updateSelector()
            }.show()
    }

    private fun renderEditor() {
        editor.removeAllViews()
        val items = recipe().getJSONArray("items")
        editor.addView(Ui.sectionLabel(this, "执行顺序"))
        if (items.length() == 0) editor.addView(emptyState("列表为空，添加命令并设置等待秒数。"))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (i > 0) editor.addView(divider(), Ui.dividerParams(this))
            editor.addView(listRow("${i + 1}. ${item.optString("line")}",
                "${item.optString("shell").uppercase()} · 等待 ${item.optInt("delay")} 秒"))
            val keys = listOf("编辑" to { editCommand(i) }, "上移" to {
                if (i > 0) { val previous = items.get(i - 1); items.put(i - 1, item); items.put(i, previous); save(); renderEditor() }
            }, "删除" to { items.remove(i); save(); renderEditor() }).map { (title, run) ->
                button(title, run).apply { textSize = Ui.TEXT_CAPTION; if (title == "上移") isEnabled = i > 0 }
                    .also { if (title == "删除") danger(it) }
            }
            editor.addView(actions(*keys.toTypedArray()))
        }
    }

    private fun editCommand(index: Int?, seed: String = "", seedShell: String = "cmd") {
        val target = recipe()
        val items = target.getJSONArray("items")
        if (index == null && items.length() >= 50) { status("每个列表最多 50 条命令", true); return }
        val existing = index?.let { items.getJSONObject(it) }
        val box = Ui.Column(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        val shell = styleSpinner(Spinner(this)).apply {
            adapter = ArrayAdapter(this@CommandsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("CMD", "PowerShell"))
            setSelection(if ((existing?.optString("shell") ?: seedShell) == "powershell") 1 else 0)
        }
        box.addView(fieldRow("终端类型", shell))
        val line = styleInput(EditText(this)).apply {
            hint = "输入命令"; minLines = 3; maxLines = 6; setText(existing?.optString("line") ?: seed)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(8192))
        }
        box.addView(fieldRow("命令内容", line))
        val seconds = styleInput(EditText(this)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText((existing?.optInt("delay") ?: 0).toString())
            filters = arrayOf(InputFilter.LengthFilter(5))
        }
        box.addView(fieldRow("等待秒数", seconds))
        box.addView(caption("第一条从启动计时，后续从上一条结束计时。"))
        val dialog = AlertDialog.Builder(this).setTitle(if (index == null) "添加命令" else "编辑命令").setView(box)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val command = line.text.toString().trim()
            val delay = seconds.text.toString().toIntOrNull()
            if (command.isEmpty()) { line.error = "请输入命令"; return@setOnClickListener }
            if (delay == null || delay !in 0..86400) { seconds.error = "请输入 0 到 86400 秒"; return@setOnClickListener }
            val value = JSONObject().put("line", command).put("delay", delay).put("shell", if (shell.selectedItemPosition == 1) "powershell" else "cmd")
            if (index == null) items.put(value) else items.put(index, value)
            target.put("items", items)
            for (i in 0 until recipes.length()) {
                if (recipes.getJSONObject(i).optString("id") == target.optString("id")) recipes.put(i, target)
            }
            save(); renderEditor(); dialog.dismiss()
        } }
        dialog.show()
    }

    private fun submit() {
        if (submitting) return
        val value = JSONObject(recipe().toString())
        val items = value.getJSONArray("items")
        if (items.length() == 0) { status("请先添加命令", true); return }
        AlertDialog.Builder(this).setTitle("启动 ${value.optString("name")}？")
            .setMessage("共 ${items.length()} 条，按列表顺序执行。每条先等待设定秒数，上一条结束后才开始下一条的等待。\n\n手机断线仍继续；接收端退出则取消。已开始的命令不能通过取消计划撤销。")
            .setNegativeButton("取消", null).setPositiveButton("启动") { _, _ ->
                if (!submitting) {
                    submitting = true
                    task {
                        try {
                            pinnedCall(origin, "commands", JSONObject().put("action", "add").put("name", value.optString("name")).put("items", items))
                            status("计划已提交，电脑端负责等待和执行")
                            refreshJobs()
                        } finally { submitting = false }
                    }
                }
            }.show()
    }

    private fun refreshJobs() {
        if (refreshing) return
        refreshing = true
        task {
            try {
                val batches = pinnedCall(origin, "commands").getJSONArray("batches")
                jobs.removeAllViews(); jobs.addView(label("电脑上的任务", 16f))
                if (batches.length() == 0) jobs.addView(label("当前没有命令计划", 13f, R.color.dim))
                val names = mapOf("pending" to "待执行", "waiting" to "等待中", "running" to "执行中", "completed" to "已完成", "failed" to "失败", "cancelled" to "已取消")
                for (i in 0 until batches.length()) {
                    val batch = batches.getJSONObject(i)
                    val items = batch.getJSONArray("items")
                    jobs.addView(label(batch.optString("name"), 15f).apply { setPadding(0, dp(14), 0, 0) })
                    var pending = false
                    val detail = (0 until items.length()).joinToString("\n") { n ->
                        val item = items.getJSONObject(n)
                        val state = item.optString("status")
                        if (state in listOf("pending", "waiting")) pending = true
                        val due = if (state == "waiting") " · 至 " + DateFormat.getTimeInstance().format(Date((item.optDouble("due") * 1000).toLong())) else ""
                        "${n + 1}. ${names[state] ?: state}$due · ${item.optString("line").take(120)}"
                    }
                    jobs.addView(label(detail, 12f, R.color.dim))
                    jobs.addView(button("查看执行结果") {
                        val output = (0 until items.length()).joinToString("\n\n") { n ->
                            val item = items.getJSONObject(n); val result = item.optJSONObject("result")
                            "${n + 1}. ${item.optString("line")}\n${names[item.optString("status")]}\n" +
                                (result?.let { "退出码 ${it.optInt("code")}\n${it.optString("out")}${it.optString("err")}" } ?: item.optString("error"))
                        }
                        val text = android.widget.TextView(this@CommandsActivity).apply {
                            this.text = output; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12))
                        }
                        AlertDialog.Builder(this@CommandsActivity).setTitle("执行结果（刷新后更新）")
                            .setView(android.widget.ScrollView(this@CommandsActivity).apply { addView(text) }).setPositiveButton("关闭", null).show()
                    })
                    if (pending) jobs.addView(button("取消尚未执行的命令") {
                        AlertDialog.Builder(this@CommandsActivity).setTitle("取消此计划的后续命令？")
                            .setMessage("已经开始的命令继续运行，其余命令取消。")
                            .setNegativeButton("返回", null).setPositiveButton("取消计划") { _, _ -> task {
                                pinnedCall(origin, "commands", JSONObject().put("action", "cancel").put("batch_id", batch.getString("id")))
                                refreshJobs()
                            } }.show()
                    })
                }
            } finally { refreshing = false }
        }
    }

    override fun onStart() {
        super.onStart()
        poll = lifecycleScope.launch { while (isActive) { if (origin?.closed == false) refreshJobs(); delay(3000) } }
    }
    override fun onStop() { poll?.cancel(); super.onStop() }
}

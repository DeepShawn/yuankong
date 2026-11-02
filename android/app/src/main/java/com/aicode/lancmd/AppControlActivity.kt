package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 希沃白板、WPS、QQ 的快捷动作与窗口隐形。
 *
 * 每个动作都是电脑端预设的按键（可在 app-control.json 里改），
 * 发送消息走前台输入 + 剪贴板粘贴；隐形窗口在本机桌面不可见，
 * 进程继续运行，随时可以从这里恢复。
 */
class AppControlActivity : MiuixActivity() {
    internal class Item(val id: String, val label: String, val keys: String)
    internal class Win(
        val handle: String, val pid: Int, val title: String, val name: String,
        val profile: String, val minimized: Boolean
    )
    internal class Hidden(val handle: String, val title: String, val name: String, val pid: Int)

    internal var windows by mutableStateOf<List<Win>>(emptyList())
    internal var profiles by mutableStateOf<Map<String, List<Item>>>(emptyMap())
    internal var labels by mutableStateOf<Map<String, String>>(emptyMap())
    internal var hidden by mutableStateOf<List<Hidden>>(emptyList())
    internal var selected by mutableIntStateOf(0)
    internal var message by mutableStateOf("")
    internal var enter by mutableStateOf(true)
    internal var busy by mutableStateOf(false)
    internal var supported by mutableStateOf(true)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status("选择窗口后使用预设动作；隐形只在电脑桌面隐藏，仍然可以从这里操作和恢复")
        content { Screen() }
        task {
            val caps = pinnedCall(origin, "capabilities").optJSONArray("features")
            supported = caps != null && (0 until caps.length()).any { caps.optString(it) == "app_control" }
            if (!supported) {
                status("请完整更新 Windows 接收端后使用应用快捷控制", true)
                return@task
            }
            load()
        }
    }

    @Composable
    private fun Screen() = Page("应用快捷控制") {
        SectionCard("目标窗口") {
            if (windows.isEmpty()) EmptyNote("没有可控制的窗口。请先在电脑上打开希沃白板、WPS 或 QQ。")
            else {
                ChoiceField(
                    items = windows.map { label(it) },
                    title = "当前窗口",
                    selectedIndex = selected.coerceIn(0, windows.size - 1),
                    enabled = !busy
                ) { selected = it }
                ActionRow {
                    Action("切到前台", enabled = !busy, modifier = Modifier.weight(1f)) { focus() }
                    Action("隐藏窗口", enabled = !busy, modifier = Modifier.weight(1f)) { hide() }
                    Action("自定义按键", enabled = !busy, modifier = Modifier.weight(1f)) { customKeys() }
                }
            }
            Action("刷新窗口", enabled = !busy) { load() }
        }
        SectionCard("快捷动作") {
            val window = current()
            val items = window?.let { profiles[it.profile] }.orEmpty()
            if (window == null) EmptyNote("先选择一个窗口")
            else if (items.isEmpty()) EmptyNote("这个窗口没有预设动作；可用「自定义按键」发送组合键，例如 CTRL+S。")
            else items.chunked(2).forEach { row ->
                ActionRow {
                    row.forEach { item ->
                        Action("${item.label}${if (item.keys.isBlank()) "" else " · " + item.keys}",
                            enabled = !busy, modifier = Modifier.weight(1f)) { act(item.id, "message") }
                    }
                    if (row.size == 1) Action("前台执行", enabled = !busy, modifier = Modifier.weight(1f)) {
                        act(row[0].id, "input")
                    }
                }
            }
            Note("「前台执行」会先把窗口切到前台再用系统输入，适用于后台窗口不响应按键的程序。")
        }
        SectionCard("发送消息") {
            Field("消息内容", message, { message = it }, singleLine = false, maxLines = 4)
            SelectionRow("发送后回车", "QQ 等聊天窗口用它直接发送", enter, { enter = it })
            Action("粘贴到当前窗口", enabled = !busy && message.isNotBlank(), primary = true) { send() }
            Note("消息通过剪贴板粘贴，发送后会尽量还原电脑原来的剪贴板内容。")
        }
        SectionCard("隐形应用") {
            if (hidden.isEmpty()) EmptyNote("当前没有被隐藏的窗口。")
            hidden.forEach { item ->
                InfoItem(item.title.ifBlank { item.name }, "${item.name} · PID ${item.pid} · 桌面不可见")
                ActionRow {
                    Action("恢复显示", enabled = !busy, modifier = Modifier.weight(1f)) { show(item) }
                }
            }
            if (hidden.isNotEmpty()) Action("全部恢复显示", enabled = !busy) { restoreAll() }
            Note("隐藏的窗口仍在运行并占用内存；接收端停止或升级时会自动把窗口恢复显示，不会留下看不见的程序。")
        }
    }

    private fun label(item: Win): String {
        val profile = labels[item.profile]?.takeIf { it.isNotBlank() } ?: item.name
        return "${item.title.ifBlank { item.name }} · $profile"
    }

    internal fun current(): Win? = windows.getOrNull(selected.coerceIn(0, (windows.size - 1).coerceAtLeast(0)))

    private fun load(silent: Boolean = false) {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "app_control")
                val list = result.optJSONArray("windows") ?: JSONArray()
                windows = (0 until list.length()).map { index ->
                    val item = list.getJSONObject(index)
                    Win(item.getString("handle"), item.optInt("pid"), item.optString("title"),
                        item.optString("name"), item.optString("profile"), item.optBoolean("minimized"))
                }
                val actionList = result.optJSONArray("profiles") ?: JSONArray()
                val table = LinkedHashMap<String, List<Item>>()
                val names = LinkedHashMap<String, String>()
                for (index in 0 until actionList.length()) {
                    val profile = actionList.getJSONObject(index)
                    names[profile.getString("id")] = profile.optString("label")
                    val actions = profile.optJSONArray("actions") ?: JSONArray()
                    table[profile.getString("id")] = (0 until actions.length()).map { position ->
                        val action = actions.getJSONObject(position)
                        Item(action.getString("id"), action.optString("label"), action.optString("keys"))
                    }
                }
                profiles = table
                labels = names
                val hiddenList = result.optJSONArray("hidden") ?: JSONArray()
                hidden = (0 until hiddenList.length()).map { index ->
                    val item = hiddenList.getJSONObject(index)
                    Hidden(item.getString("handle"), item.optString("title"), item.optString("name"), item.optInt("pid"))
                }
                selected = selected.coerceIn(0, (windows.size - 1).coerceAtLeast(0))
                if (!silent) status(if (windows.isEmpty()) "没有可控制的窗口"
                                    else "${windows.size} 个窗口，${hidden.size} 个隐形中")
            } finally { busy = false }
        }
    }

    internal fun focus() {
        val window = current() ?: return
        call(JSONObject().put("action", "focus").put("handle", window.handle).put("pid", window.pid),
            "已切换到前台")
    }

    internal fun act(name: String, mode: String) {
        val window = current() ?: return
        call(JSONObject().put("action", "act").put("name", name).put("mode", mode)
            .put("handle", window.handle).put("pid", window.pid), "${window.name} 已执行")
    }

    internal fun send() {
        val window = current() ?: return
        if (message.isBlank()) { status("请输入消息内容", true); return }
        val text = message
        call(JSONObject().put("action", "send").put("text", text).put("enter", enter)
            .put("handle", window.handle).put("pid", window.pid), "已发送 ${text.take(20)}")
    }

    internal fun hide() {
        val window = current() ?: return
        WorkspaceDialog.Builder(this).setTitle("隐藏 ${window.title.ifBlank { window.name }}？")
            .setMessage("窗口会从电脑桌面和任务栏消失，程序继续运行；可在这里随时恢复，接收端退出时也会自动恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("隐藏") { _, _ ->
                call(JSONObject().put("action", "hide").put("handle", window.handle)
                    .put("pid", window.pid), "已隐藏，可在「隐形应用」中恢复")
            }.show()
    }

    internal fun show(item: Hidden) {
        call(JSONObject().put("action", "show").put("handle", item.handle), "已恢复 ${item.title}")
    }

    internal fun restoreAll() {
        call(JSONObject().put("action", "restore_all"), "已恢复全部隐形窗口")
    }

    internal fun customKeys() {
        val window = current() ?: return
        val input = dialogInput(hint = "例如 CTRL+S 或 PAGEDOWN")
        val dialog = WorkspaceDialog.Builder(this)
            .setTitle("发送组合键").setMessage("支持 CTRL / ALT / SHIFT / WIN 组合，或 F1–F12、方向键。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("发送", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keys = input.text.toString().trim()
                if (keys.isBlank()) { input.error = "请输入按键"; return@setOnClickListener }
                dialog.dismiss()
                call(JSONObject().put("action", "act").put("name", "").put("keys", keys)
                    .put("mode", "message").put("handle", window.handle).put("pid", window.pid),
                    "已发送 $keys")
            }
        }
        dialog.show()
    }

    private fun call(body: JSONObject, done: String) {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "app_control", body)
                status("$done（${result.optString("name")} · ${result.optString("mode")}）")
                if (result.optString("text").isNotBlank()) message = ""
            } finally {
                busy = false
                load(silent = true)
            }
        }
    }
}

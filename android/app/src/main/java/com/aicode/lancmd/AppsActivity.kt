package com.aicode.lancmd

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONObject

/** 电脑上有窗口的桌面程序：切换前台、最小化或正常关闭。界面使用 miuix。 */
class AppsActivity : MiuixActivity() {
    internal class Window(
        val title: String,
        val name: String,
        val pid: Int,
        val handle: String,
        val minimized: Boolean
    )

    internal var windows by mutableStateOf<List<Window>>(emptyList())
    internal var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status("查看有窗口的桌面应用，切换、最小化或正常关闭")
        content { Screen() }
        load()
    }

    @Composable
    private fun Screen() = Page("运行中的桌面程序") {
        SectionCard {
            Action("刷新程序列表", enabled = !busy) { load() }
        }
        SectionCard("桌面窗口") {
            if (windows.isEmpty()) EmptyNote("没有可管理的桌面窗口。")
            windows.forEach { window ->
                val detail = "${window.name} · PID ${window.pid}" + if (window.minimized) " · 已最小化" else ""
                InfoItem(window.title.ifBlank { window.name }, detail)
                ActionRow {
                    Action("切换到前台", enabled = !busy, modifier = Modifier.weight(1f)) {
                        apply(window, "activate")
                    }
                    Action("最小化", enabled = !busy, modifier = Modifier.weight(1f)) {
                        apply(window, "minimize")
                    }
                    Action("关闭", enabled = !busy, modifier = Modifier.weight(1f)) {
                        confirmClose(window)
                    }
                }
            }
        }
    }

    private fun change(window: Window, action: String): JSONObject = JSONObject()
        .put("action", action).put("handle", window.handle).put("pid", window.pid)

    internal fun apply(window: Window, action: String) = load(change(window, action))

    internal fun confirmClose(window: Window) {
        AlertDialog.Builder(this).setTitle("关闭 ${window.title}？")
            .setMessage("向此窗口发送正常关闭请求，未保存内容由该程序处理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ -> load(change(window, "close")) }
            .show()
    }

    private fun load(params: JSONObject = JSONObject()) {
        if (busy) return
        busy = true
        task {
            try {
                if (params.length() > 0) pinnedCall(origin, "apps", params)
                val result = pinnedCall(origin, "apps")
                val list = result.getJSONArray("items")
                windows = (0 until list.length()).map { index ->
                    val app = list.getJSONObject(index)
                    Window(
                        title = app.optString("title"),
                        name = app.optString("name"),
                        pid = app.optInt("pid"),
                        handle = app.getString("handle"),
                        minimized = app.optBoolean("minimized")
                    )
                }
                status(
                    if (list.length() == 0) "没有可管理的桌面窗口"
                    else "${list.length()} 个桌面窗口；关闭请求不会强制结束进程"
                )
            } finally { busy = false }
        }
    }
}

package com.aicode.lancmd

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONObject

/** 锁定指定 EXE，限制当前 Windows 用户启动。界面使用 miuix。 */
class AppLocksActivity : MiuixActivity() {
    internal class Lock(
        val id: String,
        val path: String,
        val state: String,
        val error: String?
    )

    internal var pathInput by mutableStateOf("")
    internal var locks by mutableStateOf<List<Lock>>(emptyList())
    private var backup by mutableStateOf("")
    internal var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pathInput = intent.getStringExtra("path").orEmpty()
        status("锁定所选 EXE，限制当前 Windows 用户启动；已运行的程序继续运行。")
        content { Screen() }
        refresh()
    }

    @Composable
    private fun Screen() = Page("应用锁") {
        SectionCard("锁定新程序") {
            Field(label = "EXE 完整路径", value = pathInput, onValueChange = { pathInput = it })
            Action("锁定这个 EXE", enabled = !busy, primary = true) { confirmLock() }
            Note("需要修改文件权限的权限，通常需管理员接收端。限制针对所选文件；复制、重装或替换后的程序需重新选择。尝试启动被锁文件时，Windows 可能显示拒绝访问提示。")
        }
        SectionCard {
            Action("刷新锁定列表", enabled = !busy) { refresh() }
        }
        SectionCard("已记录的应用锁") {
            if (locks.isEmpty()) EmptyNote("暂无应用锁，可在文件管理的 EXE 菜单中选择「应用锁」。")
            locks.forEach { lock ->
                val description = when (lock.state) {
                    "locked" -> "已限制启动"
                    "unlocked" -> "已恢复原权限"
                    "missing" -> "文件已不存在"
                    "replaced" -> "文件已被替换"
                    else -> "权限发生外部变化"
                }
                InfoItem(lock.path.substringAfterLast('\\'), "$description · ${lock.path}")
                if (lock.error != null) Note(lock.error)
                ActionRow {
                    Action("解锁", enabled = !busy && lock.state == "locked", modifier = Modifier.weight(1f)) {
                        mutate(JSONObject().put("action", "unlock").put("lock_id", lock.id))
                    }
                    Action(
                        "移除记录",
                        enabled = !busy && lock.state in listOf("unlocked", "missing", "replaced"),
                        modifier = Modifier.weight(1f)
                    ) {
                        mutate(JSONObject().put("action", "forget").put("lock_id", lock.id))
                    }
                }
            }
            if (backup.isNotBlank()) {
                Note("恢复备份：$backup\n锁定期间请保留此文件。权限被外部修改时会停止恢复。")
            }
        }
    }

    internal fun confirmLock() {
        if (busy) return
        val selected = pathInput.trim()
        AlertDialog.Builder(this).setTitle("限制此程序启动？")
            .setMessage("$selected\n\n通过文件执行权限限制当前 Windows 用户。原权限保存在电脑，解锁时恢复。不会结束正在运行的程序；不能锁定 Windows 系统目录和 LanCmd 自身。")
            .setNegativeButton("取消", null)
            .setPositiveButton("锁定") { _, _ -> mutate(JSONObject().put("action", "lock").put("path", selected)) }
            .show()
    }

    internal fun mutate(params: JSONObject) {
        if (busy) return
        busy = true
        task {
            try {
                pinnedCall(origin, "app_locks", params, 60_000)
                status("操作完成")
            } finally { busy = false }
            refresh()
        }
    }

    internal fun refresh() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "app_locks", timeout = 60_000)
                val items = result.getJSONArray("items")
                locks = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    Lock(
                        id = item.getString("id"),
                        path = item.getString("path"),
                        state = item.getString("status"),
                        error = if (item.has("error")) item.getString("error") else null
                    )
                }
                backup = result.getString("backup")
            } finally { busy = false }
        }
    }
}

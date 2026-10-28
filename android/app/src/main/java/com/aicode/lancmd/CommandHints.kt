package com.aicode.lancmd

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Selecting a suggestion only fills the editor; execution remains explicit. */
object CommandHints {
    data class Hint(val title: String, val cmd: String, val powershell: String = cmd)
    val hints = listOf(
        Hint("网络配置", "ipconfig /all"),
        Hint("测试网络", "ping -n 4 1.1.1.1"),
        Hint("当前用户", "whoami"),
        Hint("系统信息", "systeminfo"),
        Hint("进程列表", "tasklist", "Get-Process"),
        Hint("当前目录文件", "dir", "Get-ChildItem"),
        Hint("磁盘空间", "powershell -NoProfile -Command \"Get-PSDrive -PSProvider FileSystem\"", "Get-PSDrive -PSProvider FileSystem"),
        Hint("打开资源管理器", "explorer.exe ."),
        Hint("打开任务管理器", "taskmgr.exe")
    )

    fun show(context: Context, powershell: Boolean, selected: (String) -> Unit) {
        WorkspaceDialog.Builder(context).setTitle("常用命令 · 点选填入，确认后执行")
            .setItems(hints.map { "${it.title}\n${if (powershell) it.powershell else it.cmd}" }.toTypedArray()) { _, index ->
                selected(if (powershell) hints[index].powershell else hints[index].cmd)
            }.setNegativeButton("关闭", null).show()
    }
}

package com.aicode.lancmd

import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject
import java.io.IOException

internal fun supportsUsbConnection(caps:JSONObject):Boolean {
    val features=caps.optJSONArray("features")
    if(features!=null && (0 until features.length()).any { features.optString(it)=="usb_connection" })return true
    val version=caps.optString("version").split('.').take(3).map { it.toIntOrNull() ?: return false }
    return version.size==3 && (version[0]>2 || version[0]==2 && version[1]>=5)
}
internal fun usbUpgradeMessage(version:String)="当前 Windows 接收端（${version.ifBlank { "未报告版本" }}）不支持 USB 强制断开。请先停止旧接收端，替换完整 Windows 包（包括全部 .py 模块），重新启动并连接；仅更新安卓或 EXE 不会更新旧进程。"

suspend fun pinnedCall(client: AgentClient?, command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
    if (client == null || client.closed || runCatching { ConnectionManager.sessionClient() }.getOrNull() !== client) {
        throw IOException("连接已结束或电脑已切换，请重新打开此页；操作没有发送到新电脑")
    }
    val result = client.call(command, params, timeout)
    if (!result.optBoolean("ok")) {
        val error = result.optString("error", "操作失败")
        throw IOException(if (error.contains("未知命令")) "请完整更新 Windows 接收端至最新版" else error)
    }
    return result
}

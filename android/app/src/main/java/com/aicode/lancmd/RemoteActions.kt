package com.aicode.lancmd

import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject
import java.io.IOException

suspend fun pinnedCall(client: AgentClient?, command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
    if (client == null || client.closed || runCatching { ConnectionManager.sessionClient() }.getOrNull() !== client) {
        throw IOException("连接已结束或电脑已切换，请重新打开此页；操作没有发送到新电脑")
    }
    val result = client.call(command, params, timeout)
    if (!result.optBoolean("ok")) {
        val error = result.optString("error", "操作失败")
        throw IOException(if (error.contains("未知命令")) "请完整更新 Windows 接收端至 1.9.0" else error)
    }
    return result
}

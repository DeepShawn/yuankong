package com.aicode.lancmd.net

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Bound to the exact connection that the Windows user approved. */
class RemoteLease(private val scope: String) {
    private val client = ConnectionManager.sessionClient()
    private var sid = ""

    suspend fun open() = withContext(NonCancellable) {
        val result = client.call("session", JSONObject().put("scope", scope), 55_000)
        check(result)
        sid = result.getString("sid")
    }

    suspend fun call(command: String, params: JSONObject = JSONObject(), timeout: Long = 20_000): JSONObject {
        if (sid.isEmpty() || client.closed) throw IOException("共享连接已结束，请重新开始")
        val result = client.call(command, params.put("sid", sid), timeout)
        check(result)
        return result
    }

    suspend fun keepAlive() { call("session", JSONObject().put("action", "keepalive")) }

    suspend fun close() = withContext(NonCancellable) {
        if (sid.isNotEmpty()) {
            runCatching { client.call("session", JSONObject().put("action", "end").put("sid", sid), 3000) }
            sid = ""
        }
    }

    private fun check(result: JSONObject) {
        if (!result.optBoolean("ok")) throw IOException(result.optString("error", "电脑端未允许本次共享"))
    }
}

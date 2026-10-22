package com.aicode.lancmd

import android.content.Context

data class Target(val host: String, val port: Int, val name: String, val agentId: String)

/** 设备标识、上次连接的电脑与配对凭据。持久化后重连无需任何确认。 */
object Prefs {

    private const val FILE = "lancmd"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_NAME = "last_name"
    private const val KEY_AGENT_ID = "last_agent_id"
    private const val KEY_UPLOAD_DIR = "upload_dir"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun clientId(ctx: Context): String {
        val p = sp(ctx)
        val cached = p.getString(KEY_CLIENT_ID, null)
        if (cached != null) return cached
        val id = "and-" + java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_CLIENT_ID, id).apply()
        return id
    }

    fun target(ctx: Context): Target? {
        val p = sp(ctx)
        val host = p.getString(KEY_HOST, null) ?: return null
        return Target(
            host = host,
            port = p.getInt(KEY_PORT, com.aicode.lancmd.net.TCP_PORT),
            name = p.getString(KEY_NAME, host) ?: host,
            agentId = p.getString(KEY_AGENT_ID, "") ?: ""
        )
    }

    fun saveTarget(ctx: Context, t: Target) {
        sp(ctx).edit()
            .putString(KEY_HOST, t.host)
            .putInt(KEY_PORT, t.port)
            .putString(KEY_NAME, t.name)
            .putString(KEY_AGENT_ID, t.agentId)
            .apply()
    }

    fun token(ctx: Context, host: String, port: Int): String {
        val raw = sp(ctx).getString(KEY_TOKENS, null) ?: return ""
        return try {
            org.json.JSONObject(raw).optString("$host:$port", "")
        } catch (_: Exception) {
            ""
        }
    }

    fun saveToken(ctx: Context, host: String, port: Int, token: String) {
        val p = sp(ctx)
        val map = try {
            org.json.JSONObject(p.getString(KEY_TOKENS, null) ?: "{}")
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        map.put("$host:$port", token)
        p.edit().putString(KEY_TOKENS, map.toString()).apply()
    }

    fun uploadDir(ctx: Context): String = sp(ctx).getString(KEY_UPLOAD_DIR, "") ?: ""

    fun saveUploadDir(ctx: Context, dir: String) {
        sp(ctx).edit().putString(KEY_UPLOAD_DIR, dir).apply()
    }

    fun forget(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_TOKENS)
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_NAME)
            .remove(KEY_AGENT_ID)
            .apply()
    }
}

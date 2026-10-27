package com.aicode.lancmd

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.IOException

class DevicesActivity : FeatureActivity() {
    private var origin: AgentClient? = null
    private lateinit var policies: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var disconnected: LinearLayout
    private lateinit var refresh: MaterialButton
    private val controls = ArrayList<MaterialButton>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("U盘锁与移动设备", "读取电脑设备与访问策略…", scrollable = true)
        origin = runCatching { ConnectionManager.sessionClient() }.getOrNull()
        refresh = button("刷新设备与策略") { load() }
        body.addView(refresh)
        body.addView(button("隐私设备 · 摄像头 / 麦克风") { startActivity(android.content.Intent(this,PrivacyDevicesActivity::class.java)) })
        disconnected = panel()
        policies = panel()
        devices = panel()
        load()
    }

    private suspend fun call(params: JSONObject = JSONObject()): JSONObject {
        val client = origin ?: throw IOException("请连接电脑后重新打开此页")
        if (ConnectionManager.sessionClient() !== client || client.closed) {
            throw IOException("连接已改变，请重新打开设备管理；未向新电脑发送操作")
        }
        val response = client.call("devices", params, if(params.optString("action")=="force_disconnect")120_000 else 45_000)
        if (!response.optBoolean("ok")) {
            val error = response.optString("error", "设备操作失败")
            throw IOException(if (error.contains("未知命令")) "请更新 Windows 接收端至 1.8.0" else error)
        }
        return response
    }

    private fun load(params: JSONObject? = null) {
        if (busy) return
        busy = true
        refresh.isEnabled = false
        controls.forEach { it.isEnabled = false }
        status(if (params == null) "正在读取设备与策略…" else "正在执行设备操作…")
        task {
            try {
                val notice = params?.let { call(it).optString("notice") }.orEmpty()
                val result = call()
                render(result)
                status(notice.ifBlank { "设备列表已更新。策略需重新插拔设备，必要时重启后生效。" })
            } finally {
                busy = false
                refresh.isEnabled = true
            }
        }
    }

    private fun confirm(title: String, message: String, params: JSONObject) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> load(params) }.show()
    }

    private fun action(parent: LinearLayout, text: String, enabled: Boolean, block: () -> Unit) {
        val view = button(text, block).apply { isEnabled = enabled }
        controls.add(view)
        parent.addView(view)
    }

    private fun render(result: JSONObject) {
        controls.clear()
        policies.removeAllViews()
        devices.removeAllViews()
        disconnected.removeAllViews()
        val admin = result.optBoolean("admin")
        val records=result.optJSONArray("disconnected")
        if(records!=null && records.length()>0) {
            disconnected.addView(label("USB 连接恢复记录",16f))
            for(i in 0 until records.length()) {
                val item=records.getJSONObject(i)
                disconnected.addView(label(item.optString("name","USB 存储"),15f))
                disconnected.addView(label(if(item.optInt("problem")==22)"已禁用 · 强制断开" else if(item.optBoolean("enabled"))"已启用 · 可清理恢复记录" else item.optString("error","状态异常，请检查设备"),12f,R.color.dim))
                action(disconnected,"恢复连接 · ${item.optString("name","USB 存储")}",admin && (item.optInt("problem")==22 || item.optBoolean("enabled"))) {
                    confirm("恢复 USB 存储连接？","只恢复 LanCmd 记录的磁盘设备。若已拔出，请先重新插入；部分驱动需要重启或重新插拔。",JSONObject().put("action","restore_connection").put("device_id",item.getString("id")))
                }
            }
        }
        policies.addView(label(if (admin) "接收端权限：管理员" else "接收端权限：标准用户", 14f))
        if (!admin) policies.addView(label("修改策略或弹出设备前，请在电脑 Start.exe 选择「管理员启动 / 重启」。", 13f, R.color.dim))
        policies.addView(label("设置作用于整类设备，重启后保留。解锁恢复 LanCmd 修改前的策略；不会清除原有系统限制。", 13f, R.color.dim))
        val state = result.getJSONObject("policies")
        if (state.optBoolean("deny_all")) policies.addView(label("Windows 已禁止所有可移动存储访问，本页解锁不能解除此限制。", 13f, R.color.danger))
        listOf("storage" to "U盘 / 可移动磁盘", "portable" to "MTP/PTP 手机与便携设备").forEach { (scope, title) ->
            val policy = state.getJSONObject(scope)
            val mode = when (policy.optString("mode")) {
                "blocked" -> "禁止访问"
                "read_only" -> "只读"
                else -> "允许访问"
            }
            policies.addView(label("$title · 已配置：$mode", 15f).apply { setPadding(0, dp(16), 0, dp(4)) })
            val conflict = policy.optBoolean("conflict")
            if (conflict) policies.addView(label("策略被其他程序更改，请由本机管理员检查。", 13f, R.color.danger))
            val choices = if (scope == "storage") listOf("锁定" to "blocked", "设为只读" to "read_only", "解锁 / 恢复原策略" to "allow")
                else listOf("锁定便携设备" to "blocked", "解锁便携设备 / 恢复原策略" to "allow")
            choices.forEach { (name, value) ->
                action(policies, name, admin && !conflict && (value != "allow" || policy.optBoolean("managed"))) {
                    confirm("$name？", "$title：此设置影响该类别的所有设备，且重启后保留。\n\n" +
                        if (value == "allow") "恢复 LanCmd 修改前的策略。其他 Windows 限制仍然有效。"
                        else "请先保存并关闭设备中的文件。策略保存后请重新插拔设备，必要时重启 Windows。",
                        JSONObject().put("action", "policy").put("scope", scope).put("mode", value))
                }
            }
        }
        policies.addView(label("USB 移动硬盘可能被 Windows 识别为固定磁盘，未必受可移动磁盘策略限制。此页显示配置值，不代表驱动已生效。", 12f, R.color.dim))
        devices.addView(label("已连接设备", 16f))
        val list = result.getJSONArray("devices")
        if (list.length() == 0) devices.addView(label("没有检测到 USB 存储或 MTP/PTP 设备。", 13f, R.color.dim))
        for (i in 0 until list.length()) {
            val device = list.getJSONObject(i)
            val name = device.optString("name").ifBlank { "可移动设备" }
            devices.addView(label(name, 15f).apply { setPadding(0, dp(14), 0, 0) })
            val paths = device.optJSONArray("paths")
            val locations = if (paths == null) "" else (0 until paths.length()).joinToString(" · ") { paths.optString(it) }
            val detail = if (device.optString("kind") == "portable") "MTP/PTP" else "USB 存储 · ${device.optLong("size") / (1024 * 1024)} MB"
            devices.addView(label(listOf(detail, locations, device.optString("id")).filter { it.isNotBlank() }.joinToString("\n"), 12f, R.color.dim))
            if (device.optBoolean("protected")) devices.addView(label("系统磁盘，禁止弹出", 13f, R.color.dim))
            action(devices, "安全弹出 $name", admin && device.optBoolean("ejectable") && !device.optBoolean("protected")) {
                confirm("安全弹出 $name？", "请先保存文件并关闭正在使用设备的程序。设备忙时会返回失败，不会强制卸载。",
                    JSONObject().put("action", "eject").put("device_id", device.getString("id")))
            }
            if(device.optString("kind")=="storage")action(devices,"强制断开 $name",admin && device.optBoolean("ejectable") && !device.optBoolean("protected")) {
                confirm("强制断开 $name？","禁用整个 USB 存储设备，会立即中断所有分区访问，正在读写时可能丢失数据或损坏文件系统。设备可能仍有供电。建议先安全弹出；可在本页恢复连接。",
                    JSONObject().put("action","force_disconnect").put("device_id",device.getString("id")).put("confirm_data_loss",true))
            }
        }
    }
}

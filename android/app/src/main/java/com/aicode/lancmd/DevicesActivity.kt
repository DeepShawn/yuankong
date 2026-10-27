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
    override val inlineResults=false
    private var origin: AgentClient? = null
    private lateinit var policies: LinearLayout
    private lateinit var devices: LinearLayout
    private lateinit var disconnected: LinearLayout
    private lateinit var refresh: MaterialButton
    private val controls = ArrayList<MaterialButton>()
    private var busy = false
    private var receiverVersion=""
    private var connectionSupported=false
    private var compatibilitySupported=false
    private var checkedCapabilities=false
    private val eligibility=java.util.IdentityHashMap<MaterialButton,Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("U盘锁与移动设备", "读取电脑设备与访问策略…", scrollable = true)
        origin = runCatching { ConnectionManager.sessionClient() }.getOrNull()
        refresh = button("刷新设备与策略") { load() }
        body.addView(actions(refresh,
            button("隐私设备 · 摄像头 / 麦克风") { startActivity(android.content.Intent(this,PrivacyDevicesActivity::class.java)) }),
            LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(Ui.GAP) })
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
            if(params.optString("action") in listOf("force_disconnect","restore_connection") && (error.contains("无效的设备管理操作") || error.contains("未知命令"))) {
                connectionSupported=false
                throw IOException(usbUpgradeMessage(receiverVersion))
            }
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
                if(!checkedCapabilities) {
                    val caps=pinnedCall(origin,"capabilities")
                    receiverVersion=caps.optString("version")
                    connectionSupported=supportsUsbConnection(caps)
                    val features=caps.optJSONArray("features");compatibilitySupported=features!=null && (0 until features.length()).any { features.optString(it)=="usb_connection_fallback" }
                    checkedCapabilities=true
                }
                if(params?.optString("action") in listOf("force_disconnect","restore_connection") && !connectionSupported)
                    throw IOException(usbUpgradeMessage(receiverVersion))
                val notice = params?.let { call(it).optString("notice") }.orEmpty()
                val result = call()
                render(result)
                status(notice.ifBlank { "设备列表已更新。策略需重新插拔设备，必要时重启后生效。" })
            } finally {
                busy = false
                refresh.isEnabled = true
                controls.forEach { it.isEnabled=eligibility[it]==true && (connectionSupported || !(it.text.startsWith("强制断开") || it.text.startsWith("恢复连接"))) }
            }
        }
    }

    private fun confirm(title: String, message: String, params: JSONObject) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> load(params) }.show()
    }

    private fun action(parent: LinearLayout, text: String, enabled: Boolean, block: () -> Unit) {
        parent.addView(actionButton(text, enabled, block))
    }

    /** 与 [action] 相同的登记逻辑，但返回按钮以便并排成组。 */
    private fun actionButton(text: String, enabled: Boolean, block: () -> Unit): MaterialButton =
        button(text, block).apply {
            isEnabled = enabled
            controls.add(this)
            eligibility[this] = enabled
        }

    private fun render(result: JSONObject) {
        controls.clear()
        eligibility.clear()
        policies.removeAllViews()
        devices.removeAllViews()
        disconnected.removeAllViews()
        result.optJSONArray("actions")?.let { actions->
            val supported=(0 until actions.length()).map { actions.optString(it) }
            connectionSupported="force_disconnect" in supported && "restore_connection" in supported
        }
        val admin = result.optBoolean("admin")
        val records=result.optJSONArray("disconnected")
        if(records!=null && records.length()>0) {
            disconnected.addView(Ui.sectionLabel(this,"USB 连接恢复记录"))
            for(i in 0 until records.length()) {
                val item=records.getJSONObject(i)
                val name=item.optString("name","USB 存储")
                val state=if(item.optInt("problem")==22)"已禁用 · 强制断开" else if(item.optBoolean("enabled") && item.optBoolean("config_restore"))"当前仍启用 · 有待恢复的停用配置" else if(item.optBoolean("enabled"))"已启用 · 可清理恢复记录" else item.optString("error","状态异常，请检查设备")
                if(i>0)disconnected.addView(divider(),Ui.dividerParams(this))
                disconnected.addView(listRow(name,state,icon=R.drawable.ic_usb))
                action(disconnected,"恢复连接 · $name",admin && connectionSupported && (item.optInt("problem")==22 || item.optBoolean("enabled"))) {
                    confirm("恢复 USB 存储连接？","只恢复 LanCmd 记录的磁盘设备。若已拔出，请先重新插入；会撤销未完成的兼容停用配置，驱动要求重启时保留记录，不会自动重启。",JSONObject().put("action","restore_connection").put("device_id",item.getString("id")))
                }
            }
        }
        policies.addView(Ui.sectionLabel(this,"访问策略"))
        policies.addView(listRow(if (admin) "接收端权限：管理员" else "接收端权限：标准用户",
            "Windows ${receiverVersion.ifBlank { "版本未知" }}",
            tint=if(admin)R.color.text else R.color.warn,icon=R.drawable.ic_lock))
        if(connectionSupported && !compatibilitySupported)policies.addView(caption("0x17 兼容修复需要 Windows 2.9.0 完整包；请停止旧接收端、完整替换并重启，不能只更新安卓。"))
        if(!connectionSupported)policies.addView(label(usbUpgradeMessage(receiverVersion),Ui.TEXT_CAPTION,R.color.warn))
        if (!admin) policies.addView(caption("修改策略或弹出设备前，请在电脑 Start.exe 选择「管理员启动 / 重启」。"))
        policies.addView(caption("设置作用于整类设备，重启后保留。解锁恢复 LanCmd 修改前的策略；不会清除原有系统限制。"))
        val state = result.getJSONObject("policies")
        if (state.optBoolean("deny_all")) policies.addView(label("Windows 已禁止所有可移动存储访问，本页解锁不能解除此限制。", Ui.TEXT_CAPTION, R.color.danger))
        listOf("storage" to "U盘 / 可移动磁盘", "portable" to "MTP/PTP 手机与便携设备").forEach { (scope, title) ->
            val policy = state.getJSONObject(scope)
            val mode = when (policy.optString("mode")) {
                "blocked" -> "禁止访问"
                "read_only" -> "只读"
                else -> "允许访问"
            }
            val conflict = policy.optBoolean("conflict")
            policies.addView(divider(),Ui.dividerParams(this))
            policies.addView(listRow(title,"按整类设备生效，重启后保留",
                trailing=Ui.text(this,mode,Ui.TEXT_LABEL,if(mode=="允许访问")R.color.ok else R.color.warn).apply {
                    setBackgroundResource(R.drawable.pill_bg);setPadding(dp(10),dp(4),dp(10),dp(4))
                }))
            if (conflict) policies.addView(label("策略被其他程序更改，请由本机管理员检查。", Ui.TEXT_CAPTION, R.color.danger))
            val choices = if (scope == "storage") listOf("锁定" to "blocked", "设为只读" to "read_only", "解锁 / 恢复原策略" to "allow")
                else listOf("锁定便携设备" to "blocked", "解锁便携设备 / 恢复原策略" to "allow")
            val group = choices.map { (name, value) ->
                actionButton(name, admin && !conflict && (value != "allow" || policy.optBoolean("managed"))) {
                    confirm("$name？", "$title：此设置影响该类别的所有设备，且重启后保留。\n\n" +
                        if (value == "allow") "恢复 LanCmd 修改前的策略。其他 Windows 限制仍然有效。"
                        else "请先保存并关闭设备中的文件。策略保存后请重新插拔设备，必要时重启 Windows。",
                        JSONObject().put("action", "policy").put("scope", scope).put("mode", value))
                }.apply { textSize = Ui.TEXT_CAPTION }
            }
            policies.addView(actions(*group.toTypedArray()))
        }
        policies.addView(caption("USB 移动硬盘可能被 Windows 识别为固定磁盘，未必受可移动磁盘策略限制。此页显示配置值，不代表驱动已生效。"))
        devices.addView(Ui.sectionLabel(this,"已连接设备"))
        val list = result.getJSONArray("devices")
        if (list.length() == 0) devices.addView(emptyState("没有检测到 USB 存储或 MTP/PTP 设备。"))
        for (i in 0 until list.length()) {
            val device = list.getJSONObject(i)
            val name = device.optString("name").ifBlank { "可移动设备" }
            val paths = device.optJSONArray("paths")
            val locations = if (paths == null) "" else (0 until paths.length()).joinToString(" · ") { paths.optString(it) }
            val kind = if (device.optString("kind") == "portable") "MTP/PTP" else "USB 存储 · ${device.optLong("size") / (1024 * 1024)} MB"
            if (i > 0) devices.addView(divider(),Ui.dividerParams(this))
            devices.addView(listRow(name, listOf(kind, locations).filter { it.isNotBlank() }.joinToString(" · "),
                icon=if(device.optString("kind")=="portable")R.drawable.ic_file else R.drawable.ic_usb,
                trailing=if(device.optBoolean("protected"))Ui.text(this,"系统磁盘",Ui.TEXT_LABEL,R.color.dim).apply {
                    setBackgroundResource(R.drawable.pill_bg);setPadding(dp(10),dp(4),dp(10),dp(4))
                } else null))
            devices.addView(label(device.optString("id"),Ui.TEXT_LABEL,R.color.dim).apply { setTextIsSelectable(true) })
            val eject = actionButton("安全弹出 $name", admin && device.optBoolean("ejectable") && !device.optBoolean("protected")) {
                confirm("安全弹出 $name？", "请先保存文件并关闭正在使用设备的程序。设备忙时会返回失败，不会强制卸载。",
                    JSONObject().put("action", "eject").put("device_id", device.getString("id")))
            }.apply { textSize = Ui.TEXT_CAPTION }
            if(device.optString("kind")=="storage") {
                val cut = danger(actionButton("强制断开 $name",admin && connectionSupported && device.optBoolean("ejectable") && !device.optBoolean("protected")) {
                    confirm("强制断开 $name？","禁用整个 USB 存储设备，会立即中断所有分区访问，正在读写时可能丢失数据或损坏文件系统。设备可能仍有供电。建议先安全弹出；可在本页恢复连接。",
                        JSONObject().put("action","force_disconnect").put("device_id",device.getString("id")).put("confirm_data_loss",true))
                }).apply { textSize = Ui.TEXT_CAPTION }
                devices.addView(actions(eject, cut))
            } else devices.addView(actions(eject))
        }
    }
}

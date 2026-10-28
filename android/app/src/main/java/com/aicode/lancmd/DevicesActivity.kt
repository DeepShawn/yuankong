package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject
import java.io.IOException

/** U盘与移动设备：访问策略、安全弹出、强制断开与连接恢复。界面使用 miuix。 */
class DevicesActivity : MiuixActivity() {
    internal class Policy(
        val scope: String,
        val title: String,
        val mode: String,
        val conflict: Boolean,
        val managed: Boolean
    )

    internal class Device(
        val id: String,
        val name: String,
        val kind: String,
        val detail: String,
        val ejectable: Boolean,
        val protectedDisk: Boolean
    )

    internal class Record(val id: String, val name: String, val state: String, val restorable: Boolean)

    private var client: AgentClient? = null

    internal var admin by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var policies by mutableStateOf<List<Policy>>(emptyList())
    internal var devices by mutableStateOf<List<Device>>(emptyList())
    internal var records by mutableStateOf<List<Record>>(emptyList())
    private var denyAll by mutableStateOf(false)
    private var receiverVersion by mutableStateOf("")
    internal var connectionSupported by mutableStateOf(false)
    private var compatibilitySupported by mutableStateOf(false)
    private var checkedCapabilities = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = runCatching { ConnectionManager.sessionClient() }.getOrNull()
        status("读取电脑设备与访问策略…")
        content { Screen() }
        load()
    }

    @Composable
    private fun Screen() = Page("U盘锁与移动设备") {
        SectionCard {
            ActionRow {
                Action("刷新设备与策略", enabled = !busy, modifier = Modifier.weight(1f)) { load() }
                Action("隐私设备 · 摄像头 / 麦克风", modifier = Modifier.weight(1f)) {
                    startActivity(Intent(this@DevicesActivity, PrivacyDevicesActivity::class.java))
                }
            }
        }
        if (records.isNotEmpty()) SectionCard("USB 连接恢复记录") {
            records.forEach { record ->
                InfoItem(record.name, record.state)
                Action(
                    "恢复连接 · ${record.name}",
                    enabled = !busy && admin && connectionSupported && record.restorable
                ) {
                    confirm(
                        "恢复 USB 存储连接？",
                        "只恢复 LanCmd 记录的磁盘设备。若已拔出，请先重新插入；会撤销未完成的兼容停用配置，驱动要求重启时保留记录，不会自动重启。",
                        JSONObject().put("action", "restore_connection").put("device_id", record.id)
                    )
                }
            }
        }
        SectionCard("访问策略") {
            InfoItem(
                if (admin) "接收端权限：管理员" else "接收端权限：标准用户",
                "Windows ${receiverVersion.ifBlank { "版本未知" }}"
            )
            if (connectionSupported && !compatibilitySupported) {
                Note("0x17 兼容修复需要 Windows 2.9.0 完整包；请停止旧接收端、完整替换并重启，不能只更新安卓。")
            }
            if (!connectionSupported) Note(usbUpgradeMessage(receiverVersion))
            if (!admin) Note("修改策略或弹出设备前，请在电脑 Start.exe 选择「管理员启动 / 重启」。")
            Note("设置作用于整类设备，重启后保留。解锁恢复 LanCmd 修改前的策略；不会清除原有系统限制。")
            if (denyAll) Note("Windows 已禁止所有可移动存储访问，本页解锁不能解除此限制。")
            policies.forEach { policy ->
                InfoItem("${policy.title} · ${policy.mode}", "按整类设备生效，重启后保留")
                if (policy.conflict) Note("策略被其他程序更改，请由本机管理员检查。")
                val choices = if (policy.scope == "storage")
                    listOf("锁定" to "blocked", "设为只读" to "read_only", "解锁 / 恢复原策略" to "allow")
                else listOf("锁定便携设备" to "blocked", "解锁便携设备 / 恢复原策略" to "allow")
                ActionRow {
                    choices.forEach { (name, value) ->
                        Action(
                            name,
                            enabled = !busy && admin && !policy.conflict && (value != "allow" || policy.managed),
                            modifier = Modifier.weight(1f)
                        ) {
                            confirm(
                                "$name？",
                                "${policy.title}：此设置影响该类别的所有设备，且重启后保留。\n\n" +
                                    if (value == "allow") "恢复 LanCmd 修改前的策略。其他 Windows 限制仍然有效。"
                                    else "请先保存并关闭设备中的文件。策略保存后请重新插拔设备，必要时重启 Windows。",
                                JSONObject().put("action", "policy").put("scope", policy.scope).put("mode", value)
                            )
                        }
                    }
                }
            }
            Note("USB 移动硬盘可能被 Windows 识别为固定磁盘，未必受可移动磁盘策略限制。此页显示配置值，不代表驱动已生效。")
        }
        SectionCard("已连接设备") {
            if (devices.isEmpty()) EmptyNote("没有检测到 USB 存储或 MTP/PTP 设备。")
            devices.forEach { device ->
                InfoItem(
                    device.name + if (device.protectedDisk) " · 系统磁盘" else "",
                    "${device.detail}\n${device.id}"
                )
                ActionRow {
                    Action(
                        "安全弹出 ${device.name}",
                        enabled = !busy && admin && device.ejectable && !device.protectedDisk,
                        modifier = Modifier.weight(1f)
                    ) {
                        confirm(
                            "安全弹出 ${device.name}？",
                            "请先保存文件并关闭正在使用设备的程序。设备忙时会返回失败，不会强制卸载。",
                            JSONObject().put("action", "eject").put("device_id", device.id)
                        )
                    }
                    if (device.kind == "storage") Action(
                        "强制断开 ${device.name}",
                        enabled = !busy && admin && connectionSupported && device.ejectable && !device.protectedDisk,
                        modifier = Modifier.weight(1f)
                    ) {
                        confirm(
                            "强制断开 ${device.name}？",
                            "禁用整个 USB 存储设备，会立即中断所有分区访问，正在读写时可能丢失数据或损坏文件系统。设备可能仍有供电。建议先安全弹出；可在本页恢复连接。",
                            JSONObject().put("action", "force_disconnect").put("device_id", device.id)
                                .put("confirm_data_loss", true)
                        )
                    }
                }
            }
        }
    }

    private suspend fun call(params: JSONObject = JSONObject()): JSONObject {
        val target = client ?: throw IOException("请连接电脑后重新打开此页")
        if (ConnectionManager.sessionClient() !== target || target.closed) {
            throw IOException("连接已改变，请重新打开设备管理；未向新电脑发送操作")
        }
        val response = target.call(
            "devices", params,
            if (params.optString("action") == "force_disconnect") 120_000 else 45_000
        )
        if (!response.optBoolean("ok")) {
            val error = response.optString("error", "设备操作失败")
            if (params.optString("action") in listOf("force_disconnect", "restore_connection") &&
                (error.contains("无效的设备管理操作") || error.contains("未知命令"))
            ) {
                connectionSupported = false
                throw IOException(usbUpgradeMessage(receiverVersion))
            }
            throw IOException(if (error.contains("未知命令")) "请更新 Windows 接收端至 1.8.0" else error)
        }
        return response
    }

    internal fun confirm(title: String, message: String, params: JSONObject) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> load(params) }.show()
    }

    internal fun load(params: JSONObject? = null) {
        if (busy) return
        busy = true
        status(if (params == null) "正在读取设备与策略…" else "正在执行设备操作…")
        task {
            try {
                if (!checkedCapabilities) {
                    val caps = pinnedCall(client, "capabilities")
                    receiverVersion = caps.optString("version")
                    connectionSupported = supportsUsbConnection(caps)
                    val features = caps.optJSONArray("features")
                    compatibilitySupported = features != null &&
                        (0 until features.length()).any { features.optString(it) == "usb_connection_fallback" }
                    checkedCapabilities = true
                }
                if (params?.optString("action") in listOf("force_disconnect", "restore_connection") && !connectionSupported) {
                    throw IOException(usbUpgradeMessage(receiverVersion))
                }
                val notice = params?.let { call(it).optString("notice") }.orEmpty()
                render(call())
                status(notice.ifBlank { "设备列表已更新。策略需重新插拔设备，必要时重启后生效。" })
            } finally { busy = false }
        }
    }

    private fun render(result: JSONObject) {
        result.optJSONArray("actions")?.let { actions ->
            val supported = (0 until actions.length()).map { actions.optString(it) }
            connectionSupported = "force_disconnect" in supported && "restore_connection" in supported
        }
        admin = result.optBoolean("admin")
        val state = result.getJSONObject("policies")
        denyAll = state.optBoolean("deny_all")
        policies = listOf("storage" to "U盘 / 可移动磁盘", "portable" to "MTP/PTP 手机与便携设备").map { (scope, title) ->
            val policy = state.getJSONObject(scope)
            Policy(
                scope = scope,
                title = title,
                mode = when (policy.optString("mode")) {
                    "blocked" -> "禁止访问"; "read_only" -> "只读"; else -> "允许访问"
                },
                conflict = policy.optBoolean("conflict"),
                managed = policy.optBoolean("managed")
            )
        }
        val saved = result.optJSONArray("disconnected")
        records = if (saved == null) emptyList() else (0 until saved.length()).map { index ->
            val item = saved.getJSONObject(index)
            Record(
                id = item.getString("id"),
                name = item.optString("name", "USB 存储"),
                state = when {
                    item.optInt("problem") == 22 -> "已禁用 · 强制断开"
                    item.optBoolean("enabled") && item.optBoolean("config_restore") -> "当前仍启用 · 有待恢复的停用配置"
                    item.optBoolean("enabled") -> "已启用 · 可清理恢复记录"
                    else -> item.optString("error", "状态异常，请检查设备")
                },
                restorable = item.optInt("problem") == 22 || item.optBoolean("enabled")
            )
        }
        val list = result.getJSONArray("devices")
        devices = (0 until list.length()).map { index ->
            val device = list.getJSONObject(index)
            val paths = device.optJSONArray("paths")
            val locations = if (paths == null) "" else (0 until paths.length()).joinToString(" · ") { paths.optString(it) }
            val kind = if (device.optString("kind") == "portable") "MTP/PTP"
            else "USB 存储 · ${device.optLong("size") / (1024 * 1024)} MB"
            Device(
                id = device.getString("id"),
                name = device.optString("name").ifBlank { "可移动设备" },
                kind = device.optString("kind"),
                detail = listOf(kind, locations).filter { it.isNotBlank() }.joinToString(" · "),
                ejectable = device.optBoolean("ejectable"),
                protectedDisk = device.optBoolean("protected")
            )
        }
    }
}

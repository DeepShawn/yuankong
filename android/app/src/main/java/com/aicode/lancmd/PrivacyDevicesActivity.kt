package com.aicode.lancmd

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 电脑上的摄像头与麦克风：启停设备、麦克风静音。界面使用 miuix。 */
class PrivacyDevicesActivity : MiuixActivity() {
    internal class Device(
        val id: String,
        val name: String,
        val kind: String,
        val enabled: Boolean,
        val problem: Int,
        val muted: Boolean?,
        val error: String,
        val audioError: String,
        val changeable: Boolean
    ) {
        val microphone get() = kind == "microphone"
    }

    internal var devices by mutableStateOf<List<Device>>(emptyList())
    internal var selectedKind by mutableStateOf("all")
    internal var busy by mutableStateOf(false)
    internal var admin by mutableStateOf(false)
    internal var feedback by mutableStateOf<Map<String, Pair<String, Boolean>>>(emptyMap())
    private var expanded by mutableStateOf<Set<String>>(emptySet())
    private var lastNotice = ""
    private var lastNoticeError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedKind = savedInstanceState?.getString("kind") ?: "all"
        status("读取设备状态…")
        content { Screen() }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("kind", selectedKind)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun Screen() = Page("隐私设备 · 摄像头与麦克风") {
        val cameras = devices.count { it.kind == "camera" }
        val microphones = devices.size - cameras
        SectionCard {
            ActionRow {
                listOf("全部" to "all", "摄像头" to "camera", "麦克风" to "microphone").forEach { (name, kind) ->
                    Action(name, primary = selectedKind == kind, modifier = Modifier.weight(1f)) {
                        selectedKind = kind
                    }
                }
            }
            Action("刷新设备状态", enabled = !busy) { refresh() }
            Note(
                (if (admin) "管理员接收端" else "标准权限 · 启停需要管理员") +
                    " · $cameras 个摄像头 / $microphones 个麦克风"
            )
            Note("停用前会释放 LanCmd 的相关共享和录制；仍会影响其他应用。设备启停需管理员，驱动可能要求重启。系统隐私提示与指示灯由系统和硬件管理。")
        }
        val shown = devices.filter { selectedKind == "all" || it.kind == selectedKind }
        if (shown.isEmpty()) SectionCard { EmptyNote("此分类没有可管理的设备，请刷新或检查驱动与连接。") }
        shown.forEach { device ->
            val state = when {
                device.problem == 22 -> "已停用"
                device.enabled -> "已启用"
                else -> "驱动异常 ${device.problem}"
            }
            SectionCard {
                InfoItem(device.name, "${if (device.microphone) "麦克风" else "摄像头"} · $state")
                ActionRow {
                    Action(
                        if (device.enabled) "停用设备" else "启用设备",
                        enabled = !busy && admin && device.changeable,
                        modifier = Modifier.weight(1f)
                    ) { confirmToggle(device) }
                    if (device.microphone) {
                        Action(
                            if (device.muted == true) "取消静音" else "静音麦克风",
                            enabled = !busy && device.enabled && device.muted != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            operate(
                                JSONObject().put("action", "mute").put("device_id", device.id)
                                    .put("muted", device.muted != true)
                            )
                        }
                    }
                }
                feedback[device.id]?.let { (message, failed) -> StatusNote(message, failed) }
                Action(if (device.id in expanded) "收起设备详情" else "设备详情") {
                    expanded = if (device.id in expanded) expanded - device.id else expanded + device.id
                }
                if (device.id in expanded) {
                    Note("设备编号：${device.id}\n驱动问题代码：${device.problem}\n${device.error}\n${device.audioError}")
                }
            }
        }
    }

    internal fun confirmToggle(device: Device) {
        AlertDialog.Builder(this)
            .setTitle(if (device.enabled) "停用此设备？" else "启用此设备？")
            .setMessage(
                "${device.name}\n\n" + if (device.enabled)
                    "将先停止 LanCmd 相关共享/录制并等待释放设备，再停用选中的设备；会影响其他应用。不会停用父控制器。"
                else "启用后仍受 Windows 隐私权限与物理开关限制，不会自动授予应用权限。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                operate(
                    JSONObject().put("action", "set_enabled").put("device_id", device.id)
                        .put("enabled", !device.enabled)
                )
            }.show()
    }

    internal fun operate(params: JSONObject) {
        if (busy) return
        val id = params.getString("device_id")
        feedback = feedback + (id to ("正在执行，请稍候…" to false))
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "privacy_devices", params, 90_000)
                val released = result.optJSONObject("released")
                val count = (released?.optInt("camera_sessions") ?: 0) + (released?.optInt("recordings") ?: 0)
                val message = (if (count > 0) "已释放 $count 项共享/录制；" else "") +
                    result.optString(
                        "notice",
                        if (result.optBoolean("unchanged")) "设备已处于所选状态" else "操作完成"
                    )
                lastNoticeError = result.optBoolean("pending_reboot")
                feedback = feedback + (id to (message to lastNoticeError))
                lastNotice = message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedback = feedback + (id to ((e.message ?: "设备操作失败") to true))
                lastNotice = e.message.orEmpty()
                lastNoticeError = true
            } finally { busy = false }
            refresh()
        }
    }

    internal fun refresh() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "privacy_devices", timeout = 45_000)
                admin = result.optBoolean("admin")
                val list = result.getJSONArray("items")
                devices = (0 until list.length()).map { index ->
                    val item = list.getJSONObject(index)
                    Device(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        kind = item.optString("kind"),
                        enabled = item.optBoolean("enabled"),
                        problem = item.optInt("problem"),
                        muted = if (item.has("muted")) item.optBoolean("muted") else null,
                        error = item.optString("error"),
                        audioError = item.optString("audio_error"),
                        changeable = !item.has("error") && item.optInt("problem") in listOf(0, 22)
                    )
                }
                status(lastNotice.ifBlank { "已读取 ${list.length()} 个设备" }, lastNoticeError)
            } finally { busy = false }
        }
    }
}

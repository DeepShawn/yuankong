package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

/** 录制电脑的麦克风、屏幕或摄像头，并下载到手机。界面使用 miuix。 */
class RecordingsActivity : MiuixActivity() {
    internal class Record(
        val id: String,
        val mode: String,
        val state: String,
        val name: String,
        val stateName: String,
        val detail: String,
        val reason: String,
        val error: String?,
        val size: Long
    ) {
        /** 电脑上的文件路径，下载时使用。 */
        var pathOnComputer: String = ""
        val active get() = state in listOf("starting", "recording", "stopping")
    }

    private val modes = listOf("环境声音（WAV）", "屏幕视频（MP4）", "摄像头视频（MP4）")

    private var modeIndex by mutableIntStateOf(0)
    private var withAudio by mutableStateOf(true)
    private var microphoneNames by mutableStateOf(listOf("Windows 默认录音设备"))
    private var microphoneIndex by mutableIntStateOf(0)
    private var cameraNames by mutableStateOf(listOf("读取设备后选择"))
    private var cameraIndex by mutableIntStateOf(0)
    private var seconds by mutableStateOf("300")
    internal var records by mutableStateOf<List<Record>>(emptyList())
    internal var busy by mutableStateOf(false)
    private var canRecord by mutableStateOf(false)

    private var microphoneIds = listOf(-1)
    private var cameraIds = emptyList<Int>()
    private var poll: Job? = null
    private var transferring: Job? = null
    private var download: JSONObject? = null
    private var directory = ""

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val record = download
        download = null
        if (uri != null && record != null) {
            transferring = task {
                val cached = withContext(Dispatchers.IO) { File.createTempFile("recording-", ".tmp", cacheDir) }
                try {
                    FileTransfer.downloadFrom(record.getString("path"), cached, origin) { p ->
                        runOnUiThread { status("下载 ${p.percent}% · ${FileTransfer.humanSize(p.done)} / ${FileTransfer.humanSize(p.total)}") }
                    }
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            cached.inputStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            }
                        } ?: error("无法写入手机文件")
                    }
                    status("录制已保存到手机")
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@RecordingsActivity)
                        .setTitle("文件已保存").setMessage("可使用手机上的音视频播放器查看。")
                        .setNegativeButton("完成", null).setPositiveButton("播放") { _, _ ->
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(
                                        uri,
                                        if (record.optString("mode") == "audio") "audio/wav" else "video/mp4"
                                    ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }.onFailure { status("手机没有可用播放器，文件已保存", true) }
                        }.show()
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                    }
                    throw e
                } finally { withContext(Dispatchers.IO) { cached.delete() } }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        download = savedInstanceState?.getString("download")?.let { runCatching { JSONObject(it) }.getOrNull() }
        modeIndex = when (intent.getStringExtra("kind")) { "screen" -> 1; "camera" -> 2; else -> 0 }
        status("录制电脑上的麦克风、屏幕或摄像头，文件保存在电脑。")
        content { Screen() }
        task {
            val features = pinnedCall(origin, "capabilities").optJSONArray("features")
            if (features == null || (0 until features.length()).none { features.optString(it) == "recordings" }) {
                error("请完整更新 Windows 2.2.0，包含 LanCmdRecord.exe")
            }
            canRecord = true
            loadDevices()
        }
    }

    @Composable
    private fun Screen() = Page("录音 / 录屏 / 摄像头") {
        SectionCard("录制设置") {
            SuperDropdown(
                items = modes,
                title = "录制内容",
                selectedIndex = modeIndex,
                onSelectedIndexChange = { modeIndex = it }
            )
            SuperSwitch(
                checked = withAudio,
                onCheckedChange = { withAudio = it },
                title = "视频同时录制麦克风声音"
            )
            SuperDropdown(
                items = microphoneNames,
                title = "麦克风",
                selectedIndex = microphoneIndex.coerceAtMost(microphoneNames.size - 1),
                onSelectedIndexChange = { microphoneIndex = it }
            )
            SuperDropdown(
                items = cameraNames,
                title = "摄像头",
                summary = "仅摄像头模式使用",
                selectedIndex = cameraIndex.coerceAtMost(cameraNames.size - 1),
                onSelectedIndexChange = { cameraIndex = it }
            )
            Field(
                label = "录制时长（秒，1–3600）",
                value = seconds,
                onValueChange = { seconds = it.filter(Char::isDigit).take(4) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Action("开始录制", enabled = canRecord && !busy, primary = true) { confirmStart() }
            ActionRow {
                Action("刷新麦克风 / 摄像头", enabled = !busy, modifier = Modifier.weight(1f)) { loadDevices() }
                Action("管理摄像头 / 麦克风启停", modifier = Modifier.weight(1f)) {
                    startActivity(Intent(this@RecordingsActivity, PrivacyDevicesActivity::class.java))
                }
            }
            Note("到时自动停止，也可在下方手动停止。离开此页或手机断线仍录制，到所选时长结束；退出电脑接收端会停止录制。单文件上限约 1 GB，空间不足会提前停止。遵循 Windows 隐私权限和设备使用指示。")
        }
        SectionCard {
            ActionRow {
                Action("刷新列表", enabled = !busy, modifier = Modifier.weight(1f)) { refresh() }
                Action("电脑录制目录", modifier = Modifier.weight(1f)) {
                    if (directory.isNotEmpty()) task {
                        pinnedCall(origin, "capabilities")
                        startActivity(
                            Intent(this@RecordingsActivity, StorageActivity::class.java).putExtra("path", directory)
                        )
                    }
                }
                Action("取消下载", modifier = Modifier.weight(1f)) { transferring?.cancel() }
            }
        }
        SectionCard("已录制文件") {
            if (records.isEmpty()) EmptyNote("暂无录制，选择模式后点击开始。")
            records.forEach { record ->
                InfoItem("${record.name} · ${record.stateName}", record.detail)
                if (record.reason.isNotEmpty()) Note(record.reason)
                record.error?.let { Note(it) }
                if (record.active) {
                    Action("停止录制", enabled = record.state != "stopping") { stop(record) }
                } else {
                    ActionRow {
                        Action("下载 / 播放", enabled = record.size > 0, modifier = Modifier.weight(1f)) {
                            startDownload(record)
                        }
                        Action("删除", modifier = Modifier.weight(1f)) { confirmDelete(record) }
                    }
                }
            }
        }
    }

    private fun loadDevices() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "recordings", JSONObject().put("action", "devices"))
                val mics = result.getJSONArray("microphones")
                microphoneIds = listOf(-1) + (0 until mics.length()).map { mics.getJSONObject(it).getInt("id") }
                microphoneNames = listOf("Windows 默认录音设备") +
                    (0 until mics.length()).map { mics.getJSONObject(it).getString("name") }
                if (microphoneIndex >= microphoneNames.size) microphoneIndex = 0
                val cameras = result.getJSONArray("cameras")
                cameraIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getInt("id") }
                cameraNames = if (cameraIds.isEmpty()) listOf("没有可用摄像头")
                else (0 until cameras.length()).map { cameras.getJSONObject(it).getString("name") }
                if (cameraIndex >= cameraNames.size) cameraIndex = 0
                status("设备已刷新 · ${mics.length()} 个麦克风 / ${cameras.length()} 个摄像头")
            } finally { busy = false }
        }
    }

    internal fun confirmStart() {
        if (busy) return
        val duration = seconds.toIntOrNull()
        if (duration == null || duration !in 1..3600) { status("请输入 1–3600 秒", true); return }
        val kind = listOf("audio", "screen", "camera")[modeIndex]
        if (kind == "camera" && cameraIds.isEmpty()) { status("请先选择可用摄像头", true); return }
        val params = JSONObject().put("action", "start").put("mode", kind).put("seconds", duration)
            .put("audio", kind == "audio" || withAudio)
            .put("microphone", microphoneIds.getOrElse(microphoneIndex) { -1 })
            .put("camera", cameraIds.getOrElse(cameraIndex) { 0 })
        AlertDialog.Builder(this).setTitle("开始录制电脑？")
            .setMessage("${modes[modeIndex]}，最长 $duration 秒。\n请确认设备和录制范围，文件保存在电脑，可随时手动停止。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                if (!busy) {
                    busy = true
                    task {
                        try {
                            pinnedCall(origin, "recordings", params, 30_000)
                            status("录制已开始")
                        } finally { busy = false; refresh() }
                    }
                }
            }.show()
    }

    internal fun stop(record: Record) = task {
        pinnedCall(origin, "recordings", JSONObject().put("action", "stop").put("recording_id", record.id))
        refresh()
    }

    private fun startDownload(record: Record) {
        if (transferring?.isActive == true) return
        download = JSONObject().put("path", record.pathOnComputer).put("mode", record.mode)
        saveDocument.launch(
            "LanCmd-${record.mode}-${record.id.take(8)}.${if (record.mode == "audio") "wav" else "mp4"}"
        )
    }

    internal fun confirmDelete(record: Record) {
        AlertDialog.Builder(this).setTitle("删除电脑上的录制文件？").setMessage("删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                task {
                    pinnedCall(origin, "recordings", JSONObject().put("action", "delete").put("recording_id", record.id))
                    refresh()
                }
            }.show()
    }

    internal fun refresh() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "recordings")
                directory = result.getString("directory")
                val items = result.getJSONArray("recordings")
                records = (0 until items.length()).map { index ->
                    val record = items.getJSONObject(index)
                    val mode = record.getString("mode")
                    val name = when (mode) { "audio" -> "环境录音"; "screen" -> "屏幕录制"; else -> "摄像头录制" }
                    val state = record.getString("status")
                    val date = DateFormat.getDateTimeInstance()
                        .format(Date((record.getDouble("created") * 1000).toLong()))
                    Record(
                        id = record.getString("id"),
                        mode = mode,
                        state = state,
                        name = name,
                        stateName = when (state) {
                            "starting" -> "正在启动"; "recording" -> "正在录制"
                            "stopping" -> "正在封装文件"; "completed" -> "已完成"
                            "interrupted" -> "曾被中断"; else -> "失败"
                        },
                        detail = "$date · ${FileTransfer.humanSize(record.optLong("size"))} · 最长 ${record.optInt("seconds")} 秒" +
                            if (record.optBoolean("audio") && name != "环境录音") " · 含麦克风声音" else "",
                        reason = when (record.optString("reason")) {
                            "size_limit" -> "已达到单文件大小上限"
                            "disk_space" -> "磁盘空间不足，已提前停止"
                            else -> ""
                        },
                        error = if (record.has("error")) record.getString("error") else null,
                        size = record.optLong("size")
                    ).also { it.pathOnComputer = record.optString("path") }
                }
            } finally { busy = false }
        }
    }

    override fun onStart() {
        super.onStart()
        poll = lifecycleScope.launch { while (isActive) { refresh(); delay(2000) } }
    }

    override fun onStop() { poll?.cancel(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        download?.let { outState.putString("download", it.toString()) }
        super.onSaveInstanceState(outState)
    }
}

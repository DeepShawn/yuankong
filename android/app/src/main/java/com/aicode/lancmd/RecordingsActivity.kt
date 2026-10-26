package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

class RecordingsActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var mode: Spinner
    private lateinit var microphone: Spinner
    private lateinit var camera: Spinner
    private lateinit var audio: CheckBox
    private lateinit var seconds: EditText
    private lateinit var records: LinearLayout
    private lateinit var start: com.google.android.material.button.MaterialButton
    private var microphoneIds = listOf(-1)
    private var cameraIds = emptyList<Int>()
    private var poll: Job? = null
    private var busy = false
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
                        contentResolver.openOutputStream(uri)?.use { output -> cached.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                        } }
                            ?: error("无法写入手机文件")
                    }
                    status("录制已保存到手机")
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@RecordingsActivity)
                        .setTitle("文件已保存").setMessage("可使用手机上的音视频播放器查看。")
                        .setNegativeButton("完成", null).setPositiveButton("播放") { _, _ ->
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                                if (record.optString("mode") == "audio") "audio/wav" else "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                .onFailure { status("手机没有可用播放器，文件已保存", true) }
                        }.show()
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
                    throw e
                } finally { withContext(Dispatchers.IO) { cached.delete() } }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origin
        download = savedInstanceState?.getString("download")?.let { runCatching { JSONObject(it) }.getOrNull() }
        setup("录音 / 录屏 / 摄像头", "录制电脑上的麦克风、屏幕或摄像头，文件保存在电脑。", scrollable = true)
        val settings = panel()
        fun spinner(names: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@RecordingsActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        mode = spinner(listOf("环境声音（WAV）", "屏幕视频（MP4）", "摄像头视频（MP4）"))
        mode.setSelection(when (intent.getStringExtra("kind")) { "screen" -> 1; "camera" -> 2; else -> 0 })
        settings.addView(mode)
        audio = CheckBox(this).apply { text = "视频同时录制麦克风声音"; isChecked = true; setTextColor(color(R.color.text)) }
        settings.addView(audio)
        settings.addView(label("麦克风", 12f, R.color.dim))
        microphone = spinner(listOf("Windows 默认录音设备")); settings.addView(microphone)
        settings.addView(label("摄像头（仅摄像头模式使用）", 12f, R.color.dim))
        camera = spinner(listOf("读取设备后选择")); settings.addView(camera)
        settings.addView(label("录制时长（秒，1–3600）", 12f, R.color.dim))
        seconds = EditText(this).apply { hint = "录制时长，1–3600 秒"; inputType = InputType.TYPE_CLASS_NUMBER; setText("300"); contentDescription = "录制时长秒数" }
        settings.addView(seconds)
        settings.addView(button("刷新麦克风 / 摄像头") { loadDevices() })
        start = primary(button("开始录制") { confirmStart() }).apply { isEnabled = false }
        settings.addView(start)
        body.addView(label("到时自动停止，也可在下方手动停止。离开此页或手机断线仍录制，到所选时长结束；退出电脑接收端会停止录制。单文件上限约 1 GB，空间不足会提前停止。遵循 Windows 隐私权限和设备使用指示。", 12f, R.color.dim))
        val actions = LinearLayout(this)
        actions.addView(button("刷新列表") { refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("电脑录制目录") {
            if (directory.isNotEmpty()) task { pinnedCall(origin, "capabilities"); startActivity(Intent(this@RecordingsActivity, StorageActivity::class.java).putExtra("path", directory)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(actions)
        body.addView(button("取消下载") { transferring?.cancel() })
        records = panel()
        task {
            val features = pinnedCall(origin, "capabilities").optJSONArray("features")
            if (features == null || (0 until features.length()).none { features.optString(it) == "recordings" }) error("请完整更新 Windows 2.2.0，包含 LanCmdRecord.exe")
            start.isEnabled = true
            loadDevices()
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
                microphone.adapter = ArrayAdapter(this@RecordingsActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Windows 默认录音设备") + (0 until mics.length()).map { mics.getJSONObject(it).getString("name") })
                val cameras = result.getJSONArray("cameras")
                cameraIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getInt("id") }
                camera.adapter = ArrayAdapter(this@RecordingsActivity, android.R.layout.simple_spinner_dropdown_item,
                    if (cameraIds.isEmpty()) listOf("没有可用摄像头") else (0 until cameras.length()).map { cameras.getJSONObject(it).getString("name") })
                status("设备已刷新 · ${mics.length()} 个麦克风 / ${cameras.length()} 个摄像头")
            } finally { busy = false }
        }
    }

    private fun confirmStart() {
        if (busy) return
        val duration = seconds.text.toString().toIntOrNull()
        if (duration == null || duration !in 1..3600) { seconds.error = "请输入 1–3600 秒"; return }
        val kind = listOf("audio", "screen", "camera")[mode.selectedItemPosition]
        if (kind == "camera" && cameraIds.isEmpty()) { status("请先选择可用摄像头", true); return }
        val params = JSONObject().put("action", "start").put("mode", kind).put("seconds", duration)
            .put("audio", kind == "audio" || audio.isChecked).put("microphone", microphoneIds.getOrElse(microphone.selectedItemPosition) { -1 })
            .put("camera", cameraIds.getOrElse(camera.selectedItemPosition) { 0 })
        AlertDialog.Builder(this).setTitle("开始录制电脑？").setMessage("${mode.selectedItem}，最长 $duration 秒。\n请确认设备和录制范围，文件保存在电脑，可随时手动停止。")
            .setNegativeButton("取消", null).setPositiveButton("开始") { _, _ ->
                if (!busy) {
                    busy = true; start.isEnabled = false
                    task { try { pinnedCall(origin, "recordings", params, 30_000); status("录制已开始") }
                        finally { busy = false; start.isEnabled = true; refresh() } }
                }
            }.show()
    }

    private fun refresh() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "recordings")
                directory = result.getString("directory")
                records.removeAllViews()
                val items = result.getJSONArray("recordings")
                if (items.length() == 0) records.addView(label("暂无录制，选择模式后点击开始。", 13f, R.color.dim))
                for (i in 0 until items.length()) renderRecord(items.getJSONObject(i))
            } finally { busy = false }
        }
    }

    private fun renderRecord(record: JSONObject) {
        val state = record.getString("status")
        val active = state in listOf("starting", "recording", "stopping")
        val name = when (record.getString("mode")) { "audio" -> "环境录音"; "screen" -> "屏幕录制"; else -> "摄像头录制" }
        val stateName = when (state) { "starting" -> "正在启动"; "recording" -> "正在录制"; "stopping" -> "正在封装文件"; "completed" -> "已完成"; "interrupted" -> "曾被中断"; else -> "失败" }
        records.addView(label("$name · $stateName", 15f, if (active) R.color.warn else R.color.text))
        val date = DateFormat.getDateTimeInstance().format(Date((record.getDouble("created") * 1000).toLong()))
        records.addView(label("$date\n${FileTransfer.humanSize(record.optLong("size"))} · 最长 ${record.optInt("seconds")} 秒${if (record.optBoolean("audio") && name != "环境录音") " · 含麦克风声音" else ""}", 12f, R.color.dim))
        val reason = when (record.optString("reason")) { "size_limit" -> "已达到单文件大小上限"; "disk_space" -> "磁盘空间不足，已提前停止"; else -> "" }
        if (reason.isNotEmpty()) records.addView(label(reason, 12f, R.color.warn))
        if (record.has("error")) records.addView(label(record.getString("error"), 12f, R.color.danger))
        if (active) records.addView(button("停止录制") {
            task { pinnedCall(origin, "recordings", JSONObject().put("action", "stop").put("id", record.getString("id"))); refresh() }
        }.apply { isEnabled = state != "stopping" })
        else {
            val row = LinearLayout(this)
            row.addView(button("下载 / 播放") {
                if (transferring?.isActive != true) {
                    download = JSONObject(record.toString())
                    saveDocument.launch("LanCmd-${record.getString("mode")}-${record.getString("id").take(8)}.${if (record.getString("mode") == "audio") "wav" else "mp4"}")
                }
            }.apply { isEnabled = record.optLong("size") > 0 }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("删除") {
                AlertDialog.Builder(this).setTitle("删除电脑上的录制文件？").setMessage("删除后无法恢复。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        task { pinnedCall(origin, "recordings", JSONObject().put("action", "delete").put("id", record.getString("id"))); refresh() }
                    }.show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            records.addView(row)
        }
        records.addView(label("", 6f))
    }

    override fun onStart() { super.onStart(); poll = lifecycleScope.launch { while (isActive) { refresh(); delay(2000) } } }
    override fun onStop() { poll?.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { download?.let { outState.putString("download", it.toString()) }; super.onSaveInstanceState(outState) }
}

package com.aicode.lancmd

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

class GesturesActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val prefs by lazy { getSharedPreferences("gestures", MODE_PRIVATE) }
    private lateinit var board: GestureBoard
    private lateinit var record: com.google.android.material.button.MaterialButton
    private lateinit var play: com.google.android.material.button.MaterialButton
    private lateinit var mode: CheckBox
    private lateinit var delaySeconds: EditText
    private var playback: Job? = null
    private var lease: RemoteLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origin
        setup("手势记录板", "记录仅在手机本地进行；播放才会控制电脑鼠标，空白区域映射电脑整个桌面。", scrollable = true)
        mode = CheckBox(this).apply { text = "记录左键点击 / 拖动（关闭后仅移动鼠标）"; isChecked = true; setTextColor(color(R.color.text)) }
        body.addView(mode)
        board = GestureBoard(this)
        body.addView(board, LinearLayout.LayoutParams(-1, dp(300)))
        val row = LinearLayout(this)
        record = primary(button("开始记录") {
            if (board.recording) board.finish() else { board.dragMode = mode.isChecked; board.begin() }
        })
        row.addView(record, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("清空") { if (playback?.isActive != true) board.load(JSONArray()) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        val saved = LinearLayout(this)
        saved.addView(button("保存手势") { saveGesture() }, LinearLayout.LayoutParams(0, -2, 1f))
        saved.addView(button("手势列表") { chooseGesture() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(saved)
        delaySeconds = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "延迟秒数（0 为立即）"; setText("0"); contentDescription = "手势延迟秒数" }
        body.addView(delaySeconds)
        val controls = LinearLayout(this)
        play = primary(button("在电脑播放") { confirmPlay() })
        controls.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("取消播放") { playback?.cancel() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(controls)
        body.addView(label("按原时间间隔回放，单次最多 5 分钟；延迟由电脑计时。离开此页或断线会取消，并释放鼠标。最多保存 20 条手势。", 12f, R.color.dim))
        board.changed = {
            record.text = if (board.recording) "结束记录" else "开始记录"
            mode.isEnabled = !board.recording && playback?.isActive != true
            play.isEnabled = !board.recording && playback?.isActive != true && board.eventCount > 0
            if (board.recording) status("正在记录 · ${board.eventCount} 个事件")
        }
        savedInstanceState?.getString("events")?.let { runCatching { board.load(JSONArray(it)) } }
        play.isEnabled = board.snapshot().length() > 0
        task {
            val caps = pinnedCall(origin, "capabilities").optJSONArray("features")
            if (caps == null || (0 until caps.length()).none { caps.optString(it) == "gestures" }) error("请更新 Windows 至 2.2.0")
            val dimensions = pinnedCall(origin, "gestures", JSONObject().put("action", "dimensions"))
            board.aspect = dimensions.getInt("width").toFloat() / dimensions.getInt("height").coerceAtLeast(1)
        }
    }

    private fun saved() = runCatching { JSONArray(prefs.getString("saved", "[]")) }.getOrElse { JSONArray() }
    private fun saveGesture() {
        if (board.recording || playback?.isActive == true) { status("请先结束记录或播放", true); return }
        val events = board.snapshot()
        if (events.length() == 0) { status("请先记录手势", true); return }
        val data = saved()
        if (data.length() >= 20) { status("手势列表已满，请先删除旧手势", true); return }
        val input = EditText(this).apply { setSingleLine(); setText("手势 ${DateFormat.getTimeInstance().format(Date())}") }
        AlertDialog.Builder(this).setTitle("保存本地手势").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                data.put(JSONObject().put("name", input.text.toString().trim().take(60).ifBlank { "未命名手势" }).put("events", events).put("aspect", board.aspect.toDouble()))
                prefs.edit().putString("saved", data.toString()).apply(); status("手势已保存在手机")
            }.show()
    }

    private fun chooseGesture() {
        if (board.recording || playback?.isActive == true) { status("请先结束记录或播放", true); return }
        val data = saved()
        if (data.length() == 0) { status("暂无已保存手势"); return }
        AlertDialog.Builder(this).setTitle("手势列表").setItems((0 until data.length()).map { data.getJSONObject(it).getString("name") }.toTypedArray()) { _, index ->
            val item = data.getJSONObject(index)
            AlertDialog.Builder(this).setTitle(item.getString("name"))
                .setMessage("${item.getJSONArray("events").length()} 个事件。加载会替换画板上的当前记录。")
                .setNegativeButton("取消", null).setNeutralButton("删除") { _, _ -> data.remove(index); prefs.edit().putString("saved", data.toString()).apply(); status("本地手势已删除") }
                .setPositiveButton("加载") { _, _ -> board.load(item.getJSONArray("events")); status("手势已加载，可设置延迟后播放") }.show()
        }.show()
    }

    private fun confirmPlay() {
        if (board.recording || playback?.isActive == true) return
        val seconds = delaySeconds.text.toString().toIntOrNull()
        if (seconds == null || seconds !in 0..86400) { delaySeconds.error = "请输入 0–86400 秒"; return }
        val events = board.snapshot()
        if (events.length() == 0) return
        AlertDialog.Builder(this).setTitle("在电脑回放手势？").setMessage("$seconds 秒后按原时间间隔移动、点击或拖动鼠标。\n请先确认电脑当前画面，回放可能操作当前窗口；可随时取消。")
            .setNegativeButton("取消", null).setPositiveButton("播放") { _, _ -> beginPlayback(events, seconds) }.show()
    }

    private fun beginPlayback(events: JSONArray, seconds: Int) {
        if (playback?.isActive == true) return
        record.isEnabled = false; play.isEnabled = false; board.isEnabled = false; mode.isEnabled = false
        playback = task {
            var current: RemoteLease? = null
            try {
                pinnedCall(origin, "capabilities")
                current = RemoteLease("input", origin ?: error("连接已结束")); lease = current
                current.open()
                current.call("gestures", JSONObject().put("action", "play").put("events", events).put("delay", seconds))
                while (coroutineContext.isActive) {
                    current.keepAlive()
                    val job = current.call("gestures").optJSONObject("job") ?: error("回放任务已结束")
                    when (job.getString("status")) {
                        "waiting" -> status("等待回放 · 约 ${((job.getDouble("due") * 1000 - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)} 秒")
                        "playing" -> status("正在回放 ${job.optInt("index")} / ${job.optInt("count")}")
                        "completed" -> { status("手势回放完成"); break }
                        "cancelled" -> { status("手势回放已取消"); break }
                        else -> error(job.optString("error", "回放失败"))
                    }
                    delay(500)
                }
            } finally {
                current?.close(); lease = null
                record.isEnabled = true; board.isEnabled = true; mode.isEnabled = true; play.isEnabled = board.snapshot().length() > 0
            }
        }
    }

    override fun onStop() { board.finish(); playback?.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { board.finish(); outState.putString("events", board.snapshot().toString()); super.onSaveInstanceState(outState) }
}

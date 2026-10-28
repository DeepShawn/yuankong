package com.aicode.lancmd

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

/** 在手机上记录手势，再让电脑按原时间间隔回放。界面使用 miuix。 */
class GesturesActivity : MiuixActivity() {
    private val prefs by lazy { getSharedPreferences("gestures", MODE_PRIVATE) }

    /** 画板是自绘 View，Compose 里用 AndroidView 承载。 */
    private lateinit var board: GestureBoard

    private var dragMode by mutableStateOf(true)
    private var recording by mutableStateOf(false)
    internal var eventCount by mutableIntStateOf(0)
    private var delaySeconds by mutableStateOf("0")
    internal var playing by mutableStateOf(false)
    private var playback: Job? = null
    private var lease: RemoteLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        board = GestureBoard(this)
        board.dragMode = dragMode
        board.changed = {
            recording = board.recording
            eventCount = board.eventCount
            status(if (board.recording) "正在记录 · ${board.eventCount} 个事件" else "已记录 · ${board.eventCount} 个事件")
        }
        savedInstanceState?.getString("events")?.let { runCatching { board.load(JSONArray(it)) } }
        eventCount = board.snapshot().length()
        status("记录仅在手机本地进行；播放才会控制电脑鼠标，空白区域映射电脑整个桌面。")
        content { Screen() }
        task {
            val caps = pinnedCall(origin, "capabilities").optJSONArray("features")
            if (caps == null || (0 until caps.length()).none { caps.optString(it) == "gestures" }) {
                error("请更新 Windows 至 2.2.0")
            }
            val dimensions = pinnedCall(origin, "gestures", JSONObject().put("action", "dimensions"))
            board.aspect = dimensions.getInt("width").toFloat() / dimensions.getInt("height").coerceAtLeast(1)
        }
    }

    @Composable
    private fun Screen() = FixedPage("手势记录板") {
        WorkingArea(board,Modifier.fillMaxWidth().weight(1f)) {
        SectionCard {
            SuperSwitch(
                checked = dragMode,
                onCheckedChange = { dragMode = it; board.dragMode = it },
                title = "记录左键点击 / 拖动",
                summary = "关闭后仅移动鼠标",
                enabled = !recording && !playing
            )
        }
        SectionCard {
            ActionRow {
                Action(
                    if (recording) "结束记录" else "开始记录",
                    enabled = !playing,
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) {
                    if (board.recording) board.finish() else { board.dragMode = dragMode; board.begin() }
                }
                Action("清空", enabled = !playing, modifier = Modifier.weight(1f)) {
                    board.load(JSONArray())
                    eventCount = 0
                }
            }
            ActionRow {
                Action("保存手势", enabled = !playing, modifier = Modifier.weight(1f)) { saveGesture() }
                Action("手势列表", enabled = !playing, modifier = Modifier.weight(1f)) { chooseGesture() }
            }
        }
        SectionCard("回放") {
            Field(
                label = "延迟播放（秒，0 为立即）",
                value = delaySeconds,
                onValueChange = { delaySeconds = it.filter(Char::isDigit).take(5) }
            )
            ActionRow {
                Action(
                    "在电脑播放",
                    enabled = !recording && !playing && eventCount > 0,
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) { confirmPlay() }
                Action("取消播放", enabled = playing, modifier = Modifier.weight(1f)) { playback?.cancel() }
            }
            Note("按原时间间隔回放，单次最多 5 分钟；延迟由电脑计时。离开此页或断线会取消，并释放鼠标。最多保存 20 条手势。")
        }
        }
    }

    private fun saved() = runCatching { JSONArray(prefs.getString("saved", "[]")) }.getOrElse { JSONArray() }

    internal fun saveGesture() {
        if (board.recording || playing) { status("请先结束记录或播放", true); return }
        val events = board.snapshot()
        if (events.length() == 0) { status("请先记录手势", true); return }
        val data = saved()
        if (data.length() >= 20) { status("手势列表已满，请先删除旧手势", true); return }
        val input = dialogInput(text = "手势 ${DateFormat.getTimeInstance().format(Date())}")
        WorkspaceDialog.Builder(this).setTitle("保存本地手势").setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                data.put(
                    JSONObject()
                        .put("name", input.text.toString().trim().take(60).ifBlank { "未命名手势" })
                        .put("events", events)
                        .put("aspect", board.aspect.toDouble())
                )
                prefs.edit().putString("saved", data.toString()).apply()
                status("手势已保存在手机")
            }.show()
    }

    internal fun chooseGesture() {
        if (board.recording || playing) { status("请先结束记录或播放", true); return }
        val data = saved()
        if (data.length() == 0) { status("暂无已保存手势"); return }
        WorkspaceDialog.Builder(this).setTitle("手势列表")
            .setItems((0 until data.length()).map { data.getJSONObject(it).getString("name") }.toTypedArray()) { _, index ->
                val item = data.getJSONObject(index)
                WorkspaceDialog.Builder(this).setTitle(item.getString("name"))
                    .setMessage("${item.getJSONArray("events").length()} 个事件。加载会替换画板上的当前记录。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("删除") { _, _ ->
                        data.remove(index)
                        prefs.edit().putString("saved", data.toString()).apply()
                        status("本地手势已删除")
                    }
                    .setPositiveButton("加载") { _, _ ->
                        board.load(item.getJSONArray("events"))
                        eventCount = board.snapshot().length()
                        status("手势已加载，可设置延迟后播放")
                    }.show()
            }.show()
    }

    internal fun confirmPlay() {
        if (board.recording || playing) return
        val seconds = delaySeconds.toIntOrNull()
        if (seconds == null || seconds !in 0..86400) { status("请输入 0–86400 秒", true); return }
        val events = board.snapshot()
        if (events.length() == 0) return
        WorkspaceDialog.Builder(this).setTitle("在电脑回放手势？")
            .setMessage("$seconds 秒后按原时间间隔移动、点击或拖动鼠标。\n请先确认电脑当前画面，回放可能操作当前窗口；可随时取消。")
            .setNegativeButton("取消", null)
            .setPositiveButton("播放") { _, _ -> beginPlayback(events, seconds) }.show()
    }

    private fun beginPlayback(events: JSONArray, seconds: Int) {
        if (playing) return
        playing = true
        board.isEnabled = false
        playback = task {
            var current: RemoteLease? = null
            try {
                pinnedCall(origin, "capabilities")
                current = RemoteLease("input", origin ?: error("连接已结束")); lease = current
                current.open()
                current.call(
                    "gestures",
                    JSONObject().put("action", "play").put("events", events).put("delay", seconds)
                )
                while (coroutineContext.isActive) {
                    current.keepAlive()
                    val job = current.call("gestures").optJSONObject("job") ?: error("回放任务已结束")
                    when (job.getString("status")) {
                        "waiting" -> status(
                            "等待回放 · 约 ${((job.getDouble("due") * 1000 - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)} 秒"
                        )
                        "playing" -> status("正在回放 ${job.optInt("index")} / ${job.optInt("count")}")
                        "completed" -> { status("手势回放完成"); break }
                        "cancelled" -> { status("手势回放已取消"); break }
                        else -> error(job.optString("error", "回放失败"))
                    }
                    delay(500)
                }
            } finally {
                current?.close(); lease = null
                if (!coroutineContext.isActive) status("手势回放已取消")
                playing = false
                board.isEnabled = true
                eventCount = board.snapshot().length()
            }
        }
    }

    override fun onStop() {
        board.finish(); playback?.cancel(); super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        board.finish()
        outState.putString("events", board.snapshot().toString())
        super.onSaveInstanceState(outState)
    }
}

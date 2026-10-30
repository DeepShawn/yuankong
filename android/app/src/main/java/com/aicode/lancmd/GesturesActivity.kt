package com.aicode.lancmd

import android.os.Bundle
import android.graphics.BitmapFactory
import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val library by lazy { GestureLibrary(this) }
    private var loaded by mutableStateOf(false)
    private var frameLoading by mutableStateOf(false)
    private var autosave: Job? = null

    /** 画板是自绘 View，Compose 里用 AndroidView 承载。 */
    internal lateinit var board: GestureBoard

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
            if (loaded) {
                status(if (board.recording) "正在记录 · ${board.eventCount} 个事件" else "已记录 · ${board.eventCount} 个事件")
                if (!board.recording) saveDraft()
                else if (autosave?.isActive != true) autosave = task { delay(1000); saveDraft() }
            }
        }
        status("正在恢复手机上的手势记录…")
        content { Screen() }
        task {
            val data = library.load()
            val saved = data.optJSONArray("saved") ?: JSONArray()
            val draft = data.optJSONObject("draft") ?: saved.optJSONObject(saved.length() - 1)
            dragMode = draft?.optBoolean("drag", true) ?: true
            delaySeconds = draft?.optString("delay", "0") ?: "0"
            board.dragMode = dragMode
            if (draft != null) board.aspect = draft.optDouble("aspect", 16.0 / 9).toFloat()
            val restored = savedInstanceState?.getString("events")?.let { JSONArray(it) }
                ?: draft?.optJSONArray("events") ?: JSONArray()
            board.load(restored)
            loaded = true
            status(if (eventCount > 0) "已恢复上次记录 · $eventCount 个事件" else "记录保存在手机；回放才会操作电脑。")
            refreshFrame()
        }
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
                onCheckedChange = { dragMode = it; board.dragMode = it; saveDraft() },
                title = "记录左键点击 / 拖动",
                summary = "关闭后仅移动鼠标",
                enabled = loaded && !recording && !playing
            )
        }
        SectionCard {
            Action("刷新桌面底图", enabled = loaded && !frameLoading && !recording && !playing) { refreshFrame() }
            Note("底图是刷新时的桌面最后一帧，保持原比例；录制不会向电脑发送操作。")
            ActionRow {
                Action(
                    if (recording) "结束记录" else "开始记录",
                    enabled = loaded && !playing && !frameLoading,
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) {
                    if (board.recording) board.finish() else { board.dragMode = dragMode; board.begin() }
                }
                Action("清空", enabled = loaded && !playing, modifier = Modifier.weight(1f)) {
                    board.load(JSONArray())
                    eventCount = 0
                }
            }
            ActionRow {
                Action("保存手势", enabled = loaded && !playing, modifier = Modifier.weight(1f)) { saveGesture() }
                Action("手势列表", enabled = loaded && !playing, modifier = Modifier.weight(1f)) { chooseGesture() }
            }
        }
        SectionCard("回放") {
            Field(
                label = "延迟播放（秒，0 为立即）",
                value = delaySeconds,
                onValueChange = { delaySeconds = it.filter(Char::isDigit).take(5); saveDraft() }
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
            Note("按原时间间隔回放，单次最多 5 分钟；延迟由电脑计时。离开此页或断线会取消回放，并释放鼠标。草稿自动保存，命名手势最多 20 条。")
        }
        }
    }

    private fun draft() = JSONObject().put("events", board.snapshot()).put("aspect", board.aspect.toDouble())
        .put("drag", dragMode).put("delay", delaySeconds)

    private fun saveDraft() {
        if (!loaded) return
        val value = draft()
        library.enqueueDraft(value) { error ->
            runOnUiThread { if (!isDestroyed) status("草稿保存失败：${error.message}，请保留当前记录后重试", true) }
        }
    }

    private fun refreshFrame() {
        if (frameLoading || recording || playing) return
        frameLoading = true
        task {
            val screen = RemoteLease("screen", origin ?: run { frameLoading = false; error("请先连接电脑") })
            try {
                screen.open()
                val result = screen.call("screen")
                val bitmap = withContext(Dispatchers.Default) {
                    val bytes = Base64.decode(result.getString("data"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: error("桌面底图解码失败")
                board.desktopFrame = bitmap
                board.aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                status("桌面底图已更新 · ${bitmap.width} × ${bitmap.height} · 已恢复 $eventCount 个事件")
            } finally { screen.close(); frameLoading = false }
        }
    }

    internal fun saveGesture() {
        if (!loaded || board.recording || playing) { status("请先结束记录或播放", true); return }
        val value = draft()
        if (board.eventCount == 0) { status("请先记录手势", true); return }
        val input = dialogInput(text = "手势 ${DateFormat.getTimeInstance().format(Date())}")
        WorkspaceDialog.Builder(this).setTitle("保存本地手势").setView(input).focusInput(input,selectAll=true)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().take(60).ifBlank { "未命名手势" }
                task { library.add(name, value); status("手势已保存在手机：$name") }
            }.show()
    }

    internal fun chooseGesture() {
        if (!loaded || board.recording || playing) { status("请先结束记录或播放", true); return }
        task {
            val data = library.load().optJSONArray("saved") ?: JSONArray()
            if (data.length() == 0) { status("暂无已保存手势"); return@task }
            WorkspaceDialog.Builder(this@GesturesActivity).setTitle("手势列表")
                .setItems((0 until data.length()).map { data.getJSONObject(it).getString("name") }.toTypedArray()) { _, index ->
                    val item = data.getJSONObject(index)
                    WorkspaceDialog.Builder(this@GesturesActivity).setTitle(item.getString("name"))
                        .setMessage("${item.getJSONArray("events").length()} 个事件。加载会替换画板上的当前记录。")
                        .setNegativeButton("取消", null)
                        .setNeutralButton("删除") { _, _ -> task { library.remove(index); status("本地手势已删除") } }
                        .setPositiveButton("加载") { _, _ ->
                            dragMode = item.optBoolean("drag", true); board.dragMode = dragMode
                            delaySeconds = item.optString("delay", "0")
                            board.load(item.getJSONArray("events"))
                            eventCount = board.eventCount
                            saveDraft()
                            status("手势已加载，可设置延迟后播放")
                        }.show()
                }.show()
        }
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
        board.finish(); autosave?.cancel(); saveDraft(); playback?.cancel(); super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        board.finish()
        // Persist outside the Binder state bundle; also survives a real page exit.
        saveDraft()
        super.onSaveInstanceState(outState)
    }
}

package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 远程按键与桌面消息。界面使用 miuix。 */
class ControlActivity : MiuixActivity() {
    private val combinations = listOf("Ctrl+C", "Ctrl+V", "Ctrl+A", "Alt+Tab")

    private var lease: RemoteLease? = null
    private var sessionJob: Job? = null

    /** 虚拟键盘是自绘 View，Compose 里用 AndroidView 承载。 */
    private lateinit var keyboard: VirtualKeyboard

    /** 已取得键盘控制：组合键、虚拟键盘与文字发送才可用。 */
    internal var controlling by mutableStateOf(false)
    private var requesting by mutableStateOf(false)

    private var messageTitle by mutableStateOf("手机消息")
    private var messageText by mutableStateOf("")
    private var typed by mutableStateOf("")
    private var sending by mutableStateOf(false)

    private val message get() = intent.getStringExtra("kind") == "message"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppVisibility.observe(this) { sessionJob?.cancel() }
        if (message) {
            status("消息会在 Windows 桌面显示，电脑端可直接关闭")
            content { MessageScreen() }
        } else {
            keyboard = VirtualKeyboard(this) { names ->
                task { (lease ?: error("请先开始键盘控制")).call("input", JSONObject().put("keys", JSONArray(names))) }
            }
            status("快捷按键、虚拟键盘与文字输入 · 控制电脑当前前台窗口")
            content { KeyboardScreen() }
        }
    }

    @Composable
    private fun MessageScreen() = Page("发送消息") {
        SectionCard("消息内容") {
            Field(label = "标题", value = messageTitle, onValueChange = { messageTitle = it.take(60) })
            Field(
                label = "写一条消息…",
                value = messageText,
                onValueChange = { messageText = it.take(2000) },
                singleLine = false
            )
            Action("发送到电脑", enabled = !sending, primary = true) { send() }
        }
    }

    @Composable
    private fun KeyboardScreen() = Page("远程按键") {
        SectionCard("键盘控制") {
            Note("开始控制后，按键和文字将发送至电脑当前前台窗口。")
            ActionRow {
                Action(
                    "请求键盘控制",
                    enabled = !controlling && !requesting,
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) { begin() }
                Action("停止控制", enabled = controlling, modifier = Modifier.weight(1f)) { sessionJob?.cancel() }
            }
        }
        SectionCard("常用组合键") {
            ActionRow {
                combinations.forEach { name ->
                    Action(name, enabled = controlling, modifier = Modifier.weight(1f)) { sendKeys(name) }
                }
            }
        }
        SectionCard("虚拟键盘") {
            keyboard.isEnabled = controlling
            HostedView(keyboard, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        SectionCard("文字输入") {
            Field(
                label = "输入文字到电脑",
                value = typed,
                onValueChange = { typed = it.take(1000) },
                singleLine = false
            )
            Action("发送文字", enabled = controlling) { sendText() }
        }
    }

    internal fun send() {
        if (sending) return
        if (messageText.isBlank()) { status("请输入消息内容", true); return }
        sending = true
        task {
            try {
                // 与旧版一致：消息发往当前连接的电脑，不固定到打开页面时的连接。
                val result = ConnectionManager.send(
                    "message",
                    JSONObject().put("title", messageTitle).put("text", messageText)
                )
                if (!result.optBoolean("ok")) {
                    val error = result.optString("error", "操作失败")
                    throw IOException(if (error.contains("未知命令")) "$error，请更新电脑端至 1.5.0" else error)
                }
                status("已发送到电脑，消息将在桌面显示")
            } finally { sending = false }
        }
    }

    internal fun sendText() {
        if (typed.isEmpty()) return
        task {
            (lease ?: error("请先请求键盘控制")).call("input", JSONObject().put("text", typed))
            status("文字已发送到前台窗口")
        }
    }

    internal fun sendKeys(label: String) {
        val symbols = mapOf("←" to "LEFT", "↑" to "UP", "↓" to "DOWN", "→" to "RIGHT", "退格" to "BACKSPACE")
        val keys = (symbols[label] ?: label).uppercase().split("+")
        task {
            (lease ?: error("请先请求键盘控制")).call("input", JSONObject().put("keys", JSONArray(keys)))
            status("已发送 $label")
        }
    }

    internal fun begin() {
        if (sessionJob?.isActive == true) return
        requesting = true
        sessionJob = lifecycleScope.launch {
            var current: RemoteLease? = null
            try {
                val source = ConnectionManager.sessionClient()
                val features = pinnedCall(source, "capabilities").getJSONArray("features")
                if ((0 until features.length()).none { features.optString(it) == "input" }) {
                    error("请更新 Windows 接收端至 1.5.0")
                }
                current = RemoteLease("input", source)
                status("正在开始键盘控制…")
                current.open()
                coroutineContext.ensureActive()
                lease = current
                controlling = true
                status("控制已开始，按键将发送到电脑当前前台窗口")
                while (isActive) {
                    delay(3000)
                    check(ConnectionManager.sessionClient() === source && !source.closed) {
                        "电脑连接已切换，请重新开始键盘控制"
                    }
                    current.keepAlive()
                }
            } catch (e: CancellationException) { status("键盘控制已停止"); throw e }
            catch (e: Exception) { status(e.message ?: "无法开始控制", true) }
            finally {
                lease = null
                current?.close()
                controlling = false
                requesting = false
            }
        }
    }

    override fun onStop() {
        if (isFinishing) sessionJob?.cancel()
        super.onStop()
    }
}

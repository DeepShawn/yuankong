package com.aicode.lancmd

import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.RemoteLease
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class ControlActivity : FeatureActivity() {
    private var lease: RemoteLease? = null
    private var sessionJob: Job? = null
    private val controlled = ArrayList<android.view.View>()
    private lateinit var start: com.google.android.material.button.MaterialButton
    private lateinit var stop: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("kind") == "message") messageUi() else keyboardUi()
    }

    private fun input(parent: LinearLayout, hint: String, lines: Int, limit: Int): EditText {
        val field = EditText(this).apply {
            minLines = lines
            maxLines = lines + 2
            filters = arrayOf(InputFilter.LengthFilter(limit))
            setTextColor(color(R.color.text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or if (lines > 1) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        }
        val wrapper = TextInputLayout(this).apply {
            this.hint = hint
            addView(field)
        }
        parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return field
    }

    private fun messageUi() {
        setup("发送消息", "消息会在 Windows 桌面显示，电脑端可直接关闭", scrollable = true)
        val box = panel()
        val title = input(box, "标题", 1, 60).apply { setText("手机消息") }
        val text = input(box, "写一条消息…", 5, 2000)
        lateinit var send: com.google.android.material.button.MaterialButton
        send = button("发送到电脑") {
            if (text.text.isBlank()) { status("请输入消息内容", true); return@button }
            send.isEnabled = false
            task {
                try {
                    request("message", JSONObject().put("title", title.text.toString()).put("text", text.text.toString()))
                    status("已发送到电脑，消息将在桌面显示")
                } finally { send.isEnabled = true }
            }
        }
        box.addView(send)
        primary(send)
    }

    private fun keyboardUi() {
        setup("远程按键", "快捷按键、虚拟键盘与文字输入 · 控制电脑当前前台窗口", scrollable = true)
        val session = panel()
        session.addView(label("开始控制后，按键和文字将发送至电脑当前前台窗口。", 13f, R.color.dim))
        start = button("请求键盘控制") { begin() }
        primary(start)
        stop = button("停止控制") { sessionJob?.cancel() }.apply { isEnabled = false }
        session.addView(start)
        session.addView(stop)
        val keys = panel()
        listOf(listOf("Esc", "Tab", "Enter", "退格"), listOf("←", "↑", "↓", "→"),
            listOf("Ctrl+C", "Ctrl+V", "Ctrl+A", "Alt+Tab"), listOf("Win+D", "F5", "Space", "Delete")).forEach { row ->
            val line = LinearLayout(this)
            row.forEach { name ->
                val key = button(name) { sendKeys(name) }.apply {
                    textSize = 11f; isEnabled = false; minWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setSingleLine()
                }
                controlled.add(key)
                line.addView(key, LinearLayout.LayoutParams(0, -2, 1f))
            }
            keys.addView(line)
        }
        val text = input(keys, "输入文字到电脑", 2, 1000)
        val send = button("发送文字") {
            if (text.text.isNotEmpty()) task {
                (lease ?: error("请先请求键盘控制")).call("input", JSONObject().put("text", text.text.toString()))
                status("文字已发送到前台窗口")
            }
        }.apply { isEnabled = false }
        controlled.add(send)
        keys.addView(send)
        val virtual = VirtualKeyboard(this) { names ->
            task { (lease ?: error("请先开始键盘控制")).call("input", JSONObject().put("keys", JSONArray(names))) }
        }.apply { isEnabled = false }
        controlled.add(virtual)
        keys.addView(label("虚拟键盘 · 修饰键作用于下一个按键，横滑可查看更多键", 12f, R.color.dim))
        keys.addView(virtual)
    }

    private fun sendKeys(label: String) {
        val symbols = mapOf("←" to "LEFT", "↑" to "UP", "↓" to "DOWN", "→" to "RIGHT", "退格" to "BACKSPACE")
        val keys = (symbols[label] ?: label).uppercase().split("+")
        task {
            (lease ?: error("请先请求键盘控制")).call("input", JSONObject().put("keys", JSONArray(keys)))
            status("已发送 $label")
        }
    }

    private fun begin() {
        if (sessionJob?.isActive == true) return
        start.isEnabled = false
        stop.isEnabled = true
        sessionJob = lifecycleScope.launch {
            var current: RemoteLease? = null
            try {
                val features = request("capabilities").getJSONArray("features")
                if ((0 until features.length()).none { features.optString(it) == "input" }) error("请更新 Windows 接收端至 1.5.0")
                current = RemoteLease("input")
                status("正在开始键盘控制…")
                current.open()
                coroutineContext.ensureActive()
                lease = current
                controlled.forEach { it.isEnabled = true }
                status("控制已开始，按键将发送到电脑当前前台窗口")
                while (isActive) { delay(8000); current.keepAlive() }
            } catch (e: CancellationException) { status("键盘控制已停止"); throw e }
            catch (e: Exception) { status(e.message ?: "无法开始控制", true) }
            finally {
                lease = null
                current?.close()
                if (!isDestroyed) {
                    controlled.forEach { it.isEnabled = false }
                    start.isEnabled = true
                    stop.isEnabled = false
                }
            }
        }
    }

    override fun onStop() {
        sessionJob?.cancel()
        super.onStop()
    }
}

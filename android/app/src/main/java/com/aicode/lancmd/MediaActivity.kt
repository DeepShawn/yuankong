package com.aicode.lancmd

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class MediaActivity : FeatureActivity() {
    private lateinit var image: ImageView
    private lateinit var start: com.google.android.material.button.MaterialButton
    private lateinit var stop: com.google.android.material.button.MaterialButton
    private lateinit var save: com.google.android.material.button.MaterialButton
    private lateinit var selector: Spinner
    private var stream: Job? = null
    private var frame: ByteArray? = null
    private var saveFrame: ByteArray? = null
    private var deviceIds = listOf(0)
    private val camera get() = intent.getStringExtra("kind") == "camera"
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val bytes = saveFrame
        saveFrame = null
        if (uri != null && bytes != null) task {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入文件")
            }
            status("当前画面已保存")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup(if (camera) "摄像头" else "电脑屏幕", "点击开始查看建立预览，关闭页面结束共享")
        val help = panel()
        help.addView(label("随时停止共享", 16f))
        help.addView(label("关闭本页会结束预览，也可点击下方「停止查看」。", 12f, R.color.dim))
        selector = Spinner(this)
        selector.isEnabled = false
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("开始后选择摄像头"))
        selector.visibility = if (camera) View.VISIBLE else View.GONE
        body.addView(selector)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(color(R.color.surface))
            contentDescription = if (camera) "摄像头实时画面" else "电脑屏幕实时画面"
        }
        body.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        start = button("开始查看") { begin() }
        primary(start)
        stop = button("停止查看") {
            status("正在停止共享…")
            stream?.cancel()
        }.apply { isEnabled = false }
        save = button("保存当前帧") {
            saveFrame = frame?.copyOf()
            if (saveFrame != null) saveDocument.launch("LanCmd-${System.currentTimeMillis()}.jpg")
        }.apply { isEnabled = false }
        val buttons = LinearLayout(this)
        listOf(start, stop).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        body.addView(buttons)
        body.addView(save)
    }

    private fun begin() {
        if (stream?.isActive == true) return
        start.isEnabled = false
        stop.isEnabled = true
        stream = lifecycleScope.launch {
            var lease: RemoteLease? = null
            try {
                val kind = if (camera) "camera" else "screen"
                val caps = request("capabilities").optJSONArray("features")
                if (caps == null || (0 until caps.length()).none { caps.optString(it) == kind }) {
                    error("请完整安装 Windows 1.5.0 接收端及采集组件")
                }
                lease = RemoteLease(kind)
                status("正在建立${if (camera) "摄像头" else "屏幕"}预览…")
                lease.open()
                coroutineContext.ensureActive()
                if (camera) {
                    val devices = lease.call("camera", JSONObject().put("action", "list")).getJSONArray("devices")
                    if (devices.length() == 0) error("电脑上没有可用摄像头，请检查系统隐私权限和设备连接")
                    deviceIds = (0 until devices.length()).map { devices.getJSONObject(it).getInt("id") }
                    selector.adapter = ArrayAdapter(this@MediaActivity, android.R.layout.simple_spinner_dropdown_item,
                        (0 until devices.length()).map { devices.getJSONObject(it).getString("name") })
                    selector.isEnabled = true
                }
                while (isActive) {
                    val started = System.currentTimeMillis()
                    val params = JSONObject().put("device", deviceIds.getOrElse(selector.selectedItemPosition) { 0 })
                    val result = lease.call(kind, params)
                    val bytes = withContext(Dispatchers.Default) { Base64.decode(result.getString("data"), Base64.DEFAULT) }
                    val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        ?: error("无法解码画面")
                    coroutineContext.ensureActive()
                    frame = bytes
                    image.setImageBitmap(bitmap)
                    save.isEnabled = true
                    status("正在预览 · ${bitmap.width} × ${bitmap.height} · ${System.currentTimeMillis() - started} ms")
                    delay(if (camera) 1200 else 800)
                }
            } catch (e: CancellationException) {
                status("预览已停止")
                throw e
            } catch (e: Exception) { status(e.message ?: "预览失败", true) }
            finally {
                lease?.close()
                frame = null
                if (!isDestroyed) {
                    image.setImageDrawable(null)
                    selector.isEnabled = false
                    save.isEnabled = false
                    stop.isEnabled = false
                    start.isEnabled = true
                }
            }
        }
    }

    override fun onStop() {
        stream?.cancel()
        super.onStop()
    }
}

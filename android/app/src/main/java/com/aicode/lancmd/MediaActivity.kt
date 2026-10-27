package com.aicode.lancmd

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class MediaActivity : FeatureActivity() {
    private companion object { const val STATE_FULLSCREEN = "fullscreen" }

    private lateinit var image: ImageView
    private lateinit var start: com.google.android.material.button.MaterialButton
    private lateinit var stop: com.google.android.material.button.MaterialButton
    private lateinit var save: com.google.android.material.button.MaterialButton
    private lateinit var selector: Spinner
    private lateinit var fullscreenButton: com.google.android.material.button.MaterialButton
    private lateinit var exitFullscreen: com.google.android.material.button.MaterialButton
    private lateinit var toolbarView: View
    /** 全屏时隐藏的页面元素：设备选择、按钮组与说明文字。 */
    private val chrome = ArrayList<View>()
    private var fullscreen = false
    private var stream: Job? = null
    private var frame: ByteArray? = null
    private var saveFrame: ByteArray? = null
    private var pageVisible = false
    private val frameMutex=Mutex()
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
        setup(if (camera) "摄像头" else "电脑屏幕", "应用内进入子页面保留共享，返回后继续预览")
        AppVisibility.observe(this) { stream?.cancel() }
        toolbarView = (body.parent as ViewGroup).getChildAt(0)
        selector = Spinner(this)
        selector.isEnabled = false
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("开始后选择摄像头"))
        styleSpinner(selector)
        if (camera) {
            val field = fieldRow("摄像头设备", selector)
            body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(Ui.GAP) })
            chrome.add(field)
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.status_bg)
            clipToOutline = true
            contentDescription = if (camera) "摄像头实时画面" else "电脑屏幕实时画面"
        }
        val imageHost = FrameLayout(this)
        imageHost.addView(image, FrameLayout.LayoutParams(-1, -1))
        exitFullscreen = button("退出全屏") { setFullscreen(false) }.apply {
            id = R.id.mediaExitFullscreen
            visibility = View.GONE
            textSize = Ui.TEXT_CAPTION
        }
        imageHost.addView(exitFullscreen, FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.END)
            .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        body.addView(imageHost, LinearLayout.LayoutParams(-1, 0, 1f))
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
        fullscreenButton = button("全屏") { setFullscreen(!fullscreen) }.apply { id = R.id.mediaFullscreen }
        val record = button(if (camera) "录制摄像头视频" else "录制屏幕视频") {
            openChild(android.content.Intent(this, RecordingsActivity::class.java).putExtra("kind", if (camera) "camera" else "screen"))
        }
        val rows = ArrayList<View>()
        rows.add(actions(start, stop))
        rows.add(actions(save, fullscreenButton))
        rows.add(if (camera) actions(record,
            button("摄像头 / 麦克风设备管理") { openChild(android.content.Intent(this,PrivacyDevicesActivity::class.java)) }
                .apply { textSize = Ui.TEXT_CAPTION })
            else actions(record))
        rows.forEachIndexed { index, row ->
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (index == 0) Ui.GAP else 6) })
            chrome.add(row)
        }
        val note = caption("进入应用内子页面时暂停取帧，返回继续；退出本页、离开应用或切换电脑会结束共享。全屏时画面等比显示，点右上角或返回键退出。")
        body.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        chrome.add(note)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) setFullscreen(false) else finish() }
        })
        if (savedInstanceState?.getBoolean(STATE_FULLSCREEN) == true) setFullscreen(true)
    }

    /** 全屏只保留等比画面和退出按钮；画面本身继续按 FIT_CENTER 显示，黑边不裁切。 */
    private fun setFullscreen(value: Boolean) {
        fullscreen = value
        toolbarView.visibility = if (value) View.GONE else View.VISIBLE
        body.setPadding(if (value) 0 else dp(Ui.PAD_PAGE), 0, if (value) 0 else dp(Ui.PAD_PAGE), if (value) 0 else dp(12))
        exitFullscreen.visibility = if (value) View.VISIBLE else View.GONE
        chrome.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        hideInlineFeedback(value)
        fullscreenButton.text = if (value) "退出全屏" else "全屏"
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FULLSCREEN, fullscreen)
        super.onSaveInstanceState(outState)
    }

    private fun begin() {
        if (stream?.isActive == true) return
        start.isEnabled = false
        stop.isEnabled = true
        stream = lifecycleScope.launch(feedbackContext()) {
            var lease: RemoteLease? = null
            try {
                val kind = if (camera) "camera" else "screen"
                val source=ConnectionManager.sessionClient()
                val caps = pinnedCall(source,"capabilities").optJSONArray("features")
                if (caps == null || (0 until caps.length()).none { caps.optString(it) == kind }) {
                    error("请完整安装 Windows 1.5.0 接收端及采集组件")
                }
                val current = RemoteLease(kind,source)
                lease = current
                status("正在建立${if (camera) "摄像头" else "屏幕"}预览…")
                current.open()
                coroutineContext.ensureActive()
                if (camera) {
                    val devices = current.call("camera", JSONObject().put("action", "list")).getJSONArray("devices")
                    if (devices.length() == 0) error("电脑上没有可用摄像头，请检查系统隐私权限和设备连接")
                    deviceIds = (0 until devices.length()).map { devices.getJSONObject(it).getInt("id") }
                    selector.adapter = ArrayAdapter(this@MediaActivity, android.R.layout.simple_spinner_dropdown_item,
                        (0 until devices.length()).map { devices.getJSONObject(it).getString("name") })
                    selector.isEnabled = true
                }
                while (isActive) {
                    check(ConnectionManager.sessionClient()===source && !source.closed) { "电脑连接已切换，请重新开始预览" }
                    if(!pageVisible) { current.keepAlive();delay(1000);continue }
                    if(camera && (0 until caps.length()).any { caps.optString(it)=="recordings" }) {
                        val records=current.call("recordings").optJSONArray("recordings")
                        if(records!=null && (0 until records.length()).any {
                            val item=records.getJSONObject(it)
                            item.optString("mode")=="camera" && item.optString("status") in listOf("starting","recording","stopping")
                        }) {
                            status("摄像头正在录制；停止录制后自动继续预览")
                            current.keepAlive();delay(1500);continue
                        }
                    }
                    val started = System.currentTimeMillis()
                    val params = JSONObject().put("device", deviceIds.getOrElse(selector.selectedItemPosition) { 0 })
                    val result = frameMutex.withLock { if(pageVisible)current.call(kind, params) else null } ?: continue
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

    private fun openChild(intent:android.content.Intent) {
        pageVisible=false
        task {
            try { frameMutex.withLock { startActivity(intent) } }
            catch(e:Exception) { pageVisible=true;throw e }
        }
    }

    override fun onStart() { super.onStart();pageVisible=true }
    override fun onStop() {
        pageVisible=false
        if(isFinishing)stream?.cancel()
        super.onStop()
    }
}

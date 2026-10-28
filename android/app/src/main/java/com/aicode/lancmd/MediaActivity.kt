package com.aicode.lancmd

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.RemoteLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperDropdown
import kotlin.coroutines.coroutineContext

/** 电脑屏幕与摄像头实时预览，支持全屏。界面使用 miuix。 */
class MediaActivity : MiuixActivity() {
    private companion object { const val STATE_FULLSCREEN = "fullscreen" }

    /** 画面是 ImageView（位图输出），Compose 里用 AndroidView 承载。 */
    private lateinit var image: ImageView

    internal var fullscreen by mutableStateOf(false)
    internal var previewing by mutableStateOf(false)
    internal var canSave by mutableStateOf(false)
    private var deviceNames by mutableStateOf(listOf("开始后选择摄像头"))
    private var deviceIndex by mutableIntStateOf(0)
    private var devicesReady by mutableStateOf(false)

    private var stream: Job? = null
    private var frame: ByteArray? = null
    private var saveFrame: ByteArray? = null
    private var pageVisible = false
    private val frameMutex = Mutex()
    private var deviceIds = listOf(0)

    private val camera get() = intent.getStringExtra("kind") == "camera"

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
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
        AppVisibility.observe(this) { stream?.cancel() }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.status_bg)
            clipToOutline = true
            contentDescription = if (camera) "摄像头实时画面" else "电脑屏幕实时画面"
        }
        status("应用内进入子页面保留共享，返回后继续预览")
        content { Screen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) showFullscreen(false) else finish() }
        })
        if (savedInstanceState?.getBoolean(STATE_FULLSCREEN) == true) showFullscreen(true)
    }

    @Composable
    private fun Screen() {
        // 全屏只保留等比画面和退出按钮；画面本身继续按 FIT_CENTER 显示，黑边不裁切。
        if (fullscreen) Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { image }, modifier = Modifier.fillMaxSize())
            Action("退出全屏", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                showFullscreen(false)
            }
        } else Page(if (camera) "摄像头" else "电脑屏幕") {
            if (camera) SectionCard {
                SuperDropdown(
                    items = deviceNames, title = "摄像头设备",
                    selectedIndex = deviceIndex.coerceIn(0, (deviceNames.size - 1).coerceAtLeast(0)),
                    onSelectedIndexChange = { deviceIndex = it },
                    enabled = devicesReady
                )
            }
            AndroidView(
                factory = { image },
                modifier = Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 12.dp, vertical = 6.dp)
            )
            SectionCard {
                ActionRow {
                    Action(
                        "开始查看",
                        enabled = !previewing,
                        primary = true,
                        modifier = Modifier.weight(1f)
                    ) { begin() }
                    Action("停止查看", enabled = previewing, modifier = Modifier.weight(1f)) {
                        status("正在停止共享…")
                        stream?.cancel()
                    }
                }
                ActionRow {
                    Action("保存当前帧", enabled = canSave, modifier = Modifier.weight(1f)) { save() }
                    Action("全屏", modifier = Modifier.weight(1f)) { showFullscreen(true) }
                }
                ActionRow {
                    Action(
                        if (camera) "录制摄像头视频" else "录制屏幕视频",
                        modifier = Modifier.weight(1f)
                    ) {
                        openChild(
                            Intent(this@MediaActivity, RecordingsActivity::class.java)
                                .putExtra("kind", if (camera) "camera" else "screen")
                        )
                    }
                    if (camera) Action("摄像头 / 麦克风设备管理", modifier = Modifier.weight(1f)) {
                        openChild(Intent(this@MediaActivity, PrivacyDevicesActivity::class.java))
                    }
                }
                Note(
                    "进入应用内子页面时暂停取帧，返回继续；退出本页、离开应用或切换电脑会结束共享。" +
                        "全屏时画面等比显示，点右上角或返回键退出。"
                )
            }
        }
    }

    internal fun save() {
        saveFrame = frame?.copyOf()
        if (saveFrame != null) saveDocument.launch("LanCmd-${System.currentTimeMillis()}.jpg")
    }

    internal fun showFullscreen(value: Boolean) {
        fullscreen = value
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FULLSCREEN, fullscreen)
        super.onSaveInstanceState(outState)
    }

    internal fun begin() {
        if (stream?.isActive == true) return
        previewing = true
        stream = lifecycleScope.launch {
            var lease: RemoteLease? = null
            try {
                val kind = if (camera) "camera" else "screen"
                val source = ConnectionManager.sessionClient()
                val caps = pinnedCall(source, "capabilities").optJSONArray("features")
                if (caps == null || (0 until caps.length()).none { caps.optString(it) == kind }) {
                    error("请完整安装 Windows 1.5.0 接收端及采集组件")
                }
                val current = RemoteLease(kind, source)
                lease = current
                status("正在建立${if (camera) "摄像头" else "屏幕"}预览…")
                current.open()
                coroutineContext.ensureActive()
                if (camera) {
                    val devices = current.call("camera", JSONObject().put("action", "list")).getJSONArray("devices")
                    if (devices.length() == 0) error("电脑上没有可用摄像头，请检查系统隐私权限和设备连接")
                    deviceIds = (0 until devices.length()).map { devices.getJSONObject(it).getInt("id") }
                    deviceNames = (0 until devices.length()).map { devices.getJSONObject(it).getString("name") }
                    deviceIndex = 0
                    devicesReady = true
                }
                while (isActive) {
                    check(ConnectionManager.sessionClient() === source && !source.closed) {
                        "电脑连接已切换，请重新开始预览"
                    }
                    if (!pageVisible) { current.keepAlive(); delay(1000); continue }
                    if (camera && (0 until caps.length()).any { caps.optString(it) == "recordings" }) {
                        val records = current.call("recordings").optJSONArray("recordings")
                        if (records != null && (0 until records.length()).any {
                                val item = records.getJSONObject(it)
                                item.optString("mode") == "camera" &&
                                    item.optString("status") in listOf("starting", "recording", "stopping")
                            }
                        ) {
                            status("摄像头正在录制；停止录制后自动继续预览")
                            current.keepAlive(); delay(1500); continue
                        }
                    }
                    val started = System.currentTimeMillis()
                    val params = JSONObject().put("device", deviceIds.getOrElse(deviceIndex) { 0 })
                    val result = frameMutex.withLock { if (pageVisible) current.call(kind, params) else null }
                        ?: continue
                    val bytes = withContext(Dispatchers.Default) {
                        Base64.decode(result.getString("data"), Base64.DEFAULT)
                    }
                    val bitmap = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } ?: error("无法解码画面")
                    coroutineContext.ensureActive()
                    frame = bytes
                    image.setImageBitmap(bitmap)
                    canSave = true
                    status("正在预览 · ${bitmap.width} × ${bitmap.height} · ${System.currentTimeMillis() - started} ms")
                    delay(if (camera) 1200 else 800)
                }
            } catch (e: CancellationException) { status("预览已停止"); throw e }
            catch (e: Exception) { status(e.message ?: "预览失败", true) }
            finally {
                lease?.close()
                frame = null
                if (!isDestroyed) {
                    image.setImageDrawable(null)
                    devicesReady = false
                    canSave = false
                    previewing = false
                }
            }
        }
    }

    private fun openChild(intent: Intent) {
        pageVisible = false
        task {
            try { frameMutex.withLock { startActivity(intent) } }
            catch (e: Exception) { pageVisible = true; throw e }
        }
    }

    override fun onStart() {
        super.onStart(); pageVisible = true
    }

    override fun onStop() {
        pageVisible = false
        if (isFinishing) stream?.cancel()
        super.onStop()
    }
}

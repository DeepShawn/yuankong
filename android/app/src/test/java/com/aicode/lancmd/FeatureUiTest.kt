package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.parentOf
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
// Robolectric 的原生图形库只提供 x86_64/arm64 桌面版，
// 在 aarch64 Linux 容器上会抛 "native runtime is not supported on Linux (aarch64)"。
// 这是环境限制，不是代码问题，所以仅在支持原生运行时的平台执行。
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureUiTest {

    /**
     * Robolectric 的原生图形库只有 x86_64 桌面版，
     * aarch64 Linux 上会抛 "native runtime is not supported on Linux (aarch64)"。
     * 属环境限制，非代码问题，这里跳过。
     */
    @org.junit.Before
    fun skipOnUnsupportedNativeRuntime() {
        org.junit.Assume.assumeFalse(
            "需要 Robolectric 原生图形运行时（x86_64 桌面），当前 " +
                System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
            System.getProperty("os.arch") == "aarch64" && !System.getProperty("os.name").orEmpty().contains("Windows")
        )
    }
    @After fun stopConnection() {
        ConnectionManager.stop()
        val field = ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible = true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun render(activity: android.app.Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.findViewById<View>(android.R.id.content)
        view.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 411, 891)
        val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val destination = File("build/ui-previews/$name.png")
        destination.parentFile!!.mkdirs()
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("UI should render content", destination.length() > 3000)
        bitmap.recycle()
    }

    private fun buttons(view: View): List<MaterialButton> = when (view) {
        is MaterialButton -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** 该视图及其所有父级是否都可见：隐藏整行时按钮自身的 visibility 不变。 */
    private fun onScreen(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    @Test fun cameraAndScreenPreviewSupportFullscreen() {
        stopConnection()
        listOf("camera" to "camera-fullscreen", "screen" to "screen-fullscreen").forEach { (kind, preview) ->
            val intent = Intent(RuntimeEnvironment.getApplication(), MediaActivity::class.java).putExtra("kind", kind)
            val controller = Robolectric.buildActivity(MediaActivity::class.java, intent).setup()
            val activity = controller.get()
            val root = activity.findViewById<View>(android.R.id.content)
            val image = activity.findViewById<View>(android.R.id.content).let { content ->
                fun find(view: View): View? = when {
                    view.contentDescription == "摄像头实时画面" || view.contentDescription == "电脑屏幕实时画面" -> view
                    view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                    else -> null
                }
                find(content)!!
            }
            val exit = activity.findViewById<MaterialButton>(R.id.mediaExitFullscreen)
            val toggle = activity.findViewById<MaterialButton>(R.id.mediaFullscreen)
            assertEquals(View.GONE, exit.visibility)

            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Exit control must appear in fullscreen", View.VISIBLE, exit.visibility)
            assertEquals("退出全屏", toggle.text.toString())
            assertFalse("Start control is hidden in fullscreen",
                onScreen(buttons(root).single { it.text == "开始查看" }))
            root.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 411, 891)
            assertTrue("Fullscreen preview must use the full height, was ${image.height}", image.height > 800)
            render(activity, preview)

            exit.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, exit.visibility)
            assertEquals("全屏", toggle.text.toString())
            assertTrue("Controls return after leaving fullscreen",
                onScreen(buttons(root).single { it.text == "开始查看" }))

            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Back must leave fullscreen before closing the page", View.GONE, exit.visibility)
            assertFalse(activity.isFinishing)
            controller.pause().stop().destroy()
        }
    }

    @Test fun homeRoutesToScreenCameraKeyboardMessagesAndStorage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        stopConnection()
        StubAgent().use { stub ->
            stub.install()
            val activity = controller.get()
            MainActivity::class.java.getDeclaredField("firewallChecked").apply { isAccessible = true }.setBoolean(activity, true)
            activity.onStatus(ConnectionManager.Status(state = ConnectionManager.State.CONNECTED, name = "个人电脑", host = "192.168.1.8", latencyMs = 12, paired = true))
            activity.onEvent("连接已就绪。选择上方功能，或在下方输入命令。", ConnectionManager.LogKind.INFO)
            val rows = listOf(activity.findViewById<LinearLayout>(R.id.rowActions1), activity.findViewById(R.id.rowActions2))
            val tiles = rows.flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
            activity.findViewById<View>(R.id.cardDesktop).performClick()
            assertEquals(DesktopActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            assertFalse("Screen is consolidated into the desktop card",tiles.any { it.contentDescription=="屏幕" })
            val expected = mapOf("摄像头" to MediaActivity::class.java,
                "按键" to ControlActivity::class.java, "消息" to ControlActivity::class.java, "文件管理" to StorageActivity::class.java, "文件搜索" to FileSearchActivity::class.java)
            expected.forEach { (label, type) ->
                tiles.single { it.contentDescription == label }.performClick()
                assertEquals(type.name, shadowOf(activity).nextStartedActivity.component!!.className)
            }
            render(activity, "home")
            controller.pause().stop().destroy()
        }
    }

    @Test fun mediaNeverStartsCaptureOnPageOpen() {
        listOf("screen", "camera").forEach { kind ->
            val intent = Intent(RuntimeEnvironment.getApplication(), MediaActivity::class.java).putExtra("kind", kind)
            val controller = Robolectric.buildActivity(MediaActivity::class.java, intent).setup()
            val activity = controller.get()
            val controls = buttons(activity.findViewById(android.R.id.content))
            assertTrue(controls.single { it.text == "开始查看" }.isEnabled)
            assertFalse(controls.single { it.text == "停止查看" }.isEnabled)
            assertFalse(controls.single { it.text == "保存当前帧" }.isEnabled)
            render(activity, kind)
            controller.pause().stop().destroy()
        }
    }

    @Test fun keyboardRequiresConsentAndMessagePageRenders() {
        listOf("input", "message").forEach { kind ->
            val intent = Intent(RuntimeEnvironment.getApplication(), ControlActivity::class.java).putExtra("kind", kind)
            val controller = Robolectric.buildActivity(ControlActivity::class.java, intent).setup()
            val activity = controller.get()
            val controls = buttons(activity.findViewById(android.R.id.content))
            if (kind == "input") {
                assertFalse(controls.single { it.text == "Ctrl+V" }.isEnabled)
                assertFalse(controls.single { it.text == "发送文字" }.isEnabled)
                assertTrue(controls.single { it.text == "请求键盘控制" }.isEnabled)
            }
            render(activity, kind)
            controller.pause().stop().destroy()
        }
    }

    @Test fun storageShowsDiskUsageAndDirectoriesBeforeFiles() {
        stopConnection()
        StubAgent().use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(StorageActivity::class.java).setup()
            val activity = controller.get()
            var list: RecyclerView? = null
            fun find(view: View): RecyclerView? {
                if (view is RecyclerView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                list = find(activity.findViewById(android.R.id.content))
                if (list?.adapter?.itemCount == 3) break
                Thread.sleep(20)
            }
            assertEquals(3, list?.adapter?.itemCount)
            render(activity, "storage")
            val first = list!!.findViewHolderForAdapterPosition(0)!!.itemView as android.view.ViewGroup
            val labels = first.getChildAt(1) as LinearLayout
            assertEquals("Documents", (labels.getChildAt(0) as TextView).text.toString())
            controller.pause().stop().destroy()
        }
    }

    @Test fun keyboardStopsItsApprovedConnectionWhenPageCloses() {
        stopConnection()
        StubAgent().use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(ControlActivity::class.java,
                Intent(RuntimeEnvironment.getApplication(), ControlActivity::class.java).putExtra("kind", "input")).setup()
            val controls = buttons(controller.get().findViewById(android.R.id.content))
            controls.single { it.text == "请求键盘控制" }.performClick()
            waitUntil { controls.single { it.text == "发送文字" }.isEnabled }
            assertEquals("input", stub.requests.single { it.optString("cmd") == "session" }.optString("scope"))
            controller.pause().stop()
            shadowOf(Looper.getMainLooper()).idleFor(1,java.util.concurrent.TimeUnit.SECONDS)
            waitUntil { stub.requests.any { it.optString("cmd") == "session" && it.optString("action") == "end" } }
            assertEquals("approved-test-session", stub.requests.single { it.optString("action") == "end" }.optString("sid"))
            waitUntil { !controls.single { it.text == "发送文字" }.isEnabled }
            controller.destroy()
        }
    }

    @Test fun cameraPreviewSurvivesInternalChildAndWaitsForCameraRecordingToFinish() {
        stopConnection()
        StubAgent().use { stub->
            stub.install()
            val parent=Robolectric.buildActivity(MediaActivity::class.java,Intent(RuntimeEnvironment.getApplication(),MediaActivity::class.java).putExtra("kind","camera")).setup()
            val activity=parent.get();val root=activity.findViewById<View>(android.R.id.content)
            buttons(root).single { it.text=="开始查看" }.performClick()
            waitUntil { buttons(root).single { it.text=="保存当前帧" }.isEnabled }
            val child=Robolectric.buildActivity(android.app.Activity::class.java).setup()
            parent.pause().stop();stub.recordingActive=true
            shadowOf(Looper.getMainLooper()).idleFor(1500,java.util.concurrent.TimeUnit.MILLISECONDS)
            assertFalse(stub.requests.any { it.optString("action")=="end" })
            child.get().finish();child.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
            assertFalse("A slow return handoff must not end preview leases",stub.requests.any { it.optString("action")=="end" })
            parent.restart().start().resume()
            waitUntil { allTexts(root).any { it.contains("摄像头正在录制") } }
            assertFalse(stub.requests.any { it.optString("action")=="end" })
            val before=stub.requests.count { it.optString("cmd")=="camera" && it.optString("action")!="list" }
            stub.recordingActive=false
            waitUntil { stub.requests.count { it.optString("cmd")=="camera" && it.optString("action")!="list" }>before }
            assertEquals(1,stub.requests.count { it.optString("cmd")=="session" && it.optString("action").isEmpty() })
            parent.pause().stop().destroy()
        }
    }

    private fun allTexts(view:View):List<String> = (if(view is TextView)listOf(view.text.toString()) else emptyList())+
        if(view is android.view.ViewGroup)(0 until view.childCount).flatMap { allTexts(view.getChildAt(it)) } else emptyList()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(10,java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Asynchronous UI action did not finish")
    }

    private class StubAgent : Closeable {
        val server = ServerSocket(0)
        val client = AgentClient("127.0.0.1", server.localPort)
        val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        @Volatile var recordingActive=false
        private val jpeg=java.io.ByteArrayOutputStream().also { out->
            Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG,80,out)
        }.toByteArray()
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = input.readLine() ?: break
                        val request = JSONObject(line)
                        requests.add(request)
                        val result = JSONObject().put("ok", true).put("id", request.getInt("id"))
                        when (request.optString("cmd")) {
                            "capabilities" -> result.put("features", JSONArray(listOf("input","screen","camera","recordings")))
                            "camera" -> if(request.optString("action")=="list")result.put("devices",JSONArray().put(JSONObject().put("id",0).put("name","Test camera"))) else result.put("data",android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP))
                            "screen" -> result.put("data",android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP))
                            "recordings" -> result.put("recordings",JSONArray().apply { if(recordingActive)put(JSONObject().put("mode","camera").put("status","recording")) })
                            "session" -> result.put("sid", "approved-test-session").put("active", true)
                            "storage" -> result.put("home", "C:\\Users\\Public").put("drives", JSONArray()
                                .put(JSONObject().put("path", "C:\\").put("total", 256L * 1024 * 1024 * 1024)
                                    .put("free", 120L * 1024 * 1024 * 1024).put("percent", 53)))
                            "ls" -> result.put("path", request.optString("path", "C:\\Users\\Public")).put("items", JSONArray()
                                .put(JSONObject().put("name", "旅行照片.jpg").put("dir", false).put("size", 3456789).put("mtime", 1792700000))
                                .put(JSONObject().put("name", "Documents").put("dir", true).put("mtime", 1792700000))
                                .put(JSONObject().put("name", "会议纪要.txt").put("dir", false).put("size", 1245).put("mtime", 1792700000)))
                        }
                        output.write(result.toString() + "\n"); output.flush()
                    }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }

        fun install() {
            runBlocking { withContext(Dispatchers.IO) { client.connect() } }
            ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(ConnectionManager, client)
        }
        override fun close() { client.close(); server.close(); worker.join(2000) }
    }
}

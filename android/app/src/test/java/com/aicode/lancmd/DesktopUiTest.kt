package com.aicode.lancmd

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesktopUiTest {
    @Before fun prepare() {
        stopConnection()
        ConnectionManager::class.java.getDeclaredField("appCtx").apply { isAccessible = true }
            .set(ConnectionManager, RuntimeEnvironment.getApplication())
    }

    @After fun cleanup() { stopConnection() }

    private fun stopConnection() {
        ConnectionManager.stop()
        val field = ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible = true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(timeout: Long = 8000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeout * 1_000_000
        while (System.nanoTime() < end) {
            // Advance coroutine delays as well as immediate callbacks. Robolectric
            // pauses the main clock, unlike a device running the preview loop.
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Desktop action did not finish")
    }

    private fun render(activity: DesktopActivity, name: String, width: Int = 411, height: Int = 891) {
        val view = activity.findViewById<View>(android.R.id.content)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/ui-previews/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 3000)
        bitmap.recycle()
    }

    @Test fun pageStartsIdleAndRendersEveryModeAndFullscreen() {
        val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.findViewById<MaterialButton>(R.id.desktopStart).isEnabled)
        assertFalse(activity.findViewById<MaterialButton>(R.id.desktopStop).isEnabled)
        assertFalse(activity.findViewById<RemoteScreenView>(R.id.desktopScreen).touchEnabled)
        listOf(R.id.desktopTouchTab, R.id.desktopKeyboardTab, R.id.desktopTerminalTab).forEachIndexed { index, id ->
            activity.findViewById<View>(id).performClick()
            render(activity, "desktop-idle-$index")
        }
        activity.findViewById<View>(R.id.desktopFullscreen).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.desktopStart).parent.let { it as View }.visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, (activity.findViewById<View>(R.id.desktopStart).parent as View).visibility)
        controller.pause().stop().destroy()
    }

    @Test fun visibleImageMapsGesturesAndCancelsHeldDrag() {
        val app = RuntimeEnvironment.getApplication()
        val view = RemoteScreenView(app)
        view.layout(0, 0, 400, 800)
        view.showFrame(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888))
        view.touchEnabled = true
        val events = ArrayList<JSONObject>()
        view.onPointer = { events.add(it) }
        val base = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float, time: Long) {
            MotionEvent.obtain(base, base + time, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 200f, 100f, 0)
        touch(MotionEvent.ACTION_UP, 200f, 100f, 20)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertTrue("Letterbox taps must be ignored", events.isEmpty())
        touch(MotionEvent.ACTION_DOWN, 200f, 400f, 500)
        touch(MotionEvent.ACTION_UP, 200f, 400f, 520)
        shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS)
        assertEquals("click", events.single().getString("action"))
        assertEquals(0.5, events.single().getDouble("x"), 0.0001)
        assertEquals(0.5, events.single().getDouble("y"), 0.0001)
        events.clear()
        touch(MotionEvent.ACTION_DOWN, 100f, 400f, 2000)
        touch(MotionEvent.ACTION_MOVE, 200f, 440f, 2050)
        touch(MotionEvent.ACTION_CANCEL, 200f, 440f, 2080)
        assertEquals("down", events.first().getString("action"))
        assertEquals("up", events.last().getString("action"))
        assertEquals(1, events.count { it.optString("action") == "up" })
    }

    @Test fun twoFingerScrollDoesNotGenerateClicksOrLeaveDrag() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 800)
        view.showFrame(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)); view.touchEnabled = true
        val events = ArrayList<JSONObject>(); view.onPointer = { events.add(it) }
        val base = SystemClock.uptimeMillis()
        fun touch(action: Int, count: Int, y: Float, elapsed: Long) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply { x = 100f + index * 100; this.y = y; pressure = 1f; size = 1f } }
            MotionEvent.obtain(base, base + elapsed, action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                .also { view.onTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 1, 400f, 0)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 400f, 20)
        touch(MotionEvent.ACTION_MOVE, 2, 304f, 50)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, 304f, 70)
        touch(MotionEvent.ACTION_UP, 1, 304f, 90)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(listOf("scroll"), events.map { it.getString("action") })
        assertEquals(-2, events.single().getInt("delta"))
    }

    @Test fun doubleTapAndLongPressProduceDistinctMouseButtons() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 800)
        view.showFrame(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)); view.touchEnabled = true
        val events = ArrayList<JSONObject>(); view.onPointer = { events.add(it) }
        fun tap(action: Int, down: Long) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 200f, 400f, 0)
                .also { view.onTouchEvent(it); it.recycle() }
        }
        var down = SystemClock.uptimeMillis()
        tap(MotionEvent.ACTION_DOWN, down)
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        tap(MotionEvent.ACTION_UP, down)
        shadowOf(Looper.getMainLooper()).idleFor(80, TimeUnit.MILLISECONDS)
        down = SystemClock.uptimeMillis()
        tap(MotionEvent.ACTION_DOWN, down)
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
        tap(MotionEvent.ACTION_UP, down)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(listOf("double_click"), events.map { it.getString("action") })
        events.clear()
        down = SystemClock.uptimeMillis()
        tap(MotionEvent.ACTION_DOWN, down)
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        tap(MotionEvent.ACTION_UP, down)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals("click", events.single().getString("action"))
        assertEquals("right", events.single().getString("button"))
    }

    @Test fun slowTerminalDoesNotBlockScreenOrTouchAndStopClosesBothLeases() {
        Stub().use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<View>(R.id.desktopStart).performClick()
            val screen = activity.findViewById<RemoteScreenView>(R.id.desktopScreen)
            waitUntil { screen.drawable != null }
            render(activity, "desktop-touch")
            activity.findViewById<View>(R.id.desktopKeyboardTab).performClick()
            activity.findViewById<EditText>(R.id.desktopKeyboardText).setText("你好 LanCmd")
            activity.findViewById<View>(R.id.desktopKeyboardSend).performClick()
            waitUntil { stub.requests.any { it.second.optString("cmd") == "input" && it.second.optString("text") == "你好 LanCmd" } }
            render(activity, "desktop-keyboard")
            activity.findViewById<View>(R.id.desktopTerminalTab).performClick()
            activity.findViewById<EditText>(R.id.desktopCommand).setText("slow")
            activity.findViewById<View>(R.id.desktopExecute).performClick()
            waitUntil { stub.requests.any { it.second.optString("cmd") == "exec" } }
            val before = stub.requests.count { it.second.optString("cmd") == "screen" }
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                MotionEvent.obtain(now, now + index * 30, action, screen.width / 2f, screen.height / 2f, 0)
                    .also { screen.onTouchEvent(it); it.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            waitUntil { stub.requests.any { it.second.optString("cmd") == "pointer" } }
            waitUntil { stub.requests.count { it.second.optString("cmd") == "screen" } > before }
            assertFalse(activity.findViewById<View>(R.id.desktopExecute).isEnabled)
            val connections = listOf("screen", "pointer", "exec").map { command -> stub.requests.first { it.second.optString("cmd") == command }.first }
            assertEquals("Three independent connections are required", 3, connections.toSet().size)
            stub.commandGate.countDown()
            waitUntil { activity.findViewById<TextView>(R.id.desktopTerminalLog).text.contains("terminal-output") }
            render(activity, "desktop-terminal")
            activity.findViewById<View>(R.id.desktopFullscreen).performClick()
            render(activity, "desktop-fullscreen-landscape", 891, 411)
            activity.onBackPressedDispatcher.onBackPressed()
            activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
            activity.findViewById<View>(R.id.desktopKeyboardTab).performClick()
            render(activity, "desktop-keyboard-landscape", 891, 411)
            activity.findViewById<View>(R.id.desktopStop).performClick()
            waitUntil { activity.findViewById<View>(R.id.desktopStart).isEnabled }
            assertFalse(screen.touchEnabled); assertNull(screen.drawable)
            assertEquals(2, stub.requests.count { it.second.optString("cmd") == "session" && it.second.optString("action") == "end" })
            controller.pause().stop().destroy()
        }
    }

    @Test fun oldReceiverShowsUpgradeMessageWithoutOpeningControlSessions() {
        Stub(pointerSupported = false).use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<View>(R.id.desktopStart).performClick()
            waitUntil { activity.findViewById<TextView>(R.id.desktopStatus).text.contains("1.6.0") }
            assertFalse(stub.requests.any { it.second.optString("cmd") == "session" })
            assertTrue(activity.findViewById<View>(R.id.desktopStart).isEnabled)
            controller.pause().stop().destroy()
        }
    }

    @Test fun switchingComputerClosesOriginalSessionsWithoutSendingToNewTarget() {
        Stub().use { original ->
            original.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<View>(R.id.desktopStart).performClick()
            waitUntil { activity.findViewById<RemoteScreenView>(R.id.desktopScreen).drawable != null }
            Stub().use { replacement ->
                replacement.install()
                waitUntil { activity.findViewById<View>(R.id.desktopStart).isEnabled }
                assertEquals(2, original.requests.count { it.second.optString("action") == "end" })
                assertTrue(replacement.requests.isEmpty())
                assertFalse(activity.findViewById<RemoteScreenView>(R.id.desktopScreen).touchEnabled)
            }
            controller.pause().stop().destroy()
        }
    }

    @Test fun unlimitedLocalPauseIsExplicitAndStopReleasesOwningSession() {
        Stub(localSupported = true).use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<View>(R.id.desktopStart).performClick()
            val pause = activity.findViewById<MaterialButton>(R.id.desktopLocalInput)
            waitUntil { pause.isEnabled }
            pause.performClick()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertFalse(stub.requests.any { it.second.optString("action") == "pause" })
            assertEquals("开始暂停（手动解除）", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals(View.GONE, confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil { pause.text == "恢复本机键鼠" }
            val request = stub.requests.single { it.second.optString("action") == "pause" }.second
            assertEquals(0, request.getInt("seconds"))
            assertTrue(request.getString("sid").startsWith("session-"))
            render(activity, "desktop-local-pause")
            activity.findViewById<View>(R.id.desktopStop).performClick()
            waitUntil { activity.findViewById<View>(R.id.desktopStart).isEnabled }
            assertFalse(pause.isEnabled)
            assertEquals(2, stub.requests.count { it.second.optString("action") == "end" })
            controller.pause().stop().destroy()
        }
    }

    private class Stub(private val pointerSupported: Boolean = true, private val localSupported: Boolean = false) : Closeable {
        private val server = ServerSocket(0)
        private val clients = CopyOnWriteArrayList<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        val requests = CopyOnWriteArrayList<Pair<Int, JSONObject>>()
        val commandGate = CountDownLatch(1)
        @Volatile private var paused = false
        private val base = AgentClient("127.0.0.1", server.localPort)
        private val frame = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff23445e.toInt()) }
                .compress(Bitmap.CompressFormat.JPEG, 90, output)
        }.toByteArray()
        private val accept = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept(); clients.add(socket)
                    val connection = clients.size
                    workers.add(Thread {
                        try {
                            socket.use {
                                val reader = socket.getInputStream().bufferedReader()
                                val writer = socket.getOutputStream().bufferedWriter()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    val request = JSONObject(line); requests.add(connection to request)
                                    val result = JSONObject().put("ok", true).put("id", request.getInt("id"))
                                    when (request.optString("cmd")) {
                                        "hello" -> result.put("agent_id", "desktop-test").put("token", "test-token")
                                        "capabilities" -> result.put("features", JSONArray(listOf("screen", "input") + (if (pointerSupported) listOf("pointer") else emptyList()) + (if (localSupported) listOf("local_input") else emptyList())))
                                        "local_input" -> {
                                            if (request.optString("action") == "pause") paused = true
                                            if (request.optString("action") == "release") paused = false
                                            result.put("paused", paused).put("remaining", if (paused) JSONObject.NULL else 0)
                                        }
                                        "session" -> result.put("sid", "session-$connection").put("stopped", true)
                                        "screen" -> result.put("data", android.util.Base64.encodeToString(frame, android.util.Base64.NO_WRAP))
                                        "exec" -> { commandGate.await(7, TimeUnit.SECONDS); result.put("out", "terminal-output").put("code", 0) }
                                    }
                                    writer.write(result.toString() + "\n"); writer.flush()
                                }
                            }
                        } catch (_: IOException) {}
                    }.apply { isDaemon = true; start() })
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }

        fun install() {
            val app = RuntimeEnvironment.getApplication()
            Prefs.saveTarget(app, Target("127.0.0.1", server.localPort, "测试电脑", "desktop-test"))
            runBlocking { withContext(Dispatchers.IO) { base.connect() } }
            ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(ConnectionManager, base)
        }

        override fun close() {
            commandGate.countDown(); base.close(); clients.forEach { it.close() }; server.close()
            accept.join(2000); workers.forEach { it.join(2000) }
        }
    }
}

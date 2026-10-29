package com.aicode.lancmd

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
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
        // 页面是 Compose 的：状态改完要先让重组跑完，再测量和绘制。
        shadowOf(Looper.getMainLooper()).idle()
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
        // 远程桌面页已迁移到 Compose（miuix）：画面与虚拟键盘仍是 View，其余按状态断言。
        val activity = controller.get()
        assertFalse("打开页面不应开始共享", activity.sharing)
        assertFalse(activity.findViewById<RemoteScreenView>(R.id.desktopScreen).touchEnabled)
        for (index in 0..2) {
            activity.mode = index
            render(activity, "desktop-idle-$index")
            assertEquals(PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL), activity.findViewById<RemoteScreenView>(R.id.desktopScreen).pointerIcon)
        }
        activity.showFullscreen(true)
        render(activity, "desktop-fullscreen-portrait")
        assertTrue(activity.fullscreen)
        assertEquals(PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL), activity.findViewById<RemoteScreenView>(R.id.desktopScreen).pointerIcon)
        assertTrue("Fullscreen image must use the full body height", activity.findViewById<RemoteScreenView>(R.id.desktopScreen).height > 750)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertFalse(activity.fullscreen)
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

    @Test fun renderedFramesKeepAspectRatioIndependentOfBitmapDensity() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN); density = 480
        }
        view.showFrame(source)
        for ((width, height) in listOf(400 to 400, 600 to 200)) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            assertEquals(android.graphics.Color.GREEN, output.getPixel(width / 2, height / 2))
            val letterbox = if (width == 400) output.getPixel(200, 40) else output.getPixel(40, 100)
            assertEquals("Frame must be letterboxed instead of stretched", 0xff05080c.toInt(), letterbox)
            output.recycle()
        }
        view.clearFrame(); source.recycle()
    }

    @Test fun remoteSurfaceSuppressesAndroidMouseAndStylusPointersInEveryInputState() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 400)
        view.showFrame(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888))
        for (source in listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_STYLUS, InputDevice.SOURCE_TOUCHSCREEN)) {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, 200f, 200f, 0)
            event.source = source
            try {
                for (enabled in listOf(false, true)) {
                    view.touchEnabled = enabled
                    assertEquals(PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_NULL), view.onResolvePointerIcon(event, 0))
                    assertEquals(PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_NULL), view.pointerIcon)
                }
                view.clearFrame()
                assertEquals("An empty preview must not restore Android's arrow", PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_NULL),
                    view.onResolvePointerIcon(event, 0))
            } finally { event.recycle() }
        }
    }

    @Test fun hidingSystemPointerPreservesImmediatePhoneTouchCircle() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 400)
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        view.showFrame(source); view.touchEnabled = true
        val events = ArrayList<JSONObject>()
        view.onPointer = { events.add(it) }
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 200f, 200f, 0).also {
            view.onTouchEvent(it); it.recycle()
        }
        val output = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        assertTrue("Finger down must show the ring before a confirmed tap sends a click", events.isEmpty())
        val ring = output.getPixel(208, 200)
        assertTrue("The phone's green touch ring must remain visible",
            android.graphics.Color.green(ring) > android.graphics.Color.red(ring) + 50)
        assertEquals(android.graphics.Color.WHITE, output.getPixel(200, 200))
        assertEquals(android.graphics.Color.WHITE, output.getPixel(220, 200))
        view.clearFrame(); source.recycle(); output.recycle()
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

    @Test fun nativeSurfaceForwardsContactLifetimesAndCancellationWithoutMouseEvents() {
        val view = RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0,0,400,400)
        view.showFrame(Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888))
        view.nativeTouchEnabled=true;view.touchEnabled=true
        val frames=ArrayList<JSONObject>();val mouse=ArrayList<JSONObject>()
        view.onTouchFrame={frames.add(it)};view.onPointer={mouse.add(it)}
        val base=SystemClock.uptimeMillis()
        fun touch(action:Int,count:Int) {
            val properties=Array(count){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(count){i->MotionEvent.PointerCoords().apply{x=100f+i*100;y=200f;pressure=1f;size=1f}}
            MotionEvent.obtain(base,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                .also{view.onTouchEvent(it);it.recycle()}
        }
        touch(MotionEvent.ACTION_DOWN,1)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),2)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl 8),2)
        view.touchEnabled=false
        assertEquals(listOf("down"),frames[0].getJSONArray("contacts").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("state")}})
        assertEquals(listOf("move","down"),frames[1].getJSONArray("contacts").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("state")}})
        assertEquals(listOf("move","up"),frames[2].getJSONArray("contacts").let{a->(0 until a.length()).map{a.getJSONObject(it).getString("state")}})
        assertEquals("cancel",frames.last().getString("action"))
        shadowOf(Looper.getMainLooper()).idleFor(1000,TimeUnit.MILLISECONDS)
        assertTrue("Native touch must never emit delayed mouse clicks",mouse.isEmpty())
    }

    @Test fun nativeDoubleTapLongHoldAndDragKeepSeparateContactLifetimes() {
        val view=RemoteScreenView(RuntimeEnvironment.getApplication())
        view.layout(0,0,400,400);view.showFrame(Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888))
        view.nativeTouchEnabled=true;view.touchEnabled=true
        val frames=ArrayList<JSONObject>();val mouse=ArrayList<JSONObject>()
        view.onTouchFrame={frames.add(it)};view.onPointer={mouse.add(it)}
        fun event(action:Int,x:Float=200f) {
            val now=SystemClock.uptimeMillis()
            MotionEvent.obtain(now,now,action,x,200f,0).also{view.onTouchEvent(it);it.recycle()}
        }
        repeat(2){event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_UP)}
        event(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
        event(MotionEvent.ACTION_MOVE,260f);event(MotionEvent.ACTION_UP,260f)
        val states=frames.map{it.getJSONArray("contacts").getJSONObject(0).getString("state")}
        assertEquals(listOf("down","up","down","up","down","move","up"),states)
        assertTrue("The native path must not synthesize mouse double_click or right-click",mouse.isEmpty())
        view.clearFrame()
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
            activity.begin()
            val screen = activity.findViewById<RemoteScreenView>(R.id.desktopScreen)
            waitUntil { screen.drawable != null }
            render(activity, "desktop-touch")
            activity.mode = 1
            activity.keyboardText = "你好 LanCmd"
            activity.sendText()
            waitUntil { stub.requests.any { it.second.optString("cmd") == "input" && it.second.optString("text") == "你好 LanCmd" } }
            render(activity, "desktop-keyboard")
            activity.toggleVirtualKeyboard()
            assertTrue(activity.virtualKeyboardExpanded)
            shadowOf(Looper.getMainLooper()).idle()
            render(activity, "desktop-keyboard-expanded")
            val virtual = activity.findViewById<VirtualKeyboard>(R.id.desktopVirtualKeyboard)
            assertTrue("Expanded keyboard must be laid out", virtual.height > 0)
            activity.toggleVirtualKeyboard()
            assertFalse(activity.virtualKeyboardExpanded)
            activity.mode = 2
            activity.commandLine = "slow"
            activity.execute()
            waitUntil { stub.requests.any { it.second.optString("cmd") == "exec" } }
            val before = stub.requests.count { it.second.optString("cmd") == "screen" }
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                MotionEvent.obtain(now, now + index * 30, action, screen.width / 2f, screen.height / 2f, 0)
                    .also { screen.onTouchEvent(it); it.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            waitUntil { stub.requests.any { it.second.optString("cmd") == "touch" && it.second.optString("action") == "frame" } }
            waitUntil { stub.requests.count { it.second.optString("cmd") == "screen" } > before }
            assertTrue("命令执行中不应允许再次执行", activity.commandBusy)
            val connections = listOf("screen", "touch", "exec").map { command -> stub.requests.first { it.second.optString("cmd") == command }.first }
            assertEquals("Three independent connections are required", 3, connections.toSet().size)
            stub.commandGate.countDown()
            waitUntil { activity.terminalText.contains("terminal-output") }
            render(activity, "desktop-terminal")
            activity.showFullscreen(true)
            render(activity, "desktop-fullscreen-landscape", 891, 411)
            activity.onBackPressedDispatcher.onBackPressed()
            activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
            activity.mode = 1
            render(activity, "desktop-keyboard-landscape", 891, 411)
            activity.stopSession()
            waitUntil { !activity.sharing }
            assertFalse(screen.touchEnabled); assertNull(screen.drawable)
            assertEquals(2, stub.requests.count { it.second.optString("cmd") == "session" && it.second.optString("action") == "end" })
            controller.pause().stop().destroy()
        }
    }

    @Test fun nativeTouchDoesNotAlterCursorSchemesAcrossModesAndInternalChildren() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(DesktopActivity::class.java).setup();val page=controller.get()
            try {
                page.begin();waitUntil{page.sharing && stub.requests.any{it.second.optString("cmd")=="touch"}}
                for(index in 0..2)page.mode=index
                controller.pause().stop()
                controller.start().resume().visible()
                waitUntil{stub.requests.count{it.second.optString("cmd")=="screen"}>=2}
                assertFalse(stub.requests.any{it.second.optString("cmd")=="cursor"})
                page.stopSession();waitUntil{!page.sharing}
                assertTrue(stub.requests.any{it.second.optString("cmd")=="session" && it.second.optString("action")=="end"})
                assertFalse(stub.requests.any{it.second.optString("cmd")=="cursor"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun missingOfficialTouchSupportCannotFallBackToMouseControl() {
        Stub(nativeTouchSupported=false).use {stub ->
            stub.install();val controller=Robolectric.buildActivity(DesktopActivity::class.java).setup();val page=controller.get()
            try {
                page.begin();waitUntil{!page.sharing && page.note.contains("v3.6.0")}
                assertFalse(stub.requests.any{it.second.optString("cmd")=="session"})
                assertFalse(stub.requests.any{it.second.optString("cmd")=="pointer"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun touchInitializationFailureDoesNotEnableTheSurfaceOrModifyTheCursor() {
        Stub(touchReady=false).use {stub ->
            stub.install();val controller=Robolectric.buildActivity(DesktopActivity::class.java).setup();val page=controller.get()
            try {
                page.begin();waitUntil{!page.sharing && page.note.contains("官方触控接口未就绪")}
                assertFalse(page.findViewById<RemoteScreenView>(R.id.desktopScreen).touchEnabled)
                assertFalse(stub.requests.any{it.second.optString("cmd") in listOf("pointer","cursor")})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun oldReceiverShowsUpgradeMessageWithoutOpeningControlSessions() {
        Stub(pointerSupported = false).use { stub ->
            stub.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.begin()
            waitUntil { activity.note.contains("1.6.0") }
            assertFalse(stub.requests.any { it.second.optString("cmd") == "session" })
            assertFalse(activity.sharing)
            controller.pause().stop().destroy()
        }
    }

    @Test fun switchingComputerClosesOriginalSessionsWithoutSendingToNewTarget() {
        Stub().use { original ->
            original.install()
            val controller = Robolectric.buildActivity(DesktopActivity::class.java).setup()
            val activity = controller.get()
            activity.begin()
            waitUntil { activity.findViewById<RemoteScreenView>(R.id.desktopScreen).drawable != null }
            Stub().use { replacement ->
                replacement.install()
                waitUntil { !activity.sharing }
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
            activity.begin()
            waitUntil { activity.localPauseEnabled }
            activity.changeLocalInput()
            shadowOf(Looper.getMainLooper()).idle()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertFalse(stub.requests.any { it.second.optString("action") == "pause" })
            assertEquals("开始暂停（手动解除）", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals(View.GONE, confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).visibility)
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil { activity.localPauseLabel == "恢复本机输入" }
            val request = stub.requests.single { it.second.optString("action") == "pause" }.second
            assertEquals(0, request.getInt("seconds"))
            assertTrue(request.getString("sid").startsWith("session-"))
            render(activity, "desktop-local-pause")
            activity.stopSession()
            waitUntil { !activity.sharing }
            assertFalse(activity.localPauseEnabled)
            assertEquals(2, stub.requests.count { it.second.optString("action") == "end" })
            controller.pause().stop().destroy()
        }
    }

    @Test fun internalChildNavigationKeepsDesktopLeasesAndResumesFramesWithoutRestarting() {
        Stub().use { stub->
            stub.install()
            val parent=Robolectric.buildActivity(DesktopActivity::class.java).setup();val activity=parent.get()
            activity.begin()
            waitUntil { activity.findViewById<RemoteScreenView>(R.id.desktopScreen).drawable!=null }
            val child=Robolectric.buildActivity(android.app.Activity::class.java).setup()
            parent.pause().stop()
            shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
            assertFalse(stub.requests.any { it.second.optString("action")=="end" })
            val before=stub.requests.count { it.second.optString("cmd")=="screen" }
            child.get().finish();child.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
            assertFalse("A slow return handoff must not end desktop leases",stub.requests.any { it.second.optString("action")=="end" })
            parent.restart().start().resume()
            waitUntil { stub.requests.count { it.second.optString("cmd")=="screen" }>before }
            assertEquals(2,stub.requests.count { it.second.optString("cmd")=="session" && it.second.optString("action").isEmpty() })
            assertTrue(activity.sharing)
            parent.pause().stop().destroy()
        }
    }

    @Test fun interruptedChildReturnStillEndsSharingAfterBoundedGrace() {
        Stub().use { stub->
            stub.install()
            val parent=Robolectric.buildActivity(DesktopActivity::class.java).setup()
            parent.get().begin()
            waitUntil { parent.get().findViewById<RemoteScreenView>(R.id.desktopScreen).drawable!=null }
            val child=Robolectric.buildActivity(android.app.Activity::class.java).setup()
            parent.pause().stop()
            child.get().finish();child.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(5500,TimeUnit.MILLISECONDS)
            waitUntil { stub.requests.count { it.second.optString("action")=="end" }==2 }
            parent.destroy()
        }
    }

    @Test fun leavingApplicationEndsSharingAndReleasesInputAfterHandoffGrace() {
        Stub().use { stub->
            stub.install();val controller=Robolectric.buildActivity(DesktopActivity::class.java).setup()
            controller.get().begin()
            waitUntil { controller.get().findViewById<RemoteScreenView>(R.id.desktopScreen).drawable!=null }
            controller.pause().stop();shadowOf(Looper.getMainLooper()).idleFor(1000,TimeUnit.MILLISECONDS)
            waitUntil { stub.requests.count { it.second.optString("action")=="end" }==2 && !controller.get().sharing }
            assertFalse(controller.get().sharing)
            controller.destroy()
        }
    }

    private class Stub(private val pointerSupported: Boolean = true, private val localSupported: Boolean = false, private val nativeTouchSupported:Boolean=true, private val touchReady:Boolean=true) : Closeable {
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
                                        "capabilities" -> result.put("features", JSONArray(listOf("screen", "input") + (if(nativeTouchSupported)listOf("native_touch")else emptyList()) + (if (pointerSupported) listOf("pointer") else emptyList()) + (if (localSupported) listOf("local_input", "touch_pause") else emptyList())))
                                        "touch" -> result.put("api",if(touchReady)"InjectTouchInput" else "unavailable").put("max_contacts",10).put("active",0)
                                        "local_input" -> {
                                            if (request.optString("action") == "pause") paused = true
                                            if (request.optString("action") == "release") paused = false
                                            result.put("paused", paused).put("remaining", if (paused) JSONObject.NULL else 0).put("touch_devices", if (paused) 1 else 0)
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

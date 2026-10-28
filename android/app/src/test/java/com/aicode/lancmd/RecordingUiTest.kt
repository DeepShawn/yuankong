package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingUiTest {
    private fun board(): GestureBoard {
        val activity = Robolectric.buildActivity(GesturesActivity::class.java).setup().get()
        return GestureBoard(activity).apply {
            activity.setContentView(this)
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 300)
        }
    }
    private fun touch(view: GestureBoard, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun dragRecordsOriginalTimingAndClampsReleaseOutsideBoard() {
        val board = board(); board.begin()
        touch(board, MotionEvent.ACTION_DOWN, 50f, 70f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(120))
        touch(board, MotionEvent.ACTION_MOVE, 200f, 150f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(180))
        touch(board, MotionEvent.ACTION_UP, 900f, 900f)
        board.finish()
        val events = board.snapshot()
        assertEquals(3, events.length()); assertEquals("down", events.getJSONObject(0).getString("action"))
        assertEquals("move", events.getJSONObject(1).getString("action")); assertEquals("up", events.getJSONObject(2).getString("action"))
        assertTrue(events.getJSONObject(2).getLong("ms") >= 300)
        assertEquals(1.0, events.getJSONObject(2).getDouble("x"), 0.0)
        assertEquals(1.0, events.getJSONObject(2).getDouble("y"), 0.0)
    }
    @Test fun cancelAndFinishBalanceHeldButtonAndMoveModeNeverClicks() {
        val board = board(); board.begin()
        touch(board, MotionEvent.ACTION_DOWN, 80f, 100f)
        touch(board, MotionEvent.ACTION_CANCEL, 80f, 100f)
        board.finish()
        assertEquals("up", board.snapshot().getJSONObject(1).getString("action"))
        board.dragMode = false; board.begin()
        touch(board, MotionEvent.ACTION_DOWN, 80f, 100f); touch(board, MotionEvent.ACTION_UP, 200f, 150f); board.finish()
        assertTrue((0 until board.eventCount).all { board.snapshot().getJSONObject(it).getString("action") == "move" })
        board.load(JSONArray()); assertEquals(0, board.eventCount)
    }
    @Test fun newPagesRenderAndKeepControlsReachable() {
        for ((type, name) in listOf(RecordingsActivity::class.java to "recordings", GesturesActivity::class.java to "gestures")) {
            val controller = Robolectric.buildActivity(type).setup()
            val activity = controller.get()
            val view = activity.window.decorView
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 411, 891)
            val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("页面应渲染出内容", file.length() > 3000)
            if (name == "recordings") {
                assertTrue(findButtons(view).any { it.text.contains("开始录制") })
            } else {
                // 手势页已迁移到 Compose（miuix）：回放入口由页面提供，
                // 没有记录时不应提交回放。
                val gestures = activity as GesturesActivity
                gestures.confirmPlay()
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            }
            controller.pause().stop().destroy()
        }
    }
    private fun findButtons(view: View): List<com.google.android.material.button.MaterialButton> = when (view) {
        is com.google.android.material.button.MaterialButton -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { findButtons(view.getChildAt(it)) }
        else -> emptyList()
    }
}

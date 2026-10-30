package com.aicode.lancmd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** A local screenshot-backed canvas. Recording never sends input to the PC. */
class GestureBoard(context: Context) : View(context) {
    var desktopFrame: Bitmap? = null
        set(value) { field = value; invalidate() }
    var dragMode = true
    var changed: (() -> Unit)? = null
    var aspect = 16f / 9f
        set(value) { field = value.coerceIn(.1f, 10f); invalidate() }
    var recording = false
        private set
    private var started = 0L
    private var events = JSONArray()
    val eventCount get() = events.length()
    private var finger = -1
    private var pressed = false
    private var lastX = .5f
    private var lastY = .5f
    private var lastMs = -20L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    init { contentDescription = "空白手势记录板"; isClickable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }
    fun snapshot() = JSONArray(events.toString())
    fun load(value: JSONArray) {
        finish()
        events = JSONArray(value.toString())
        // A periodic draft can be the last durable snapshot after Android kills
        // the process while a finger is held. Restore a playable, balanced trace.
        var held = false
        for (i in 0 until events.length()) when (events.getJSONObject(i).optString("action")) {
            "down" -> held = true
            "up" -> held = false
        }
        if (held && events.length() < 6000) {
            events.put(JSONObject(events.getJSONObject(events.length() - 1).toString()).put("action", "up"))
        }
        invalidate(); changed?.invoke()
    }
    fun begin() { events = JSONArray(); recording = true; started = SystemClock.elapsedRealtime(); lastMs = -20; finger = -1; pressed = false; invalidate(); changed?.invoke() }
    fun finish() {
        if (!recording && !pressed) return
        if (pressed) append("up", lastX, lastY, (SystemClock.elapsedRealtime() - started).coerceAtMost(300000))
        pressed = false; finger = -1; recording = false; invalidate(); changed?.invoke()
    }
    private fun bounds(): RectF {
        val inset = 8 * resources.displayMetrics.density
        val w = (width - 2 * inset).coerceAtLeast(1f); val h = (height - 2 * inset).coerceAtLeast(1f)
        val bw = minOf(w, h * aspect); val bh = bw / aspect
        area.set((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2)
        return area
    }
    private fun append(action: String, x: Float, y: Float, ms: Long) {
        // Reserve a final up event even if the finger is still held at the cap.
        if (events.length() >= 5999 && action != "up") return
        events.put(JSONObject().put("ms", ms).put("action", action).put("x", x.toDouble()).put("y", y.toDouble()))
        lastX = x; lastY = y; lastMs = ms; invalidate(); changed?.invoke()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!recording || !isEnabled) return false
        val rect = bounds()
        val ms = (SystemClock.elapsedRealtime() - started).coerceIn(0, 300000)
        if (ms >= 300000 || events.length() >= 5999) { finish(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!rect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                finger = event.getPointerId(0)
                val x = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
                val y = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
                append(if (dragMode) "down" else "move", x, y, ms); pressed = dragMode
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(finger)
                if (index < 0) return true
                if (ms - lastMs >= 20) append("move", ((event.getX(index) - rect.left) / rect.width()).coerceIn(0f, 1f),
                    ((event.getY(index) - rect.top) / rect.height()).coerceIn(0f, 1f), ms)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == finger) {
                    val x = ((event.getX(event.actionIndex) - rect.left) / rect.width()).coerceIn(0f, 1f)
                    val y = ((event.getY(event.actionIndex) - rect.top) / rect.height()).coerceIn(0f, 1f)
                    append(if (pressed) "up" else "move", x, y, ms)
                    pressed = false; finger = -1; parent?.requestDisallowInterceptTouchEvent(false); performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> { if (pressed) append("up", lastX, lastY, ms); pressed = false; finger = -1 }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = bounds()
        val unit=resources.displayMetrics.density
        paint.style = Paint.Style.FILL; paint.color = Themes.color(context,R.color.surface_variant); canvas.drawRoundRect(rect, 24*unit, 24*unit, paint)
        paint.color = Themes.color(context,R.color.outline);paint.strokeWidth=unit
        desktopFrame?.let { canvas.drawBitmap(it, null, rect, paint) }
        val spacing=24*unit
        var gx=rect.left+spacing
        while(desktopFrame == null && gx<rect.right-spacing/2) {
            var gy=rect.top+spacing
            while(gy<rect.bottom-spacing/2) {canvas.drawCircle(gx,gy,unit,paint);gy+=spacing}
            gx+=spacing
        }
        paint.color = Themes.color(context, R.color.accent); paint.strokeWidth = 3 * resources.displayMetrics.density; paint.style = Paint.Style.STROKE
        val path = Path(); var connected = false
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i); val x = rect.left + e.getDouble("x").toFloat() * rect.width(); val y = rect.top + e.getDouble("y").toFloat() * rect.height()
            if (!connected || e.getString("action") == "down") path.moveTo(x, y) else path.lineTo(x, y)
            connected = e.getString("action") != "up"
            if (e.getString("action") == "down") canvas.drawCircle(x, y, 5 * resources.displayMetrics.density, paint)
        }
        canvas.drawPath(path, paint)
        if (events.length() == 0 && desktopFrame == null) {
            paint.style = Paint.Style.FILL; paint.color = Themes.color(context,R.color.dim); paint.textAlign = Paint.Align.CENTER; paint.textSize = 14 * resources.displayMetrics.scaledDensity
            canvas.drawText(if (recording) "在此移动、点击或拖动" else "点击「开始记录」后在此操作", rect.centerX(), rect.centerY(), paint)
        }
    }
}

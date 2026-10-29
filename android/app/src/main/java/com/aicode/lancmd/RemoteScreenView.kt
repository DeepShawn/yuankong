package com.aicode.lancmd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PointerIcon
import androidx.appcompat.widget.AppCompatImageView
import org.json.JSONObject
import org.json.JSONArray

/** Forwards native touch lifetimes; mouse gestures remain an explicit compatibility path. */
class RemoteScreenView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    // Android's mouse/stylus pointer is independent of the Windows cursor and
    // our painted touch circle. Resolve it here, including after Compose moves
    // this View between the normal workspace and fullscreen.
    private val hiddenPointer = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    var onPointer: ((JSONObject) -> Unit)? = null
    var onTouchFrame: ((JSONObject) -> Unit)? = null
    var nativeTouchEnabled = false
        set(value) { if (field != value) cancelGesture(); field = value }
    private val nativePoints = LinkedHashMap<Int, RemotePoint>()
    private var nativeSequence = false
    private var lastNativeMove = 0L
    var touchEnabled = false
        set(value) { if (!value) cancelGesture(); field = value }
    private var imageWidth = 0
    private var imageHeight = 0
    private var frameBitmap: Bitmap? = null
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var accepted = false
    private var dragging = false
    private var multiple = false
    private var startPoint = RemotePoint(0.5f, 0.5f)
    private var lastPoint = startPoint
    private var cursor: RemotePoint? = null
    private var lastMove = 0L
    private var scrollY = 0f
    private var scrollRemainder = 0f
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff35d0a5.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            point(e.x, e.y)?.let { emit("click", it); performClick() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            point(e.x, e.y)?.let { emit("double_click", it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!dragging && !multiple) point(e.x, e.y)?.let { emit("click", it, "right") }
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (multiple) return true
            if (!dragging) { emit("down", startPoint); dragging = true }
            val point = point(e2.x, e2.y, true) ?: return true
            lastPoint = point
            val now = SystemClock.uptimeMillis()
            if (now - lastMove >= 40) { emit("move", point); lastMove = now }
            return true
        }
    })

    init {
        pointerIcon = hiddenPointer
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(0xff05080c.toInt())
        contentDescription = "电脑屏幕；轻点单击、双击、长按右键、单指拖动、双指滚动"
        isClickable = true
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon = hiddenPointer

    fun showFrame(bitmap: Bitmap) {
        if (imageWidth != bitmap.width || imageHeight != bitmap.height) cancelGesture()
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        bitmap.density = Bitmap.DENSITY_NONE
        frameBitmap = bitmap
        setImageBitmap(bitmap)
    }

    fun clearFrame() {
        cancelGesture()
        imageWidth = 0; imageHeight = 0; cursor = null
        frameBitmap = null
        setImageDrawable(null)
    }

    private fun point(x: Float, y: Float, clamp: Boolean = false) = RemoteTouch.point(
        x - paddingLeft, y - paddingTop, width - paddingLeft - paddingRight,
        height - paddingTop - paddingBottom, imageWidth, imageHeight, clamp)

    private fun emit(action: String, point: RemotePoint, button: String = "left", delta: Int = 0) {
        if (!touchEnabled && action != "up") return
        lastPoint = point
        cursor = point
        invalidate()
        onPointer?.invoke(JSONObject().put("action", action).put("x", point.x.toDouble())
            .put("y", point.y.toDouble()).put("button", button).apply { if (delta != 0) put("delta", delta) })
    }

    fun cancelGesture() {
        if (nativePoints.isNotEmpty()) {
            nativePoints.clear()
            onTouchFrame?.invoke(JSONObject().put("action", "cancel"))
        }
        nativeSequence = false
        val release = dragging
        dragging = false; accepted = false; multiple = false
        if (release) emit("up", lastPoint)
        val cancel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel)
        cancel.recycle()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0) cancelGesture()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || !isEnabled || imageWidth == 0) return false
        if (nativeTouchEnabled) return nativeTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startPoint = point(event.x, event.y) ?: return false
            lastPoint = startPoint; accepted = true; multiple = false; scrollRemainder = 0f
            cursor = startPoint
            invalidate()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!accepted) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (dragging) { emit("up", lastPoint); dragging = false }
            multiple = true
            scrollY = (event.getY(0) + event.getY(1)) / 2
        }
        if (multiple) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount >= 2) {
                val y = (event.getY(0) + event.getY(1)) / 2
                scrollRemainder += (y - scrollY) / (48 * resources.displayMetrics.density)
                scrollY = y
                val steps = scrollRemainder.toInt().coerceIn(-10, 10)
                if (steps != 0) { emit("scroll", lastPoint, delta = steps); scrollRemainder -= steps }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancelGesture()
            else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
            }
            return true
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (dragging) {
                val end = point(event.x, event.y, true) ?: lastPoint
                emit("move", end); emit("up", end); dragging = false
            }
            accepted = false
            parent?.requestDisallowInterceptTouchEvent(false)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelGesture()
        return true
    }

    private fun nativeTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val first = point(event.x, event.y) ?: return false
            cancelGesture()
            nativeSequence = true
            nativePoints[event.getPointerId(0)] = first
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!nativeSequence) return false
        if (action == MotionEvent.ACTION_CANCEL) { cancelGesture(); return true }
        if (action == MotionEvent.ACTION_POINTER_DOWN && nativePoints.size < 10) {
            val index = event.actionIndex
            point(event.getX(index), event.getY(index))?.let { nativePoints[event.getPointerId(index)] = it }
        }
        if (action !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP)) return true
        val frame = JSONArray()
        val released = ArrayList<Int>()
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            if (id !in nativePoints) continue
            val position = point(event.getX(index), event.getY(index), true) ?: nativePoints.getValue(id)
            nativePoints[id] = position
            cursor = position
            val changed = index == event.actionIndex
            val state = when {
                changed && action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN) -> "down"
                changed && action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP) -> "up"
                else -> "move"
            }
            if (state == "up") released.add(id)
            frame.put(JSONObject().put("id", id).put("state", state)
                .put("x", position.x.toDouble()).put("y", position.y.toDouble()))
        }
        invalidate()
        val now = SystemClock.uptimeMillis()
        if (frame.length() > 0 && (action != MotionEvent.ACTION_MOVE || now - lastNativeMove >= 20)) {
            onTouchFrame?.invoke(JSONObject().put("action", "frame").put("contacts", frame))
            lastNativeMove = now
        }
        released.forEach { nativePoints.remove(it) }
        if (action == MotionEvent.ACTION_UP) {
            nativeSequence = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        val bitmap = frameBitmap ?: return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val frame = RemoteTouch.frame(w, h, imageWidth, imageHeight) ?: return
        val rect = RectF(paddingLeft + frame.left, paddingTop + frame.top,
            paddingLeft + frame.left + frame.width, paddingTop + frame.top + frame.height)
        canvas.drawBitmap(bitmap, null, rect, framePaint)
        val point = cursor ?: return
        val x = rect.left + point.x * rect.width()
        val y = rect.top + point.y * rect.height()
        canvas.drawCircle(x, y, 9 * resources.displayMetrics.density, cursorPaint)
    }
}

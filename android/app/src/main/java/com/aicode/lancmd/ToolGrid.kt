package com.aicode.lancmd

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Tool destinations keep their identities while adapting to available width and font size. */
class ToolGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var columns = 2
    private var cellHeight = 0
    private val gap get() = Ui.dp(context, 8)
    private fun items() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val minimum = Ui.dp(context, (150 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
        columns = ((available + gap) / (minimum + gap)).coerceIn(1, 4)
        val cellWidth = ((available - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val visible = items()
        cellHeight = 0
        visible.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            cellHeight = maxOf(cellHeight, it.measuredHeight)
        }
        val rows = (visible.size + columns - 1) / columns
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(paddingTop + paddingBottom + rows * cellHeight + (rows - 1).coerceAtLeast(0) * gap, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = ((width - paddingLeft - paddingRight - gap * (columns - 1)) / columns).coerceAtLeast(0)
        items().forEachIndexed { index, child ->
            val column = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) columns - 1 - index % columns else index % columns
            val x = paddingLeft + column * (cellWidth + gap)
            val y = paddingTop + index / columns * (cellHeight + gap)
            child.layout(x, y, x + cellWidth, y + cellHeight)
        }
    }
}

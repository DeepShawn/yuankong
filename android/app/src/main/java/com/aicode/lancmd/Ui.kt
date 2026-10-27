package com.aicode.lancmd

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * 界面基础：间距、字号、圆角等设计标度，以及各页面共用的排版组件。
 *
 * 过去每个页面都用 `label()` 直接堆文本和按钮，字号和间距各写一套，
 * 结果行距拥挤、层级不清。这里把标度集中起来，页面只描述内容结构。
 */
object Ui {
    /** 垂直节奏：卡片内相邻元素的标准间隔。 */
    const val GAP = 10
    /** 紧凑间隔：标题与其说明之间。 */
    const val GAP_TIGHT = 4
    /** 分区间隔：卡片之间。 */
    const val GAP_SECTION = 14
    /** 卡片内边距。 */
    const val PAD_CARD = 16
    /** 页面左右边距。 */
    const val PAD_PAGE = 16
    /** 可点击元素的最小高度。 */
    const val TOUCH = 48
    /** 卡片圆角。 */
    const val RADIUS_CARD = 20
    /** 控件圆角。 */
    const val RADIUS_CONTROL = 14

    /** 字号标度：标题、正文、说明。 */
    const val TEXT_TITLE = 16.5f
    const val TEXT_ITEM = 14.5f
    const val TEXT_BODY = 13.5f
    const val TEXT_CAPTION = 12f
    const val TEXT_LABEL = 11.5f

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * 自动留出垂直间距的竖向容器：子视图按加入顺序获得统一间隔，
     * 页面不再需要给每个元素手写 padding 或 margin。
     */
    class Column(context: Context, private val gap: Int = Ui.GAP) : LinearLayout(context) {
        init { orientation = VERTICAL }

        /** 下一个加入的子视图使用紧凑间距（例如标题紧跟的说明文字）。 */
        var tightNext = false

        override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
            val resolved = params ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (resolved is LayoutParams && resolved.topMargin == 0 && childCount > 0) {
                resolved.topMargin = Ui.dp(context, if (tightNext) Ui.GAP_TIGHT else gap)
            }
            tightNext = false
            super.addView(child, index, resolved)
        }
    }

    /** Reflow operation groups when narrow screens or large fonts need more room. */
    class ActionRow(context: Context) : LinearLayout(context) {
        init { orientation = HORIZONTAL }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
            val minimum = visible.maxOfOrNull { child ->
                val textWidth = if (child is TextView) child.paint.measureText(child.text.toString()).toInt() else 0
                maxOf(dp(context, 96), minOf(dp(context, 200), textWidth + child.paddingLeft + child.paddingRight + dp(context, 24)))
            } ?: 0
            val stacked = available > 0 && visible.size * minimum > available
            orientation = if (stacked) VERTICAL else HORIZONTAL
            visible.forEachIndexed { index, child ->
                val params = child.layoutParams as LayoutParams
                val targetWidth = if (stacked) LayoutParams.MATCH_PARENT else 0
                val targetWeight = if (stacked) 0f else 1f
                val gap = if (index == 0) 0 else dp(context, 8)
                if (params.width != targetWidth || params.weight != targetWeight ||
                    params.topMargin != (if (stacked) gap else 0) ||
                    params.marginStart != (if (stacked) 0 else gap) || params.marginEnd != 0) {
                    params.width = targetWidth; params.weight = targetWeight
                    params.leftMargin = 0; params.rightMargin = 0
                    params.marginStart = if (stacked) 0 else gap; params.marginEnd = 0
                    params.topMargin = if (stacked) gap else 0
                    child.layoutParams = params
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** 统一的文字外观：按字号套用行距、字重和颜色，避免逐处手写。 */
    fun text(context: Context, value: CharSequence, size: Float, colorId: Int): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Themes.color(context, colorId))
            setLineSpacing(dp(context, if (size <= TEXT_CAPTION) 3 else 2).toFloat(), 1f)
            if (size >= TEXT_TITLE) typeface = medium()
        }

    /** 分区小标题：强调色、字距舒展，用于卡片与分组的开头。 */
    fun sectionLabel(context: Context, value: String): TextView =
        text(context, value, TEXT_LABEL, R.color.accent).apply {
            letterSpacing = 0.05f
            typeface = medium()
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }

    /** 细分隔线：列表相邻条目之间，替代空白堆叠。 */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Themes.color(context, R.color.outline_soft))
    }

    fun dividerParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))

    /** 次要按钮外观：表面底、细描边，作为页面内多数操作的默认样式。 */
    fun styleSecondary(button: MaterialButton) = button.apply {
        val ctx = button.context
        isAllCaps = false
        minHeight = dp(ctx, TOUCH)
        minWidth = 0
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        cornerRadius = dp(ctx, RADIUS_CONTROL)
        typeface = medium()
        textSize = TEXT_BODY
        setPadding(dp(ctx, 12), paddingTop, dp(ctx, 12), paddingBottom)
        rippleColor = ColorStateList.valueOf(Themes.color(ctx, R.color.accent_glow))
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.surface_variant), Themes.color(ctx, R.color.surface)))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.text), Themes.color(ctx, R.color.dim))))
        strokeWidth = dp(ctx, 1)
        strokeColor = ColorStateList.valueOf(Themes.color(ctx, R.color.outline_soft))
    }

    /** 主操作按钮：强调色填充。 */
    fun stylePrimary(button: MaterialButton) = button.apply {
        val ctx = button.context
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.accent), Themes.color(ctx, R.color.surface)))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.on_accent), Themes.color(ctx, R.color.dim))))
        rippleColor = ColorStateList.valueOf(0x55FFFFFF)
        strokeWidth = 0
    }

    /** 破坏性操作按钮：危险色文字与描边，填充保持低调以免误触视觉引导。 */
    fun styleDanger(button: MaterialButton) = button.apply {
        val ctx = button.context
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.surface), Themes.color(ctx, R.color.surface)))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Themes.color(ctx, R.color.danger), Themes.color(ctx, R.color.dim))))
        strokeWidth = dp(ctx, 1)
        strokeColor = ColorStateList.valueOf(Themes.color(ctx, R.color.danger))
        rippleColor = ColorStateList.valueOf(0x33FF6B6B)
    }
}

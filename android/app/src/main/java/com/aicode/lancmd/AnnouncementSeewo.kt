package com.aicode.lancmd

import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color
import android.graphics.RectF

/**
 * “希沃样式”通知条的唯一尺寸表，与 windows/announcement_seewo.py 一一对应。
 *
 * 两端都按 96 dpi 的逻辑像素书写，再乘 dpi/96。任何一侧改数字都要同步改另一侧，
 * tools/test_announcement_seewo.py 会解析这两个文件并断言两边一致。
 */
internal object SeewoBar {
    const val HEIGHT = 56f
    const val RADIUS = 10f
    const val SIDE_MARGIN = 24f
    const val TOP = 12f
    const val MAX_WIDTH = 1760f
    const val PAD = 8f

    const val ICON = 30f
    const val ICON_RADIUS = 8f
    const val ICON_GAP = 12f

    const val PREFIX_SIZE = 20f
    const val PREFIX_GAP = 4f

    const val DIVIDER_GAP = 12f
    const val DIVIDER_HEIGHT = 20f

    const val TEXT_SIZE = 22f
    const val FADE = 20f
    const val GAP = 64f

    const val CLOSE = 24f
    const val CLOSE_HIT = 36f
    const val CLOSE_GAP = 14f

    const val SPEED = 132f
    const val OPACITY = 96

    const val TOP_COLOR = "#12324F"
    const val BOTTOM_COLOR = "#071522"
    const val ACCENT = "#8FD8FF"
    const val TEXT_COLOR = "#FFFFFF"
    const val BORDER_COLOR = "#2E9BF0"
    const val BORDER_ALPHA = 0x48
    const val DIVIDER_ALPHA = 0x28
    const val CLOSE_COLOR = "#DCE8F5"
    const val SHADOW_ALPHA = 0x6E
    const val SHADOW_STEPS = 6

    const val STYLE = "seewo"

    /** 手机点选后发送的配置片段；锁定项与 Windows 的 lock_seewo 完全一致。 */
    fun preset(prefix: String = "消息通知"): JSONObject = JSONObject()
        .put("style", STYLE).put("mode", "ticker").put("motion", "left").put("position", "top")
        .put("shape", "rounded").put("bold", true).put("font", "Microsoft YaHei UI")
        .put("background", TOP_COLOR).put("foreground", TEXT_COLOR).put("accent", ACCENT)
        .put("width", 100).put("font_size", TEXT_SIZE.toInt()).put("speed", SPEED.toInt())
        .put("opacity", OPACITY).put("placement", "preset").put("rect", JSONObject.NULL)
        .put("prefix", prefix)

    fun isSeewo(config: JSONObject) = config.optString("style") == STYLE

    fun advance(text: String, size: Float): Float {
        var total = 0f
        for (character in text) {
            val code = character.code
            val wide = code in 0x1100..0x115F || code in 0x2E80..0xA4CF ||
                code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF || code in 0xFE30..0xFE4F ||
                code in 0xFF00..0xFF60 || code in 0xFFE0..0xFFE6 || code in 0x1F300..0x1FAFF
            total += if (wide) 1f else 0.55f
        }
        return total * size
    }

    data class Bar(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val icon: RectF, val prefix: RectF?, val divider: RectF?, val viewport: RectF,
        val close: RectF, val radius: Float, val iconRadius: Float, val prefixSize: Float,
        val textSize: Float, val fade: Float, val gap: Float, val closeSize: Float,
        val pad: Float
    )

    fun window(area: AnnouncementGeometry.Area): AnnouncementGeometry.Box {
        val s = area.scale
        val side = (SIDE_MARGIN * s).roundToInt()
        val pad = (PAD * s).roundToInt()
        val widest = min((MAX_WIDTH * s).roundToInt(), (area.width - 2 * side).coerceAtLeast(1))
        val width = min(area.width, widest + 2 * pad)
        val height = min(area.height, ((HEIGHT + 2 * PAD) * s).roundToInt())
        val x = area.x + (area.width - width) / 2
        val y = area.y + min((TOP * s).roundToInt(), (area.height - height).coerceAtLeast(0))
        return AnnouncementGeometry.Box(x, y, width, height)
    }

    fun layout(area: AnnouncementGeometry.Area, config: JSONObject): Bar {
        val s = area.scale
        fun px(value: Float) = (value * s).roundToInt().toFloat()
        val box = window(area)
        val pad = px(PAD)
        val barX = pad
        val barY = pad
        val barWidth = (box.width - 2 * pad).coerceAtLeast(1f)
        val barHeight = (box.height - 2 * pad).coerceAtLeast(1f)
        val iconSize = px(ICON)
        val icon = RectF(barX + px(16f), barY + (barHeight - iconSize) / 2f,
            barX + px(16f) + iconSize, barY + (barHeight + iconSize) / 2f)
        val closeHit = px(CLOSE_HIT)
        val closeRight = barX + barWidth - px(CLOSE_GAP)
        val close = RectF(closeRight - closeHit, barY + (barHeight - closeHit) / 2f,
            closeRight, barY + (barHeight + closeHit) / 2f)
        var left = icon.right + px(ICON_GAP)
        var prefix: RectF? = null
        var divider: RectF? = null
        val text = config.optString("prefix")
        if (text.isNotBlank()) {
            val column = px(advance(text, PREFIX_SIZE) + PREFIX_GAP * 2)
            prefix = RectF(left, barY, left + column, barY + barHeight)
            val dividerX = left + column + px(DIVIDER_GAP)
            val dividerHeight = px(DIVIDER_HEIGHT)
            divider = RectF(dividerX, barY + (barHeight - dividerHeight) / 2f, dividerX + 1f,
                barY + (barHeight + dividerHeight) / 2f)
            left = dividerX + 1f + px(DIVIDER_GAP)
        }
        val viewport = RectF(left, barY, (close.left - px(CLOSE_GAP)).coerceAtLeast(left + 1f),
            barY + barHeight)
        return Bar(barX, barY, barWidth, barHeight, icon, prefix, divider, viewport, close,
            px(RADIUS), px(ICON_RADIUS), px(PREFIX_SIZE), px(TEXT_SIZE), px(FADE), px(GAP),
            px(CLOSE), pad)
    }

    /** 与 Windows 的 offset() 完全相同：视口宽度 + 文本宽度 + 间隔为一个周期。 */
    fun offset(elapsed: Float, speed: Float, viewportWidth: Float, textWidth: Float, gap: Float): Pair<Float, Int> {
        val time = elapsed.coerceAtLeast(0f)
        val period = (viewportWidth + textWidth + gap).coerceAtLeast(1f)
        val travel = time * speed
        return (viewportWidth - travel % period) to (travel / period).toInt()
    }

    fun gradientColor(fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val top = Color.parseColor(TOP_COLOR)
        val bottom = Color.parseColor(BOTTOM_COLOR)
        return Color.rgb(
            (Color.red(top) * (1 - t) + Color.red(bottom) * t).roundToInt(),
            (Color.green(top) * (1 - t) + Color.green(bottom) * t).roundToInt(),
            (Color.blue(top) * (1 - t) + Color.blue(bottom) * t).roundToInt()
        )
    }

    /** 视口两端的淡出条；Windows 端按 8 段横向渐变绘制同样的效果。 */
    fun fadeRects(viewport: RectF, fade: Float): List<Pair<RectF, Boolean>> {
        if (fade <= 0f || viewport.width() <= 2 * fade) return emptyList()
        return listOf(
            RectF(viewport.left, viewport.top, viewport.left + fade, viewport.bottom) to true,
            RectF(viewport.right - fade, viewport.top, viewport.right, viewport.bottom) to false
        )
    }

    /** 喇叭图标：锥形 + 两道声波弧，坐标与 Windows 的 glyph() 相同。 */
    fun speaker(size: Float): Pair<FloatArray, List<Triple<Float, Float, Float>>> {
        val cone = floatArrayOf(
            0.16f, 0.36f, 0.36f, 0.36f, 0.56f, 0.14f, 0.56f, 0.86f, 0.36f, 0.64f, 0.16f, 0.64f
        )
        for (index in cone.indices) cone[index] *= size
        val centreX = size * 0.56f
        val centreY = size * 0.5f
        val arcs = listOf(0.16f, 0.28f).map { Triple(centreX, centreY, size * it) }
        return cone to arcs
    }

    fun shadowRects(bar: Bar): List<Triple<RectF, Float, Int>> {
        val items = ArrayList<Triple<RectF, Float, Int>>()
        for (step in SHADOW_STEPS downTo 1) {
            val grow = (bar.pad * step / SHADOW_STEPS)
            val alpha = (SHADOW_ALPHA * Math.pow(1.0 - (step - 1).toDouble() / SHADOW_STEPS, 1.6) /
                SHADOW_STEPS * 3).toInt().coerceIn(0, SHADOW_ALPHA)
            items.add(Triple(
                RectF(bar.x - grow, bar.y - grow + maxOf(1f, bar.pad / 3), bar.x + bar.width + grow,
                    bar.y + bar.height + grow), alpha.toFloat(), maxOf(2f, bar.pad * step / SHADOW_STEPS).roundToInt()))
        }
        return items
    }
}

internal fun announcementSeewoDefaults() = announcementDefaults().apply {
    val preset = SeewoBar.preset(optString("prefix").ifBlank { "消息通知" })
    val keys = preset.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, preset.get(key))
    }
}

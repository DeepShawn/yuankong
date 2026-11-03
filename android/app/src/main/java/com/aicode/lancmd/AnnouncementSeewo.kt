package com.aicode.lancmd

import android.graphics.Color
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * “希沃样式”通知条：与 windows/announcement_seewo.py 同一张尺寸表。
 *
 * 参考截图是 1920×1080 桌面的 1/3 缩略（640×360，桌面图标 16 px），
 * 所以下面每个数字都是“屏高的几分之几”，1080p 与 4K 上的观感一致：
 *
 * ```
 * y=158 屏幕顶
 * y=168 条顶      ← 距屏幕顶 10 单位
 * y=219 条底      ← 高 52 单位
 * x=73  喇叭左    ← 内缩 19 单位，宽 20，描边 2
 * x=100 文字左    ← 图标后 8 单位
 * ```
 *
 * 没有圆角、阴影、徽标底框、分隔线和关闭按钮：整条是通栏的纯色带，
 * 前缀「消息提醒：」和正文在同一行里一起滚动。
 */
internal object SeewoBar {
    const val BAR_HEIGHT = 52f / 360f
    const val TOP = 10f / 360f
    const val FONT = 19.5f / 360f
    const val ICON = 20f / 360f
    const val ICON_INSET = 19f / 360f
    const val ICON_GAP = 8f / 360f
    const val RIGHT_INSET = 19f / 360f
    const val STROKE = 2f / 360f

    const val SPEED_EM = 2.4f
    const val GAP_EM = 3.0f

    const val MIN_BAR = 36
    const val MIN_FONT = 12

    const val TOP_COLOR = "#020914"
    const val BOTTOM_COLOR = "#040E1C"
    const val ACCENT = "#559BE2"
    const val TEXT_COLOR = "#FFFFFF"
    const val STYLE = "seewo"
    const val DEFAULT_PREFIX = "消息提醒"

    data class Band(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val icon: RectF, val viewport: RectF, val font: Float, val stroke: Float,
        val gap: Float, val speed: Float
    )

    fun isSeewo(config: JSONObject) = config.optString("style") == STYLE

    /** 设计单位 → 设备像素：上面的常量本来就是屏高比例。 */
    fun scale(area: AnnouncementGeometry.Area) = area.height.toFloat()

    private fun px(value: Float, area: AnnouncementGeometry.Area) =
        (value * scale(area)).roundToInt().coerceAtLeast(1).toFloat()

    fun fontSize(area: AnnouncementGeometry.Area) =
        Math.max(MIN_FONT, (FONT * scale(area)).roundToInt()).toFloat()

    fun speed(area: AnnouncementGeometry.Area) = fontSize(area) * SPEED_EM

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

    /** 蓝色前缀，结尾保证只有一个全角冒号。 */
    fun prefixText(config: JSONObject): String {
        val prefix = config.optString("prefix").trim().trimEnd('：', ':')
        return if (prefix.isEmpty()) "" else "$prefix："
    }

    fun window(area: AnnouncementGeometry.Area): AnnouncementGeometry.Box {
        val height = min(area.height, Math.max(MIN_BAR, (BAR_HEIGHT * scale(area)).roundToInt()))
        val top = min((TOP * scale(area)).roundToInt(), maxOf(0, area.height - height))
        return AnnouncementGeometry.Box(area.x, area.y + top, area.width, height)
    }

    /** 返回的是**相对条左上角**的坐标：预览画布已平移到条的左上角。 */
    fun layout(area: AnnouncementGeometry.Area, config: JSONObject): Band {
        val box = window(area)
        val height = box.height.toFloat()
        val width = box.width.toFloat()
        val font = fontSize(area)
        val iconSize = px(ICON, area)
        val inset = px(ICON_INSET, area)
        val icon = RectF(inset, (height - iconSize) / 2f, inset + iconSize,
            (height + iconSize) / 2f)
        val left = icon.right + px(ICON_GAP, area)
        return Band(0f, 0f, width, height, icon,
            RectF(left, 0f, maxOf(left + 1f, width - px(RIGHT_INSET, area)), height), font,
            maxOf(1f, STROKE * scale(area)), font * GAP_EM, speed(area))
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

    /** 静止消息每 5 秒算一圈，供播放次数使用。 */
    fun staticCycle(elapsed: Float) = (elapsed.coerceAtLeast(0f) / 5f).toInt()

    /**
     * 每条可见副本的位置与已完成圈数。第一遍**从左边开始显示**（和参考图一致，
     * 老师点了发送就应当立刻看到），滚出去后空一段间隔，之后的每一遍从右侧进场。
     * 短于视口的消息不动。
     */
    fun positions(elapsed: Float, speedPx: Float, viewportWidth: Float, runWidth: Float,
                  gap: Float): Pair<List<Float>, Int> {
        if (runWidth <= viewportWidth) return listOf(0f) to staticCycle(elapsed)
        val travel = elapsed.coerceAtLeast(0f) * speedPx
        if (travel < runWidth) return listOf(-travel) to 0
        var ahead = travel - runWidth
        if (ahead < gap) return emptyList<Float>() to 1
        ahead -= gap
        val period = (viewportWidth + runWidth + gap).coerceAtLeast(1f)
        val x = viewportWidth - (ahead % period)
        val cycles = 1 + (ahead / period).toInt()
        val copies = mutableListOf(x)
        if (x + runWidth < viewportWidth) copies.add(x + period)
        if (x > 0) copies.add(x - period)
        return copies.filter { it <= viewportWidth && it + runWidth > 0 } to cycles
    }

    /** 描边喇叭：左侧小方箱 + 锥形 + 两道声波弧，坐标与 Windows 的 glyph() 相同。 */
    data class Glyph(val body: FloatArray, val arcs: List<FloatArray>)

    fun glyph(size: Float): Glyph {
        val points = floatArrayOf(
            0.05f, 0.32f, 0.22f, 0.32f, 0.53f, 0.03f, 0.53f, 0.97f,
            0.22f, 0.68f, 0.05f, 0.68f, 0.05f, 0.32f
        )
        for (index in points.indices) points[index] *= size
        val arcs = listOf(
            floatArrayOf(0.56f * size, 0.50f * size, 0.17f * size, -52f, 104f),
            floatArrayOf(0.58f * size, 0.50f * size, 0.36f * size, -50f, 100f)
        )
        return Glyph(points, arcs)
    }

    fun preset(prefix: String = DEFAULT_PREFIX): JSONObject = JSONObject()
        .put("style", STYLE).put("mode", "ticker").put("motion", "left").put("position", "top")
        .put("shape", "rectangle").put("bold", false).put("font", "Microsoft YaHei UI")
        .put("background", TOP_COLOR).put("foreground", TEXT_COLOR).put("accent", ACCENT)
        .put("width", 100).put("font_size", 18).put("speed", 150).put("opacity", 100)
        .put("placement", "preset").put("rect", JSONObject.NULL).put("prefix", prefix)
}

internal fun announcementSeewoDefaults() = announcementDefaults().apply {
    val preset = SeewoBar.preset(optString("prefix").ifBlank { SeewoBar.DEFAULT_PREFIX })
    val keys = preset.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, preset.get(key))
    }
}

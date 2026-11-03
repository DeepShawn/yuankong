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
 * 没有圆角、阴影、徽标底框、分隔线和关闭按钮：整条是通栏的纯色带。
 *
 * 行为是跑马灯：文字始终向左滚动（短消息也滚），多条消息轮巡播放。
 * 每条都从左侧对齐开始（老师按下发送就该立刻读到），向左移出视口后停顿
 * 一个间隔，再由下一条接上；只有一条时就循环它自己。
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

    const val MAX_MESSAGES = 30
    const val MAX_MESSAGE = 500
    const val MIN_SECONDS = 4f
    const val WIDTH_SLACK = 1.05f

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

    /** 要轮播的每一条；列表为空时退回单条 text。 */
    fun messages(config: JSONObject): List<String> {
        val items = ArrayList<String>()
        val array = config.optJSONArray("messages")
        if (array != null) {
            for (index in 0 until minOf(array.length(), MAX_MESSAGES)) {
                val text = array.optString(index).replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) items.add(text.take(MAX_MESSAGE))
            }
        }
        if (items.isEmpty()) {
            val text = config.optString("text").replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) items.add(text.take(MAX_MESSAGE))
        }
        return items
    }

    /** 每条消息的估算宽度（含前缀），与 Windows 的 runs() 同一公式。 */
    fun runs(config: JSONObject, font: Float): List<Float> {
        val prefix = prefixText(config)
        return messages(config).map { advance(prefix + it, font) }
    }

    /** 每条消息的时长：滚到左边缘 + 停顿；两端用同一份估算，时刻表一致。 */
    fun durations(config: JSONObject, font: Float, speedPx: Float, gap: Float): List<Float> {
        val pace = speedPx.coerceAtLeast(1f)
        return runs(config, font).map { maxOf(MIN_SECONDS, (it * WIDTH_SLACK + gap) / pace) }
    }

    /** 当前是第几条、已经播了多久、跑完几轮。 */
    fun locate(elapsed: Float, durations: List<Float>): Triple<Int, Float, Int> {
        if (durations.isEmpty()) return Triple(0, 0f, 0)
        val total = durations.sum().coerceAtLeast(0.0001f)
        val time = elapsed.coerceAtLeast(0f)
        val rounds = (time / total).toInt()
        var local = (time - rounds * total).coerceAtLeast(0f)
        for ((index, duration) in durations.withIndex()) {
            // 1 微秒容差：浮点累计误差不会把边界时刻算成上一条的最后一瞬。
            if (local < duration - 1e-6f) return Triple(index, local, rounds)
            local -= duration
        }
        return Triple(durations.size - 1, durations.last(), rounds)
    }

    /** 行在视口内的左边缘：从 0 开始向左，滚出后停住等间隔。 */
    fun offset(local: Float, speedPx: Float, runWidth: Float): Float =
        -minOf(local.coerceAtLeast(0f) * speedPx, runWidth)

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
        .put("messages", org.json.JSONArray())
}

internal fun announcementSeewoDefaults() = announcementDefaults().apply {
    val preset = SeewoBar.preset(optString("prefix").ifBlank { SeewoBar.DEFAULT_PREFIX })
    val keys = preset.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, preset.get(key))
    }
}

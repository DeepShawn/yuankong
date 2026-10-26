package com.aicode.lancmd

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/** 外观主题：主题色 × 背景色调。颜色以主题属性提供，界面代码通过 [color] 取当前主题的值。 */
object Themes {
    class Option(val key: String, val label: String, val style: Int, val swatch: Int, val swatch2: Int)

    val accents = listOf(
        Option("mint", "薄荷", R.style.Accent_Mint, 0xFF3EE0B5.toInt(), 0xFF5AA9FF.toInt()),
        Option("ocean", "海蓝", R.style.Accent_Ocean, 0xFF4FA8FF.toInt(), 0xFF6EE7F0.toInt()),
        Option("violet", "紫罗兰", R.style.Accent_Violet, 0xFFA98BFF.toInt(), 0xFFFF8BD1.toInt()),
        Option("amber", "琥珀", R.style.Accent_Amber, 0xFFFFB547.toInt(), 0xFFFF7A59.toInt()),
        Option("rose", "玫瑰", R.style.Accent_Rose, 0xFFFF7096.toInt(), 0xFFFFB86B.toInt()),
        Option("lime", "青柠", R.style.Accent_Lime, 0xFFA3E635.toInt(), 0xFF34D399.toInt())
    )

    val tones = listOf(
        Option("deep", "深海", R.style.Tone_Deep, 0xFF080C12.toInt(), 0xFF172131.toInt()),
        Option("navy", "午夜蓝", R.style.Tone_Navy, 0xFF0A1022.toInt(), 0xFF19244A.toInt()),
        Option("graphite", "石墨", R.style.Tone_Graphite, 0xFF14161A.toInt(), 0xFF252932.toInt()),
        Option("black", "纯黑", R.style.Tone_Black, 0xFF000000.toInt(), 0xFF141417.toInt())
    )

    fun accent(ctx: Context) = accents.firstOrNull { it.key == Prefs.accent(ctx) } ?: accents[0]
    fun tone(ctx: Context) = tones.firstOrNull { it.key == Prefs.tone(ctx) } ?: tones[0]

    /** 在 super.onCreate 之前调用，使窗口、布局和对话框都使用所选主题。 */
    fun apply(activity: Activity) {
        activity.theme.applyStyle(tone(activity).style, true)
        activity.theme.applyStyle(accent(activity).style, true)
    }

    private val attrs = mapOf(
        R.color.bg to R.attr.lcBg,
        R.color.surface to R.attr.lcSurface,
        R.color.surface_variant to R.attr.lcSurfaceVariant,
        R.color.surface_high to R.attr.lcSurfaceHigh,
        R.color.outline to R.attr.lcOutline,
        R.color.outline_soft to R.attr.lcOutlineSoft,
        R.color.text to R.attr.lcText,
        R.color.dim to R.attr.lcDim,
        R.color.overlay to R.attr.lcOverlay,
        R.color.accent to R.attr.lcAccent,
        R.color.accent_dim to R.attr.lcAccentDim,
        R.color.accent_glow to R.attr.lcAccentGlow,
        R.color.accent_2 to R.attr.lcAccent2,
        R.color.on_accent to R.attr.lcOnAccent
    )

    /** 取颜色：主题相关的颜色按当前主题解析，状态色（危险/警告/成功）保持固定。 */
    fun color(ctx: Context, id: Int): Int {
        val attr = attrs[id] ?: return ContextCompat.getColor(ctx, id)
        val value = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, value, true) && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
            value.data else ContextCompat.getColor(ctx, id)
    }
}

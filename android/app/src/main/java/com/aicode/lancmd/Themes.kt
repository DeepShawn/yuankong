package com.aicode.lancmd

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/** 外观主题：主题色 × 背景色调。颜色以主题属性提供，界面代码通过 [color] 取当前主题的值。 */
object Themes {
    class Option(val key: String, val label: String, val style: Int, val swatch: Int, val swatch2: Int, val light: Boolean = false, val lightStyle: Int = style, val lightSwatch: Int = swatch)

    val accents = listOf(
        Option("mint", "薄荷", R.style.Accent_Mint, 0xFF3EE0B5.toInt(), 0xFF5AA9FF.toInt(), lightStyle = R.style.Accent_LightMint, lightSwatch = 0xFF007D65.toInt()),
        Option("ocean", "海蓝", R.style.Accent_Ocean, 0xFF4FA8FF.toInt(), 0xFF6EE7F0.toInt(), lightStyle = R.style.Accent_LightOcean, lightSwatch = 0xFF005BBB.toInt()),
        Option("violet", "紫罗兰", R.style.Accent_Violet, 0xFFA98BFF.toInt(), 0xFFFF8BD1.toInt(), lightStyle = R.style.Accent_LightViolet, lightSwatch = 0xFF6440CF.toInt()),
        Option("amber", "琥珀", R.style.Accent_Amber, 0xFFFFB547.toInt(), 0xFFFF7A59.toInt(), lightStyle = R.style.Accent_LightAmber, lightSwatch = 0xFF8D5400.toInt()),
        Option("rose", "玫瑰", R.style.Accent_Rose, 0xFFFF7096.toInt(), 0xFFFFB86B.toInt(), lightStyle = R.style.Accent_LightRose, lightSwatch = 0xFFB32050.toInt()),
        Option("lime", "青柠", R.style.Accent_Lime, 0xFFA3E635.toInt(), 0xFF34D399.toInt(), lightStyle = R.style.Accent_LightLime, lightSwatch = 0xFF3F6800.toInt()),
        Option("cyan", "青蓝", R.style.Accent_Cyan, 0xFF50D5E8.toInt(), 0xFF6CA7FF.toInt(), lightStyle = R.style.Accent_LightCyan, lightSwatch = 0xFF006D7A.toInt()),
        Option("indigo", "靛蓝", R.style.Accent_Indigo, 0xFF9DA8FF.toInt(), 0xFFB89DFF.toInt(), lightStyle = R.style.Accent_LightIndigo, lightSwatch = 0xFF4544C7.toInt()),
        Option("orange", "橙色", R.style.Accent_Orange, 0xFFFFAA6B.toInt(), 0xFFFFCB75.toInt(), lightStyle = R.style.Accent_LightOrange, lightSwatch = 0xFFA7470B.toInt()),
        Option("emerald", "翡翠", R.style.Accent_Emerald, 0xFF62DCA1.toInt(), 0xFF5DDBD5.toInt(), lightStyle = R.style.Accent_LightEmerald, lightSwatch = 0xFF006B40.toInt()),
        Option("coral", "珊瑚", R.style.Accent_Coral, 0xFFFF9288.toInt(), 0xFFFFB68A.toInt(), lightStyle = R.style.Accent_LightCoral, lightSwatch = 0xFFB33D2F.toInt()),
        Option("slate", "雾蓝", R.style.Accent_Slate, 0xFFAEC4E3.toInt(), 0xFFB8C6D8.toInt(), lightStyle = R.style.Accent_LightSlate, lightSwatch = 0xFF41546E.toInt())
    )

    val tones = listOf(
        Option("deep", "深海", R.style.Tone_Deep, 0xFF080C12.toInt(), 0xFF172131.toInt()),
        Option("navy", "午夜蓝", R.style.Tone_Navy, 0xFF0A1022.toInt(), 0xFF19244A.toInt()),
        Option("graphite", "石墨", R.style.Tone_Graphite, 0xFF14161A.toInt(), 0xFF252932.toInt()),
        Option("black", "纯黑", R.style.Tone_Black, 0xFF000000.toInt(), 0xFF141417.toInt()),
        Option("porcelain", "瓷白", R.style.Tone_Porcelain, 0xFFF6F8FC.toInt(), 0xFFE4EAF2.toInt(), light = true),
        Option("paper", "暖纸", R.style.Tone_Paper, 0xFFFAF7F1.toInt(), 0xFFE8E1D5.toInt(), light = true)
    )

    fun accent(ctx: Context) = accents.firstOrNull { it.key == Prefs.accent(ctx) } ?: accents[0]
    fun tone(ctx: Context) = tones.firstOrNull { it.key == Prefs.tone(ctx) } ?: tones[0]

    /** 在 super.onCreate 之前调用，使窗口、布局和对话框都使用所选主题。 */
    fun apply(activity: Activity) {
        val background = tone(activity)
        val selected = accent(activity)
        activity.setTheme(if (background.light) R.style.Theme_LanCmd_Light else R.style.Theme_LanCmd)
        activity.theme.applyStyle(background.style, true)
        activity.theme.applyStyle(if (background.light) selected.lightStyle else selected.style, true)
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
        R.color.on_accent to R.attr.lcOnAccent,
        R.color.danger to R.attr.lcDanger,
        R.color.warn to R.attr.lcWarn,
        R.color.ok to R.attr.lcSuccess
    )

    /** 取颜色：主题相关的颜色按当前主题解析，状态色（危险/警告/成功）保持固定。 */
    fun color(ctx: Context, id: Int): Int {
        val attr = attrs[id] ?: return ContextCompat.getColor(ctx, id)
        val value = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, value, true) && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
            value.data else ContextCompat.getColor(ctx, id)
    }
}

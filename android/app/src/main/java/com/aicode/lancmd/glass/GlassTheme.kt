package com.aicode.lancmd.glass

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.aicode.lancmd.InterfaceStyle
import com.aicode.lancmd.Prefs
import com.aicode.lancmd.R
import com.aicode.lancmd.Themes
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme as NativeMiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/** Liquid Glass 的材质配色：半透明填充、高光描边、柔光、阴影和背景光斑。 */
@Immutable
data class GlassPalette(
    val light: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val text: Color,
    val dim: Color,
    val danger: Color,
    val warn: Color,
    val ok: Color,
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassSunken: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    val stroke: Color,
    val highlight: Color,
    val shadow: Color,
    val scrim: Color,
    val console: Color,
    val accentGlass: Color,
    val accentStroke: Color,
    val dockFill: Color
) {
    companion object {
        /** 没有主题上下文时的兜底（与深海 + 薄荷一致）。 */
        val Fallback: GlassPalette get() = darkPalette(
            bg = Color(0xFF080C12),
            accent = Color(0xFF3EE0B5),
            accent2 = Color(0xFF5AA9FF),
            onAccent = Color(0xFF022B21),
            text = Color(0xFFEAF1F8),
            dim = Color(0xFF8B9AB0),
            danger = Color(0xFFFF6B6B),
            warn = Color(0xFFF2B860),
            ok = Color(0xFF4ADE9B)
        )
    }
}

/** 根据当前主题色与背景色调生成玻璃材质配色。 */
internal fun glassPalette(context: Context): GlassPalette {
    fun color(id: Int) = Color(Themes.color(context, id))
    val accent = color(R.color.accent)
    val accent2 = color(R.color.accent_2)
    val bg = color(R.color.bg)
    val text = color(R.color.text)
    val dim = color(R.color.dim)
    val onAccent = color(R.color.on_accent)
    val danger = color(R.color.danger)
    val warn = color(R.color.warn)
    val ok = color(R.color.ok)
    return if (Themes.tone(context).light) lightPalette(bg, accent, accent2, onAccent, text, dim, danger, warn, ok)
    else darkPalette(bg, accent, accent2, onAccent, text, dim, danger, warn, ok)
}

private fun darkPalette(
    bg: Color,
    accent: Color,
    accent2: Color,
    onAccent: Color,
    text: Color,
    dim: Color,
    danger: Color,
    warn: Color,
    ok: Color
): GlassPalette {
    val deep = lerp(bg, Color.Black, 0.45f)
    return GlassPalette(
        light = false,
        bgTop = lerp(bg, Color.Black, 0.10f),
        bgBottom = lerp(bg, Color.Black, 0.74f),
        blobA = lerp(accent, deep, 0.62f).copy(alpha = 0.34f),
        blobB = lerp(accent2, deep, 0.68f).copy(alpha = 0.24f),
        blobC = Color.White.copy(alpha = 0.018f),
        accent = accent,
        accent2 = accent2,
        onAccent = onAccent,
        text = lerp(text, Color.White, 0.25f),
        dim = lerp(dim, Color.White, 0.18f),
        danger = danger,
        warn = warn,
        ok = ok,
        glassFillTop = Color(0xFFBECFE4).copy(alpha = 0.095f),
        glassFillBottom = Color.White.copy(alpha = 0.026f),
        glassSunken = Color.Black.copy(alpha = 0.32f),
        strokeTop = Color.White.copy(alpha = 0.19f),
        strokeBottom = Color.White.copy(alpha = 0.056f),
        stroke = Color.White.copy(alpha = 0.16f),
        highlight = Color.White.copy(alpha = 0.055f),
        shadow = Color.Black.copy(alpha = 0.55f),
        scrim = deep.copy(alpha = 0.66f),
        console = lerp(bg, Color.Black, 0.74f),
        accentGlass = accent.copy(alpha = 0.15f),
        accentStroke = accent.copy(alpha = 0.40f),
        dockFill = Color.White.copy(alpha = 0.05f)
    )
}

private fun lightPalette(
    bg: Color,
    accent: Color,
    accent2: Color,
    onAccent: Color,
    text: Color,
    dim: Color,
    danger: Color,
    warn: Color,
    ok: Color
): GlassPalette = GlassPalette(
    light = true,
    bgTop = lerp(bg, Color.White, 0.60f),
    bgBottom = lerp(bg, Color(0xFFE1E9F2), 0.55f),
    blobA = lerp(accent, bg, 0.55f).copy(alpha = 0.20f),
    blobB = lerp(accent2, bg, 0.60f).copy(alpha = 0.15f),
    blobC = Color.White.copy(alpha = 0.50f),
    accent = accent,
    accent2 = accent2,
    onAccent = onAccent,
    text = text,
    dim = dim,
    danger = danger,
    warn = warn,
    ok = ok,
    glassFillTop = Color.White.copy(alpha = 0.72f),
    glassFillBottom = Color.White.copy(alpha = 0.44f),
    glassSunken = Color(0xFF0B1B2A).copy(alpha = 0.055f),
    strokeTop = Color.White.copy(alpha = 0.96f),
    strokeBottom = Color.White.copy(alpha = 0.30f),
    stroke = Color(0xFF0B1B2A).copy(alpha = 0.16f),
    highlight = Color.White.copy(alpha = 0.42f),
    shadow = Color(0xFF1B2A3A).copy(alpha = 0.15f),
    scrim = bg.copy(alpha = 0.55f),
    console = lerp(bg, Color.White, 0.42f),
    accentGlass = accent.copy(alpha = 0.13f),
    accentStroke = accent.copy(alpha = 0.42f),
    dockFill = Color.White.copy(alpha = 0.58f)
)

private val LocalWorkspaceColors = compositionLocalOf { darkColorScheme() }
val LocalGlassPalette = compositionLocalOf { GlassPalette.Fallback }
internal val LocalInterfaceStyle = compositionLocalOf { InterfaceStyle.COMPACT }

@Composable
internal fun WorkspaceColorProvider(colors: Colors, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val style = InterfaceStyle.fromKey(Prefs.uiStyle(context))
    val palette = remember(context, colors, style) {
        val glass = glassPalette(context)
        if (style == InterfaceStyle.MIUIX) {
            glass.copy(
                bgTop = colors.background,
                bgBottom = colors.background,
                accent2 = colors.primary,
                text = colors.onSurface,
                dim = colors.onSurfaceVariantSummary,
                glassFillTop = colors.surfaceVariant,
                glassFillBottom = colors.surfaceVariant,
                glassSunken = colors.surfaceVariant,
                strokeTop = colors.outline,
                strokeBottom = colors.outline,
                stroke = colors.outline,
                console = colors.background,
                dockFill = colors.surface
            )
        } else glass
    }
    CompositionLocalProvider(
        LocalWorkspaceColors provides colors,
        LocalInterfaceStyle provides style,
        LocalGlassPalette provides palette
    ) {
        if (style == InterfaceStyle.MIUIX) NativeMiuixTheme(colors = colors, content = content)
        else content()
    }
}

object MiuixTheme {
    val colorScheme: Colors @Composable get() = LocalWorkspaceColors.current
}

/** 动效开关：Robolectric 截图测试和系统「关闭动画」时不跑装饰动画。 */
object GlassMotion {
    val isTestEnvironment: Boolean
        get() = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

    fun animationsEnabled(context: Context): Boolean {
        if (isTestEnvironment) return false
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }

    /** 跟手回弹：按下/松开用轻阻尼弹簧。 */
    val press: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = 0.62f, stiffness = 520f)

    /** 页面/胶囊滑动：轻微回弹。 */
    val slide: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = 0.78f, stiffness = 360f, visibilityThreshold = 0.05f)

    /** Dp 尺寸滑动（Dock 指示器、开关滑块）。 */
    val slideDp: androidx.compose.animation.core.SpringSpec<androidx.compose.ui.unit.Dp> =
        spring(dampingRatio = 0.78f, stiffness = 360f, visibilityThreshold = 0.5f.dp)

    /** 大块淡入淡出：更稳一些。 */
    val settle: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 300f)
}

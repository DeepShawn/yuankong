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
import com.aicode.lancmd.Prefs
import com.aicode.lancmd.R
import com.aicode.lancmd.Themes

/**
 * 页面配色（与 miuix 的 Colors 字段保持一致）。
 *
 * 页面与对话框仍通过 [MiuixTheme.colorScheme] 取色，这样旧的按主题取色代码
 * 不用改；真正的玻璃材质颜色由 [GlassPalette] 提供。
 */
@Immutable
data class Colors(
    val primary: Color = Color(0xFF3EE0B5),
    val onPrimary: Color = Color(0xFF022B21),
    val primaryContainer: Color = Color(0xFF123F37),
    val onPrimaryContainer: Color = Color(0xFF3EE0B5),
    val primaryVariant: Color = Color(0xFF3EE0B5),
    val onPrimaryVariant: Color = Color(0xFF022B21),
    val disabledPrimary: Color = Color(0xFF123F37),
    val disabledOnPrimary: Color = Color(0xFF8B9AB0),
    val disabledPrimaryButton: Color = Color(0xFF172131),
    val disabledOnPrimaryButton: Color = Color(0xFF8B9AB0),
    val disabledPrimarySlider: Color = Color(0xFF123F37),
    val secondary: Color = Color(0xFF172131),
    val onSecondary: Color = Color(0xFFEAF1F8),
    val secondaryVariant: Color = Color(0xFF1E2A3D),
    val onSecondaryVariant: Color = Color(0xFFEAF1F8),
    val disabledSecondary: Color = Color(0xFF101722),
    val disabledOnSecondary: Color = Color(0xFF8B9AB0),
    val disabledSecondaryVariant: Color = Color(0xFF101722),
    val disabledOnSecondaryVariant: Color = Color(0xFF8B9AB0),
    val secondaryContainer: Color = Color(0xFF172131),
    val onSecondaryContainer: Color = Color(0xFF8B9AB0),
    val secondaryContainerVariant: Color = Color(0xFF1E2A3D),
    val onSecondaryContainerVariant: Color = Color(0xFFEAF1F8),
    val tertiaryContainer: Color = Color(0xFF123F37),
    val onTertiaryContainer: Color = Color(0xFF3EE0B5),
    val tertiaryContainerVariant: Color = Color(0xFF1E2A3D),
    val onTertiaryContainerVariant: Color = Color(0xFFEAF1F8),
    val background: Color = Color(0xFF080C12),
    val onBackground: Color = Color(0xFFEAF1F8),
    val onBackgroundVariant: Color = Color(0xFF8B9AB0),
    val surface: Color = Color(0xFF101722),
    val onSurface: Color = Color(0xFFEAF1F8),
    val surfaceVariant: Color = Color(0xFF172131),
    val onSurfaceSecondary: Color = Color(0xFFEAF1F8),
    val onSurfaceVariantSummary: Color = Color(0xFF8B9AB0),
    val onSurfaceVariantActions: Color = Color(0xFF3EE0B5),
    val disabledOnSurface: Color = Color(0x808B9AB0),
    val surfaceContainer: Color = Color(0xFF101722),
    val onSurfaceContainer: Color = Color(0xFFEAF1F8),
    val onSurfaceContainerVariant: Color = Color(0xFF8B9AB0),
    val surfaceContainerHigh: Color = Color(0xFF1E2A3D),
    val onSurfaceContainerHigh: Color = Color(0xFFEAF1F8),
    val surfaceContainerHighest: Color = Color(0xFF1E2A3D),
    val onSurfaceContainerHighest: Color = Color(0xFFEAF1F8),
    val outline: Color = Color(0xFF24324A),
    val dividerLine: Color = Color(0xFF1A2537),
    val error: Color = Color(0xFFFF6B6B)
)

/** 深色背景的默认配色（仅作为没包主题时的兜底，实际由 lanCmdColors 覆盖）。 */
fun darkColorScheme(): Colors = Colors()

/** 亮色背景的默认配色（字段会被 lanCmdColors 逐个覆盖）。 */
fun lightColorScheme(): Colors = Colors(
    primary = Color(0xFF007D65),
    onPrimary = Color(0xFFF4FFFB),
    primaryContainer = Color(0xFFD3F4EA),
    onPrimaryContainer = Color(0xFF007D65),
    primaryVariant = Color(0xFF007D65),
    onPrimaryVariant = Color(0xFFF4FFFB),
    disabledPrimary = Color(0xFFD3F4EA),
    disabledOnPrimary = Color(0xFF6B7A8B),
    disabledPrimaryButton = Color(0xFFE4EAF2),
    disabledOnPrimaryButton = Color(0xFF6B7A8B),
    disabledPrimarySlider = Color(0xFFD3F4EA),
    secondary = Color(0xFFE4EAF2),
    onSecondary = Color(0xFF172131),
    secondaryVariant = Color(0xFFF2F5F9),
    onSecondaryVariant = Color(0xFF172131),
    disabledSecondary = Color(0xFFF6F8FC),
    disabledOnSecondary = Color(0xFF6B7A8B),
    disabledSecondaryVariant = Color(0xFFF6F8FC),
    disabledOnSecondaryVariant = Color(0xFF6B7A8B),
    secondaryContainer = Color(0xFFE4EAF2),
    onSecondaryContainer = Color(0xFF5A6675),
    secondaryContainerVariant = Color(0xFFF2F5F9),
    onSecondaryContainerVariant = Color(0xFF172131),
    tertiaryContainer = Color(0xFFD3F4EA),
    onTertiaryContainer = Color(0xFF007D65),
    tertiaryContainerVariant = Color(0xFFF2F5F9),
    onTertiaryContainerVariant = Color(0xFF172131),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF111A24),
    onBackgroundVariant = Color(0xFF5A6675),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111A24),
    surfaceVariant = Color(0xFFE4EAF2),
    onSurfaceSecondary = Color(0xFF111A24),
    onSurfaceVariantSummary = Color(0xFF5A6675),
    onSurfaceVariantActions = Color(0xFF007D65),
    disabledOnSurface = Color(0x805A6675),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceContainer = Color(0xFF111A24),
    onSurfaceContainerVariant = Color(0xFF5A6675),
    surfaceContainerHigh = Color(0xFFF2F5F9),
    onSurfaceContainerHigh = Color(0xFF111A24),
    surfaceContainerHighest = Color(0xFFF2F5F9),
    onSurfaceContainerHighest = Color(0xFF111A24),
    outline = Color(0xFFC9D3DF),
    dividerLine = Color(0xFFE2E8F0),
    error = Color(0xFFC2333F)
)

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
) = GlassPalette(
    light = false,
    bgTop = lerp(bg, accent, 0.07f),
    bgBottom = lerp(bg, Color.Black, 0.52f),
    blobA = accent.copy(alpha = 0.30f),
    blobB = accent2.copy(alpha = 0.22f),
    blobC = Color.White.copy(alpha = 0.055f),
    accent = accent,
    accent2 = accent2,
    onAccent = onAccent,
    text = text,
    dim = dim,
    danger = danger,
    warn = warn,
    ok = ok,
    glassFillTop = Color.White.copy(alpha = 0.095f),
    glassFillBottom = Color.White.copy(alpha = 0.032f),
    glassSunken = Color.Black.copy(alpha = 0.26f),
    strokeTop = Color.White.copy(alpha = 0.26f),
    strokeBottom = Color.White.copy(alpha = 0.015f),
    stroke = Color.White.copy(alpha = 0.075f),
    highlight = Color.White.copy(alpha = 0.13f),
    shadow = Color.Black.copy(alpha = 0.60f),
    scrim = bg.copy(alpha = 0.62f),
    console = lerp(bg, Color.Black, 0.42f),
    accentGlass = accent.copy(alpha = 0.17f),
    accentStroke = accent.copy(alpha = 0.52f),
    dockFill = Color.White.copy(alpha = 0.075f)
)

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
) = GlassPalette(
    light = true,
    bgTop = lerp(bg, Color.White, 0.45f),
    bgBottom = lerp(bg, Color(0xFFDCE6F1), 0.55f),
    blobA = accent.copy(alpha = 0.22f),
    blobB = accent2.copy(alpha = 0.16f),
    blobC = Color.White.copy(alpha = 0.62f),
    accent = accent,
    accent2 = accent2,
    onAccent = onAccent,
    text = text,
    dim = dim,
    danger = danger,
    warn = warn,
    ok = ok,
    glassFillTop = Color.White.copy(alpha = 0.74f),
    glassFillBottom = Color.White.copy(alpha = 0.46f),
    glassSunken = Color(0xFF0B1B2A).copy(alpha = 0.05f),
    strokeTop = Color.White.copy(alpha = 0.98f),
    strokeBottom = Color.White.copy(alpha = 0.32f),
    stroke = Color(0xFF0B1B2A).copy(alpha = 0.06f),
    highlight = Color.White.copy(alpha = 0.76f),
    shadow = Color(0xFF1B2A3A).copy(alpha = 0.18f),
    scrim = bg.copy(alpha = 0.55f),
    console = lerp(bg, Color.White, 0.35f),
    accentGlass = accent.copy(alpha = 0.13f),
    accentStroke = accent.copy(alpha = 0.44f),
    dockFill = Color.White.copy(alpha = 0.62f)
)

val LocalGlassColors = compositionLocalOf { darkColorScheme() }
val LocalGlassPalette = compositionLocalOf { GlassPalette.Fallback }

/** 设置页面配色与玻璃材质；换主题时 Activity 会重建，所以按 Context 缓存即可。 */
@Composable
fun MiuixTheme(colors: Colors = darkColorScheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val palette = remember(context, colors) { glassPalette(context) }
    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalGlassPalette provides palette,
        content = content
    )
}

object MiuixTheme {
    val colorScheme: Colors @Composable get() = LocalGlassColors.current
}

/** 当前页面是否为紧凑的「KernelSU 参考风格」。 */
@Composable
fun kernelStyle(): Boolean = Prefs.uiStyle(LocalContext.current) == "kernel"

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

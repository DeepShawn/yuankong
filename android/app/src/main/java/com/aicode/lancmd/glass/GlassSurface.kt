package com.aicode.lancmd.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import com.aicode.lancmd.InterfaceStyle
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/**
 * 流体质感背景：深色渐变 + 缓慢漂移的彩色光斑 + 边缘柔光。
 *
 * 光斑画在静态 Canvas 上，动画只改 [graphicsLayer] 的位移/缩放（不重绘画布、
 * 不重组页面），因此在真机上开销很小；Robolectric 截图测试里完全静止。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val enabled = remember(context) { GlassMotion.animationsEnabled(context) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameNanosCompat()
        while (isActive) {
            withFrameNanosCompat().let { now ->
                val elapsed = (now - start) % 52_000_000_000L
                phase = (elapsed / 52_000_000_000f) * (Math.PI * 2).toFloat()
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val dx = 30.dp.toPx()
                    val dy = 22.dp.toPx()
                    translationX = sin(phase) * dx
                    translationY = cos(phase * 0.83f) * dy
                    scaleX = 1.24f
                    scaleY = 1.24f
                }
        ) {
            Canvas(Modifier.fillMaxSize()) { drawAurora(palette) }
        }
        // 边缘压暗 + 底部渐隐，让玻璃卡片从暗底里浮起来。
        Canvas(Modifier.fillMaxSize()) {
            val strength = if (palette.light) 0.10f else 0.34f
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to palette.shadow.copy(alpha = strength),
                    center = Offset(size.width * 0.5f, size.height * 0.38f),
                    radius = size.maxDimension * 0.80f
                )
            )
            drawRect(
                Brush.verticalGradient(
                    0.50f to Color.Transparent,
                    1f to palette.bgBottom.copy(alpha = if (palette.light) 0.28f else 0.62f)
                )
            )
        }
        // 顶部环境光：非常淡的强调色雾，不改变整体中性色。
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to palette.accent.copy(alpha = if (palette.light) 0.05f else 0.045f),
                    0.30f to Color.Transparent
                )
            )
        }
    }
}

private suspend fun withFrameNanosCompat(): Long =
    androidx.compose.runtime.withFrameNanos { it }

private fun DrawScope.drawAurora(palette: GlassPalette) {
    val w = size.width
    val h = size.height
    fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
        if (color.alpha <= 0f) return
        drawRect(
            Brush.radialGradient(
                0f to color,
                0.55f to color.copy(alpha = color.alpha * 0.45f),
                1f to Color.Transparent,
                center = Offset(w * cx, h * cy),
                radius = radius
            )
        )
    }
    blob(palette.blobA, 0.06f, 0.02f, w * 0.80f)
    blob(palette.blobB, 1.00f, 0.40f, w * 0.70f)
    blob(palette.blobC, 0.50f, 1.02f, w * 0.95f)
    // 小而淡的强调色光点，给暗底一点层次。
    blob(palette.accent2.copy(alpha = if (palette.light) 0.05f else 0.045f), 0.92f, 0.92f, w * 0.34f)
    blob(palette.accent.copy(alpha = if (palette.light) 0.05f else 0.04f), 0.04f, 0.72f, w * 0.30f)
}

/**
 * 玻璃材质：半透明渐变填充 + 1 dp 渐变高光描边 + 顶部镜面高光 + 柔和阴影。
 *
 * 不做实时背景模糊（真机性能红线），而是靠背景光斑的透色、边缘高光和阴影
 * 让材质看起来「托」在背景之上。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(26.dp),
    fillTop: Color? = null,
    fillBottom: Color? = null,
    stroke: Boolean = true,
    elevation: Dp = 14.dp,
    glow: Color = Color.Transparent,
    sunken: Boolean = false,
    brush: Brush? = null
): Modifier {
    val palette = LocalGlassPalette.current
    if (LocalInterfaceStyle.current == InterfaceStyle.MIUIX) {
        val colors = MiuixTheme.colorScheme
        val base = if (sunken) colors.surfaceVariant else colors.surface
        val surface = clip(shape).background(fillTop?.compositeOver(base) ?: base)
        return if (stroke) surface.border(1.dp, colors.outline, shape) else surface
    }
    val top = fillTop ?: palette.glassFillTop
    val bottom = fillBottom ?: palette.glassFillBottom
    var modifier = this
    if (elevation > 0.dp) {
        modifier = modifier.shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = palette.shadow.copy(alpha = palette.shadow.alpha * 0.55f),
            spotColor = palette.shadow
        )
    }
    modifier = modifier
        .clip(shape)
        .drawWithCache {
            val fill = brush ?: Brush.verticalGradient(listOf(top, bottom))
            val reflection = Brush.linearGradient(
                0f to palette.highlight,
                0.45f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.25f),
                1f to Color.Transparent,
                end = Offset(size.width * 1.05f, size.height * 1.18f)
            )
            val inset = if (sunken) Brush.verticalGradient(
                0f to Color.Black.copy(alpha = if (palette.light) 0.05f else 0.20f),
                0.45f to Color.Transparent
            ) else null
            val illumination = if (glow.alpha > 0f) Brush.radialGradient(
                0f to glow,
                1f to Color.Transparent,
                center = Offset(size.width * 0.92f, size.height * 1.02f),
                radius = (size.maxDimension * 0.75f).coerceAtLeast(1f)
            ) else null
            onDrawBehind {
                drawRect(fill)
                drawRect(reflection)
                if (inset != null) drawRect(inset)
                if (illumination != null) drawRect(illumination)
            }
        }
    if (stroke) {
        modifier = modifier.border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(palette.strokeTop, palette.strokeBottom)),
            shape = shape
        )
    }
    return modifier
}

/** 圆形玻璃底座（返回键、图标按钮）。 */
@Composable
fun Modifier.glassCircle(
    elevation: Dp = 8.dp,
    accent: Boolean = false
): Modifier {
    val palette = LocalGlassPalette.current
    val shape = if (LocalInterfaceStyle.current == InterfaceStyle.MIUIX) RoundedCornerShape(13.dp) else CircleShape
    val surface = glassSurface(
        shape = shape,
        fillTop = if (accent) palette.accentGlass else null,
        fillBottom = if (accent) palette.accentGlass.copy(alpha = .06f) else null,
        elevation = elevation,
        stroke = !accent
        )
    return if (accent) surface.border(1.dp, palette.accentStroke, shape) else surface
}

/** 跟手的按压状态，配合 [Modifier.pressFeedback] 使用。 */
@Composable
fun rememberGlassPress(): GlassPressState {
    val context = LocalContext.current
    val animated = remember(context) { GlassMotion.animationsEnabled(context) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (animated && pressed) 0.962f else 1f,
        animationSpec = GlassMotion.press,
        label = "glass-press"
    )
    return remember(interaction, pressed, scale) { GlassPressState(interaction, pressed, scale) }
}

@androidx.compose.runtime.Stable
class GlassPressState internal constructor(
    val interaction: MutableInteractionSource,
    val pressed: Boolean,
    val scale: Float
)

/** 把 [GlassPressState] 的缩放应用到节点上。 */
@Composable
fun Modifier.pressFeedback(state: GlassPressState, enabled: Boolean = true): Modifier =
    this.graphicsLayer {
        if (enabled) {
            scaleX = state.scale
            scaleY = state.scale
        }
    }

/** 带弹簧缩放的点击修饰符（组合 clickable + 缩放）。 */
@Composable
fun Modifier.glassClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier {
    val state = rememberGlassPress()
    return this
        .pressFeedback(state, enabled)
        .clickable(
            interactionSource = state.interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}

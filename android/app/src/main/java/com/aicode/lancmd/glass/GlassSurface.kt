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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
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
        // 边缘压暗，让玻璃卡片浮起来。
        Canvas(Modifier.fillMaxSize()) {
            val strength = if (palette.light) 0.14f else 0.42f
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to palette.shadow.copy(alpha = strength),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.maxDimension * 0.78f
                )
            )
        }
        // 顶部环境光：强调色往上铺一层薄雾。
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to palette.accent.copy(alpha = if (palette.light) 0.10f else 0.12f),
                    0.36f to Color.Transparent
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
    blob(palette.blobA, 0.16f, 0.10f, w * 0.95f)
    blob(palette.blobB, 0.94f, 0.30f, w * 0.90f)
    blob(palette.blobC, 0.42f, 0.98f, w * 1.05f)
    // 两处更小的暖光，增加折射层次。
    blob(palette.accent2.copy(alpha = if (palette.light) 0.08f else 0.07f), 0.05f, 0.62f, w * 0.42f)
    blob(palette.accent.copy(alpha = if (palette.light) 0.09f else 0.08f), 0.86f, 0.78f, w * 0.38f)
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
    sunken: Boolean = false
): Modifier {
    val palette = LocalGlassPalette.current
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
        .drawBehind {
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            // 左上镜面高光
            drawRect(
                Brush.linearGradient(
                    0f to palette.highlight,
                    0.45f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.25f),
                    1f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 1.05f, size.height * 1.18f)
                )
            )
            // 内凹材质：顶部压一层暗色，像磨砂玻璃的凹槽。
            if (sunken) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = if (palette.light) 0.05f else 0.20f),
                        0.45f to Color.Transparent
                    )
                )
            }
            // 强调色柔光从右下角透出来。
            if (glow != Color.Transparent) {
                drawRect(
                    Brush.radialGradient(
                        0f to glow,
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.92f, size.height * 1.02f),
                        radius = size.maxDimension * 0.75f
                    )
                )
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
    diameter: Dp = 44.dp,
    elevation: Dp = 8.dp,
    accent: Boolean = false
): Modifier {
    val palette = LocalGlassPalette.current
    val shape = CircleShape
    var modifier = this
    if (elevation > 0.dp) {
        modifier = modifier.shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = palette.shadow.copy(alpha = palette.shadow.alpha * 0.5f),
            spotColor = palette.shadow
        )
    }
    return modifier
        .clip(shape)
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        if (accent) palette.accentGlass else palette.glassFillTop,
                        if (accent) palette.accentGlass.copy(alpha = palette.accentGlass.alpha * 0.5f) else palette.glassFillBottom
                    )
                )
            )
            drawRect(
                Brush.linearGradient(
                    0f to palette.highlight,
                    1f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                )
            )
        }
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    if (accent) palette.accentStroke else palette.strokeTop,
                    if (accent) palette.accentStroke.copy(alpha = 0.25f) else palette.strokeBottom
                )
            ),
            shape = shape
        )
}

/** 按下缩小、松开回弹的点击反馈。不引入 ripple，保持玻璃表面干净。 */
@Composable
fun Modifier.pressScale(enabled: Boolean = true, scale: Float = 0.965f): Modifier {
    // 由调用方把 clickable 加在同一个 Modifier 链上，这里只负责视觉。
    // 为了让按下状态可用，调用方通过 [GlassPressState] 组合。
    return this
}

/** 跟手的按压状态，配合 [Modifier.pressFeedback] 使用。 */
@Composable
fun rememberGlassPress(): GlassPressState {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.962f else 1f,
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
    onClick: () -> Unit
): Modifier {
    val state = rememberGlassPress()
    return this
        .pressFeedback(state, enabled)
        .clickable(
            interactionSource = state.interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

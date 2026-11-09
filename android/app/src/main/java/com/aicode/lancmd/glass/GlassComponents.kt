package com.aicode.lancmd.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// 排版
// ---------------------------------------------------------------------------

/**
 * 文本。默认取玻璃主题的前景色；显式传入的颜色优先，页面里的主题取色逻辑不变。
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null
) {
    val palette = LocalGlassPalette.current
    val merged = style.merge(
        TextStyle(
            color = if (color == Color.Unspecified) palette.text else color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight
        )
    )
    BasicText(
        text = text,
        modifier = modifier,
        style = merged,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalGlassPalette.current.text
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}

/** 圆形玻璃图标按钮：返回、更多操作。 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .glassCircle()
            .glassClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------------------------------------------------------------------
// 卡片 / 分区
// ---------------------------------------------------------------------------

/** 半透明玻璃卡片。行为与原来的 miuix Card 一致：可点击、带内边距、内容用 Column 排。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    insideMargin: PaddingValues = PaddingValues(12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val base = modifier.glassSurface(
        shape = shape,
        elevation = 10.dp,
        glow = palette.accent.copy(alpha = if (palette.light) 0.05f else 0.035f)
    )
    val surface = if (onClick != null) base.glassClickable(onClick = onClick) else base
    Column(modifier = surface) {
        Column(Modifier.padding(insideMargin), content = content)
    }
}

/**
 * 页面骨架：Aurora 背景 + 悬浮玻璃顶栏 + 内容。
 *
 * 内容滚到顶栏下方时会透过半透明玻璃，形成真正的「液体玻璃」层叠感。
 * [content] 收到的是底部导航栏与顶栏占位的内边距，页面按需应用到滚动容器上。
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val insets = remember(statusTop, navBottom) {
        PaddingValues(
            top = statusTop + GlassTopBar.space,
            bottom = navBottom + 12.dp
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        content(insets)
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)) { topBar() }
    }
}

/** 悬浮顶栏的尺寸约定。 */
object GlassTopBar {
    /** 顶栏胶囊高度。 */
    val height: Dp = 54.dp
    /** 顶栏占用的垂直空间（状态栏之外）。 */
    val space: Dp = 70.dp
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/** 玻璃按钮的配色。 */
@androidx.compose.runtime.Immutable
data class GlassButtonColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val solid: Boolean = false,
    val glow: Color = Color.Transparent
)

/** 按钮配色的默认值（兼容旧的 ButtonDefaults 调用写法）。 */
object ButtonDefaults {
    @Composable
    fun textButtonColors(): GlassButtonColors {
        val palette = LocalGlassPalette.current
        return GlassButtonColors(
            container = palette.glassFillTop,
            content = palette.text.copy(alpha = 0.94f),
            border = palette.strokeTop.copy(alpha = 0.55f),
            disabledContainer = palette.glassFillTop.copy(alpha = 0.03f),
            disabledContent = palette.dim.copy(alpha = 0.55f)
        )
    }

    @Composable
    fun textButtonColorsPrimary(): GlassButtonColors {
        val palette = LocalGlassPalette.current
        return GlassButtonColors(
            container = palette.accent,
            content = palette.onAccent,
            border = Color.Transparent,
            disabledContainer = palette.glassFillTop.copy(alpha = 0.04f),
            disabledContent = palette.dim.copy(alpha = 0.55f),
            solid = true,
            glow = palette.accent.copy(alpha = 0.30f)
        )
    }
}

/** 玻璃文字按钮：主操作是实心强调色渐变，其余是中性薄玻璃。 */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    insideMargin: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    colors: GlassButtonColors = ButtonDefaults.textButtonColors()
) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val container = if (enabled) colors.container else colors.disabledContainer
    val contentColor = if (enabled) colors.content else colors.disabledContent
    val solid = colors.solid && enabled
    Box(
        modifier = modifier
            .heightIn(min = 42.dp)
            .glassSurface(
                shape = shape,
                fillTop = container,
                fillBottom = container,
                elevation = if (solid) 10.dp else 0.dp,
                stroke = false,
                glow = if (enabled) colors.glow else Color.Transparent,
                brush = if (solid) {
                    Brush.linearGradient(listOf(palette.accent, palette.accent2))
                } else {
                    Brush.verticalGradient(
                        listOf(container, container.copy(alpha = (container.alpha * 0.62f).coerceAtLeast(0.02f)))
                    )
                }
            )
            .border(1.dp, colors.border, shape)
            .glassClickable(enabled = enabled, onClick = onClick)
            .padding(insideMargin),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.5.sp,
            fontWeight = if (solid) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// 输入框
// ---------------------------------------------------------------------------

/** 带浮动标签的玻璃输入框：空且未聚焦时标签当占位符，聚焦/有内容时浮到上方。 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val floating = label.isNotBlank() && (!singleLine || value.isNotEmpty() || focused)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(palette.glassSunken, palette.glassSunken.copy(alpha = palette.glassSunken.alpha * 0.55f))
                    )
                )
            }
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        if (focused) palette.accent.copy(alpha = 0.85f) else palette.stroke.copy(alpha = 0.9f),
                        if (focused) palette.accent.copy(alpha = 0.28f) else palette.stroke.copy(alpha = 0.30f)
                    )
                ),
                shape = shape
            )
            .padding(horizontal = 14.dp, vertical = if (floating) 8.dp else 11.dp),
        enabled = enabled,
        textStyle = TextStyle(color = palette.text, fontSize = 14.5.sp, lineHeight = 20.sp),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth()) {
                if (floating) {
                    Text(
                        text = label,
                        fontSize = 10.5.sp,
                        color = if (focused) palette.accent else palette.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Box(Modifier.fillMaxWidth()) {
                    if (!floating && label.isNotBlank()) {
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            color = palette.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// 开关 / 进度
// ---------------------------------------------------------------------------

/** 玻璃开关：整行可点，动作与 miuix 的 SuperSwitch 一致。 */
@Composable
fun SuperSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onCheckedChange?.invoke(it) })
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                color = if (enabled) palette.text else palette.dim,
                maxLines = 2
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    fontSize = 11.5.sp,
                    color = palette.dim,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        GlassSwitch(checked = checked, enabled = enabled)
    }
}

@Composable
private fun GlassSwitch(checked: Boolean, enabled: Boolean) {
    val palette = LocalGlassPalette.current
    val offset by animateDpAsState(
        targetValue = if (checked) 20.dp else 3.dp,
        animationSpec = GlassMotion.slideDp,
        label = "switch-thumb"
    )
    val trackColor by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = GlassMotion.settle,
        label = "switch-track"
    )
    Box(
        modifier = Modifier
            .size(width = 45.dp, height = 26.dp)
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        lerpColor(palette.glassSunken, palette.accent, trackColor),
                        lerpColor(palette.glassSunken, palette.accent2, trackColor)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        lerpColor(palette.strokeTop, palette.accentStroke, trackColor),
                        palette.strokeBottom
                    )
                ),
                shape = CircleShape
            )
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 3.dp)
                .size(20.dp)
                .shadow(6.dp, CircleShape, clip = false, ambientColor = palette.shadow, spotColor = palette.shadow)
                .background(if (enabled) Color.White else palette.dim, CircleShape)
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** 确定进度的玻璃进度条（不画不确定动画，避免每帧重组）。 */
@Composable
fun LinearProgressIndicator(progress: Float, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(palette.glassSunken)
            .border(1.dp, palette.stroke.copy(alpha = 0.5f), CircleShape)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(palette.accent, palette.accent2)))
        )
    }
}

// ---------------------------------------------------------------------------
// 其它旧 miuix 组件的兼容小件
// ---------------------------------------------------------------------------

/** 小标题，保留旧导入的兼容别名。 */
@Composable
fun SmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 12.sp, color = LocalGlassPalette.current.accent, fontWeight = FontWeight.Medium)
}

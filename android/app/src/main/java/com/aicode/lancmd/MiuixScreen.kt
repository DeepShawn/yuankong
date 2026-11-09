package com.aicode.lancmd

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.stylusHoverIcon
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.glass.AuroraBackground
import com.aicode.lancmd.glass.ButtonDefaults
import com.aicode.lancmd.glass.Card
import com.aicode.lancmd.glass.Colors
import com.aicode.lancmd.glass.GlassTopBar
import com.aicode.lancmd.glass.Icon
import com.aicode.lancmd.glass.IconButton
import com.aicode.lancmd.glass.LinearProgressIndicator
import com.aicode.lancmd.glass.LocalGlassPalette
import com.aicode.lancmd.glass.MiuixTheme
import com.aicode.lancmd.glass.Scaffold
import com.aicode.lancmd.glass.Text
import com.aicode.lancmd.glass.TextButton
import com.aicode.lancmd.glass.TextField
import com.aicode.lancmd.glass.darkColorScheme
import com.aicode.lancmd.glass.glassClickable
import com.aicode.lancmd.glass.glassSurface
import com.aicode.lancmd.glass.lightColorScheme
import com.aicode.lancmd.glass.rememberGlassPress
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 界面迁移到 Liquid Glass 的公共部分。
 *
 * 全部页面都继承 [MiuixActivity]，界面由 Compose 描述；
 * 自绘 View（远程画面、画板、虚拟键盘）通过 [HostedView] 嵌入。
 */
@Composable
fun LanCmdMiuixTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accent = Themes.accent(context).key
    val tone = Themes.tone(context).key
    val colors = remember(accent, tone) { lanCmdColors(context) }
    MiuixTheme(colors = colors, content = content)
}

/**
 * 把「外观主题」里选择的主题色与背景色调映射到页面配色。
 *
 * 复用 View 版同一套颜色标记（[Themes.color] 按当前主题解析），
 * 所以页面、旧对话框和玻璃材质的颜色保持一致，用户的选择继续生效。
 */
internal fun lanCmdColors(context: Context): Colors {
    fun pick(id: Int) = Color(Themes.color(context, id))
    return (if (Themes.tone(context).light) lightColorScheme() else darkColorScheme()).copy(
        primary = pick(R.color.accent),
        onPrimary = pick(R.color.on_accent),
        primaryContainer = pick(R.color.accent_dim),
        onPrimaryContainer = pick(R.color.accent),
        primaryVariant = pick(R.color.accent),
        onPrimaryVariant = pick(R.color.on_accent),
        disabledPrimary = pick(R.color.accent_dim),
        disabledOnPrimary = pick(R.color.dim),
        disabledPrimaryButton = pick(R.color.surface_variant),
        disabledOnPrimaryButton = pick(R.color.dim),
        disabledPrimarySlider = pick(R.color.accent_dim),
        secondary = pick(R.color.surface_variant),
        onSecondary = pick(R.color.text),
        secondaryVariant = pick(R.color.surface_high),
        onSecondaryVariant = pick(R.color.text),
        disabledSecondary = pick(R.color.surface),
        disabledOnSecondary = pick(R.color.dim),
        disabledSecondaryVariant = pick(R.color.surface),
        disabledOnSecondaryVariant = pick(R.color.dim),
        secondaryContainer = pick(R.color.surface_variant),
        onSecondaryContainer = pick(R.color.dim),
        secondaryContainerVariant = pick(R.color.surface_high),
        onSecondaryContainerVariant = pick(R.color.text),
        tertiaryContainer = pick(R.color.accent_dim),
        onTertiaryContainer = pick(R.color.accent),
        tertiaryContainerVariant = pick(R.color.surface_high),
        onTertiaryContainerVariant = pick(R.color.text),
        background = pick(R.color.bg),
        onBackground = pick(R.color.text),
        onBackgroundVariant = pick(R.color.dim),
        surface = pick(R.color.surface),
        onSurface = pick(R.color.text),
        surfaceVariant = pick(R.color.surface_variant),
        onSurfaceSecondary = pick(R.color.text),
        onSurfaceVariantSummary = pick(R.color.dim),
        onSurfaceVariantActions = pick(R.color.accent),
        disabledOnSurface = pick(R.color.dim).copy(alpha = .5f),
        surfaceContainer = pick(R.color.surface),
        onSurfaceContainer = pick(R.color.text),
        onSurfaceContainerVariant = pick(R.color.dim),
        surfaceContainerHigh = pick(R.color.surface_high),
        onSurfaceContainerHigh = pick(R.color.text),
        surfaceContainerHighest = pick(R.color.surface_high),
        onSurfaceContainerHighest = pick(R.color.text),
        outline = pick(R.color.outline),
        dividerLine = pick(R.color.outline_soft),
        error = pick(R.color.danger)
    )
}

/** 当前外观是否为紧凑的「KernelSU 参考风格」；两种风格都使用 Liquid Glass 材质。 */
@Composable
internal fun kernelStyle() = Prefs.uiStyle(LocalContext.current) == "kernel"

/** 页面顶部的状态与结果提示，等同于旧 View 页面的状态条。 */
@Composable
fun StatusNote(text: String, failed: Boolean) {
    if (text.isBlank()) return
    val expanded = remember(text) { mutableStateOf(false) }
    val palette = LocalGlassPalette.current
    val tint = if (failed) palette.danger else palette.accent
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .glassSurface(
                shape = shape,
                fillTop = tint.copy(alpha = if (palette.light) .18f else .16f),
                fillBottom = tint.copy(alpha = if (palette.light) .07f else .05f),
                elevation = 8.dp,
                glow = tint.copy(alpha = .16f)
            )
            .border(1.dp, tint.copy(alpha = .32f), shape)
            .glassClickable { expanded.value = !expanded.value }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = .18f)),
            contentAlignment = Alignment.Center
        ) { Text(if (failed) "!" else "✓", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Column(
            Modifier.weight(1f).heightIn(max = if (expanded.value) 160.dp else 44.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = if (expanded.value) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                color = palette.text
            )
        }
    }
}

/** 悬浮玻璃顶栏：返回键是圆形玻璃，标题区可容纳副标题。 */
@Composable
fun WorkspaceHeader(title: String, back: (() -> Unit)? = null, subtitle: String = "") {
    val palette = LocalGlassPalette.current
    val kernel = kernelStyle()
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to palette.bgTop.copy(alpha = .96f),
                    0.62f to palette.bgTop.copy(alpha = .78f),
                    1f to Color.Transparent
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GlassTopBar.height)
                .glassSurface(
                    shape = RoundedCornerShape(GlassTopBar.height / 2),
                    elevation = 18.dp,
                    glow = palette.accent.copy(alpha = if (palette.light) .08f else .06f)
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (back != null) IconButton(onClick = back) {
                Icon(painterResource(R.drawable.ic_back), "返回")
            } else {
                BrandMark()
            }
            Column(
                Modifier.weight(1f).padding(
                    start = if (back == null) 12.dp else 8.dp,
                    end = 14.dp
                )
            ) {
                if (subtitle.isNotBlank()) Text(
                    subtitle,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.accent
                )
                Text(
                    title,
                    fontSize = if (kernel && back == null) 22.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

/** 首页顶栏左侧的品牌圆点：强调色渐变玻璃珠。 */
@Composable
private fun BrandMark() {
    val palette = LocalGlassPalette.current
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(
            Brush.linearGradient(
                listOf(
                    palette.accent.copy(alpha = .9f),
                    palette.accent2.copy(alpha = .55f)
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape).background(Color.White.copy(alpha = .92f))
        )
    }
}

fun Modifier.workspaceBackground(top: Color, bottom: Color) =
    background(Brush.verticalGradient(listOf(top, bottom)))

/**
 * 所有页面的基类：提供连接绑定、后台任务和状态提示，界面由 Compose 描述。
 */
abstract class MiuixActivity : AppCompatActivity() {
    /** 打开页面时的连接，操作不会跟随电脑切换。 */
    protected val origin: AgentClient? by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }

    protected val noteText = mutableStateOf("")
    protected val noteFailed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Themes.tone(this@MiuixActivity).light
            isAppearanceLightNavigationBars = Themes.tone(this@MiuixActivity).light
        }
        // 让系统栏与页面背景连成一片；导航栏不再额外压一层灰色遮罩。
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        if (android.os.Build.VERSION.SDK_INT < 26 && Themes.tone(this).light) window.navigationBarColor = 0xFF172131.toInt()
        AppVisibility.install(application)
        origin
        EntryMotion.enter(this, savedInstanceState)
    }

    /** 用 Compose 作为页面内容，不引入 activity-compose 依赖。 */
    protected fun content(body: @Composable () -> Unit) {
        setContentView(ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setContent { LanCmdMiuixTheme { body() } }
        })
    }

    /**
     * 标准页面骨架：悬浮顶栏、可滚动内容，以及顶部的状态提示。
     * 内容滚到玻璃顶栏下方时会透过去，页面只需描述分区。
     */
    @Composable
    protected fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
        Scaffold(topBar = { WorkspaceHeader(title, { onBack() }) }) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(insets.calculateTopPadding()))
                StatusNote(noteText.value, noteFailed.value)
                content()
                Spacer(Modifier.height(insets.calculateBottomPadding() + 16.dp))
            }
        }
    }

    /**
     * 不滚动的页面骨架：内容自己分配高度，用于画面要占满剩余空间的页面
     * （远程桌面）。状态提示同样在顶部。
     */
    @Composable
    protected fun FixedPage(title: String, content: @Composable ColumnScope.() -> Unit) {
        Scaffold(topBar = { WorkspaceHeader(title, { onBack() }) }) { insets ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())
            ) {
                StatusNote(noteText.value, noteFailed.value)
                content()
            }
        }
    }

    /** Large management lists render only visible rows and scroll under the glass bar. */
    @Composable
    protected fun ListPage(title: String, content: LazyListScope.() -> Unit) {
        Scaffold(topBar = { WorkspaceHeader(title, { onBack() }) }) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = insets
            ) {
                item { StatusNote(noteText.value, noteFailed.value) }
                content()
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    /** 顶栏返回键的行为，子类可覆盖（例如先提示未保存内容）。 */
    protected open fun onBack() {
        finish()
    }

    /** 当前状态提示，供测试读取（Compose 文本不在 View 树里）。 */
    internal val note: String get() = noteText.value

    protected fun status(text: String, failed: Boolean = false) {
        noteText.value = text
        noteFailed.value = failed
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * 供系统确认框内使用的输入框。确认与删除一类的弹窗仍用系统对话框，
     * 这里让其中的控件与页面外观一致。
     */
    protected fun dialogInput(
        hint: String = "",
        text: String = "",
        singleLine: Boolean = true,
        enabled: Boolean = true
    ): android.widget.EditText = android.widget.EditText(this).apply {
        this.hint = hint
        setText(text)
        isEnabled = enabled
        if (singleLine) setSingleLine()
        setBackgroundResource(R.drawable.input_bg)
        setTextColor(Themes.color(this@MiuixActivity, R.color.text))
        setHintTextColor(Themes.color(this@MiuixActivity, R.color.dim))
        setPadding(px(14), px(10), px(14), px(10))
        textSize = 13.5f
        minHeight = px(48)
    }

    /** 供系统确认框内使用的下拉框。 */
    protected fun dialogSpinner(items: List<String>, selected: Int = 0): android.widget.Spinner =
        android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MiuixActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )
            if (selected in items.indices) setSelection(selected)
            setBackgroundResource(R.drawable.input_bg)
            minimumHeight = px(48)
            setPadding(px(14), paddingTop, px(14), paddingBottom)
        }

    /** 供系统确认框内使用的按钮。 */
    protected fun dialogButton(text: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            minHeight = px(48)
            setTextColor(Themes.color(this@MiuixActivity, R.color.accent))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Themes.color(this@MiuixActivity, R.color.surface_variant)
            )
            cornerRadius = px(18)
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    /** 系统确认框内的纵向容器。 */
    protected fun dialogBox(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(px(20), 0, px(20), 0)
    }

    protected fun task(block: suspend () -> Unit): Job = lifecycleScope.launch(com.aicode.lancmd.net.DelayNotice { status(it) }) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(e.message ?: "操作失败", true) }
    }
}

/** 纵向排列的页面主体，统一左右留白。 */
@Composable
fun MiuixColumn(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) { content() }
}

// ----------------------------------------------------------------- 排版组件
//
// 各页面只描述内容，留白、分区和圆角由这里统一给出。

/** 卡片分区：可选分区标题 + 玻璃卡片。 */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val kernel = kernelStyle()
    val palette = LocalGlassPalette.current
    if (title != null) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 3.dp, height = 12.dp).clip(CircleShape)
                    .background(palette.accent.copy(alpha = .85f))
            )
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
                color = palette.accent,
                modifier = Modifier.padding(start = 7.dp)
            )
        }
    }
    val radius = if (kernel) 22.dp else 26.dp
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        cornerRadius = radius,
        insideMargin = PaddingValues(10.dp)
    ) { content() }
}

/** 列表条目：标题 + 说明，可点击。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun RowItem(title: String, summary: String? = null, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val press = rememberGlassPress()
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = palette.text.copy(alpha = if (enabled) 1f else .4f)
            )
            if (!summary.isNullOrBlank()) Text(
                summary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 5.dp),
                color = palette.dim
            )
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(palette.glassFillTop)
                .border(1.dp, palette.strokeBottom, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("›", fontSize = 18.sp, color = palette.accent)
        }
    }
}

/** Observable checked rows: a check mark and a tinted surface make selection unambiguous. */
@Composable
fun SelectionRow(title: String, summary: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)
            .glassSurface(
                shape = shape,
                fillTop = if (checked) palette.accentGlass else palette.glassFillTop.copy(alpha = .04f),
                fillBottom = if (checked) palette.accentGlass.copy(alpha = .1f) else palette.glassFillBottom.copy(alpha = .02f),
                elevation = 0.dp,
                glow = if (checked) palette.accent.copy(alpha = .12f) else Color.Transparent
            )
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) palette.accent else Color.Transparent)
                .border(1.5.dp, if (checked) palette.accent else palette.stroke, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.onAccent)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.text
            )
            if (!summary.isNullOrBlank()) Text(
                summary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.dim
            )
        }
    }
}

/** Compact visual selectors retain a 48 dp touch target. */
@Composable
fun SelectionAction(text: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(selected, enabled, role = Role.Button, onClick = onClick)
            .glassSurface(
                shape = shape,
                fillTop = if (selected) palette.accentGlass else palette.glassFillTop.copy(alpha = .05f),
                fillBottom = if (selected) palette.accentGlass.copy(alpha = .08f) else palette.glassFillBottom.copy(alpha = .02f),
                elevation = 0.dp,
                glow = if (selected) palette.accent.copy(alpha = .10f) else Color.Transparent
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            color = if (!enabled) palette.dim.copy(alpha = .5f) else if (selected) palette.accent else palette.text
        )
    }
}

/** 只读的列表条目：展示状态，不可点击。 */
@Composable
fun InfoItem(title: String, summary: String? = null) {
    val palette = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
        if (!summary.isNullOrBlank()) Text(
            summary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 5.dp),
            color = palette.dim
        )
    }
}

/** 说明文字。 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = LocalGlassPalette.current.dim
    )
}

/** 空列表提示。 */
@Composable
fun EmptyNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp),
        color = LocalGlassPalette.current.dim
    )
}

/** 操作按钮；[primary] 用强调色，用于页面主操作。 */
@Composable
fun Action(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        cornerRadius = if (kernelStyle()) 24.dp else 18.dp,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        modifier = modifier.padding(vertical = 4.dp).heightIn(min = 48.dp),
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
    )
}

/** 并排的操作按钮，等分宽度。 */
@Composable
fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

/**
 * 承载一个自绘 View（画面、画板、虚拟键盘）。
 *
 * 每个节点自带容器，并在 update 时把 [view] 搬进来：同一个 View 实例可能出现在
 * 界面的不同分支（例如全屏与非全屏），直接交给 AndroidView 的 factory 会让它
 * 同时挂在两个节点上，然后被反复 attach / detach。
 */
@Composable
fun HostedView(view: View, modifier: Modifier = Modifier) {
    // Compose resolves hover icons before the embedded View on some input
    // paths. Apply the same empty icon here, scoped to the remote picture.
    val hostedModifier = if (view is RemoteScreenView) {
        val hidden = PointerIcon(android.view.PointerIcon.TYPE_NULL)
        modifier.pointerHoverIcon(hidden, overrideDescendants = true)
            .stylusHoverIcon(hidden, overrideDescendants = true)
    } else modifier
    AndroidView(
        factory = { FrameLayout(it) },
        modifier = hostedModifier,
        update = { host ->
            if (view.parent !== host) {
                (view.parent as? ViewGroup)?.removeView(view)
                host.removeAllViews()
                // Keyboards measure all rows naturally inside their scrollable workspace.
                host.addView(view, ViewGroup.LayoutParams(-1, if (view is VirtualKeyboard) -2 else -1))
            }
        }
    )
}

/**
 * 进度条。
 *
 * 只接受已知进度：不确定进度是每帧重组的无限动画，进度未知时由条目标题说明
 * 当前阶段，不画进度条。
 */
@Composable
fun Progress(progress: Float) {
    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
}

/**
 * 带名称的输入框。
 *
 * 多行输入必须给出 [maxLines]：页面主体是可滚动的，
 * 高度不设上限的输入框会让测量没有收敛的高度。
 */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
    password: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

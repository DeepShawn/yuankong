package com.aicode.lancmd

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.stylusHoverIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 界面迁移到 miuix（MIUI 风格的 Compose 组件库）的公共部分。
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
 * 把「外观主题」里选择的主题色与背景色调映射到 miuix 的配色。
 *
 * 复用 View 版同一套颜色标记（[Themes.color] 按当前主题解析），
 * 所以两种页面在迁移期间颜色一致，用户的选择继续生效。
 */
internal fun lanCmdColors(context: Context): Colors {
    fun pick(id: Int) = Color(Themes.color(context, id))
    return (if(Themes.tone(context).light)lightColorScheme() else darkColorScheme()).copy(
        primary = pick(R.color.accent),
        onPrimary = pick(R.color.on_accent),
        primaryContainer = pick(R.color.accent_dim),
        onPrimaryContainer = pick(R.color.accent),
        primaryVariant=pick(R.color.accent),
        onPrimaryVariant=pick(R.color.on_accent),
        disabledPrimary=pick(R.color.accent_dim),
        disabledOnPrimary=pick(R.color.dim),
        disabledPrimaryButton=pick(R.color.surface_variant),
        disabledOnPrimaryButton=pick(R.color.dim),
        disabledPrimarySlider=pick(R.color.accent_dim),
        secondary = pick(R.color.surface_variant),
        onSecondary=pick(R.color.text),
        secondaryVariant=pick(R.color.surface_high),
        onSecondaryVariant=pick(R.color.text),
        disabledSecondary=pick(R.color.surface),
        disabledOnSecondary=pick(R.color.dim),
        disabledSecondaryVariant=pick(R.color.surface),
        disabledOnSecondaryVariant=pick(R.color.dim),
        secondaryContainer=pick(R.color.surface_variant),
        onSecondaryContainer=pick(R.color.dim),
        secondaryContainerVariant=pick(R.color.surface_high),
        onSecondaryContainerVariant=pick(R.color.text),
        tertiaryContainer=pick(R.color.accent_dim),
        onTertiaryContainer=pick(R.color.accent),
        tertiaryContainerVariant=pick(R.color.surface_high),
        background = pick(R.color.bg),
        onBackground = pick(R.color.text),
        onBackgroundVariant = pick(R.color.dim),
        surface = pick(R.color.surface),
        onSurface = pick(R.color.text),
        surfaceVariant = pick(R.color.surface_variant),
        onSurfaceSecondary=pick(R.color.text),
        onSurfaceVariantSummary=pick(R.color.dim),
        onSurfaceVariantActions=pick(R.color.accent),
        disabledOnSurface=pick(R.color.dim).copy(alpha=.5f),
        surfaceContainer=pick(R.color.surface),
        onSurfaceContainer=pick(R.color.text),
        onSurfaceContainerVariant=pick(R.color.dim),
        surfaceContainerHigh=pick(R.color.surface_high),
        onSurfaceContainerHigh=pick(R.color.text),
        surfaceContainerHighest=pick(R.color.surface_high),
        onSurfaceContainerHighest=pick(R.color.text),
        outline = pick(R.color.outline),
        dividerLine = pick(R.color.outline_soft),
        error = pick(R.color.danger)
    )
}

/** The selectable KernelSU-inspired presentation keeps the existing miuix components and palettes. */
@Composable
internal fun kernelStyle() = Prefs.uiStyle(LocalContext.current)=="kernel"

/** 页面顶部的状态与结果提示，等同于旧 View 页面的状态条。 */
@Composable
fun StatusNote(text: String, failed: Boolean) {
    if (text.isBlank()) return
    val expanded = remember(text) { mutableStateOf(false) }
    val tint=if(failed)MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Card(modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
        .border(1.dp,tint.copy(alpha=.25f),RoundedCornerShape(18.dp)),cornerRadius=18.dp,
        insideMargin=PaddingValues(8.dp),onClick={expanded.value=!expanded.value}) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(failed)"!" else "✓",color=tint,fontWeight=FontWeight.Bold)
            Column(Modifier.weight(1f).heightIn(max=if(expanded.value)160.dp else 44.dp).verticalScroll(rememberScrollState())) {
                Text(text,fontSize=13.sp,lineHeight=19.sp,maxLines=if(expanded.value)Int.MAX_VALUE else 2,
                    overflow=TextOverflow.Ellipsis,color=MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun WorkspaceHeader(title:String,back:(()->Unit)?=null,subtitle:String="") {
    val kernel=kernelStyle()
    Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background).padding(horizontal=if(kernel)16.dp else 12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        if(back!=null)IconButton(onClick=back) { Icon(painterResource(R.drawable.ic_back),"返回") }
        Column(Modifier.weight(1f).padding(start=if(back==null)0.dp else 8.dp)) {
            if(subtitle.isNotBlank())Text(subtitle,fontSize=11.sp,letterSpacing=1.sp,color=MiuixTheme.colorScheme.primary)
            Text(title,fontSize=if(kernel && back==null)28.sp else 22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=4.dp))
        }
    }
}

fun Modifier.workspaceBackground(top:Color,bottom:Color)=background(Brush.verticalGradient(listOf(top,bottom)))

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
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=Themes.tone(this@MiuixActivity).light
            isAppearanceLightNavigationBars=Themes.tone(this@MiuixActivity).light
        }
        if(android.os.Build.VERSION.SDK_INT<26 && Themes.tone(this).light)window.navigationBarColor=0xFF172131.toInt()
        AppVisibility.install(application)
        origin
        EntryMotion.enter(this,savedInstanceState)
    }

    /** 用 Compose 作为页面内容，不引入 activity-compose 依赖。 */
    protected fun content(body: @Composable () -> Unit) {
        setContentView(ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setContent { LanCmdMiuixTheme { body() } }
        })
    }

    /**
     * 标准页面骨架：顶栏与返回、可滚动内容，以及顶部的状态提示。
     * 页面只需在 [content] 里描述分区，留白由骨架给出。
     */
    @Composable
    protected fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
        Scaffold(
            topBar = { WorkspaceHeader(title, {onBack()}) }
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(insets).verticalScroll(rememberScrollState())
            ) {
                StatusNote(noteText.value, noteFailed.value)
                content()
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    /**
     * 不滚动的页面骨架：内容自己分配高度，用于画面要占满剩余空间的页面
     * （远程桌面）。状态提示同样在顶部。
     */
    @Composable
    protected fun FixedPage(title: String, content: @Composable ColumnScope.() -> Unit) {
        Scaffold(
            topBar = { WorkspaceHeader(title,{onBack()}) }
        ) { insets ->
            Column(modifier = Modifier.fillMaxSize().padding(insets)) {
                StatusNote(noteText.value, noteFailed.value)
                content()
            }
        }
    }

    /** Large management lists render only visible rows. */
    @Composable
    protected fun ListPage(title: String, content: LazyListScope.() -> Unit) {
        Scaffold(topBar = {
            WorkspaceHeader(title,{onBack()})
        }) { insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets)) {
                item { StatusNote(noteText.value,noteFailed.value) }
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
// 各页面只描述内容，留白、分区和圆角由这里统一给出，
// 页面只描述内容，排版集中在这里。

/** 卡片分区：可选分区标题 + 圆角卡片。 */
@Composable
fun SectionCard(title: String? = null, modifier:Modifier=Modifier, content: @Composable ColumnScope.() -> Unit) {
    val kernel=kernelStyle()
    if (title != null) Text(title,fontSize=12.sp,fontWeight=FontWeight.Medium,
        modifier=Modifier.padding(start=20.dp,top=8.dp,bottom=4.dp),color=MiuixTheme.colorScheme.primary)
    val radius=if(kernel)20.dp else 24.dp
    Card(modifier=modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
        .border(if(kernel)0.dp else 1.dp,if(kernel)Color.Transparent else MiuixTheme.colorScheme.outline.copy(alpha=.25f),RoundedCornerShape(radius)),
        cornerRadius=radius,insideMargin=PaddingValues(8.dp)) { content() }
}

/** 列表条目：标题 + 说明，可点击。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun RowItem(title: String, summary: String? = null, enabled: Boolean = true, onLongClick:(()->Unit)?=null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(enabled=enabled,onClick=onClick,onLongClick=onLongClick).padding(horizontal=8.dp,vertical=10.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium,color=MiuixTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .4f))
            if(!summary.isNullOrBlank())Text(summary,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=5.dp),color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text("›",fontSize=24.sp,color=MiuixTheme.colorScheme.primary,modifier=Modifier.padding(start=12.dp))
    }
}

/** Observable checked rows: a check mark and a tinted surface make selection unambiguous. */
@Composable
fun SelectionRow(title:String,summary:String?=null,checked:Boolean,onCheckedChange:(Boolean)->Unit,enabled:Boolean=true) {
    val colors=MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp)
        .background(if(checked)colors.primaryContainer else colors.surface,RoundedCornerShape(16.dp))
        .toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onCheckedChange)
        .padding(horizontal=12.dp,vertical=10.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(if(checked)colors.primary else Color.Transparent,RoundedCornerShape(7.dp))
            .border(1.5.dp,if(checked)colors.primary else colors.outline,RoundedCornerShape(7.dp)),contentAlignment=Alignment.Center) {
            if(checked)Text("✓",fontSize=17.sp,fontWeight=FontWeight.Bold,color=colors.onPrimary)
        }
        Column(Modifier.weight(1f).padding(start=12.dp)) {
            Text(title,fontSize=15.sp,fontWeight=if(checked)FontWeight.SemiBold else FontWeight.Normal,color=colors.onSurface)
            if(!summary.isNullOrBlank())Text(summary,fontSize=12.sp,lineHeight=17.sp,color=colors.onSurfaceVariantSummary)
        }
    }
}

/** Compact visual selectors retain a 48 dp touch target. */
@Composable
fun SelectionAction(text:String,selected:Boolean=false,enabled:Boolean=true,modifier:Modifier=Modifier,onClick:()->Unit) {
    Box(modifier.heightIn(min=48.dp).selectable(selected,enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Text(text,fontSize=13.sp,textAlign=TextAlign.Center,
            modifier=Modifier.fillMaxWidth().background(if(selected)MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp)).padding(horizontal=8.dp,vertical=7.dp),
            color=if(!enabled)MiuixTheme.colorScheme.disabledOnSurface else if(selected)MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
    }
}

/** 只读的列表条目：展示状态，不可点击。 */
@Composable
fun InfoItem(title: String, summary: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=12.dp)) {
        Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium)
        if(!summary.isNullOrBlank())Text(summary,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=5.dp),color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 说明文字。 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize=13.sp,lineHeight=19.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

/** 空列表提示。 */
@Composable
fun EmptyNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
        cornerRadius = if(kernelStyle())24.dp else 16.dp,
        insideMargin = PaddingValues(horizontal=8.dp,vertical=10.dp),
        modifier = modifier.padding(vertical = 4.dp).heightIn(min=48.dp),
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
                host.addView(view, ViewGroup.LayoutParams(-1, if(view is VirtualKeyboard)-2 else -1))
            }
        }
    )
}

/**
 * 进度条。
 *
 * 只接受已知进度：miuix 的不确定进度是一条无限动画，每帧都要重组，
 * 进度未知时由条目标题说明当前阶段，不画进度条。
 */
@Composable
fun Progress(progress: Float) {
    LinearProgressIndicator(
        progress = progress,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
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
    modifier:Modifier=Modifier,
    password:Boolean=false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = if(password)androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

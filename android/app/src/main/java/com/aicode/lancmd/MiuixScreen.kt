package com.aicode.lancmd

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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

/**
 * 界面迁移到 miuix（MIUI 风格的 Compose 组件库）的公共部分。
 *
 * 迁移按页进行，期间 View 页面与 Compose 页面共存：
 * 已迁移的页面继承 [MiuixActivity] 用 Compose 描述界面，
 * 未迁移的页面仍继承 FeatureActivity。
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
private fun lanCmdColors(context: Context): Colors {
    fun pick(id: Int) = Color(Themes.color(context, id))
    return darkColorScheme(
        primary = pick(R.color.accent),
        onPrimary = pick(R.color.on_accent),
        primaryContainer = pick(R.color.accent_dim),
        onPrimaryContainer = pick(R.color.accent),
        secondary = pick(R.color.accent_2),
        background = pick(R.color.bg),
        onBackground = pick(R.color.text),
        surface = pick(R.color.surface),
        onSurface = pick(R.color.text),
        surfaceVariant = pick(R.color.surface_variant),
        outline = pick(R.color.outline),
        dividerLine = pick(R.color.outline_soft),
        error = pick(R.color.danger)
    )
}

/** 页面顶部的状态与结果提示，等同于旧 View 页面的状态条。 */
@Composable
fun StatusNote(text: String, failed: Boolean) {
    if (text.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = if (failed) MiuixTheme.colorScheme.onErrorContainer
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/**
 * Compose 页面的基类：提供与 FeatureActivity 相同的连接绑定、
 * 后台任务和状态提示，但界面由 Compose 描述。
 */
abstract class MiuixActivity : AppCompatActivity() {
    /** 打开页面时的连接，操作不会跟随电脑切换。 */
    protected val origin: AgentClient? by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }

    protected val noteText = mutableStateOf("")
    protected val noteFailed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        AppVisibility.install(application)
        origin
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
            topBar = {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(painterResource(R.drawable.ic_back), "返回")
                        }
                    }
                )
            }
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            ) {
                StatusNote(noteText.value, noteFailed.value)
                content()
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    /** 顶栏返回键的行为，子类可覆盖（例如先提示未保存内容）。 */
    protected open fun onBack() {
        finish()
    }

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

    /** 系统确认框内的纵向容器。 */
    protected fun dialogBox(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(px(20), 0, px(20), 0)
    }

    protected fun task(block: suspend () -> Unit): Job = lifecycleScope.launch {
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
// 对应旧 View 版 Ui.kt 的作用。

/** 卡片分区：可选分区标题 + 圆角卡片。 */
@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SmallTitle(title)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) { content() }
}

/** 列表条目：标题 + 说明，可点击。 */
@Composable
fun RowItem(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    SuperArrow(title = title, summary = summary, enabled = enabled, onClick = onClick)
}

/** 只读的列表条目：展示状态，不可点击。 */
@Composable
fun InfoItem(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary)
}

/** 说明文字。 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
        modifier = modifier.padding(vertical = 4.dp),
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

/** 带名称的输入框。 */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    )
}

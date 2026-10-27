package com.aicode.lancmd

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 界面迁移到 miuix（MIUI 风格的 Compose 组件库）的公共部分。
 *
 * 迁移按页进行，期间 View 页面与 Compose 页面共存：
 * 已迁移的页面继承 [MiuixActivity] 用 Compose 描述界面，
 * 未迁移的页面仍继承 FeatureActivity。
 */
@Composable
fun LanCmdMiuixTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
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

    protected fun status(text: String, failed: Boolean = false) {
        noteText.value = text
        noteFailed.value = failed
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

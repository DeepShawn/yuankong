package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aicode.lancmd.net.ConnectionManager

internal object SharedWebLink {
    fun extract(text: String?): String? {
        if (text == null || text.length > 16384) return null
        return Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).map { match ->
            match.value.trimEnd('.', ',', ')', ']', '}', '。', '，', '）', '】')
        }.firstOrNull { url ->
            val uri = Uri.parse(url)
            url.length <= 8192 && uri.scheme?.lowercase() in listOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && url.none { it.code < 32 }
        }
    }
}

/** The exported share receiver only stages a URL; it never executes an incoming intent. */
class ShareWebActivity : MiuixActivity() {
    private var url by mutableStateOf("")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        url = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            SharedWebLink.extract(intent.getStringExtra(Intent.EXTRA_TEXT)).orEmpty() else ""
        content { Page("发送手机网页到电脑") {
            SectionCard {
                if (url.isBlank()) EmptyNote("分享内容中没有有效的 HTTP / HTTPS 网页链接。")
                else {
                    InfoItem("将发送的网址", url)
                    Action("选择电脑浏览器并发送", primary = true) {
                        if (ConnectionManager.isConnected()) {
                            startActivity(Intent(this@ShareWebActivity, BrowserActivity::class.java).putExtra("shared_url", url))
                            finish()
                        } else status("请先连接电脑，再返回本页继续发送。", true)
                    }
                    Action("连接电脑") { startActivity(Intent(this@ShareWebActivity, MainActivity::class.java)) }
                }
                Note("在手机浏览器选择分享 → LanCmd。只传递网址；登录状态、表单和网页播放进度由两端浏览器分别管理。")
            }
        } }
        status("确认网址后选择电脑浏览器；尚未向电脑发送。")
    }
}

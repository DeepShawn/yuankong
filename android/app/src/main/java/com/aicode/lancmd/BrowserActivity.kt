package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 向电脑发送网址，并保存常用网址。界面使用 miuix。 */
class BrowserActivity : MiuixActivity() {
    private val prefs by lazy { getSharedPreferences("browser_links", MODE_PRIVATE) }

    private var address by mutableStateOf("")
    private var linkName by mutableStateOf("")
    private data class Browser(val id: String, val name: String)
    private var browsers by mutableStateOf(listOf(Browser("default", "Windows 默认浏览器")))
    private var selectedBrowser by mutableStateOf("default")
    private var links by mutableStateOf<List<Pair<String, String>>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = savedInstanceState?.getString("address").orEmpty()
        linkName = savedInstanceState?.getString("linkName").orEmpty()
        selectedBrowser = savedInstanceState?.getString("browser") ?: "default"
        status("发送 HTTP / HTTPS 网址，由电脑浏览器打开。浏览器窗口会按打开网页的正常行为显示。")
        links = readLinks()
        content { Page() }
        discover()
    }

    @Composable
    private fun Page() {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "电脑浏览器",
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(painterResource(R.drawable.ic_back), "返回")
                        }
                    }
                )
            }
        ) { insets ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth().padding(insets).verticalScroll(rememberScrollState())
            ) {
                StatusNote(noteText.value, noteFailed.value)
                SmallTitle("发送网址")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        label = "网址",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    ChoiceField(
                        items = browsers.map { it.name },
                        title = "使用浏览器",
                        selectedIndex = browsers.indexOfFirst { it.id == selectedBrowser }.coerceAtLeast(0),
                        onSelectedIndexChange = { index -> browsers.getOrNull(index)?.let { selectedBrowser = it.id } }
                    )
                    TextButton(
                        text = "在电脑打开网址",
                        onClick = { open(address) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                }
                SmallTitle("保存到手机")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextField(
                        value = linkName,
                        onValueChange = { linkName = it },
                        label = "名称（留空用网址主机名）",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(text = "保存当前网址", onClick = { saveLink() }, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(10.dp))
                        TextButton(text = "刷新已安装浏览器", onClick = { discover() }, modifier = Modifier.weight(1f))
                    }
                }
                SmallTitle("手机保存的网址")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    if (links.isEmpty()) {
                        Text(
                            text = "暂无保存的网址，填好网址后点「保存当前网址」。",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    links.forEachIndexed { index, link ->
                        SuperArrow(
                            title = link.first,
                            summary = link.second,
                            onClick = { address = link.second; open(link.second) }
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            TextButton(text = "删除", onClick = { removeLink(index) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    text = "最多保存 50 个网址。仅支持 HTTP / HTTPS。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }

    private fun discover() = task {
        val result = pinnedCall(origin, "browser")
        val items = result.getJSONArray("items")
        val found = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) null else Browser(id, name)
            }
        }
        browsers = (listOf(Browser("default", "Windows 默认浏览器")) + found).distinctBy { it.id }
        if (browsers.none { it.id == selectedBrowser }) selectedBrowser = "default"
        status("已读取 ${items.length()} 个注册浏览器")
    }

    private fun normalized(value: String): String {
        val input = value.trim()
        return if (input.contains("://")) input else "https://$input"
    }

    private fun open(value: String) {
        val url = normalized(value)
        task {
            pinnedCall(origin, "browser", JSONObject().put("action", "open").put("url", url)
                .put("browser", selectedBrowser))
            status("已发送到电脑：$url")
        }
    }

    private fun readLinks(): List<Pair<String, String>> {
        val data = runCatching { JSONArray(prefs.getString("links", "[]")) }.getOrElse { JSONArray() }
        return (0 until data.length()).map {
            val item = data.getJSONObject(it)
            item.getString("name") to item.getString("url")
        }
    }

    private fun writeLinks(values: List<Pair<String, String>>) {
        val data = JSONArray()
        values.forEach { data.put(JSONObject().put("name", it.first).put("url", it.second)) }
        prefs.edit().putString("links", data.toString()).apply()
        links = values
    }

    private fun saveLink() {
        val value = normalized(address)
        val parsed = android.net.Uri.parse(value)
        if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrEmpty()) {
            status("请输入 HTTP / HTTPS 网址", true)
            return
        }
        if (links.size >= 50) {
            status("最多保存 50 个网址", true)
            return
        }
        val name = linkName.trim().take(80).ifBlank { parsed.host!! }
        writeLinks(links + (name to value))
        linkName = ""
        status("已保存：$name")
    }

    private fun removeLink(index: Int) {
        if (index !in links.indices) return
        writeLinks(links.filterIndexed { position, _ -> position != index })
        status("已删除保存的网址")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("address", address); outState.putString("linkName", linkName)
        outState.putString("browser", selectedBrowser)
        super.onSaveInstanceState(outState)
    }
}

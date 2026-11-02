package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * 公网联机：电脑用 frpc 拨号到 frps，手机连「节点地址:远程端口」。
 *
 * 电脑仍然只监听本机与局域网，由 frp 把远程端口映射到接收端端口；
 * 手机侧不需要改协议，用首页的「手动输入电脑地址」填外网地址即可。
 */
class TunnelActivity : MiuixActivity() {
    internal var server by mutableStateOf("")
    internal var port by mutableStateOf("7000")
    internal var token by mutableStateOf("")
    internal var remote by mutableStateOf("")
    internal var local by mutableStateOf("")
    internal var proxy by mutableStateOf("lancmd")
    internal var pasted by mutableStateOf("")
    internal var autoStart by mutableStateOf(false)
    internal var tls by mutableStateOf(true)
    internal var enabled by mutableStateOf(false)
    internal var provider by mutableStateOf("chmlfrp")

    internal var running by mutableStateOf(false)
    internal var connected by mutableStateOf(false)
    internal var endpoint by mutableStateOf("")
    internal var log by mutableStateOf<List<String>>(emptyList())
    internal var lastError by mutableStateOf("")
    internal var hint by mutableStateOf("")
    internal var busy by mutableStateOf(false)
    internal var supported by mutableStateOf(true)
    internal var loaded by mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status("填写 ChmlFrp 节点并启动；手机改用「手动输入电脑地址」连外网端口")
        content { Screen() }
        task {
            val caps = pinnedCall(origin, "capabilities").optJSONArray("features")
            supported = caps != null && (0 until caps.length()).any { caps.optString(it) == "tunnel" }
            if (!supported) {
                status("请完整更新 Windows 接收端后使用公网联机", true)
                return@task
            }
            refresh()
        }
    }

    @Composable
    private fun Screen() = Page("公网联机（内网穿透）") {
        SectionCard("当前状态") {
            InfoItem(if (running) "frpc 运行中" else "frpc 未运行",
                buildString {
                    if (connected) append("已登录 frps 并建立映射")
                    else if (running) append("正在连接 frps…")
                    else append("电脑只在本机与局域网可访问")
                    if (endpoint.isNotBlank()) append(" · 外网地址 $endpoint")
                })
            if (lastError.isNotBlank()) Note("最近错误：$lastError")
            ActionRow {
                Action("刷新", enabled = !busy, modifier = Modifier.weight(1f)) { refresh() }
                Action("启动", enabled = !busy && supported, modifier = Modifier.weight(1f)) { control("start") }
                Action("停止", enabled = !busy && running, modifier = Modifier.weight(1f)) { control("stop") }
            }
            if (log.isNotEmpty()) Note(log.takeLast(6).joinToString("\n"))
        }
        SectionCard("服务端") {
            Field("节点地址", server, { server = it })
            Field("节点端口（默认 7000）", port, { port = it })
            Field("用户令牌", token, { token = it }, password = true)
            Note("令牌留空表示保留已保存的值；只有更换账号时才需要重填。")
            Field("隧道名称", proxy, { proxy = it })
            Field("远端端口（外网访问端口）", remote, { remote = it })
            Field("本地端口（留空用接收端端口）", local, { local = it })
            SelectionRow("开启 TLS", "ChmlFrp 节点通常需要", tls, { tls = it })
            SelectionRow("接收端启动时自动拨号", "开机自启时一并建立隧道", autoStart, { autoStart = it })
            SelectionRow("启用公网联机", "关闭后即使 frpc 在运行也不对外声明", enabled, { enabled = it })
            ActionRow {
                Action("查询节点", enabled = !busy && supported, modifier = Modifier.weight(1f)) { probe() }
                Action("保存并应用", enabled = !busy && supported, primary = true, modifier = Modifier.weight(1f)) { save() }
            }
            if (hint.isNotBlank()) Note(hint)
        }
        SectionCard("直接粘贴配置") {
            Field("frpc 配置（留空则按上面的字段生成）", pasted, { pasted = it }, singleLine = false, maxLines = 6)
            Note("从 ChmlFrp 面板复制的 frpc.ini / frpc.toml 可以整段贴进来，节点附加参数不会丢。填写后上面的生成配置不再使用。")
        }
        SectionCard("使用说明") {
            Note("1. 电脑目录里放 frpc.exe，或放在 frp 子目录；没有客户端时本页只能保存设置。\n" +
                 "2. 在 ChmlFrp 建一条 TCP 隧道：内网端口填接收端端口（默认 47823），记下节点与远端端口。\n" +
                 "3. 启动后把「外网地址」填到首页「手动输入电脑地址」，手机用移动网络也能连。\n" +
                 "4. 隧道流量经过第三方节点，握手没有额外加密，敏感环境请用自建 frps。")
        }
    }

    private fun refresh() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "tunnel")
                render(result)
            } finally { busy = false }
        }
    }

    private fun render(result: JSONObject) {
        running = result.optBoolean("running")
        connected = result.optBoolean("connected")
        endpoint = result.optString("endpoint")
        lastError = result.optString("last_error")
        val lines = result.optJSONArray("log") ?: JSONArray()
        log = (0 until lines.length()).map { lines.optString(it) }
        val providers = result.optJSONArray("providers") ?: JSONArray()
        for (index in 0 until providers.length()) {
            val item = providers.getJSONObject(index)
            if (item.optString("id") == provider) hint = item.optString("hint")
        }
        val config = result.optJSONObject("config") ?: JSONObject()
        if (!loaded) {
            server = config.optString("server_addr")
            port = config.optInt("server_port", 7000).toString()
            token = config.optString("token").let { if (it == "********") "" else it }
            remote = config.optInt("remote_port", 0).takeIf { it > 0 }?.toString() ?: ""
            local = config.optInt("local_port", 0).takeIf { it > 0 }?.toString() ?: ""
            proxy = config.optString("proxy", "lancmd")
            autoStart = config.optBoolean("auto_start")
            tls = config.optBoolean("tls", true)
            enabled = config.optBoolean("enabled")
            provider = config.optString("provider", "chmlfrp")
            pasted = config.optString("config_text")
            loaded = true
        }
    }

    private fun params(): JSONObject = JSONObject()
        .put("server_addr", server.trim()).put("server_port", port.trim().toIntOrNull() ?: 0)
        .put("token", token.trim()).put("remote_port", remote.trim().toIntOrNull() ?: 0)
        .put("local_port", local.trim().toIntOrNull() ?: 0).put("proxy", proxy.trim())
        .put("provider", provider).put("tls", tls).put("auto_start", autoStart)
        .put("enabled", enabled).put("config_text", pasted)

    private fun save() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "tunnel", params().put("action", "save"), timeout = 60_000)
                if (result.optBoolean("saved")) status("已保存")
                render(result)
                status(if (running) "设置已保存，frpc 已按新配置重启" else "设置已保存，点「启动」建立隧道")
            } finally { busy = false }
        }
    }

    private fun control(action: String) {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "tunnel", JSONObject().put("action", action), timeout = 60_000)
                render(result)
                status(when {
                    action == "stop" -> "已停止，电脑恢复为只在本机与局域网可访问"
                    result.optBoolean("connected") -> "隧道已建立：$endpoint"
                    else -> "frpc 已启动，正在登录节点；可稍后刷新状态"
                })
            } finally { busy = false }
        }
    }

    private fun probe() {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "tunnel",
                    params().put("action", "probe"), timeout = 40_000)
                val address = result.optString("ip").ifBlank { result.optString("node") }
                status("节点在线：$address:${result.optInt("port")} · frpc ${result.optString("version")}")
            } finally { busy = false }
        }
    }
}

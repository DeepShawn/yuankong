package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Explicit startup/connection settings; Windows system protections stay in force. */
class StartupNetworkActivity : MiuixActivity() {
    internal var autostart by mutableStateOf(false)
    internal var autoHotspot by mutableStateOf(false)
    internal var autoFirewall by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var supported by mutableStateOf(false)
    internal var hotspot by mutableStateOf("尚未读取")
    private var directory by mutableStateOf("")
    private var registration by mutableStateOf("")
    private var retention by mutableStateOf("")
    private var confirm by mutableStateOf(false)
    private var restoredDraft = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        restoredDraft = state != null
        autostart=state?.getBoolean("autostart") ?: false
        autoHotspot=state?.getBoolean("hotspot") ?: false
        autoFirewall=state?.getBoolean("firewall") ?: false
        content {
            Page("启动与网络") {
                SectionCard("电脑当前状态") {
                    InfoItem("接收端目录", directory.ifBlank { "正在读取…" })
                    InfoItem("当前用户自启动", registration.ifBlank { "正在读取…" })
                    InfoItem("Windows 移动热点", hotspot)
                    Action("刷新实际状态", enabled=!busy) { refresh() }
                    Action("热点、Wi-Fi 与蓝牙管理") { startActivity(android.content.Intent(this@StartupNetworkActivity,NetworkManagementActivity::class.java)) }
                }
                SectionCard("启动后自动执行") {
                    SelectionRow("开机自启动", "当前用户登录 Windows 后运行本目录接收端", autostart,
                        { autostart=it }, enabled=supported && !busy)
                    SelectionRow("自动开启移动热点", "沿用 Windows 设置中的热点名称和密码", autoHotspot,
                        { autoHotspot=it }, enabled=supported && !busy)
                    SelectionRow("自动放行局域网端口", "仅本接收端程序的 TCP / UDP 端口；防火墙保持开启", autoFirewall,
                        { autoFirewall=it }, enabled=supported && !busy)
                    Action("保存并应用", enabled=supported && !busy, primary=true) { confirm=true }
                    Action("立即执行已保存设置", enabled=supported && !busy) { request("apply") }
                    Note("端口放行需要管理员接收端；不会自动弹出提权。移动热点受无线网卡、网络连接与系统策略支持限制，状态以 Windows 实际结果为准。")
                }
                SectionCard("C 盘重启还原") {
                    Note(retention.ifBlank { "重启会还原 C 盘时，需要在还原软件允许的维护模式或保留策略中保存自启动项。" })
                    Note("自动执行开关保存在接收端目录的 startup-network.json。热点名称和密码可在「网络与蓝牙」管理，请让还原策略保留对应 Windows 配置。")
                }
            }
            if(confirm) ConfirmAction("应用启动与网络设置？",
                "开机自启：${if(autostart)"开启" else "关闭"}\n自动开启热点：${if(autoHotspot)"开启" else "关闭"}\n自动放行接收端局域网端口：${if(autoFirewall)"开启" else "关闭"}\n\n保存后立即应用；热点或网卡状态改变可能需要手机重新连接。关闭自动开关不会关闭当前热点，也不会移除既有防火墙规则。",
                confirm="保存并应用", onDismiss={confirm=false}, onConfirm={confirm=false;applySettings()})
        }
        task {
            val caps=pinnedCall(origin,"capabilities").optJSONArray("features")
            supported=caps!=null && (0 until caps.length()).any { caps.optString(it)=="startup_network" }
            if(!supported) { status("请完整更新 Windows 接收端后使用启动与网络设置",true);return@task }
            val result=pinnedCall(origin,"startup_network")
            render(result, !restoredDraft)
            if(result.optJSONObject("job")?.optString("status")=="running") monitor()
        }
    }

    private fun render(result:JSONObject, settings:Boolean=false) {
        directory=result.optString("directory")
        retention=result.optString("persistence_notice")
        val registered=result.optJSONObject("autostart") ?: JSONObject()
        registration=if(registered.optBoolean("enabled"))"已登记本目录接收端" else if(registered.optBoolean("other_installation"))"启动项指向其他目录：${registered.optString("command")}" else "尚未登记本目录接收端"
        if(settings) {
            val saved=result.optJSONObject("settings") ?: JSONObject()
            autostart=registered.optBoolean("enabled")
            autoHotspot=saved.optBoolean("auto_hotspot");autoFirewall=saved.optBoolean("auto_firewall")
        }
        val network=result.optJSONObject("hotspot") ?: JSONObject()
        hotspot=when(network.optString("state")) {
            "On" -> "已开启 · ${network.optString("name")} · ${network.optInt("clients")} 个客户端"
            "Off" -> "已关闭"
            "InTransition" -> "Windows 正在切换热点状态"
            "Unavailable" -> network.optString("error","当前系统或网卡不可用")
            else -> "尚未读取；可刷新实际状态"
        }
        val job=result.optJSONObject("job")
        when(job?.optString("status")) {
            "running" -> status(job.optString("phase","正在处理启动与网络设置…"))
            "failed" -> status("设置未全部应用：${job.optString("error")}",true)
            "completed" -> status("启动与网络操作已完成，以页面显示的实际状态为准")
        }
        if(result.optString("warning").isNotBlank())status(result.optString("warning"),true)
    }

    internal fun applySettings() = request("configure", JSONObject().put("autostart",autostart)
        .put("auto_hotspot",autoHotspot).put("auto_firewall",autoFirewall))

    internal fun refresh() = request("refresh")

    private fun request(action:String, settings:JSONObject?=null) {
        if(busy || !supported)return
        busy=true
        task { try {
            val result=pinnedCall(origin,"startup_network",JSONObject().put("action",action).apply { if(settings!=null)put("settings",settings) })
            render(result)
            monitor()
        } finally { busy=false } }
    }

    private suspend fun monitor() {
        busy=true
        try {
            while(true) {
                val result=pinnedCall(origin,"startup_network")
                render(result)
                if(result.optJSONObject("job")?.optString("status")!="running")break
                delay(800)
            }
        } finally { busy=false }
    }

    override fun onSaveInstanceState(state:Bundle) {
        state.putBoolean("autostart",autostart);state.putBoolean("hotspot",autoHotspot);state.putBoolean("firewall",autoFirewall)
        super.onSaveInstanceState(state)
    }
}

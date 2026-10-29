package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Windows owns long operations. Only non-secret job identifiers survive navigation. */
class NetworkManagementActivity : MiuixActivity() {
    internal var area by mutableStateOf("hotspot")
    internal var supported by mutableStateOf(false)
    private var hardwareSupported by mutableStateOf(false)
    private var accessSupported by mutableStateOf(false)
    private var devicesExpanded by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var job by mutableStateOf<JSONObject?>(null)
    internal val snapshots = mutableStateMapOf<String, JSONObject>()
    internal var hotspotName by mutableStateOf("")
    internal var hotspotPassword by mutableStateOf("")
    internal var wifiPassword by mutableStateOf("")
    internal var pairingPin by mutableStateOf("")
    private var band by mutableStateOf("auto")
    private var wifiSelection by mutableStateOf<JSONObject?>(null)
    private var wifiInterface by mutableStateOf("")
    private var adapterSelection by mutableStateOf<JSONObject?>(null)
    private var dns by mutableStateOf("")
    private data class Change(val area:String,val operation:String,val params:JSONObject,val description:String)
    private var confirmation by mutableStateOf<Change?>(null)
    private var owner:AgentClient?=null
    private var peerId=""
    private var peerHost=""
    private var peerPort=0
    private var pendingId=""
    private var pendingRequest=""
    private var pendingArea=""
    private var monitor:Job?=null
    private val pendingPrefs by lazy {getSharedPreferences("network-pending",MODE_PRIVATE)}

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        owner=origin
        peerId=state?.getString("peer") ?: Prefs.target(this)?.agentId.orEmpty()
        peerHost=state?.getString("host") ?: origin?.host.orEmpty()
        peerPort=state?.getInt("port") ?: origin?.port ?: 0
        area=state?.getString("area")?.takeIf {it in areas} ?: "hotspot"
        val stored=runCatching{JSONObject(pendingPrefs.getString("pending","{}")!!)}.getOrDefault(JSONObject())
        val same=if(peerId.isNotBlank())stored.optString("peer")==peerId
            else stored.optString("host")==peerHost && stored.optInt("port")==peerPort
        if(same) {
            pendingId=stored.optString("job");pendingRequest=stored.optString("request")
            pendingArea=stored.optString("area").takeIf{it in areas}.orEmpty()
            if(pendingArea.isNotBlank())area=pendingArea
        }
        content {
            val listState=rememberLazyListState()
            LaunchedEffect(area,wifiSelection,adapterSelection){listState.scrollToItem(0)}
            FixedPage("网络与蓝牙") {
                ActionRow {areas.forEachIndexed {index,key ->
                    SelectionAction(labels[index],selected=area==key,modifier=Modifier.weight(1f)) {selectArea(key)}
                }}
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState) {
                    item {SectionCard {
                        Action(if(busy)"正在处理，可等待或返回" else "刷新设备与连接",enabled=supported && !busy,primary=true){refresh()}
                        if(!supported)Note("需要完整更新 Windows 接收端，设备能力以电脑实际结果为准。")
                        if(job!=null)Note(job!!.optString("message"))
                        snapshots[area]?.optString("error")?.takeIf{it.isNotBlank()}?.let{Note(it)}
                    }}
                    val snapshot=snapshots[area] ?: JSONObject()
                    item {HardwareControls(snapshot)}
                    when(area) {
                        "hotspot" -> {
                            item {HotspotControls(snapshot)}
                            items(snapshot.rows("clients"),key={it.optString("address")}) {client ->SectionCard {
                                InfoItem(client.optString("address"),client.strings("hosts").joinToString("\n").ifBlank{"已连接热点的设备"})
                                if(client.optBoolean("blocked"))Note(if(client.optBoolean("access_active"))"禁止联网规则已安装，Wi-Fi 可能仍显示已连接" else "已保存限制，但当前规则未生效")
                                Action(if(client.optBoolean("blocked"))"恢复联网" else "禁止联网",enabled=canChange() && accessSupported) {
                                    val blocked=!client.optBoolean("blocked")
                                    ask("client_access",JSONObject().put("address",client.optString("address")).put("blocked",blocked),
                                        if(blocked)"按 MAC 禁止此设备通过热点通信，设备仍可能显示 Wi-Fi 已连接。若选择的是当前控制手机，远控也会断开。" else "移除此 MAC 的热点联网限制。")
                                }
                            }}
                            val connected=snapshot.rows("clients").map{it.optString("address")}.toSet()
                            items(snapshot.rows("client_access").filter{it.optString("address") !in connected},key={"access:"+it.optString("address")}) {rule ->SectionCard("已保存的客户端限制") {
                                InfoItem(rule.optString("address"),if(rule.optBoolean("active"))"禁止联网规则已安装" else "限制待生效，开启热点后自动恢复")
                                Action("恢复联网",enabled=canChange() && accessSupported) {
                                    ask("client_access",JSONObject().put("address",rule.optString("address")).put("blocked",false),"移除该 MAC 的热点联网限制，设备重新连接时不再应用此限制。")
                                }
                            }}
                        }
                        "wifi" -> {
                            item {RadioControls(snapshot)}
                            wifiSelection?.let {network ->item {SectionCard("连接 Wi-Fi") {
                                InfoItem(network.optString("ssid"),"${network.optString("auth_name")} · 密码只用于此电脑的 Wi-Fi 连接")
                                SecretField("连接密码（已保存可留空）",wifiPassword,{wifiPassword=it.take(63)})
                                ActionRow {
                                    Action("连接 / 更新密码",enabled=canChange(),primary=true,modifier=Modifier.weight(1f)) {
                                        ask("connect",JSONObject().put("interface",wifiInterface).put("profile",network.optString("profile"))
                                            .put("name",network.optString("ssid")).put("ssid_hex",network.optString("ssid_hex"))
                                            .put("auth",network.optInt("auth")).put("password",wifiPassword),"连接 ${network.optString("ssid")}，可能断开手机当前连接。")
                                    }
                                    Action("收起",modifier=Modifier.weight(1f)){wifiSelection=null;wifiPassword=""}
                                }
                            }}}
                            snapshot.rows("interfaces").forEach {wifi ->
                                item {SectionCard(wifi.optString("name")) {
                                    InfoItem(wifi.optString("state"),wifi.optString("error"))
                                    ActionRow {
                                        Action("扫描",enabled=supported && !busy,modifier=Modifier.weight(1f)){refresh(true,wifi.optString("id"))}
                                        Action("断开 Wi-Fi",enabled=canChange(),modifier=Modifier.weight(1f)) {
                                            ask("disconnect",JSONObject().put("interface",wifi.optString("id")),"断开此网卡的 Wi-Fi，手机可能需要重新连接。")
                                        }
                                    }
                                    Note("扫描需要数秒，完成后可再次刷新；新版本 Windows 的附近网络权限由系统管理。")
                                }}
                                items(wifi.rows("networks"),key={wifi.optString("id")+it.optString("ssid_hex")+it.optString("profile")+it.optInt("auth")}) {network ->SectionCard {
                                    RowItem(network.optString("ssid"),"${if(network.optBoolean("connected"))"已连接 · " else ""}${network.optInt("signal")}% · ${network.optString("auth_name")}${if(network.optBoolean("saved"))" · 已保存" else ""}",enabled=canChange()) {
                                        wifiInterface=wifi.optString("id");wifiSelection=network;wifiPassword=""
                                    }
                                }}
                                items(wifi.rows("profiles"),key={wifi.optString("id")+"saved:"+it.optString("name")}) {profile ->SectionCard {
                                    InfoItem("已保存：${profile.optString("name")}",if(profile.optBoolean("policy"))"由组织策略管理" else "密码保留在 Windows，不读取或回传")
                                    ActionRow {
                                        Action("连接",enabled=canChange(),modifier=Modifier.weight(1f)) {
                                            ask("connect",JSONObject().put("interface",wifi.optString("id")).put("profile",profile.optString("name")),"使用电脑上已保存的 Wi-Fi 配置连接，可能中断当前连接。")
                                        }
                                        Action("忘记",enabled=canChange() && !profile.optBoolean("policy"),modifier=Modifier.weight(1f)) {
                                            ask("forget",JSONObject().put("interface",wifi.optString("id")).put("profile",profile.optString("name")),"从当前用户删除此 Wi-Fi 配置，下次连接需要重新输入密码。")
                                        }
                                    }
                                }}
                            }
                            if(snapshot.has("interfaces") && snapshot.rows("interfaces").isEmpty())item{EmptyNote("未发现 Windows WLAN 网卡")}
                        }
                        "bluetooth" -> {
                            item {RadioControls(snapshot)}
                            item {SectionCard {Note("刷新会搜索可发现的蓝牙设备。配对时请在手机核对验证码；音频、鼠标等连接由 Windows 对应设备服务建立，配对成功不等于已经连接。")}}
                            job?.optJSONObject("question")?.let {question ->item {SectionCard("蓝牙配对验证") {
                                InfoItem(when(question.optString("kind")){"ProvidePin"->"输入设备上的 PIN";"DisplayPin"->"在蓝牙设备上输入此代码";else->"确认正在配对的设备"},question.optString("display_code"))
                                if(question.optString("kind")=="ProvidePin")SecretField("配对 PIN",pairingPin,{pairingPin=it.take(16)})
                                Note("请核对设备和代码，未确认会超时取消。")
                                ActionRow {
                                    Action("拒绝",modifier=Modifier.weight(1f)){answerPairing(false)}
                                    Action("确认配对",primary=true,modifier=Modifier.weight(1f)){answerPairing(true)}
                                }
                            }}}
                            items(snapshot.rows("devices"),key={it.optString("id")}) {device ->SectionCard {
                                InfoItem(device.optString("name").ifBlank{"未命名蓝牙设备"},"${device.optString("address")}\n${if(device.optBoolean("paired"))"已配对" else "未配对"} · ${if(device.optBoolean("connected"))"已连接" else "未连接"}")
                                val paired=device.optBoolean("paired")
                                Action(if(paired)"取消配对" else "配对设备",enabled=canChange() && (paired || device.optBoolean("can_pair"))) {
                                    ask(if(paired)"unpair" else "pair",JSONObject().put("id",device.optString("id")),
                                        if(paired)"移除此设备的配对关系，键盘、鼠标或网络设备可能断开。" else "与 ${device.optString("name")} 配对，请保持此页以完成验证。")
                                }
                            }}
                            if(snapshot.has("devices") && snapshot.rows("devices").isEmpty())item{EmptyNote("未发现设备，请开启设备蓝牙及可发现模式后刷新")}
                        }
                        "adapters" -> {
                            adapterSelection?.let {adapter ->item {SectionCard("DNS · ${adapter.optString("name")}") {
                                Field("DNS 地址，以逗号分隔；留空恢复自动",dns,{dns=it},singleLine=false,maxLines=3)
                                Action("应用 DNS",enabled=canChange(),primary=true) {
                                    val servers=dns.split(',', '，','\n',' ').map{it.trim()}.filter{it.isNotEmpty()}
                                    ask("dns",JSONObject().put("adapter",adapter.optString("id")).put("servers",JSONArray(servers)),"更改此网卡 DNS，可能影响域名解析。留空时恢复 Windows 自动 DNS。")
                                }
                            }}}
                            items(snapshot.rows("adapters"),key={it.optString("id")}) {adapter ->SectionCard {
                                InfoItem(adapter.optString("name"),"${adapter.optString("description")}\n${adapter.optString("status")} · ${adapter.optString("speed")} · ${adapter.optString("mac")}")
                                InfoItem("IP 地址",(adapter.strings("ipv4")+adapter.strings("ipv6")).joinToString("\n").ifBlank{"无地址"})
                                if(adapter.optString("address_notice").isNotBlank())Note(adapter.optString("address_notice"))
                                InfoItem("网关 / DNS",adapter.strings("gateways").joinToString()+"\n"+adapter.strings("dns").joinToString())
                                ActionRow {
                                    Action("启用",enabled=canChange(),modifier=Modifier.weight(1f)){ask("enable",JSONObject().put("adapter",adapter.optString("id")),"启用 ${adapter.optString("name")}。需要电脑已有管理员权限。")}
                                    Action("停用",enabled=canChange(),modifier=Modifier.weight(1f)){ask("disable",JSONObject().put("adapter",adapter.optString("id")),"停用 ${adapter.optString("name")}，可能立刻断开手机连接。")}
                                }
                                ActionRow {
                                    Action("修改 DNS",enabled=canChange(),modifier=Modifier.weight(1f)){adapterSelection=adapter;dns=adapter.strings("dns").joinToString(", ")}
                                    Action("DHCP 续租",enabled=canChange(),modifier=Modifier.weight(1f)){ask("renew",JSONObject().put("adapter",adapter.optString("id")),"向 DHCP 服务器续租此网卡地址，可能短暂断开连接。")}
                                }
                            }}
                        }
                    }
                }
            }
            confirmation?.let {change ->ConfirmAction("确认网络操作？",change.description+"\n操作由电脑计时，手机断线后不会自动重复提交。",confirm="执行",onDismiss={confirmation=null},onConfirm={confirmation=null;submit(change.area,change.operation,change.params)})}
        }
        task {
            val caps=pinnedCall(owner,"capabilities").optJSONArray("features")
            supported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="network_management"}
            hardwareSupported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="network_device_state"}
            accessSupported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="hotspot_client_access"}
            if(!supported){status("请完整更新 Windows 接收端后使用网络与蓝牙",true);return@task}
            if(pendingArea.isNotBlank())watchPending() else refresh()
        }
    }

    @Composable private fun SecretField(label:String,value:String,onChange:(String)->Unit) =
        Field(label,value,onChange,password=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))

    @Composable private fun HotspotControls(snapshot:JSONObject) {
        SectionCard("移动热点") {
            InfoItem(when(snapshot.optString("state")){"On"->"热点已开启";"Off"->"热点已关闭";"InTransition"->"正在切换热点状态";else->"尚未取得热点状态"},
                "${snapshot.optString("name")} · ${snapshot.optInt("client_count")} / ${snapshot.optInt("max_clients")} 台设备")
            ActionRow {
                Action("开启",enabled=canChange(),modifier=Modifier.weight(1f)){ask("start",JSONObject(),"开启 Windows 移动热点。")}
                Action("关闭",enabled=canChange(),modifier=Modifier.weight(1f)){ask("stop",JSONObject(),"关闭移动热点，所有热点客户端都会断开。")}
                Action("重启",enabled=canChange(),modifier=Modifier.weight(1f)){ask("restart",JSONObject(),"重启热点，所有客户端会暂时断开。")}
            }
            Field("热点名称",hotspotName,{hotspotName=it.take(32)})
            SecretField("新密码（留空沿用原密码）",hotspotPassword,{hotspotPassword=it.take(63)})
            ActionRow {listOf("auto","2.4","5").forEach {key ->SelectionAction(if(key=="auto")"自动频段" else "$key GHz",band==key,
                enabled=key=="auto" || snapshot.optBoolean("band_supported"),modifier=Modifier.weight(1f)){band=key}}}
            Action("保存热点配置",enabled=canChange(),primary=true){ask("configure",JSONObject().put("name",hotspotName).put("password",hotspotPassword).put("band",band),"修改热点名称、密码或频段；已开启的热点会重启，客户端需重新连接。")}
            Note("密码为 8–63 个可打印 ASCII 字符，不读取旧密码。单台设备可按 MAC 禁止联网，Wi-Fi 仍可能显示已连接；更换随机 MAC 后需重新设置。")
            if(!accessSupported)Note("单个客户端联网限制需要完整更新并重启 Windows 接收端")
            snapshot.optString("access_error").takeIf{it.isNotBlank()}?.let{Note(it)}
        }
    }

    @Composable private fun RadioControls(snapshot:JSONObject) {
        SectionCard("无线开关") {
            snapshot.rows("radios").forEach {radio ->
                InfoItem(radio.optString("name"),radio.optString("state"))
                ActionRow {
                    Action("开启",enabled=canChange(),modifier=Modifier.weight(1f)){ask("radio",JSONObject().put("name",radio.optString("name")).put("enabled",true),"开启此无线设备。")}
                    Action("关闭",enabled=canChange(),modifier=Modifier.weight(1f)){ask("radio",JSONObject().put("name",radio.optString("name")).put("enabled",false),"关闭此无线设备，现有连接可能中断。")}
                }
            }
            if(snapshot.has("radios") && snapshot.rows("radios").isEmpty())Note("没有可由 Windows 管理的无线开关")
        }
    }

    @Composable private fun HardwareControls(snapshot:JSONObject) {
        SectionCard(if(area=="bluetooth")"蓝牙设备级启停" else "电脑网络设备级启停") {
            if(!hardwareSupported)Note("设备级启停需完整更新并重启 Windows 接收端")
            else {
                Action(if(devicesExpanded)"收起设备级启停" else "展开设备级启停"){devicesExpanded=!devicesExpanded}
                if(devicesExpanded) {
                Note("停用会使设备停止工作并可能断开当前连接，需要电脑管理员权限。停用后可在此启用；这与关闭无线开关不同。")
                snapshot.rows("hardware").forEach {device ->
                    InfoItem(device.optString("name").ifBlank{device.optString("id")},
                        "${if(device.optInt("problem")==22)"已停用" else if(device.optBoolean("enabled"))"已启用" else "状态异常"} · 驱动代码 ${device.optInt("problem")}\n${device.optString("error")}")
                    val valid=device.optInt("problem",-1) in listOf(0,22) && device.optString("error").isBlank()
                    ActionRow {
                        Action("启用设备",enabled=canChange() && valid,modifier=Modifier.weight(1f)) {
                            ask("device",JSONObject().put("instance",device.optString("id")).put("enabled",true),"启用 ${device.optString("name")}。")
                        }
                        Action("停用设备",enabled=canChange() && valid,modifier=Modifier.weight(1f)) {
                            ask("device",JSONObject().put("instance",device.optString("id")).put("enabled",false),"停用 ${device.optString("name")}，相关网络或蓝牙设备将断开；若用于当前连接，可能需要在电脑端重新启用。")
                        }
                    }
                }
                snapshot.optString("hardware_error").takeIf{it.isNotBlank()}?.let{Note(it)}
                }
            }
        }
    }

    private fun canChange()=supported && !busy && pendingArea.isBlank()
    private fun ask(operation:String,params:JSONObject,description:String) {
        if(canChange())confirmation=Change(area,operation,params,description)
    }
    internal fun selectArea(key:String) {
        if(key !in areas)return
        area=key
        if(supported && !busy && !snapshots.containsKey(key))refresh()
    }
    private fun connection():AgentClient {
        val client=ConnectionManager.sessionClient()
        val same=if(peerId.isNotBlank())Prefs.target(this)?.agentId==peerId else client.host==peerHost && client.port==peerPort
        if(!same)throw IOException("已切换到另一台电脑，请重新打开此页；未向新电脑发送请求")
        owner=client
        return client
    }
    internal fun render(result:JSONObject) {
        val key=result.optString("area",area)
        result.optJSONObject("snapshot")?.takeIf{it.length()>0}?.let {
            snapshots[key]=it
            if(key=="hotspot" && hotspotName.isBlank())hotspotName=it.optString("name")
        }
        job=result.optJSONObject("job")
        job?.let{status(it.optString("message"),it.optString("status") in listOf("failed","unconfirmed"))}
    }
    internal fun refresh(scan:Boolean=false,interfaceId:String="") {
        if(!supported || busy)return
        val key=area;busy=true
        task {try {
            val response=pinnedCall(connection(),"network",JSONObject().put("area",key).put("action",if(scan)"scan" else "refresh")
                .put("params",JSONObject().put("interface",interfaceId)),6000)
            render(response)
            val id=job?.optString("id").orEmpty()
            if(id.isNotBlank())poll(key,id,"")
        } finally {busy=false}}
    }
    /** Called only after the phone confirmation; never called by reconnect polling. */
    internal fun submit(key:String,operation:String,params:JSONObject) {
        if(!canChange())return
        pendingArea=key;pendingRequest=UUID.randomUUID().toString().replace("-","");pendingId=""
        savePending();busy=true
        hotspotPassword="";wifiPassword="";pairingPin=""
        task {
            try {
                val response=pinnedCall(connection(),"network",JSONObject().put("action","submit").put("area",key).put("operation",operation)
                    .put("params",params).put("confirm",true).put("request_id",pendingRequest),6000)
                render(response);pendingId=job?.optString("id").orEmpty();savePending()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){status("正在核对电脑上的任务：${e.message}；不会重复执行",true)}
            finally {params.remove("password");busy=false;watchPending()}
        }
    }
    private fun savePending() {
        pendingPrefs.edit().putString("pending",JSONObject().put("peer",peerId).put("host",peerHost).put("port",peerPort)
            .put("area",pendingArea).put("job",pendingId).put("request",pendingRequest).toString()).apply()
    }
    private fun clearPending(){pendingId="";pendingRequest="";pendingArea="";pendingPrefs.edit().remove("pending").apply()}
    private fun watchPending() {
        if(pendingArea.isBlank() || monitor?.isActive==true)return
        monitor=task {busy=true;try {poll(pendingArea,pendingId,pendingRequest)} finally {busy=false}}
    }
    private suspend fun poll(key:String,id:String,request:String) {
        while(true) {
            try {
                val response=pinnedCall(connection(),"network",JSONObject().put("area",key).put("action","status")
                    .apply{if(id.isNotBlank())put("job",id);if(request.isNotBlank())put("request_id",request)},6000)
                render(response)
                val current=response.optJSONObject("job")
                if(current==null) {
                    if(request.isNotBlank()){clearPending();status("未找到此次任务，请刷新实际网络状态；没有重复执行",true)}
                    return
                }
                if(current.has("finished")) {
                    if(request.isNotBlank())clearPending()
                    return
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception) {
                if(request.isBlank())throw e
                if(e.message?.contains("任务已失效")==true){clearPending();throw e}
                status("等待原电脑恢复连接并读取任务结果：${e.message}；不会重复执行",true)
            }
            delay(800)
        }
    }
    internal fun answerPairing(accept:Boolean) {
        val current=job ?: return
        val question=current.optJSONObject("question") ?: return
        val pin=pairingPin;pairingPin=""
        task {render(pinnedCall(connection(),"network",JSONObject().put("action","answer").put("area","bluetooth")
            .put("job",current.optString("id")).put("question",question.optString("id")).put("accept",accept).put("pin",pin),6000))}
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("area",area);state.putString("peer",peerId);state.putString("host",peerHost);state.putInt("port",peerPort)
        // Credentials and pending confirmations intentionally remain in memory only.
        super.onSaveInstanceState(state)
    }
    override fun onDestroy(){hotspotPassword="";wifiPassword="";pairingPin="";confirmation=null;super.onDestroy()}
    companion object {
        private val areas=listOf("hotspot","wifi","bluetooth","adapters")
        private val labels=listOf("热点","Wi-Fi","蓝牙","网卡")
    }
}

private fun JSONObject.rows(key:String):List<JSONObject> = optJSONArray(key)?.let{a ->(0 until a.length()).mapNotNull{a.optJSONObject(it)}} ?: emptyList()
private fun JSONObject.strings(key:String):List<String> = optJSONArray(key)?.let{a ->(0 until a.length()).map{a.optString(it)}} ?: emptyList()

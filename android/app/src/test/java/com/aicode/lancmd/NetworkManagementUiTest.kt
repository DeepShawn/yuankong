package com.aicode.lancmd

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkManagementUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before(){disconnect();RuntimeEnvironment.getApplication().getSharedPreferences("network-pending",Context.MODE_PRIVATE).edit().clear().commit()}
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(10,java.util.concurrent.TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)}
        fail("网络管理界面没有完成预期状态")
    }
    private fun enter(page:NetworkManagementActivity) {
        waitFor{page.supported && !page.busy && page.administrator}
        page.managementPassword="test-only-password";page.unlock()
        waitFor{page.authorized && !page.busy && page.snapshots.isNotEmpty()}
    }
    @Test fun allFourAreasRefreshWithoutSendingChanges() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=controller.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.containsKey("hotspot")}
                for(area in listOf("wifi","bluetooth","adapters")){page.selectArea(area);waitFor{!page.busy && page.snapshots.containsKey(area)}}
                assertEquals(setOf("hotspot","wifi","bluetooth","adapters"),page.snapshots.keys.toSet())
                assertFalse(stub.requests.any{it.optString("action")=="submit"})
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun unsupportedReceiverIsNotSentNetworkCommands() {
        Stub(supported=false).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                waitFor{page.note.contains("完整更新 Windows")}
                page.submit("hotspot","start",JSONObject());page.refresh()
                assertFalse(stub.requests.any{it.optString("cmd")=="network"})
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun credentialsAreNotRestoredOrStoredWithJobAndFailureIsShown() {
        Stub(fail=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                page.hotspotPassword="secret<&password";page.wifiPassword="wifi-password";page.pairingPin="9876"
                val state=Bundle();c.saveInstanceState(state)
                assertFalse(state.keySet().any{it.contains("password") || it.contains("pin")})
                page.submit("hotspot","configure",JSONObject().put("name","office").put("password","secret<&password"))
                waitFor{!page.busy && page.note.contains("网卡拒绝")}
                assertEquals("",page.hotspotPassword);assertEquals("",page.wifiPassword);assertEquals("",page.pairingPin)
                assertFalse(page.getSharedPreferences("network-pending",Context.MODE_PRIVATE).all.toString().contains("secret<&password"))
                assertEquals(1,stub.requests.count{it.optString("action")=="submit"})
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun pendingPairingSurvivesRecreationAndNeedsExplicitAnswer() {
        Stub(pair=true).use {stub ->
            stub.install();var c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();var page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                page.selectArea("bluetooth");waitFor{!page.busy && page.snapshots.containsKey("bluetooth")}
                page.submit("bluetooth","pair",JSONObject().put("id","device"))
                waitFor{page.job?.optJSONObject("question")!=null}
                assertFalse(stub.requests.any{it.optString("action")=="answer"})
                val saved=Bundle();c.saveInstanceState(saved);c.pause().stop().destroy()
                c=Robolectric.buildActivity(NetworkManagementActivity::class.java).create(saved).start().resume().visible();page=c.get()
                enter(page)
                waitFor{page.job?.optJSONObject("question")!=null}
                page.pairingPin="123456";page.answerPairing(true)
                waitFor{!page.busy && page.job?.has("finished")==true}
                assertEquals(1,stub.requests.count{it.optString("action")=="submit"})
                assertEquals("123456",stub.requests.single{it.optString("action")=="answer"}.getString("pin"))
                assertEquals("",page.pairingPin)
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun lostSubmissionReplyReconnectsAndLooksUpRequestInsteadOfRepeatingIt() {
        Stub(dropReply=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                page.submit("hotspot","restart",JSONObject())
                waitFor{stub.requests.any{it.optString("action")=="submit"} && stub.client?.closed==true}
                stub.install()
                waitFor{!page.busy && page.job?.has("finished")==true}
                assertEquals(1,stub.requests.count{it.optString("action")=="submit"})
                assertTrue(stub.requests.any{it.optString("action")=="status" && it.optString("request_id").isNotBlank()})
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun deviceDisableIsConfirmedAndSentAsAnExactBluetoothInstance() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                page.selectArea("bluetooth");waitFor{!page.busy && page.snapshots.containsKey("bluetooth")}
                compose.onNodeWithText("展开设备级启停").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("停用设备").performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("operation")=="device"})
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.any{it.optString("operation")=="device"}}
                val q=stub.requests.single{it.optString("operation")=="device"}
                assertEquals("bluetooth",q.getString("area"));assertEquals("bt-device",q.getJSONObject("params").getString("instance"))
                assertFalse(q.getJSONObject("params").getBoolean("enabled"))
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun hotspotClientAccessNeedsPhoneConfirmationAndTargetsOnlyTheSelectedMac() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("禁止联网"))
                compose.onNodeWithText("禁止联网").performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("operation")=="client_access"})
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.any{it.optString("operation")=="client_access"}}
                val q=stub.requests.single{it.optString("operation")=="client_access"}
                assertEquals("hotspot",q.getString("area"));assertEquals("02:21:32:43:54:65",q.getJSONObject("params").getString("address"))
                assertTrue(q.getJSONObject("params").getBoolean("blocked"))
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun hotspotAlreadyOnCanSelectAnAdapterWithoutResubmittingAClientBlock() {
        Stub(discovery=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                compose.onNodeWithText("请开启热点并刷新").assertDoesNotExist()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选择热点网卡"))
                compose.onNodeWithText("选择热点网卡").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("使用 本地连接* 9"))
                compose.onNodeWithText("使用 本地连接* 9").performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("action")=="submit"})
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.any{it.optString("operation")=="hotspot_interface"}}
                val q=stub.requests.single{it.optString("operation")=="hotspot_interface"}
                assertEquals("11111111-2222-3333-4444-555555555555",q.getJSONObject("params").getString("interface"))
                assertFalse(stub.requests.any{it.optString("operation")=="client_access"})
                assertEquals("manual",page.snapshots["hotspot"]!!.getString("adapter_source"))
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun hotspotIdentificationErrorIsDisplayedOnlyOnceWhileTheHotspotIsOn() {
        Stub(discovery=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page)
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                val message="热点已开启，尚未识别专用网卡；请刷新或选择热点网卡"
                page.render(JSONObject().put("area","hotspot").put("snapshot",JSONObject().put("state","On")
                    .put("adapter_notice",message).put("access_error",message))
                    .put("job",JSONObject().put("status","failed").put("message",message)))
                compose.onAllNodesWithText(message).assertCountEquals(1)
                compose.onNodeWithText("热点已开启").assertExists()
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun passwordGateRejectsWrongPasswordAndDoesNotReadOrChangeDevicesBeforeLogin() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                waitFor{page.supported && !page.busy && page.administrator}
                page.refresh();page.submit("hotspot","start",JSONObject())
                assertFalse(stub.requests.any{it.optString("action") in listOf("refresh","submit","status")})
                compose.onNodeWithText("网络管理密码").assertExists()
                page.managementPassword="wrong";page.unlock();waitFor{!page.busy && page.note.contains("密码错误")}
                assertFalse(page.authorized);assertEquals("",page.managementPassword)
                val saved=Bundle();c.saveInstanceState(saved)
                assertFalse(saved.keySet().any{it.contains("password",true) || it.contains("token",true)})
                enter(page)
                assertTrue(stub.requests.filter{it.optString("action")=="refresh"}.all{it.optString("network_token").length==64})
                compose.onNodeWithText("锁定网络管理").performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(page.authorized);assertTrue(page.snapshots.isEmpty())
                compose.onNodeWithText("网络管理密码").assertExists()
            } finally{c.pause().stop().destroy()}
        }
    }
    @Test fun nonAdministratorCannotEnterOrEnableNetworkDevices() {
        Stub(admin=false).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                waitFor{page.supported && !page.busy && page.note.contains("管理员身份")}
                compose.onNodeWithText("验证并进入").assertIsNotEnabled()
                page.refresh();page.submit("bluetooth","device",JSONObject().put("instance","bt-device").put("enabled",false))
                assertFalse(stub.requests.any{it.optString("action") in listOf("refresh","submit")})
            } finally{c.pause().stop().destroy()}
        }
    }
    @Test fun multiSelectBluetoothSendsOneConfirmedBatchAndDisplaysEachResult() {
        Stub(multiple=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page);page.selectArea("bluetooth");waitFor{!page.busy && page.snapshots.containsKey("bluetooth")}
                compose.onNodeWithText("展开设备级启停").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选择 测试设备"))
                compose.onNodeWithText("选择 测试设备").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("选择 测试设备").assertIsOn()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选择 第二设备"))
                compose.onNodeWithText("选择 第二设备").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("选择 第二设备").assertIsOn()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("批量停用"))
                compose.onNodeWithText("批量停用").performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("action")=="submit"})
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && page.job?.has("finished")==true}
                val q=stub.requests.single{it.optString("action")=="submit"}
                assertEquals("device_batch",q.getString("operation"));assertEquals("bluetooth",q.getString("area"))
                assertEquals(setOf("bt-device","bt-two"),q.getJSONObject("params").getJSONArray("instances").let{a->(0 until a.length()).map{a.getString(it)}.toSet()})
                assertFalse(q.getJSONObject("params").getBoolean("enabled"))
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("驱动拒绝请求"))
                compose.onNodeWithText("已确认设备停用").assertExists();compose.onNodeWithText("驱动拒绝请求").assertExists()
            } finally{c.pause().stop().destroy()}
        }
    }
    @Test fun selectedAdaptersAndHotspotClientsUseTheirOwnBatchOperations() {
        Stub(multiple=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page);page.selectArea("adapters");waitFor{!page.busy && page.snapshots.containsKey("adapters")}
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("全选"))
                compose.onNodeWithText("全选").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("批量启用").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && page.job?.has("finished")==true}
                val adapter=stub.requests.single{it.optString("operation")=="adapter_batch"}
                assertEquals(2,adapter.getJSONObject("params").getJSONArray("adapters").length())
                page.selectArea("hotspot")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("全选客户端"))
                compose.onNodeWithText("全选客户端").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("批量禁止联网").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("执行").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.any{it.optString("operation")=="client_access_batch"}}
                val clients=stub.requests.single{it.optString("operation")=="client_access_batch"}
                assertEquals(2,clients.getJSONObject("params").getJSONArray("addresses").length());assertTrue(clients.getJSONObject("params").getBoolean("blocked"))
            } finally{c.pause().stop().destroy()}
        }
    }
    @Test fun expiredSessionReturnsToPasswordGateWithoutResubmittingTheChange() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=c.get()
            try {
                enter(page);stub.expired=true;page.refresh()
                waitFor{!page.authorized && !page.busy}
                assertTrue(page.snapshots.isEmpty());assertTrue(page.note.contains("验证已失效"))
                compose.onNodeWithText("网络管理密码").assertExists()
                enter(page)
                assertFalse(stub.requests.any{it.optString("action")=="submit"})
            } finally{c.pause().stop().destroy()}
        }
    }

    private class Stub(val supported:Boolean=true,val fail:Boolean=false,val pair:Boolean=false,val dropReply:Boolean=false,val discovery:Boolean=false,val admin:Boolean=true,val multiple:Boolean=false):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0)
        private val clients=CopyOnWriteArrayList<AgentClient>()
        var client:AgentClient?=null
        @Volatile private var submitted:JSONObject?=null
        @Volatile private var answered=false
        @Volatile var expired=false
        @Volatile private var unlocked=false
        private val thread=Thread {
            try {while(!server.isClosed){val socket=server.accept();Thread {
                try {socket.use {
                    val input=it.getInputStream().bufferedReader();val out=it.getOutputStream().bufferedWriter()
                    while(true) {
                        val q=JSONObject(input.readLine()?:break);requests.add(q)
                        val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                        if(q.optString("cmd")=="capabilities")r.put("features",JSONArray().apply{if(supported){put("network_management");put("network_device_state");put("hotspot_client_access");put("network_password");put("network_batch");if(discovery)put("hotspot_adapter_selection")}})
                        else if(q.optString("action")=="auth_status")r.put("admin",admin).put("requires_password",true)
                        else if(q.optString("action")=="unlock") {
                            if(admin && q.optString("password")=="test-only-password") {
                                unlocked=true;expired=false;r.put("network_token","c".repeat(64)).put("admin",true).put("expires_in",1800)
                            } else r.put("ok",false).put("error","网络管理密码错误")
                        }
                        else if(q.optString("action")=="lock"){unlocked=false;r.put("locked",true)}
                        else if(!unlocked || expired || q.optString("network_token")!="c".repeat(64))r.put("ok",false).put("error","NETWORK_AUTH_REQUIRED: 请重新输入网络管理密码")
                        else {
                            if(q.optString("action")=="submit"){submitted=q;if(dropReply)break}
                            if(q.optString("action")=="answer")answered=true
                            val area=q.optString("area","hotspot")
                            r.put("area",area).put("snapshot",JSONObject().put("supported",true).put("state","Off").put("name","office")
                                .put("clients",JSONArray().apply{if(area=="hotspot")put(JSONObject().put("address","02:21:32:43:54:65").put("hosts",JSONArray()))})
                                .put("interfaces",JSONArray()).put("devices",JSONArray()).put("adapters",JSONArray()).put("radios",JSONArray())
                                .put("hardware",JSONArray().put(JSONObject().put("id",if(area=="bluetooth")"bt-device" else "net-device")
                                    .put("name","测试设备").put("enabled",true).put("problem",0))))
                            if(multiple) {
                                r.getJSONObject("snapshot").getJSONArray("hardware").put(JSONObject().put("id",if(area=="bluetooth")"bt-two" else "net-two")
                                    .put("name","第二设备").put("enabled",true).put("problem",0))
                                r.getJSONObject("snapshot").getJSONArray("clients").put(JSONObject().put("address","02:AA:BB:CC:DD:EE").put("hosts",JSONArray()))
                                for(index in 1..2)r.getJSONObject("snapshot").getJSONArray("adapters").put(JSONObject().put("id","11111111-2222-3333-4444-55555555555$index").put("name","测试网卡$index").put("status","Up"))
                            }
                            if(discovery && area=="hotspot") {
                                val adapter=JSONObject().put("id","11111111-2222-3333-4444-555555555555").put("name","本地连接* 9")
                                    .put("description","Wi-Fi Direct").put("ipv4",JSONArray().put("10.77.20.1/24"))
                                r.getJSONObject("snapshot").put("state","On").put("adapter_candidates",JSONArray().put(adapter))
                                    .put("adapter_notice","热点已开启，尚未识别专用网卡；请刷新或选择热点网卡")
                                if(submitted?.optString("operation")=="hotspot_interface")r.getJSONObject("snapshot")
                                    .put("selected_adapter",adapter).put("adapter_source","manual").put("adapter_notice","")
                            }
                            if(submitted!=null && q.optString("action")!="refresh") {
                                val job=JSONObject().put("id","a".repeat(32)).put("area",submitted!!.optString("area"))
                                    .put("request_id",submitted!!.optString("request_id")).put("status",if(pair && !answered)"awaiting_input" else if(fail)"failed" else "completed")
                                    .put("message",if(fail)"网卡拒绝此次操作" else "已读取实际状态")
                                if(pair && !answered)job.put("question",JSONObject().put("id","b".repeat(32)).put("kind","ProvidePin"))
                                else job.put("finished",1)
                                if(submitted!!.optString("operation").endsWith("_batch"))job.put("result",JSONObject().put("items",JSONArray()
                                    .put(JSONObject().put("name","测试设备").put("confirmed",true).put("message","已确认设备停用"))
                                    .put(JSONObject().put("name","第二设备").put("confirmed",false).put("message","驱动拒绝请求"))))
                                r.put("job",job)
                            }
                        }
                        out.write(r.toString()+"\n");out.flush()
                    }
                }}catch(_:Exception){}
            }.apply{isDaemon=true;start()}}}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        fun install() {
            val next=AgentClient("127.0.0.1",server.localPort)
            runBlocking{withContext(Dispatchers.IO){next.connect()}};clients.add(next);client=next
            ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,next)
        }
        override fun close(){clients.forEach{it.close()};server.close();thread.join(2000)}
    }
}

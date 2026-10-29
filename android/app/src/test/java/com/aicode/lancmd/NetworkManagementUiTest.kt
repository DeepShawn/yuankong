package com.aicode.lancmd

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
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
    @Before fun before(){disconnect();RuntimeEnvironment.getApplication().getSharedPreferences("network-pending",Context.MODE_PRIVATE).edit().clear().commit()}
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)}
        fail("网络管理界面没有完成预期状态")
    }
    @Test fun allFourAreasRefreshWithoutSendingChanges() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(NetworkManagementActivity::class.java).setup();val page=controller.get()
            try {
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
                waitFor{page.supported && !page.busy && page.snapshots.isNotEmpty()}
                page.selectArea("bluetooth");waitFor{!page.busy && page.snapshots.containsKey("bluetooth")}
                page.submit("bluetooth","pair",JSONObject().put("id","device"))
                waitFor{page.job?.optJSONObject("question")!=null}
                assertFalse(stub.requests.any{it.optString("action")=="answer"})
                val saved=Bundle();c.saveInstanceState(saved);c.pause().stop().destroy()
                c=Robolectric.buildActivity(NetworkManagementActivity::class.java).create(saved).start().resume().visible();page=c.get()
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

    private class Stub(val supported:Boolean=true,val fail:Boolean=false,val pair:Boolean=false,val dropReply:Boolean=false):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0)
        private val clients=CopyOnWriteArrayList<AgentClient>()
        var client:AgentClient?=null
        @Volatile private var submitted:JSONObject?=null
        @Volatile private var answered=false
        private val thread=Thread {
            try {while(!server.isClosed){val socket=server.accept();Thread {
                try {socket.use {
                    val input=it.getInputStream().bufferedReader();val out=it.getOutputStream().bufferedWriter()
                    while(true) {
                        val q=JSONObject(input.readLine()?:break);requests.add(q)
                        val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                        if(q.optString("cmd")=="capabilities")r.put("features",JSONArray().apply{if(supported)put("network_management")})
                        else {
                            if(q.optString("action")=="submit"){submitted=q;if(dropReply)break}
                            if(q.optString("action")=="answer")answered=true
                            val area=q.optString("area","hotspot")
                            r.put("area",area).put("snapshot",JSONObject().put("supported",true).put("state","Off").put("name","office")
                                .put("clients",JSONArray()).put("interfaces",JSONArray()).put("devices",JSONArray()).put("adapters",JSONArray()).put("radios",JSONArray()))
                            if(submitted!=null && q.optString("action")!="refresh") {
                                val job=JSONObject().put("id","a".repeat(32)).put("area",submitted!!.optString("area"))
                                    .put("request_id",submitted!!.optString("request_id")).put("status",if(pair && !answered)"awaiting_input" else if(fail)"failed" else "completed")
                                    .put("message",if(fail)"网卡拒绝此次操作" else "已读取实际状态")
                                if(pair && !answered)job.put("question",JSONObject().put("id","b".repeat(32)).put("kind","ProvidePin"))
                                else job.put("finished",1)
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

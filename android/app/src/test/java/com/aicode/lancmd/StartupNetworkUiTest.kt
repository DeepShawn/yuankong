package com.aicode.lancmd

import android.app.Application
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupNetworkUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking {(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)}
        fail("启动与网络界面未更新")
    }

    @Test fun explicitOptionsAreSentAndOnlyConfirmedHotspotStateIsShownAsOn() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StartupNetworkActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.supported && page.hotspot=="已关闭"}
                assertFalse(stub.requests.any{it.optString("action")=="configure"})
                page.autostart=true;page.autoHotspot=true;page.autoFirewall=true;page.applySettings()
                waitFor{!page.busy && page.hotspot.startsWith("已开启 · office")}
                val options=stub.requests.single{it.optString("action")=="configure"}.getJSONObject("settings")
                assertEquals(setOf("autostart","auto_hotspot","auto_firewall"),options.keys().asSequence().toSet())
                assertTrue(options.getBoolean("auto_hotspot"));assertTrue(options.getBoolean("auto_firewall"))
                assertFalse(options.has("disable_firewall"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun failedHotspotDoesNotPretendItIsOnOrRepeatedlySubmitSettings() {
        Stub(fail=true).use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StartupNetworkActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.supported && page.hotspot=="已关闭"}
                page.autoHotspot=true;page.applySettings()
                waitFor{!page.busy && page.note.contains("无线网卡不可用")}
                assertFalse(page.hotspot.contains("已开启"))
                assertEquals(1,stub.requests.count{it.optString("action")=="configure"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun unsupportedReceiverDoesNotReceiveStartupChanges() {
        Stub(supported=false).use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StartupNetworkActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.note.contains("完整更新 Windows 接收端")}
                page.autoHotspot=true;page.applySettings();shadowOf(Looper.getMainLooper()).idle()
                assertFalse(stub.requests.any{it.optString("cmd")=="startup_network"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    private class Stub(private val fail:Boolean=false,private val supported:Boolean=true):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        private var configured=false
        private val thread=Thread {
            try{server.accept().use{socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true){val q=JSONObject(input.readLine()?:break);requests.add(q)
                    val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    if(q.optString("cmd")=="capabilities")r.put("features",JSONArray().apply{if(supported)put("startup_network")})
                    else {
                        if(q.optString("action")=="configure")configured=true
                        r.put("directory","D:\\LanCmd").put("persistence_notice","请在还原工具中保留 Windows 启动项")
                            .put("settings",JSONObject().put("autostart",false).put("auto_hotspot",false).put("auto_firewall",false))
                            .put("autostart",JSONObject().put("enabled",configured))
                            .put("hotspot",JSONObject().put("state",if(!configured)"Off" else if(fail)"Unavailable" else "On")
                                .put("name","office").put("error",if(fail)"无线网卡不可用" else ""))
                        if(configured)r.put("job",JSONObject().put("status",if(fail)"failed" else "completed").put("error",if(fail)"无线网卡不可用" else ""))
                    }
                    output.write(r.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

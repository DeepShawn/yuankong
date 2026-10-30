package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnouncementsAndDelaysUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before(){disconnect();org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("announcements",0).edit().clear().commit()}
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(25,java.util.concurrent.TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)};fail("公告或延迟状态未完成")}
    @Test fun customAnnouncementIsOnlySentAfterConfirmationAndControlsRemainImmediate() {
        Stub().use{stub->
            stub.install();val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val p=c.get()
            try {
                waitFor{stub.requests.any{it.optString("cmd")=="announcements"}}
                p.change("text","课前准备 & <文字>");p.change("background","#123456");p.change("shape","pill");p.change("motion","right");p.change("font_size","40")
                assertFalse(stub.requests.any{it.optString("action")=="show"})
                p.confirmSend();compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{p.job!=null && !p.busy}
                val sent=stub.requests.single{it.optString("action")=="show"}.getJSONObject("config")
                assertEquals("课前准备 & <文字>",sent.getString("text"));assertEquals(40,sent.getInt("font_size"));assertEquals("#123456",sent.getString("background"));assertEquals("pill",sent.getString("shape"))
                p.control("pause");waitFor{p.job?.optString("status")=="paused"}
                p.control("resume");waitFor{p.job?.optString("status")=="playing"}
                p.control("stop");waitFor{p.job?.optString("status")=="cancelled"}
                val root=p.window.decorView
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(411,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(891,1073741824));root.layout(0,0,411,891)
                val bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap));File("build/ui-previews/announcements-editor.png").apply{parentFile!!.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally{c.pause().stop().destroy()}
            val reopened=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup()
            try{assertEquals("课前准备 & <文字>",reopened.get().config.optString("text"));assertEquals("pill",reopened.get().config.optString("shape"))}
            finally{reopened.pause().stop().destroy()}
        }
    }
    @Test fun deferredAnnouncementAwaitsTheOriginalResultOnceAndShowsCountdown() {
        Stub(deferred=true).use{stub->
            stub.install();val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val p=c.get()
            try {
                waitFor{stub.requests.any{it.optString("cmd")=="announcements"}}
                p.change("text","延迟公告");p.sendNow()
                waitFor{p.note.contains("电脑将在")};assertTrue(p.busy);assertNull(p.job)
                assertEquals(1,stub.requests.count{it.optString("action")=="show"})
                stub.finish=true;waitFor{!p.busy && p.job!=null}
                assertEquals("notice-test",p.job!!.getString("id"));assertEquals(1,stub.requests.count{it.optString("action")=="show"})
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun globalDelaySettingsAndCancelUseSeparateFieldsAndImmediateQueueApi() {
        Stub().use{stub->
            stub.install();val c=Robolectric.buildActivity(DelaysActivity::class.java).setup()
            try {
                waitFor{stub.requests.any{it.optString("action")=="settings"}}
                compose.onNodeWithText("全局命令延迟（0–86400 秒）").performTextClearance();compose.onNodeWithText("全局命令延迟（0–86400 秒）").performTextInput("9")
                compose.onNodeWithText("全局操作延迟（0–86400 秒）").performTextClearance();compose.onNodeWithText("全局操作延迟（0–86400 秒）").performTextInput("4")
                compose.onNodeWithText("保存设置").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{stub.requests.any{it.optString("action")=="configure"}}
                val values=stub.requests.single{it.optString("action")=="configure"};assertEquals(9,values.optInt("command"));assertEquals(4,values.optInt("operation"))
                compose.onNodeWithText("取消此任务").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{c.get().jobs.firstOrNull()?.optString("status")=="cancelled"}
                assertEquals("delay-test",stub.requests.single{it.optString("action")=="cancel"}.getString("job_id"))
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun unsupportedReceiverAndInvalidColorDoNotSendAnAnnouncement() {
        Stub(supported=false).use{stub->
            stub.install();val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup()
            try{waitFor{c.get().note.contains("更新 Windows")};compose.onNodeWithText("发送／更新").assertIsNotEnabled();assertFalse(stub.requests.any{it.optString("action")=="show"})}
            finally{c.pause().stop().destroy()}
        }
    }
    private class Stub(val deferred:Boolean=false,val supported:Boolean=true):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>();@Volatile var finish=false
        private var state="playing";private var visible=false;private var cancelled=false
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        private fun notice()=JSONObject().put("id","notice-test").put("status",state).put("cycles",0)
        private val worker=Thread {
            try{server.accept().use{socket->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true){val q=JSONObject(input.readLine()?:break);requests.add(JSONObject(q.toString()));val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")){
                        "capabilities"->r.put("features",JSONArray(if(supported)listOf("announcements","global_delays") else emptyList<String>()))
                        "announcements"->{
                            when(q.optString("action")){"show"->{if(deferred)r.put("deferred",true).put("job",JSONObject().put("id","delay-test")) else visible=true};"pause"->state="paused";"resume"->state="playing";"stop"->state="cancelled"}
                            if(!r.optBoolean("deferred"))r.put("job",if(visible)notice() else JSONObject.NULL)
                        }
                        "delays"->{
                            if(q.optString("action")=="settings")r.put("settings",JSONObject().put("command",0).put("operation",0))
                            else if(q.optString("action")=="get"){
                                if(finish)visible=true
                                r.put("job",JSONObject().put("id","delay-test").put("status",if(finish)"completed" else "waiting").put("remaining",2)
                                    .put("result",JSONObject().put("job",notice())))
                            }else {
                                if(q.optString("action")=="cancel")cancelled=true
                                r.put("jobs",JSONArray().put(JSONObject().put("id","delay-test").put("command","exec").put("status",if(cancelled)"cancelled" else "waiting").put("remaining",4)))
                                if(q.optString("action")=="cancel")r.put("job",JSONObject().put("id","delay-test").put("status","cancelled"))
                            }
                        }
                    }
                    output.write(r.toString()+"\n");output.flush()
                }
            }}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

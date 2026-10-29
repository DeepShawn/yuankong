package com.aicode.lancmd

import android.app.Application
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpgradeUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)};fail("升级状态未更新")}
    private fun confirm(stub:Stub,page:UpgradeActivity){
        waitFor{page.note.startsWith("已读取")}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("校验并安装"))
        compose.onNodeWithText("校验并安装").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertFalse(stub.requests.any{it.optString("action")=="apply"})
        compose.onNodeWithText("开始升级").performSemanticsAction(SemanticsActions.OnClick){it()}
        waitFor{stub.requestId.isNotBlank() && (stub.dropApplyReply || page.note.contains("准备退出旧接收端"))}
    }

    @Test fun installationConfirmsThenTracksTheSameComputerAcrossDisconnectAndRecreation() {
        Stub().use {old ->Stub(version="3.4.2",phase="starting").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup()
            try {
                confirm(old,controller.get());waitFor{controller.get().restarting}
                old.client.close();ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,null)
                fresh.reportId=old.requestId;fresh.install()
                waitFor{controller.get().note.contains("start.bat")}
                controller.recreate();fresh.phase="completed"
                try {waitFor{controller.get().note.contains("升级已完成") && !controller.get().restarting}}
                catch(error:Throwable) {
                    val page=controller.get()
                    throw AssertionError("after recreate note=${page.note}; restarting=${page.restarting}; server phase=${fresh.phase}; closed=${fresh.client.closed}; queries=${fresh.requests.size}",error)
                }
                compose.onNodeWithText("3.4.2").assertExists()
                assertTrue(fresh.requests.any{it.optString("cmd")=="upgrade" && !it.optBoolean("include_packages",true)})
                assertEquals(1,old.requests.count{it.optString("action")=="apply"})
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun reportsFromAnOlderUpgradeCannotCompleteTheCurrentOperation() {
        Stub().use {old ->Stub(version="3.4.2",phase="completed").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,controller.get());fresh.reportId="0".repeat(32);old.client.close();fresh.install()
                waitFor{page.note.contains("等待此次更新")}
                assertTrue(page.restarting)
                fresh.reportId=old.requestId
                waitFor{page.note.contains("升级已完成") && !page.restarting}
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun reconnectionToAnotherComputerNeverReceivesUpgradeQueries() {
        Stub().use {old ->Stub(identity="different-pc").use {other ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,controller.get());old.client.close();other.install()
                waitFor{page.note.contains("另一台电脑")}
                assertTrue(other.requests.isEmpty())
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun lostApplyReplyStillReconnectsAndChecksTheSubmittedRequest() {
        Stub(dropApplyReply=true).use {old ->Stub(version="3.4.2",phase="completed").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,page);fresh.reportId=old.requestId;fresh.install()
                waitFor{page.note.contains("升级已完成") && !page.restarting}
                assertEquals(1,old.requests.count{it.optString("action")=="apply"})
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun completionUsesTheBuildSequenceWhileShowingTheRequestedDisplayVersion() {
        Stub(version="3.5.3",targetVersion="3.5.4").use {old ->
            Stub(version="3.5.0",phase="completed",buildVersion="3.5.3",targetVersion="3.5.4").use {fresh ->
                old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
                try {
                    confirm(old,page);fresh.reportId=old.requestId;old.client.close();fresh.install()
                    waitFor{page.note.contains("等待目标版本 3.5.4")}
                    assertTrue(page.restarting)
                    fresh.buildVersion="3.5.4"
                    waitFor{page.note.contains("升级已完成") && !page.restarting}
                    compose.onNodeWithText("3.5.0").assertExists()
                }finally{controller.pause().stop().destroy()}
            }
        }
    }
    private class Stub(val identity:String="same-pc",val version:String="3.4.1",phase:String="",val dropApplyReply:Boolean=false,buildVersion:String?=null,val targetVersion:String="3.4.2"):Closeable {
        @Volatile var phase=phase
        @Volatile var buildVersion=buildVersion
        val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>()
        @Volatile var requestId=""
        @Volatile var reportId=""
        val thread=Thread {
            try{server.accept().use {socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true){val q=JSONObject(input.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    if(q.optString("action")=="apply") {
                        requestId=q.getString("request_id")
                        if(dropApplyReply){requests.add(q);client.close();break}
                        result.put("restarting",true).put("version",targetVersion).put("request_id",requestId).put("message","准备退出旧接收端")
                    }else {
                        result.put("directory","C:\\LanCmd\\upgrade").put("current",version)
                            .put("items",JSONArray().put(JSONObject().put("name","LanCmd-Windows-3.4.2.zip").put("size",1234).put("sha256","a".repeat(64))))
                            .put("status",JSONObject().put("status",this@Stub.phase).put("version",targetVersion).put("request_id",reportId))
                        this@Stub.buildVersion?.let{result.put("current_build",it)}
                    }
                    requests.add(q);output.write(result.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){
            val app=RuntimeEnvironment.getApplication()
            Prefs.saveTarget(app,Target("127.0.0.1",server.localPort,"Test PC",identity))
            runBlocking{withContext(Dispatchers.IO){client.connect()}}
            ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)
        }
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

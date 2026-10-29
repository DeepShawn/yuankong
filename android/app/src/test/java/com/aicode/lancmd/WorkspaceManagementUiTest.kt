package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WorkspaceManagementUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    /** Network replies resume lifecycleScope on Android's paused test looper. */
    private fun waitFor(condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(50,java.util.concurrent.TimeUnit.MILLISECONDS)
            if(condition())return
            Thread.sleep(10)
        }
        fail("应用状态在 8 秒内没有更新")
    }
    @Test fun installedApplicationFreezeRequiresConfirmationAndSupportsThaw() {
        Stub().use { stub ->
            stub.install();val controller=Robolectric.buildActivity(ApplicationsActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.note.contains("已识别")}
                compose.onNodeWithText("冻结").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("action")=="freeze"})
                compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                try { waitFor{stub.requests.any{it.optString("action")=="freeze"} && page.note.contains("冻结 1")} }
                catch(error:Throwable){throw AssertionError("冻结后的状态=${page.note}; RPC=${stub.requests}",error)}
                assertEquals("C:\\Apps\\demo.exe",stub.requests.single{it.optString("action")=="freeze"}.getString("path"))
                compose.onNodeWithText("解冻").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{stub.requests.any{it.optString("action")=="resume"} && page.note.contains("冻结 0")}
                assertEquals("held",stub.requests.single{it.optString("action")=="resume"}.getString("freeze_id"))
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun asynchronousFreezeAndCloseKeepProgressVisibleWhileCatalogIsLoading() {
        Stub(async=true).use {stub ->
            stub.install();val controller=Robolectric.buildActivity(ApplicationsActivity::class.java).setup();val page=controller.get()
            try {
                // Sending the catalog request precedes receiving and rendering
                // its first entries; wait for the actual actionable UI.
                compose.waitUntil(8000) {
                    compose.onAllNodesWithText("冻结").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("冻结").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{page.note.contains("Windows 正在处理")}
                compose.onNodeWithText("应用列表正在后台加载，不会阻止解冻或返回。").assertExists()
                stub.finishJob=true
                waitFor{page.note.contains("已暂停") && page.note.contains("冻结 1")}
                compose.onNodeWithText("解冻").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{page.note.contains("已恢复") && page.note.contains("冻结 0")}
                compose.onNodeWithText("关闭").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("operation")=="close"})
                compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{page.note.contains("已关闭 1 个进程")}
                val request=stub.requests.single{it.optString("operation")=="close"}
                assertEquals("C:\\Apps\\demo.exe",request.optString("path"))
                assertEquals("submit",request.optString("action"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun oldApplicationPageCannotLaunchOnAChangedComputer() {
        Stub().use { first ->Stub().use { second ->
            first.install();val controller=Robolectric.buildActivity(ApplicationsActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.note.contains("已识别")}
                second.install()
                compose.onNodeWithText("启动").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{page.note.contains("电脑已切换")}
                assertFalse(first.requests.any{it.optString("cmd")=="files"})
                assertFalse(second.requests.any{it.optString("cmd")=="files"})
            }finally{controller.pause().stop().destroy()}
        } }
    }
    private class Stub(private val async:Boolean=false):Closeable {
        @Volatile var finishJob=false
        private var operation=""
        val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();var frozen=false
        val worker=Thread {
            try{server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(input.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "capabilities" ->result.put("features",JSONArray().apply{if(async)put("app_freeze_jobs").put("app_close")})
                        "app_catalog" ->result.put("loading",async).put("items",JSONArray().put(JSONObject().put("id","demo").put("name","Demo App").put("path","C:\\Apps\\demo.exe").put("can_lock",true).put("running",1)))
                        "app_freeze" -> {
                            when(q.optString("action")) {
                                "freeze"->frozen=true
                                "resume"->frozen=false
                                "submit"->{operation=q.optString("operation");result.put("job",JSONObject().put("id","operation-job").put("status","queued"))}
                                "job"->{
                                    val job=JSONObject().put("id","operation-job").put("operation",operation)
                                    if(!finishJob)job.put("status","running") else {
                                        if(operation=="freeze")frozen=true else frozen=false
                                        val done=JSONObject().put("resumed",true).put("closed",1).put("items",JSONArray().apply{if(frozen)put(JSONObject().put("id","held").put("path","C:\\Apps\\demo.exe").put("processes",JSONArray().put(JSONObject().put("pid",123))))})
                                        job.put("status","completed").put("result",done)
                                    }
                                    result.put("job",job)
                                }
                            }
                            result.put("items",JSONArray().apply{if(frozen)put(JSONObject().put("id","held").put("path","C:\\Apps\\demo.exe").put("processes",JSONArray().put(JSONObject().put("pid",123))))})
                        }
                    }
                    requests.add(q);output.write(result.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReadonlyDiagnosticsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before(){
        disconnect()
        ConnectionManager::class.java.getDeclaredField("appCtx").apply{isAccessible=true}.set(ConnectionManager,RuntimeEnvironment.getApplication())
        RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().clear().commit()
        Prefs.forget(RuntimeEnvironment.getApplication())
    }
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(50,java.util.concurrent.TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)}
        fail("状态未完成")
    }
    @Test fun filePropertyDialogEditsOnlyReadonlyAndPreservesSearch(){
        Stub().use {stub->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.isNotEmpty()}
                page.query="report"
                page.readProperties(page.visible.first(),"C:\\data\\report.txt")
                waitFor{page.propertyRecord!=null}
                compose.onNodeWithText("隐藏（仅查看）").assertIsNotEnabled()
                compose.onNodeWithText("系统（仅查看）").assertIsNotEnabled()
                assertFalse(stub.requests.any{it.optString("action")=="readonly"})
                compose.onNodeWithText("只读").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("保存只读属性").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.operationsBusy && page.visible.firstOrNull()?.optBoolean("readonly")==true}
                val request=stub.requests.single{it.optString("action")=="readonly"}
                assertTrue(request.getBoolean("readonly"));assertEquals(34,request.getInt("expected_attributes"))
                assertEquals("version",request.getString("file_id"));assertFalse(request.has("hidden"));assertFalse(request.has("system"))
                assertEquals("report",page.query)
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun maintenanceStatusUsesOnlyDiagnosticConnectionAndDoesNotExecuteOperations(){
        Stub().use {stub->
            stub.install();val controller=Robolectric.buildActivity(SelfManageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{!page.diagnosticLoading && page.directory.isNotBlank()}
                assertEquals("C:\\LanCmd",page.directory);assertEquals(1,page.records.size)
                assertTrue(page.note.contains("未执行维护操作"))
                assertFalse(stub.requests.any{it.optString("cmd") in setOf("self_manage","dates_batch")})
                assertTrue(stub.diagnosticSockets.isNotEmpty());assertFalse(stub.primary.closed)
                assertTrue(runBlocking{stub.primary.call("ping",timeoutMs=1000)}.getBoolean("ok"))
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun stalledStatusCanBeCancelledAndLeavesTheMainConnectionUsable(){
        Stub(stall=true).use {stub->
            stub.install();val controller=Robolectric.buildActivity(SelfManageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{stub.requests.any{it.optString("cmd")=="maintenance_diagnostics"}}
                page.cancelDiagnostics();waitFor{!page.busy && !page.diagnosticLoading}
                assertTrue(page.note.contains("取消"));assertFalse(stub.primary.closed)
                assertTrue(runBlocking{stub.primary.call("ping",timeoutMs=1000)}.getBoolean("ok"))
                assertFalse(stub.requests.any{it.optString("cmd")=="self_manage"})
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun stalledStatusShowsAnErrorAndEnablesRetryWithoutClosingTheMainConnection(){
        Stub(stall=true).use {stub->
            stub.install();val controller=Robolectric.buildActivity(SelfManageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{!page.diagnosticLoading && (page.note.contains("超时") || page.note.contains("读取失败"))}
                assertFalse(page.busy);assertFalse(stub.primary.closed)
                compose.onNodeWithText("刷新状态").assertIsEnabled()
                assertTrue(runBlocking{stub.primary.call("ping",timeoutMs=1000)}.getBoolean("ok"))
            }finally{controller.pause().stop().destroy()}
        }
    }
    private class Stub(val stall:Boolean=false):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        val diagnosticSockets=CopyOnWriteArrayList<Socket>()
        private val sockets=CopyOnWriteArrayList<Socket>()
        private val server=ServerSocket(0)
        val primary=AgentClient("127.0.0.1",server.localPort)
        @Volatile private var readonly=false
        private val accept=Thread{
            while(!server.isClosed) {
                val socket=runCatching{server.accept()}.getOrNull()?:break
                sockets.add(socket)
                Thread{runCatching{socket.use {
                    val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                    while(true) {
                        val q=JSONObject(input.readLine()?:break);requests.add(q)
                        val cmd=q.optString("cmd");val action=q.optString("action")
                        if(cmd=="maintenance_diagnostics"){diagnosticSockets.add(socket);if(stall)continue}
                        val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                        fun item()=JSONObject().put("name","report.txt").put("size",20).put("mtime",1).put("file_id","version")
                            .put("dir",false).put("hidden",true).put("system",false).put("readonly",readonly)
                        when(cmd) {
                            "hello"->r.put("agent_id","").put("token","fixture")
                            "capabilities"->r.put("features",JSONArray().put("maintenance_diagnostics").put("file_properties_readonly"))
                            "maintenance_diagnostics"->r.put("root","C:\\LanCmd").put("loading",false).put("pending",JSONArray())
                                .put("warnings",JSONArray()).put("items",JSONArray().put(JSONObject().put("id","record").put("files",1)))
                            "storage"->r.put("home","C:\\data").put("drives",JSONArray())
                            "ls"->r.put("path","C:\\data").put("items",JSONArray().put(item()))
                            "file_jobs"->r.put("jobs",JSONArray())
                            "files"->{
                                if(action=="readonly")readonly=q.getBoolean("readonly")
                                r.put("path","C:\\data\\report.txt").put("file_id","version").put("readonly",readonly)
                                    .put("hidden",true).put("system",false).put("editable",true).put("attributes",if(readonly)35 else 34)
                            }
                        }
                        output.write(r.toString()+"\n");output.flush()
                    }
                }}}.apply{isDaemon=true;start()}
            }
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){primary.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,primary)}
        override fun close(){primary.close();server.close();sockets.forEach{runCatching{it.close()}};accept.join(1000)}
    }
}

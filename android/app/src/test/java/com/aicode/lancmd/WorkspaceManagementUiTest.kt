package com.aicode.lancmd

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
    @After fun after(){ConnectionManager.stop()}
    @Test fun installedApplicationFreezeRequiresConfirmationAndSupportsThaw() {
        Stub().use { stub ->
            stub.install();val controller=Robolectric.buildActivity(ApplicationsActivity::class.java).setup();val page=controller.get()
            try {
                compose.waitUntil(8000){page.note.contains("已识别")}
                compose.onNodeWithText("冻结").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("action")=="freeze"})
                compose.onNodeWithText("确认").performClick()
                compose.waitUntil(8000){stub.requests.any{it.optString("action")=="freeze"} && page.note.contains("冻结 1")}
                assertEquals("C:\\Apps\\demo.exe",stub.requests.single{it.optString("action")=="freeze"}.getString("path"))
                compose.onNodeWithText("解冻").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.waitUntil(8000){stub.requests.any{it.optString("action")=="resume"} && page.note.contains("冻结 0")}
                assertEquals("held",stub.requests.single{it.optString("action")=="resume"}.getString("freeze_id"))
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun oldApplicationPageCannotLaunchOnAChangedComputer() {
        Stub().use { first ->Stub().use { second ->
            first.install();val controller=Robolectric.buildActivity(ApplicationsActivity::class.java).setup();val page=controller.get()
            try {
                compose.waitUntil(8000){page.note.contains("已识别")}
                second.install()
                compose.onNodeWithText("启动").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("确认").performClick()
                compose.waitUntil(8000){page.note.contains("电脑已切换")}
                assertFalse(first.requests.any{it.optString("cmd")=="files"})
                assertFalse(second.requests.any{it.optString("cmd")=="files"})
            }finally{controller.pause().stop().destroy()}
        } }
    }
    private class Stub:Closeable {
        val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();var frozen=false
        val worker=Thread {
            try{server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(input.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "app_catalog" ->result.put("items",JSONArray().put(JSONObject().put("id","demo").put("name","Demo App").put("path","C:\\Apps\\demo.exe").put("can_lock",true).put("running",1)))
                        "app_freeze" -> {
                            when(q.optString("action")){"freeze"->frozen=true;"resume"->frozen=false}
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

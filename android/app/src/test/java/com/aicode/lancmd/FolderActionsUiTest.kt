package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderActionsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before(){disconnect();RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().putInt("delay",0).apply()}
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};fail("文件夹操作没有更新")}
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun choose(label:String){val list=latest().listView;val index=(0 until list.adapter.count).single{list.adapter.getItem(it).toString()==label};list.performItemClick(null,index,index.toLong());shadowOf(Looper.getMainLooper()).idle()}
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()

    @Test fun folderMenuRenamesValidatesAndRetainsOpenAndLongPress() {
        Stub().use { stub ->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.any{it.optString("name")=="Photos"}}
                compose.onNodeWithContentDescription("操作：Photos").performSemanticsAction(SemanticsActions.OnClick){it()}
                shadowOf(Looper.getMainLooper()).idle();choose("传输与整理");choose("重命名")
                val dialog=latest();val field=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                field.setText("bad/name");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing);assertFalse(stub.requests.any{it.optString("cmd")=="rename"})
                field.setText("Photos 2026");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{page.visible.any{it.optString("name")=="Photos 2026"} && page.note.contains("已重命名")}
                val request=stub.requests.single{it.optString("cmd")=="rename"}
                assertEquals("C:\\notes\\Photos",request.getString("path"));assertEquals("C:\\notes\\Photos 2026",request.getString("to"))
                compose.onNodeWithText("Photos 2026").performSemanticsAction(SemanticsActions.OnLongClick){it()}
                shadowOf(Looper.getMainLooper()).idle();assertTrue(latest().isShowing)
                latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                compose.onNodeWithText("Photos 2026").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{page.path=="C:\\notes\\Photos 2026"}
            }finally{controller.pause().stop().destroy()}
        }
    }

    private class Stub:Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>();val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        var folder="Photos"
        val thread=Thread {
            try {server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true){val q=JSONObject(input.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "storage"->result.put("home","C:\\notes").put("drives",JSONArray())
                        "file_jobs"->result.put("jobs",JSONArray())
                        "ls"->result.put("path",q.optString("path").ifBlank{"C:\\notes"}).put("items",JSONArray().apply{if(q.optString("path")=="C:\\notes")put(JSONObject().put("name",folder).put("dir",true).put("mtime",1))})
                        "rename"->folder=q.getString("to").substringAfterLast('\\')
                    }
                    requests.add(q);output.write(result.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

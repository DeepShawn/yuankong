package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileSearchUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() };shadowOf(Looper.getMainLooper()).idle()}
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun waitFor(check:()->Boolean) { val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));if(check())return;Thread.sleep(10)};fail("Search UI did not settle") }
    private fun preview(v:View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));v.layout(0,0,411,640)
        val bitmap=Bitmap.createBitmap(411,640,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/ui-previews/file-search-small.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun wildcardSearchPaginatesAndCanLocateTheResultWithoutExecutingIt() {
        Stub().use { stub->
            // 文件搜索页已迁移到 Compose（miuix）：结果菜单改用系统列表弹窗。
            stub.install();val c=Robolectric.buildActivity(FileSearchActivity::class.java).setup();val a=c.get();val root=a.window.decorView
            a.typed("*.docx")
            waitFor { stub.requests.any { it.optString("query")=="*.docx" } && a.hasNext }
            preview(root)
            val row=a.items.single { it.name=="report.docx" }
            a.actions(row);shadowOf(Looper.getMainLooper()).idle()
            val menu=latest()
            assertEquals("在文件管理中定位",menu.listView.adapter.getItem(0))
            menu.listView.performItemClick(null,0,0);shadowOf(Looper.getMainLooper()).idle()
            val intent=shadowOf(a).nextStartedActivity;assertEquals(StorageActivity::class.java.name,intent.component!!.className)
            assertEquals("C:\\Docs",intent.getStringExtra("path"));assertEquals("report.docx",intent.getStringExtra("highlight"))
            assertFalse(stub.requests.any { it.optString("cmd")=="files" })
            a.offsetBy(100)
            waitFor { stub.requests.any { it.optInt("offset")==100 } && a.pageLabel=="101–101 / 101" }
            c.pause().stop().destroy()
        }
    }
    @Test fun indexBuildIsExplicitAndCancelUsesTheJobId() {
        Stub().use { stub->
            stub.install();val c=Robolectric.buildActivity(FileSearchActivity::class.java).setup();val a=c.get()
            waitFor { a.buildLabel=="更新索引" && a.canBuild }
            a.build();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="build" });latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            a.build();shadowOf(Looper.getMainLooper()).idle();latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { a.canCancel }
            assertEquals("C:\\Docs",stub.requests.single { it.optString("action")=="build" }.getJSONArray("roots").getString(0))
            a.cancelBuild()
            waitFor { stub.requests.any { it.optString("action")=="cancel" } }
            assertEquals("index-job",stub.requests.single { it.optString("action")=="cancel" }.getString("job_id"))
            c.pause().stop().destroy()
        }
    }
    @Test fun switchingComputerNeverSendsTheOldPagesQueryToReplacement() {
        Stub().use { first->Stub().use { second->
            first.install();val c=Robolectric.buildActivity(FileSearchActivity::class.java).setup();val a=c.get()
            waitFor { first.requests.any { it.optString("action")=="query" } }
            second.install();a.typed("private-query")
            waitFor { a.note.contains("电脑连接已改变") || a.indexInfo.contains("电脑连接已改变") }
            assertTrue(second.requests.isEmpty());c.pause().stop().destroy()
        } }
    }
    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();private var building=false
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    val metadata=JSONObject().put("available",true).put("count",101).put("updated",1700000000).put("roots",JSONArray().put("C:\\Docs"))
                    when(q.optString("action","status")) {
                        "build"->{building=true;r.put("job",JSONObject().put("id","index-job"))}
                        "cancel"->{building=false;r.put("cancel_requested",true)}
                        "query"->r.put("index",metadata).put("total",101).put("items",JSONArray().apply {
                            if(q.optInt("offset")==100)put(item("last.docx")) else for(i in 0..99)put(item(if(i==0)"report.docx" else "file-$i.docx"))
                        })
                        else->r.put("index",metadata).put("drives",JSONArray().put("C:\\")).put("can_cancel",building).put("job",if(building)JSONObject().put("id","index-job").put("status","building").put("scanned",50).put("skipped",0) else JSONObject.NULL)
                    }
                    requests.add(q);writer.write(r.toString()+"\n");writer.flush()
                }
            } } catch(_:Exception){}
        }.apply { isDaemon=true;start() }
        private fun item(name:String)=JSONObject().put("path","C:\\Docs\\$name").put("name",name).put("parent","C:\\Docs").put("dir",false).put("size",6).put("mtime",1700000000).put("file_id","a".repeat(64))
        fun install(){runBlocking { client.connect() };ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

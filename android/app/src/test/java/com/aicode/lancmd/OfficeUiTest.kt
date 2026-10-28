package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OfficeUiTest {
    @Before fun before(){disconnect();RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().clear().commit()}
    @After fun after(){disconnect();RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().clear().commit();File(RuntimeEnvironment.getApplication().filesDir,"office-documents").deleteRecursively()}
    private fun disconnect(){ConnectionManager.stop();runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() };shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean) { val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};fail("Office UI did not settle") }
    private fun activity()=Robolectric.buildActivity(OfficeDocumentActivity::class.java,Intent(RuntimeEnvironment.getApplication(),OfficeDocumentActivity::class.java).putExtra("path","C:\\docs\\report.docx").putExtra("file_id","original"))
    @Test fun phoneEditorUsesWritableContentUriAndUploadChecksOriginalVersion() {
        Stub().use { stub->
            // Office 页已迁移到 Compose（miuix）：直接驱动页面入口，按页面状态断言。
            stub.install();val c=activity().setup();val a=c.get()
            a.phone(true)
            waitFor { shadowOf(a).peekNextStartedActivity()!=null && !a.busy }
            val launch=shadowOf(a).nextStartedActivity
            val editor=if(launch.action==Intent.ACTION_CHOOSER)launch.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!! else launch
            assertEquals(Intent.ACTION_EDIT,editor.action);assertEquals("content",editor.data!!.scheme)
            assertTrue(editor.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0)
            a.contentResolver.openOutputStream(editor.data!!,"w")!!.use { it.write("edited".toByteArray()) }
            a.confirmUpload();shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { a.note.contains("已保存到电脑") }
            assertEquals("original",stub.requests.single { it.optString("cmd")=="upload" && it.optString("action")=="begin" }.getString("file_id"))
            assertEquals("edited",File(RuntimeEnvironment.getApplication().filesDir,"office-documents").walkTopDown().single { it.name=="report.docx" }.readText())
            c.pause().stop().destroy()
        }
    }
    @Test fun pcWpsBackgroundOpenUsesTypedOptionsWithoutShellCommand() {
        Stub().use { stub->
            stub.install();val c=activity().setup()
            c.get().computer(true)
            waitFor { stub.requests.any { it.optString("cmd")=="files" } }
            val q=stub.requests.single { it.optString("cmd")=="files" }
            assertEquals("open",q.getString("action"));assertEquals("wps",q.getString("application"));assertTrue(q.getBoolean("background"))
            assertEquals("C:\\docs\\report.docx",q.getString("path"));assertFalse(stub.requests.any { it.optString("cmd")=="exec" })
            c.pause().stop().destroy()
        }
    }
    @Test fun delayedRemoteFileActionIsQueuedOnWindowsWithSecondsAndOriginalParams() {
        Stub().use { stub->
            stub.install();RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().putInt("delay",12).commit()
            val c=activity().setup();val a=c.get()
            a.computer(false)
            waitFor { a.note=="电脑已打开" }
            val q=stub.requests.single { it.optString("cmd")=="file_schedule" && it.optString("action")=="add" }
            assertEquals(12,q.getInt("delay"));assertEquals("files",q.getString("command"));assertEquals("wps",q.getJSONObject("params").getString("application"))
            assertFalse(stub.requests.any { it.optString("cmd")=="files" });c.pause().stop().destroy()
        }
    }
    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "download"->when(q.optString("action")) {"begin"->r.put("sid","d").put("size",6);"chunk"->r.put("data","b2ZmaWNl").put("eof",true)}
                        "upload"->when(q.optString("action")) {"begin"->r.put("sid","u");"end"->r.put("file_id","updated")}
                        "files"->r.put("notice","电脑已打开")
                        "file_schedule"->r.put("job",if(q.optString("action")=="add")JSONObject().put("id","scheduled") else JSONObject().put("status","completed").put("result",JSONObject().put("notice","电脑已打开")))
                    }
                    requests.add(q);writer.write(r.toString()+"\n");writer.flush()
                }
            } } catch(_:Exception){}
        }.apply { isDaemon=true;start() }
        fun install(){runBlocking { client.connect() };ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

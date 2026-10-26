package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagementUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        val field=ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() };shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)}
        fail("UI did not settle")
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun buttons(view:View)=all(view).filterIsInstance<MaterialButton>()
    private fun render(view:View,name:String,width:Int=411,height:Int=891) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun editor()=Robolectric.buildActivity(TextEditorActivity::class.java,Intent(RuntimeEnvironment.getApplication(),TextEditorActivity::class.java).putExtra("path","C:\\notes\\test.bat")).setup()
    private fun content(root:View)=all(root).filterIsInstance<EditText>().single { it.contentDescription=="电脑文件文本内容" }
    @Test fun editorLoadsAndSavesRevisionEncodingAndTextWithoutExecuting() {
        Stub().use { stub->
            stub.install();val controller=editor();val root=controller.get().window.decorView
            waitFor { content(root).isEnabled };assertEquals("initial\r\n",content(root).text.toString())
            content(root).setText("echo 中文\nexit /b 0")
            buttons(root).single { it.text=="保存" }.performClick()
            waitFor { stub.requests.any { it.optString("action")=="text_write" } }
            val saved=stub.requests.single { it.optString("action")=="text_write" }
            assertEquals("echo 中文\nexit /b 0",saved.getString("text"));assertEquals("r1",saved.getString("revision"));assertEquals("utf-8",saved.getString("encoding"));assertEquals("crlf",saved.getString("newline"))
            assertFalse(stub.requests.any { it.optString("cmd")=="scripts" })
            render(root,"text-editor");controller.pause().stop().destroy()
        }
    }
    @Test fun autosaveDebouncesAndConflictKeepsTextAndStopsAutomaticWrites() {
        Stub().use { stub->
            stub.install();val controller=editor();val root=controller.get().window.decorView
            waitFor { content(root).isEnabled };stub.conflict=true
            val auto=all(root).filterIsInstance<CheckBox>().single()
            auto.isChecked=true;content(root).setText("draft conflict")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
            waitFor { stub.requests.any { it.optString("action")=="text_write" } && !auto.isChecked }
            assertEquals("draft conflict",content(root).text.toString())
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.contains("外部修改") })
            controller.pause().stop().destroy()
        }
    }
    @Test fun unsavedDraftSurvivesRecreationAndOldConnectionCannotSaveToNewComputer() {
        Stub().use { first->Stub().use { second->
            first.install();val controller=editor();var root=controller.get().window.decorView
            waitFor { content(root).isEnabled };content(root).setText("local draft")
            controller.recreate();root=controller.get().window.decorView
            waitFor { content(root).isEnabled && content(root).text.toString()=="local draft" }
            second.install();buttons(root).single { it.text=="保存" }.performClick()
            waitFor { all(root).filterIsInstance<TextView>().any { it.text.contains("电脑已切换") } }
            assertFalse(second.requests.any { it.optString("action")=="text_write" })
            assertEquals("local draft",content(root).text.toString());controller.pause().stop().destroy()
        } }
    }
    @Test fun privacyAndAppLockRequirePhoneConfirmationBeforeChangingComputer() {
        Stub().use { stub->
            stub.install()
            val lock=Robolectric.buildActivity(AppLocksActivity::class.java,Intent(RuntimeEnvironment.getApplication(),AppLocksActivity::class.java).putExtra("path","C:\\Apps\\test.exe")).setup()
            waitFor { stub.requests.any { it.optString("cmd")=="app_locks" } }
            buttons(lock.get().window.decorView).single { it.text=="锁定这个 EXE" }.performClick()
            assertFalse(stub.requests.any { it.optString("action")=="lock" })
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="lock" } }
            render(lock.get().window.decorView,"app-locks");lock.pause().stop().destroy()
            val privacy=Robolectric.buildActivity(PrivacyDevicesActivity::class.java).setup();val root=privacy.get().window.decorView
            waitFor { buttons(root).any { it.text=="停用设备" } }
            buttons(root).single { it.text=="停用设备" }.performClick()
            assertFalse(stub.requests.any { it.optString("action")=="set_enabled" })
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(stub.requests.any { it.optString("action")=="set_enabled" })
            buttons(root).single { it.text=="静音麦克风" }.performClick()
            waitFor { stub.requests.any { it.optString("action")=="mute" } }
            render(root,"privacy-devices");privacy.pause().stop().destroy()
        }
    }
    @Test fun registryTasksBrowserAndHomeCardsRenderWithReachableConsole() {
        Stub().use { stub->
            stub.install()
            for((type,name) in listOf(RegistryActivity::class.java to "registry-editor",TaskManagerActivity::class.java to "task-manager",BrowserActivity::class.java to "browser")) {
                val controller=Robolectric.buildActivity(type).setup();val root=controller.get().window.decorView
                val cmd=when(name){"registry-editor"->"registry";"task-manager"->"tasks";else->"browser"}
                waitFor { stub.requests.any { it.optString("cmd")==cmd } };shadowOf(Looper.getMainLooper()).idle()
                render(root,name);controller.pause().stop().destroy()
            }
            val home=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=home.get()
            for(height in listOf(640,891)) {
                render(activity.window.decorView,"management-home-$height",height=height)
                val cards=activity.findViewById<ViewGroup>(R.id.managementCards)
                assertEquals(4,cards.childCount);assertTrue(activity.findViewById<View>(R.id.btnSend).bottom>0)
                assertTrue(activity.findViewById<View>(R.id.scroll).height>0)
            }
            home.pause().stop().destroy()
        }
    }

    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();var conflict=false
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "files"->if(q.optString("action")=="text_read")result.put("text","initial\r\n").put("encoding","utf-8").put("newline","crlf").put("revision","r1").put("size",9)
                            else if(conflict)result.put("ok",false).put("error","文件已被外部修改，未覆盖") else result.put("revision","r2").put("size",20)
                        "app_locks"->result.put("items",JSONArray()).put("backup","C:\\LanCmd\\app-locks.json")
                        "privacy_devices"->result.put("admin",true).put("items",JSONArray().put(JSONObject().put("id","mic").put("kind","microphone").put("name","Test microphone").put("enabled",true).put("problem",0).put("muted",false)))
                        "registry"->result.put("root","HKCU").put("path",q.optString("path")).put("view",64).put("keys",JSONArray().put("Software")).put("values",JSONArray()).put("key_count",1).put("value_count",0).put("key_offset",0).put("value_offset",0)
                        "tasks"->result.put("items",JSONArray().put(JSONObject().put("pid",777).put("name","test.exe").put("path","C:\\Apps\\test.exe").put("created","100").put("memory",1048576).put("cpu",1.5).put("can_terminate",false)))
                        "browser"->result.put("items",JSONArray().put(JSONObject().put("id","edge").put("name","Microsoft Edge")))
                    }
                    requests.add(q);writer.write(result.toString()+"\n");writer.flush()
                }
            } } catch(_:IOException){}
        }.apply { isDaemon=true;start() }
        fun install() {
            runBlocking { withContext(Dispatchers.IO){client.connect()} }
            ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client)
        }
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

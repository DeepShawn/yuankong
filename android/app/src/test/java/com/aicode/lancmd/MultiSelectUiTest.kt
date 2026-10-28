package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiSelectUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(v:View)=all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(v:View,name:String)=all(v).filterIsInstance<MaterialButton>().single { it.text==name }
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)}
        fail("UI did not settle")
    }
    private fun preview(v:View,name:String,height:Int=891) {
        v.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,411,height)
        val bitmap=Bitmap.createBitmap(411,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun fileSelectionSurvivesFilteringAndCopyMoveUseFrozenVersions() {
        Stub().use { stub->
            stub.install();val c=Robolectric.buildActivity(StorageActivity::class.java).setup();val a=c.get();val root=a.window.decorView
            waitFor { texts(root).any { it.startsWith("2 项") } }
            button(root,"占位文件").performClick()
            assertEquals(PlaceholderFilesActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
            button(root,"多选").performClick();button(root,"全选结果").performClick()
            val search=all(root).filterIsInstance<EditText>().single { it.contentDescription=="搜索文件名" }
            search.setText("a.txt");assertTrue(texts(root).any { it.startsWith("已选 2 项") })
            preview(root,"file-multiselect")
            for((label,operation) in listOf("复制" to "copy","移动" to "move")) {
                button(root,label).performClick()
                waitFor { latest().getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled==true }
                latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
                val confirmation=latest();confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor { stub.requests.any { it.optString("action")=="create" && it.optString("operation")==operation } && texts(root).any { it.startsWith("$label：成功 1，失败 1") } && button(root,"退出多选").isEnabled }
                assertFalse(confirmation.isShowing)
                val q=stub.requests.last { it.optString("action")=="create" }
                assertEquals(operation,q.getString("operation"));assertEquals("C:\\data",q.getString("destination"))
                assertEquals(2,q.getJSONArray("items").length())
                assertEquals(setOf("a".repeat(64),"b".repeat(64)),(0..1).map { q.getJSONArray("items").getJSONObject(it).getString("file_id") }.toSet())
                preview(root,"file-batch-result")
                preview(root,"file-batch-small",640)
                assertTrue("At least one file row must remain reachable",all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single().height>=72)
                search.setText("");button(root,"全选结果").performClick()
            }
            button(root,"反选").performClick();assertTrue(texts(root).any { it.startsWith("已选 0 项") })
            c.pause().stop().destroy()
        }
    }
    @Test fun processesSkipProtectedContinueAfterFailureAndReportAtTop() {
        Stub().use { stub->
            stub.install();val c=Robolectric.buildActivity(TaskManagerActivity::class.java).setup();val root=c.get().window.decorView
            waitFor { texts(root).any { it.contains("4 个进程") } }
            button(root,"多选").performClick();button(root,"全选结果").performClick()
            preview(root,"process-multiselect")
            button(root,"结束所选").performClick();shadowOf(Looper.getMainLooper()).idle();val confirmation=latest()
            assertFalse(stub.requests.any { it.optString("action")=="terminate" })
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { texts(root).any { it.contains("成功 2，失败 1，跳过/未执行 1") } }
            assertFalse(confirmation.isShowing)
            val ended=stub.requests.filter { it.optString("action")=="terminate" }
            assertEquals(listOf(1,2,3),ended.map { it.getInt("pid") })
            assertEquals(listOf("101","102","103"),ended.map { it.getString("created") })
            assertTrue(texts(root).any { it.contains("受保护或权限不足") && it.contains("protected.exe") })
            preview(root,"process-batch-result");c.pause().stop().destroy()
        }
    }
    @Test fun processRefreshDropsSelectionWhenPidIsReused() {
        Stub().use { stub->
            stub.install();val c=Robolectric.buildActivity(TaskManagerActivity::class.java).setup();val root=c.get().window.decorView
            waitFor { texts(root).any { it.contains("4 个进程") } }
            button(root,"多选").performClick();button(root,"全选结果").performClick();stub.reused=true
            button(root,"刷新").performClick()
            waitFor { texts(root).any { it.startsWith("已选 3 项") } }
            c.pause().stop().destroy()
        }
    }
    @Test fun oldReceiverDisablesForceDisconnectWithCompleteUpgradeInstructions() {
        Stub().use { stub->
            // 设备页已迁移到 Compose（miuix）：按页面状态断言强制断开不可用、安全弹出仍可用。
            stub.version="2.4.0";stub.install();val c=Robolectric.buildActivity(DevicesActivity::class.java).setup()
            val page=c.get()
            waitFor { page.devices.isNotEmpty() && !page.busy }
            assertFalse("旧接收端不应支持强制断开",page.connectionSupported)
            assertTrue(usbUpgradeMessage(stub.version).contains("替换完整 Windows 包"))
            assertTrue(page.admin)
            val device=page.devices.single { it.name=="Test USB" }
            assertTrue("安全弹出仍应可用",device.ejectable&&!device.protectedDisk)
            assertFalse(stub.requests.any { it.optString("action")=="force_disconnect" })
            c.pause().stop().destroy()
        }
    }
    @Test fun mismatchedReceiverModulesTranslateInvalidActionWithoutRetrying() {
        Stub().use { stub->
            // 设备页已迁移到 Compose（miuix）：经页面确认框触发强制断开，断言不重试且入口随后关闭。
            stub.install();val c=Robolectric.buildActivity(DevicesActivity::class.java).setup()
            val page=c.get()
            waitFor { page.devices.isNotEmpty() && !page.busy && page.connectionSupported }
            val device=page.devices.single { it.name=="Test USB" }
            page.confirm(
                "强制断开 ${device.name}？","测试确认",
                JSONObject().put("action","force_disconnect").put("device_id",device.id)
                    .put("confirm_data_loss",true)
            )
            shadowOf(Looper.getMainLooper()).idle();val confirmation=latest()
            assertFalse(stub.requests.any { it.optString("action")=="force_disconnect" })
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { !page.busy && !page.connectionSupported }
            assertFalse(confirmation.isShowing)
            assertEquals(1,stub.requests.count { it.optString("action")=="force_disconnect" })
            c.pause().stop().destroy()
        }
    }
    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();var version="2.7.0";var reused=false;private var job:JSONObject?=null
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "storage"->r.put("home","C:\\data").put("drives",JSONArray())
                        "ls"->r.put("path","C:\\data").put("parent","C:\\").put("items",JSONArray().apply { for(name in listOf("a.txt","b.txt"))put(JSONObject().put("name",name).put("dir",false).put("size",1).put("file_id",name.take(1).repeat(64))) })
                        "file_jobs"->{
                            if(q.optString("action")=="create") {
                                job=JSONObject().put("id","batch").put("operation",q.getString("operation")).put("finished",1).put("items",JSONArray().apply {
                                    val items=q.getJSONArray("items");for(i in 0 until items.length())put(JSONObject(items.getJSONObject(i).toString()).put("status",if(i==0)"completed" else "failed").put("error","目标已存在，未覆盖"))
                                });r.put("job",job)
                            }
                            r.put("jobs",JSONArray().apply { job?.let { put(it) } })
                        }
                        "tasks"->if(q.optString("action")=="terminate") { if(q.optInt("pid")==2)r.put("ok",false).put("error","权限不足") }
                            else r.put("items",JSONArray().apply { for(pid in 1..4)put(JSONObject().put("pid",pid).put("created",if(reused && pid==1)"new" else "${100+pid}").put("name",if(pid==4)"protected.exe" else "$pid.exe").put("can_terminate",pid!=4).put("memory",100-pid)) })
                        "capabilities"->r.put("version",version).put("features",JSONArray())
                        "devices"->{
                            if(q.optString("action")=="force_disconnect")r.put("ok",false).put("error","无效的设备管理操作")
                            r.put("admin",true).put("policies",JSONObject().put("storage",JSONObject()).put("portable",JSONObject())).put("devices",JSONArray().put(JSONObject().put("id","USBSTOR\\TEST").put("kind","storage").put("name","Test USB").put("ejectable",true)))
                        }
                    }
                    requests.add(q);writer.write(r.toString()+"\n");writer.flush()
                }
            } } catch(_:Exception){}
        }.apply { isDaemon=true;start() }
        fun install() { runBlocking { client.connect() };ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client) }
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

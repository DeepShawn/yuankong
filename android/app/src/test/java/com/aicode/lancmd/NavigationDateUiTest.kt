package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationDateUiTest {
    private lateinit var zone:TimeZone
    @Before fun before(){zone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));disconnect()}
    @After fun after(){TimeZone.setDefault(zone);disconnect()}
    private fun disconnect(){ConnectionManager.stop();runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() };shadowOf(Looper.getMainLooper()).idle()}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun buttons(v:View)=all(v).filterIsInstance<MaterialButton>()
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun waitFor(check:()->Boolean) { val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};fail("UI did not settle") }
    private fun preview(v:View,name:String,height:Int=891) {
        v.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,411,height)
        val bitmap=Bitmap.createBitmap(411,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun homeCategoryFiltersToolsAndSurvivesRecreation() {
        // 首页已迁移到 Compose（miuix）：分类是页面状态，重建后保留。
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();var a=c.get()
        assertEquals(
            listOf("共享与控制","文件与设备","系统与应用","录制与自动化"),
            a.toolGroups.keys.toList()
        )
        assertEquals("命令控制台",a.homeCategory)
        a.homeCategory="文件与设备"
        assertEquals(listOf("文件管理","文件搜索","U盘与设备","隐私设备"),a.toolGroups.getValue("文件与设备").map { it.label })
        preview(a.window.decorView,"home-file-category-small",640)
        c.recreate();a=c.get()
        assertEquals("文件与设备",a.homeCategory)
        a.homeCategory="系统与应用"
        assertEquals(listOf("任务管理器","注册表编辑器","浏览器","应用中心","应用锁","桌面背景","本地升级","启动与网络"),a.toolGroups.getValue("系统与应用").map{it.label})
        a.homeCategory="命令控制台"
        c.recreate();a=c.get()
        assertEquals("命令控制台",a.homeCategory)
        c.pause().stop().destroy()
    }
    @Test fun sharedToolSearchFindsDateWorkflowAndOpensFileManager() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        ToolNavigator.show(a);shadowOf(Looper.getMainLooper()).idle()
        val dialog=ShadowDialog.getLatestDialog();val root=dialog.window!!.decorView
        all(root).filterIsInstance<EditText>().single { it.contentDescription=="搜索全部工具" }.setText("日期")
        val results=buttons(root).filter { it.contentDescription=="文件管理" }
        assertEquals(1,results.size);preview(root,"tool-search-date")
        results.single().performClick();assertFalse(dialog.isShowing)
        assertEquals(StorageActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        c.pause().stop().destroy()
    }
    @Test fun groupedFileMenuEditsModifiedDateAndRejectsInvalidCalendarDate() {
        Stub().use { stub->
            // 文件管理页已迁移到 Compose（miuix）：分组菜单改用系统列表弹窗，分组不变。
            stub.install();val c=Robolectric.buildActivity(StorageActivity::class.java).setup();val a=c.get();val root=a.window.decorView
            waitFor { a.visible.any { it.optString("name")=="report.docx" } }
            val item=JSONObject().put("name","report.docx").put("mtime",1700000000).put("file_id","a".repeat(64))
            assertEquals(listOf("打开与预览","编辑与属性","传输与整理","删除"),a.menuGroups(item).keys.toList())
            a.act("最后修改日期",item)
            shadowOf(Looper.getMainLooper()).idle();val dialog=latest();val field=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertTrue(buttons(dialog.window!!.decorView).any { it.text=="选择日期" });assertTrue(buttons(dialog.window!!.decorView).any { it.text=="选择时间" })
            field.setText("2025-02-30 10:20:30");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
            assertFalse(stub.requests.any { it.optString("action")=="mtime" })
            field.setText("2025-03-08 10:20:30");preview(dialog.window!!.decorView,"modified-date-editor")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { a.note.contains("修改日期已保存") }
            val q=stub.requests.single { it.optString("action")=="mtime" }
            assertEquals(1741429230L,q.getDouble("modified").toLong());assertEquals("a".repeat(64),q.getString("file_id"))
            assertFalse(dialog.isShowing);c.pause().stop().destroy()
        }
    }
    @Test fun deviceFailureRemainsBesideItsCardAfterRefreshAndFiltering() {
        Stub().use { stub->
            // 隐私设备页已迁移到 Compose（miuix）：失败提示按设备编号留在该设备的卡片上，
            // 刷新和切换分类都不会把它挪到全局状态里。
            stub.install();val c=Robolectric.buildActivity(PrivacyDevicesActivity::class.java).setup()
            val page=c.get();val root=page.window.decorView
            waitFor { page.devices.isNotEmpty() && !page.busy }
            page.selectedKind="camera"
            val camera=page.devices.single { it.kind=="camera" }
            page.confirmToggle(camera);shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { page.feedback["camera"]?.first?.contains("驱动拒绝")==true && !page.busy }
            assertTrue("失败提示应标记为错误",page.feedback.getValue("camera").second)
            preview(root,"privacy-device-failure")
            page.selectedKind="microphone";shadowOf(Looper.getMainLooper()).idle()
            assertTrue("切换分类不丢弃该设备的结果",page.feedback.containsKey("camera"))
            page.selectedKind="camera";shadowOf(Looper.getMainLooper()).idle()
            assertTrue(page.feedback.getValue("camera").first.contains("驱动拒绝"))
            c.pause().stop().destroy()
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
                        "storage"->r.put("home","C:\\data").put("drives",JSONArray())
                        "ls"->r.put("path","C:\\data").put("items",JSONArray().put(JSONObject().put("name","report.docx").put("mtime",1700000000).put("file_id","a".repeat(64))))
                        "file_jobs"->r.put("jobs",JSONArray())
                        "privacy_devices"->{
                            if(q.optString("action")=="set_enabled")r.put("ok",false).put("error","驱动拒绝停用，其他程序仍在占用")
                            r.put("admin",true).put("items",JSONArray().put(JSONObject().put("id","camera").put("name","SC06").put("kind","camera").put("enabled",true).put("problem",0)).put(JSONObject().put("id","mic").put("name","Microphone").put("kind","microphone").put("enabled",true).put("problem",0).put("muted",false)))
                        }
                    }
                    requests.add(q);writer.write(r.toString()+"\n");writer.flush()
                }
            } } catch(_:Exception){}
        }.apply { isDaemon=true;start() }
        fun install(){runBlocking { client.connect() };ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
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
    private fun entries(menu:Menu):List<MenuItem> =(0 until menu.size()).flatMap { i->val item=menu.getItem(i);listOf(item)+(item.subMenu?.let { entries(it) } ?: emptyList()) }
    private fun preview(v:View,name:String,height:Int=891) {
        v.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,411,height)
        val bitmap=Bitmap.createBitmap(411,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun homeCategoryFiltersToolsAndSurvivesRecreation() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();var a=c.get()
        all(a.window.decorView).filterIsInstance<Chip>().single { it.text=="文件与设备" }.performClick()
        assertEquals(View.GONE,a.findViewById<View>(R.id.managementCards).visibility)
        assertEquals(View.VISIBLE,a.findViewById<View>(R.id.rowActions2).visibility)
        preview(a.window.decorView,"home-file-category-small",640)
        c.recreate();a=c.get()
        assertTrue(all(a.window.decorView).filterIsInstance<Chip>().single { it.text=="文件与设备" }.isChecked)
        assertEquals(View.GONE,a.findViewById<View>(R.id.managementCards).visibility)
        all(a.window.decorView).filterIsInstance<Chip>().single { it.text=="全部" }.performClick()
        assertEquals(View.VISIBLE,a.findViewById<View>(R.id.managementCards).visibility)
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
            stub.install();val c=Robolectric.buildActivity(StorageActivity::class.java).setup();val a=c.get();val root=a.window.decorView
            waitFor { all(root).filterIsInstance<TextView>().any { it.text=="report.docx" } }
            val item=JSONObject().put("name","report.docx").put("mtime",1700000000).put("file_id","a".repeat(64))
            StorageActivity::class.java.getDeclaredMethod("fileActions",JSONObject::class.java,View::class.java).apply { isAccessible=true }.invoke(a,item,root)
            val menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()!!
            assertEquals(listOf("打开与预览","编辑与属性","传输与整理","删除"),(0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() })
            val date=entries(menu.menu).single { it.title=="最后修改日期" }
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowPopupMenu>(menu).onMenuItemClickListener!!.onMenuItemClick(date)
            shadowOf(Looper.getMainLooper()).idle();val dialog=latest();val field=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertTrue(buttons(dialog.window!!.decorView).any { it.text=="选择日期" });assertTrue(buttons(dialog.window!!.decorView).any { it.text=="选择时间" })
            field.setText("2025-02-30 10:20:30");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
            assertFalse(stub.requests.any { it.optString("action")=="mtime" })
            field.setText("2025-03-08 10:20:30");preview(dialog.window!!.decorView,"modified-date-editor")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { all(root).filterIsInstance<TextView>().any { it.text.contains("修改日期已保存") } }
            val q=stub.requests.single { it.optString("action")=="mtime" }
            assertEquals(1741429230L,q.getDouble("modified").toLong());assertEquals("a".repeat(64),q.getString("file_id"))
            assertFalse(dialog.isShowing);menu.dismiss();c.pause().stop().destroy()
        }
    }
    @Test fun deviceFailureRemainsBesideItsCardAfterRefreshAndFiltering() {
        Stub().use { stub->
            stub.install();val c=Robolectric.buildActivity(PrivacyDevicesActivity::class.java).setup();val root=c.get().window.decorView
            waitFor { buttons(root).any { it.text=="停用设备" && it.isEnabled } }
            buttons(root).single { it.contentDescription=="筛选摄像头" }.performClick()
            buttons(root).single { it.text=="停用设备" }.performClick();shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { all(root).filterIsInstance<TextView>().any { it.contentDescription=="设备操作结果 camera" && it.text.contains("驱动拒绝") } && buttons(root).any { it.text=="刷新设备状态" && it.isEnabled } }
            preview(root,"privacy-device-failure")
            buttons(root).single { it.contentDescription=="筛选麦克风" }.performClick()
            assertFalse(all(root).filterIsInstance<TextView>().any { it.contentDescription=="设备操作结果 camera" })
            buttons(root).single { it.contentDescription=="筛选摄像头" }.performClick()
            assertTrue(all(root).filterIsInstance<TextView>().any { it.contentDescription=="设备操作结果 camera" })
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

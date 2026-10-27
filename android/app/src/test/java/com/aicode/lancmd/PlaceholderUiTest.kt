package com.aicode.lancmd

import android.app.Application
import android.content.Intent
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaceholderUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        val field=ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() };shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) }
        fail("UI did not settle")
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun buttons(root:View)=all(root).filterIsInstance<MaterialButton>()
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun choose(index:Int) { latest().listView.performItemClick(null,index,index.toLong());shadowOf(Looper.getMainLooper()).idle() }
    private fun render(view:View,name:String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));view.layout(0,0,411,891)
        val bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun exactDecimalSizeAcceptsZeroAnd500GbWithoutOverflowOrRounding() {
        assertEquals(0L,PlaceholderFilesActivity.parseSize("0"))
        assertEquals(500_000_000_000L,PlaceholderFilesActivity.parseSize("500.000000000"))
        assertEquals(1L,PlaceholderFilesActivity.parseSize("0.000000001"))
        assertEquals(500_000_000L,PlaceholderFilesActivity.parseSize(".5"))
        for(value in listOf("-1","500.000000001","501","NaN","1e9","0.0000000001","")) {
            try { PlaceholderFilesActivity.parseSize(value);fail("accepted $value") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun placeholderCreationAndCancellationUseExplicitConfirmationAndJobId() {
        Stub().use { stub->
            stub.install()
            val controller=Robolectric.buildActivity(PlaceholderFilesActivity::class.java,Intent(RuntimeEnvironment.getApplication(),PlaceholderFilesActivity::class.java).putExtra("directory","E:\\")).setup()
            val root=controller.get().window.decorView
            waitFor { stub.requests.any { it.optString("cmd")=="placeholders" } && buttons(root).single { it.text=="创建占位文件" }.isEnabled }
            val size=all(root).filterIsInstance<EditText>().single { it.contentDescription=="占位文件大小 GB" };size.setText("500")
            buttons(root).single { it.text=="创建占位文件" }.performClick();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="create" })
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="create" })
            buttons(root).single { it.text=="创建占位文件" }.performClick();shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { buttons(root).any { it.text=="取消创建" } && buttons(root).single { it.text=="刷新占位任务" }.isEnabled }
            val request=stub.requests.single { it.optString("action")=="create" }
            assertEquals(500_000_000_000L,request.getLong("size"));assertEquals("E:\\LanCmd-placeholder.dat",request.getString("path"))
            render(root,"placeholder-files")
            buttons(root).single { it.text=="取消创建" }.performClick()
            waitFor { all(root).filterIsInstance<TextView>().any { it.text=="已取消并清理" } }
            assertEquals("placeholder-job",stub.requests.single { it.optString("action")=="cancel" }.getString("job_id"))
            controller.pause().stop().destroy()
        }
    }
    @Test fun fileManagerOpensPlaceholderPageAndShowsHiddenFiles() {
        Stub().use { stub->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val activity=controller.get();val root=activity.window.decorView
            waitFor { all(root).filterIsInstance<TextView>().any { it.text=="reserve.dat" } }
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.contains("隐藏") && it.text.contains("系统") })
            buttons(root).single { it.text=="新建" }.performClick();shadowOf(Looper.getMainLooper()).idle();choose(10)
            val opened=shadowOf(activity).nextStartedActivity
            assertEquals(PlaceholderFilesActivity::class.java.name,opened.component!!.className)
            assertEquals("E:\\",opened.getStringExtra("directory"))
            controller.pause().stop().destroy()
        }
    }
    @Test fun usbSafeEjectAndForceDisconnectAreSeparateConfirmedActionsWithRestore() {
        Stub().use { stub->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val root=controller.get().window.decorView
            waitFor { all(root).filterIsInstance<TextView>().any { it.text=="reserve.dat" } }
            fun select(force:Boolean) {
                buttons(root).single { it.text=="U盘弹出 / 断开" }.performClick();shadowOf(Looper.getMainLooper()).idle();choose(if(force)1 else 0)
                waitFor { latest().listView?.adapter?.count==1 };choose(0)
            }
            select(false);assertFalse(stub.requests.any { it.optString("action")=="eject" })
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            select(false);latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.count { it.optString("cmd")=="ls" }>=2 }
            assertEquals("USBSTOR\\TEST",stub.requests.single { it.optString("action")=="eject" }.getString("device_id"))
            assertFalse(stub.requests.any { it.optString("action")=="force_disconnect" })
            select(true);assertFalse(stub.requests.any { it.optString("action")=="force_disconnect" })
            assertEquals("仍然强制断开",latest().getButton(AlertDialog.BUTTON_POSITIVE).text)
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="force_disconnect" } }
            val forced=stub.requests.single { it.optString("action")=="force_disconnect" }
            assertTrue(forced.getBoolean("confirm_data_loss"));assertEquals("USBSTOR\\TEST",forced.getString("device_id"))
            controller.pause().stop().destroy()
            val devices=Robolectric.buildActivity(DevicesActivity::class.java).setup();val deviceRoot=devices.get().window.decorView
            waitFor { buttons(deviceRoot).any { it.text=="恢复连接 · Test USB" && it.isEnabled } }
            buttons(deviceRoot).single { it.text=="恢复连接 · Test USB" }.performClick();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="restore_connection" })
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="restore_connection" } }
            assertEquals("USBSTOR\\TEST",stub.requests.single { it.optString("action")=="restore_connection" }.getString("device_id"))
            devices.pause().stop().destroy()
        }
    }
    @Test fun pendingUsbConfirmationCannotDisconnectANewComputer() {
        Stub().use { first->Stub().use { second->
            first.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val root=controller.get().window.decorView
            waitFor { all(root).filterIsInstance<TextView>().any { it.text=="reserve.dat" } }
            buttons(root).single { it.text=="U盘弹出 / 断开" }.performClick();shadowOf(Looper.getMainLooper()).idle();choose(1)
            waitFor { latest().listView?.adapter?.count==1 };choose(0)
            second.install();latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { all(root).filterIsInstance<TextView>().any { it.text.contains("电脑连接已改变") } }
            assertFalse((first.requests+second.requests).any { it.optString("action")=="force_disconnect" })
            controller.pause().stop().destroy()
        } }
    }

    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();private var jobState="";private var disconnected=false
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "storage"->result.put("home","E:\\").put("drives",JSONArray())
                        "ls"->result.put("path","E:\\").put("items",JSONArray().put(JSONObject().put("name","reserve.dat").put("dir",false).put("size",128).put("hidden",true).put("system",true)))
                        "placeholders"->{
                            when(q.optString("action")){"create"->jobState="writing";"cancel"->jobState="cancelled"}
                            result.put("items",JSONArray().apply { if(jobState.isNotEmpty())put(JSONObject().put("id","placeholder-job").put("path","E:\\LanCmd-placeholder.dat").put("size",500_000_000_000L).put("written",12).put("status",jobState)) })
                        }
                        "devices"->{
                            when(q.optString("action")){"force_disconnect"->disconnected=true;"restore_connection"->disconnected=false}
                            val usb=JSONObject().put("id","USBSTOR\\TEST").put("name","Test USB").put("kind","storage").put("paths",JSONArray().put("E:\\")).put("protected",false).put("ejectable",true)
                            result.put("admin",true).put("notice","操作完成").put("policies",JSONObject().put("storage",JSONObject().put("mode","allow")).put("portable",JSONObject().put("mode","allow")))
                                .put("devices",JSONArray().put(usb).put(JSONObject().put("id","USBSTOR\\SYSTEM").put("name","System USB").put("kind","storage").put("protected",true).put("ejectable",true)))
                                .put("disconnected",JSONArray().apply { if(disconnected)put(JSONObject(usb.toString()).put("enabled",false).put("problem",22)) })
                        }
                        "capabilities"->result.put("features",JSONArray().put("placeholders"))
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

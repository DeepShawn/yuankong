package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
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
    private fun render(view:View,name:String,width:Int=411,height:Int=891) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun editor()=Robolectric.buildActivity(TextEditorActivity::class.java,Intent(RuntimeEnvironment.getApplication(),TextEditorActivity::class.java).putExtra("path","C:\\notes\\test.bat")).setup()
    @Test fun editorLoadsAndSavesRevisionEncodingAndTextWithoutExecuting() {
        Stub().use { stub->
            // 文本编辑页已迁移到 Compose（miuix）：驱动页面的编辑与保存入口。
            stub.install();val controller=editor();val page=controller.get();val root=page.window.decorView
            waitFor { page.editable };assertEquals("initial\r\n",page.body)
            page.edit("echo 中文\nexit /b 0")
            page.save()
            waitFor { stub.requests.any { it.optString("action")=="text_write" } }
            val saved=stub.requests.single { it.optString("action")=="text_write" }
            assertEquals("echo 中文\nexit /b 0",saved.getString("text"));assertEquals("r1",saved.getString("revision"));assertEquals("utf-8",saved.getString("encoding"));assertEquals("crlf",saved.getString("newline"))
            assertFalse(stub.requests.any { it.optString("cmd")=="scripts" })
            render(root,"text-editor");controller.pause().stop().destroy()
        }
    }
    @Test fun autosaveDebouncesAndConflictKeepsTextAndStopsAutomaticWrites() {
        Stub().use { stub->
            stub.install();val controller=editor();val page=controller.get()
            waitFor { page.editable };stub.conflict=true
            page.autoSave=true;page.edit("draft conflict")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
            waitFor { stub.requests.any { it.optString("action")=="text_write" } && !page.autoSave }
            assertEquals("draft conflict",page.body)
            assertTrue(page.note.contains("外部修改"))
            controller.pause().stop().destroy()
        }
    }
    @Test fun unsavedDraftSurvivesRecreationAndOldConnectionCannotSaveToNewComputer() {
        Stub().use { first->Stub().use { second->
            first.install();val controller=editor()
            waitFor { controller.get().editable };controller.get().edit("local draft")
            controller.recreate();val page=controller.get()
            waitFor { page.editable && page.body=="local draft" }
            second.install();page.save()
            waitFor { page.note.contains("电脑已切换") }
            assertFalse(second.requests.any { it.optString("action")=="text_write" })
            assertEquals("local draft",page.body);controller.pause().stop().destroy()
        } }
    }
    @Test fun failedReloadKeepsDraftEditableAndCanRestoreWithoutNetworkRead() {
        Stub().use { stub->
            stub.install();val controller=editor()
            waitFor { controller.get().editable };controller.get().edit("draft to retain")
            stub.failRead=true
            controller.get().confirmReload();shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { controller.get().note.contains("读取失败") }
            assertTrue(controller.get().editable);assertEquals("draft to retain",controller.get().body)
            val reads=stub.requests.count { it.optString("action")=="text_read" }
            controller.recreate();val page=controller.get()
            waitFor { page.editable && page.body=="draft to retain" }
            assertEquals("Draft restoration must not depend on a successful PC read",reads,stub.requests.count { it.optString("action")=="text_read" })
            controller.pause().stop().destroy()
        }
    }
    @Test fun revertingChangesDoesNotRestoreAnObsoleteDraftLater() {
        Stub().use { stub->
            stub.install();val controller=editor()
            waitFor { controller.get().editable };controller.get().edit("obsolete draft")
            controller.pause().resume();controller.get().edit("initial\r\n");controller.pause().stop().destroy()
            val reopened=editor();val page=reopened.get()
            waitFor { page.editable };assertEquals("initial\r\n",page.body)
            reopened.pause().stop().destroy()
        }
    }
    @Test fun privacyAndAppLockRequirePhoneConfirmationBeforeChangingComputer() {
        Stub().use { stub->
            stub.install()
            // 应用锁页已迁移到 Compose（miuix）：按钮不再是 View，
            // 这里驱动页面自身的操作入口，确认依然要经过手机上的确认框。
            val lock=Robolectric.buildActivity(AppLocksActivity::class.java,Intent(RuntimeEnvironment.getApplication(),AppLocksActivity::class.java).putExtra("path","C:\\Apps\\test.exe")).setup()
            val locks=lock.get()
            assertEquals("C:\\Apps\\test.exe",locks.pathInput)
            waitFor { !locks.busy }
            locks.confirmLock();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="lock" })
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="lock" } && locks.locks.any { it.state=="locked" } }
            assertEquals("C:\\Apps\\test.exe",stub.requests.single { it.optString("action")=="lock" }.getString("path"))
            render(locks.window.decorView,"app-locks")
            waitFor { !locks.busy }
            locks.mutate(JSONObject().put("action","unlock").put("lock_id",locks.locks.first().id))
            waitFor { stub.requests.any { it.optString("action")=="unlock" } }
            assertEquals("lock-entry",stub.requests.single { it.optString("action")=="unlock" }.getString("lock_id"))
            waitFor { !locks.busy }
            locks.mutate(JSONObject().put("action","forget").put("lock_id","lock-entry"))
            waitFor { stub.requests.any { it.optString("action")=="forget" } }
            assertEquals("lock-entry",stub.requests.single { it.optString("action")=="forget" }.getString("lock_id"))
            lock.pause().stop().destroy()
            // 隐私设备页已迁移到 Compose（miuix）：操作入口由页面提供，
            // 启停仍必须经过手机上的确认框。
            val privacy=Robolectric.buildActivity(PrivacyDevicesActivity::class.java).setup()
            val page=privacy.get();val root=page.window.decorView
            waitFor { page.devices.size==1 && !page.busy }
            val mic=page.devices.single()
            assertTrue("启停需要管理员且驱动状态可改",page.admin && mic.changeable)
            assertTrue("麦克风支持静音",mic.microphone && mic.muted!=null && mic.enabled)
            page.confirmToggle(mic)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="set_enabled" })
            val confirmation=ShadowDialog.getLatestDialog() as AlertDialog
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            waitFor { !confirmation.isShowing }
            assertFalse(stub.requests.any { it.optString("action")=="set_enabled" })
            page.operate(JSONObject().put("action","mute").put("device_id",mic.id).put("muted",mic.muted!=true))
            waitFor { stub.requests.any { it.optString("action")=="mute" } }
            assertEquals("mic",stub.requests.single { it.optString("action")=="mute" }.getString("device_id"))
            waitFor { !page.busy && page.devices.isNotEmpty() }
            page.confirmToggle(page.devices.single());shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="set_enabled" } }
            assertEquals("mic",stub.requests.single { it.optString("action")=="set_enabled" }.getString("device_id"))
            render(root,"privacy-devices");privacy.pause().stop().destroy()
        }
    }
    @Test fun batAndVbsFileMenusRunOnlyAfterConfirmationAndKeepSelectedPath() {
        Stub().use { stub->
            // 文件管理页已迁移到 Compose（miuix）：单项菜单改用系统列表弹窗，
            // 这里直接驱动菜单项，脚本仍必须经过确认才运行。
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val activity=controller.get();val root=activity.window.decorView
            waitFor { activity.note.startsWith("2 项") }
            render(root,"file-script-menu")
            for(extension in listOf("bat","vbs")) {
                val before=stub.requests.count { it.optString("cmd")=="scripts" && it.optString("action")=="run" }
                val item=JSONObject().put("name","test.$extension").put("dir",false)
                assertEquals(
                    listOf("运行 ${extension.uppercase()}"),
                    activity.menuGroups(item).getValue("高级操作")
                )
                activity.act("运行 ${extension.uppercase()}",item);shadowOf(Looper.getMainLooper()).idle()
                assertEquals(before,stub.requests.count { it.optString("cmd")=="scripts" && it.optString("action")=="run" })
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor { stub.requests.count { it.optString("cmd")=="scripts" && it.optString("action")=="run" }==before+1 }
                val request=stub.requests.last { it.optString("cmd")=="scripts" && it.optString("action")=="run" }
                assertEquals("C:\\notes\\test.$extension",request.getString("path"))
                (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            }
            controller.pause().stop().destroy()
        }
    }
    @Test fun recordingStopAndDeleteKeepEntityIdSeparateFromRequestId() {
        Stub().use { stub->
            // 录制页已迁移到 Compose（miuix）：直接驱动页面的停止与删除入口。
            stub.install();val controller=Robolectric.buildActivity(RecordingsActivity::class.java).setup()
            val page=controller.get()
            waitFor { page.records.any { it.active } && !page.busy }
            page.stop(page.records.first { it.active })
            waitFor { stub.requests.any { it.optString("action")=="stop" } && !page.busy }
            assertEquals("recording-entry",stub.requests.single { it.optString("action")=="stop" }.getString("recording_id"))
            page.confirmDelete(page.records.first());shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="delete" })
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="delete" } }
            assertEquals("recording-entry",stub.requests.single { it.optString("action")=="delete" }.getString("recording_id"))
            controller.pause().stop().destroy()
        }
    }
    @Test fun registryRestoreAndScriptCancelKeepEntityIdSeparateFromRequestId() {
        Stub().use { stub->
            // 注册表页已迁移到 Compose（miuix）：由页面入口打开修改备份列表。
            stub.install();val controller=Robolectric.buildActivity(RegistryActivity::class.java).setup()
            val page=controller.get()
            waitFor { page.keys.isNotEmpty() && !page.busy }
            page.history();shadowOf(Looper.getMainLooper()).idle()
            waitFor { (ShadowDialog.getLatestDialog() as? AlertDialog)?.listView?.adapter?.count==1 }
            val history=ShadowDialog.getLatestDialog() as AlertDialog
            history.listView.performItemClick(null,0,0);shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stub.requests.any { it.optString("action")=="restore" })
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="restore" } }
            assertEquals("backup-entry",stub.requests.single { it.optString("action")=="restore" }.getString("backup_id"))
            stub.showScriptJob=true
            ScriptJobsDialog.show(controller.get(),ConnectionManager.sessionClient());shadowOf(Looper.getMainLooper()).idle()
            val jobs=ShadowDialog.getLatestDialog() as AlertDialog
            val list=all(jobs.window!!.decorView).filterIsInstance<android.widget.ListView>().single()
            waitFor { list.adapter?.count==1 };list.performItemClick(null,0,0);shadowOf(Looper.getMainLooper()).idle()
            val detail=ShadowDialog.getLatestDialog() as AlertDialog
            waitFor { detail.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
            detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { stub.requests.any { it.optString("action")=="cancel" } }
            assertEquals("script-entry",stub.requests.single { it.optString("action")=="cancel" }.getString("job_id"))
            detail.dismiss();jobs.dismiss();controller.pause().stop().destroy()
        }
    }
    @Test fun registryTasksBrowserAndHomeCardsRenderWithReachableConsole() {
        Stub().use { stub->
            stub.install()
            run {
                // 任务管理器已迁移到 Compose（miuix）：按页面状态等待读取完成。
                val controller=Robolectric.buildActivity(TaskManagerActivity::class.java).setup()
                val page=controller.get();val root=page.window.decorView
                waitFor { stub.requests.any { it.optString("cmd")=="tasks" } && page.note.contains("1 个进程") }
                shadowOf(Looper.getMainLooper()).idle()
                render(root,"task-manager");controller.pause().stop().destroy()
            }
            // 注册表页已迁移到 Compose（miuix）：核对读取到的子项与值，而不是查 TextView。
            run {
                val controller=Robolectric.buildActivity(RegistryActivity::class.java).setup()
                val page=controller.get()
                waitFor { stub.requests.any { it.optString("cmd")=="registry" } && page.keys.isNotEmpty() && !page.busy }
                assertEquals(listOf("Software"),page.keys)
                assertTrue(page.values.isEmpty())
                shadowOf(Looper.getMainLooper()).idle()
                render(page.window.decorView,"registry-editor");controller.pause().stop().destroy()
            }
            // 浏览器页已迁移到 Compose（miuix），文本不再是 TextView，
            // 这里只核对页面打开后向电脑读取了浏览器列表。
            run {
                val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup()
                waitFor { stub.requests.any { it.optString("cmd")=="browser" } }
                shadowOf(Looper.getMainLooper()).idle()
                controller.pause().stop().destroy()
            }
            // 首页已迁移到 Compose（miuix）：分类入口按状态断言，两种高度都要能画出内容。
            val home=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=home.get()
            activity.homeCategory="系统与应用"
            assertEquals(4,activity.toolGroups.getValue("系统与应用").size)
            for(height in listOf(640,891)) {
                render(activity.window.decorView,"management-home-$height",height=height)
            }
            home.pause().stop().destroy()
        }
    }

    @Test fun endingProcessReportsAtTopWithoutAResultDialog() {
        Stub().use { stub->
            stub.taskCanTerminate=true;stub.install()
            val controller=Robolectric.buildActivity(TaskManagerActivity::class.java).setup();val activity=controller.get()
            waitFor { activity.note.contains("1 个进程") }
            activity.details(JSONObject().put("pid",777).put("created","100").put("name","test.exe").put("can_terminate",true))
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
            val confirmation=ShadowDialog.getLatestDialog() as AlertDialog
            assertFalse(stub.requests.any { it.optString("action")=="terminate" })
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor { activity.note.contains("成功 1") }
            assertFalse(confirmation.isShowing)
            assertEquals(777,stub.requests.single { it.optString("action")=="terminate" }.getInt("pid"))
            confirmation.dismiss();controller.pause().stop().destroy()
        }
    }

    private class Stub:Closeable {
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>();var conflict=false;var failRead=false;var showScriptJob=false;var taskCanTerminate=false
        private var lockState="";private var recordingState="recording"
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "files"->if(q.optString("action")=="text_read") {
                            if(failRead)result.put("ok",false).put("error","读取失败")
                            else result.put("text","initial\r\n").put("encoding","utf-8").put("newline","crlf").put("revision","r1").put("size",9)
                        }
                            else if(conflict)result.put("ok",false).put("error","文件已被外部修改，未覆盖") else result.put("revision","r2").put("size",20)
                        "app_locks"->{
                            when(q.optString("action")){"lock"->lockState="locked";"unlock"->lockState="unlocked";"forget"->lockState=""}
                            result.put("items",JSONArray().apply { if(lockState.isNotEmpty())put(JSONObject().put("id","lock-entry").put("path","C:\\Apps\\test.exe").put("status",lockState)) }).put("backup","C:\\LanCmd\\app-locks.json")
                        }
                        "privacy_devices"->result.put("admin",true).put("items",JSONArray().put(JSONObject().put("id","mic").put("kind","microphone").put("name","Test microphone").put("enabled",true).put("problem",0).put("muted",false)))
                        "registry"->if(q.optString("action")=="history")result.put("items",JSONArray().put(JSONObject().put("id","backup-entry").put("root","HKCU").put("path","Software").put("name","Test").put("view",64).put("created",1))) else result.put("root","HKCU").put("path",q.optString("path")).put("view",64).put("keys",JSONArray().put("Software")).put("values",JSONArray()).put("key_count",1).put("value_count",0).put("key_offset",0).put("value_offset",0)
                        "tasks"->result.put("items",JSONArray().put(JSONObject().put("pid",777).put("name","test.exe").put("path","C:\\Apps\\test.exe").put("created","100").put("memory",1048576).put("cpu",1.5).put("can_terminate",taskCanTerminate)))
                        "browser"->result.put("items",JSONArray().put(JSONObject().put("id","edge").put("name","Microsoft Edge")))
                        "storage"->result.put("drives",JSONArray()).put("home","C:\\notes")
                        "ls"->result.put("path","C:\\notes").put("items",JSONArray().put(JSONObject().put("name","test.bat").put("dir",false)).put(JSONObject().put("name","test.vbs").put("dir",false)))
                        "scripts"->result.put("items",JSONArray().apply { if(showScriptJob)put(JSONObject().put("id","script-entry").put("path","C:\\notes\\test.bat").put("status","running")) })
                        "capabilities"->result.put("features",JSONArray().put("recordings"))
                        "recordings"->{
                            when(q.optString("action")){"stop"->recordingState="completed";"delete"->recordingState=""}
                            result.put("directory","C:\\Recordings").put("microphones",JSONArray()).put("cameras",JSONArray())
                                .put("recordings",JSONArray().apply { if(recordingState.isNotEmpty())put(JSONObject().put("id","recording-entry").put("mode","audio").put("status",recordingState).put("created",1).put("size",128).put("seconds",300)) })
                        }
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

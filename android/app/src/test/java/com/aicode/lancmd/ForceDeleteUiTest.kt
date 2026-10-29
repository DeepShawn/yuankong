package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
@Config(sdk=[33], application=Application::class, qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForceDeleteUiTest {
    @Before fun before() {
        disconnect()
        RuntimeEnvironment.getApplication().getSharedPreferences("file-operations", 0).edit().putInt("delay", 0).apply()
    }
    @After fun after() = disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check: () -> Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end) { shadowOf(Looper.getMainLooper()).idle(); if(check())return; Thread.sleep(10) }
        fail("强制删除界面未更新")
    }
    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun all(view:View):List<View> = listOf(view) + if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun start(page:StorageActivity) {
        waitFor { page.visible.isNotEmpty() }
        page.act("强制删除", page.visible.first())
        shadowOf(Looper.getMainLooper()).idle()
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    @Test fun occupiedFileShowsRealApplicationAndNeedsASecondExplicitConfirmation() {
        Stub().use { stub ->
            stub.install(); val controller=Robolectric.buildActivity(StorageActivity::class.java).setup(); val page=controller.get()
            try {
                start(page); waitFor { page.transferText.contains("等待在手机上确认") }
                assertFalse(stub.requests.any { it.optString("action")=="confirm_force_delete" })
                page.confirmForceDelete(); shadowOf(Looper.getMainLooper()).idle()
                val confirmation=latest()
                val text=all(confirmation.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() }
                assertTrue(text.contains("Document editor")); assertTrue(text.contains("C:\\apps\\editor.exe"))
                assertTrue(text.contains("未保存内容可能丢失"))
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor { !page.operationsBusy && page.note.startsWith("强制删除：成功 1") }
                val command=stub.requests.single { it.optString("action")=="confirm_force_delete" }
                assertEquals("approval-1",command.getString("confirmation_id")); assertTrue(command.getBoolean("confirm_data_loss"))
                assertFalse(confirmation.isShowing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun cancelLeavesOccupiedApplicationRunningAndDoesNotSendConfirmation() {
        Stub().use { stub ->
            stub.install(); val controller=Robolectric.buildActivity(StorageActivity::class.java).setup(); val page=controller.get()
            try {
                start(page); waitFor { page.transferText.contains("等待在手机上确认") }
                page.cancelTransfer(); waitFor { !page.operationsBusy }
                assertFalse(stub.requests.any { it.optString("action")=="confirm_force_delete" })
                assertTrue(stub.requests.any { it.optString("action")=="cancel" })
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun oldReceiverCannotAccidentallyTreatForceDeleteAsOrdinaryDelete() {
        Stub(supported=false).use { stub ->
            stub.install(); val controller=Robolectric.buildActivity(StorageActivity::class.java).setup(); val page=controller.get()
            try {
                start(page); waitFor { page.note.contains("完整更新 Windows 包") }
                assertFalse(stub.requests.any { it.optString("action")=="create" })
                assertFalse(page.operationsBusy)
                assertEquals(listOf("删除"),page.menuGroups(JSONObject().put("dir",true).put("name","folder"))["删除"])
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun initialForceDeleteCanBeDelayedButOccupantConfirmationRunsImmediately() {
        RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().putInt("delay",12).apply()
        Stub().use { stub ->
            stub.install(); val controller=Robolectric.buildActivity(StorageActivity::class.java).setup(); val page=controller.get()
            try {
                start(page); waitFor { page.transferText.contains("等待在手机上确认") }
                val scheduled=stub.requests.single { it.optString("cmd")=="file_schedule" && it.optString("action")=="add" }
                assertEquals(12,scheduled.getInt("delay")); assertEquals("force_delete",scheduled.getJSONObject("params").getString("operation"))
                page.confirmForceDelete(); shadowOf(Looper.getMainLooper()).idle(); latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor { !page.operationsBusy }
                assertEquals(1,stub.requests.count { it.optString("cmd")=="file_schedule" && it.optString("action")=="add" })
                assertEquals(1,stub.requests.count { it.optString("cmd")=="file_jobs" && it.optString("action")=="confirm_force_delete" })
            } finally { controller.pause().stop().destroy() }
        }
    }

    private class Stub(private val supported:Boolean=true):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0); private val client=AgentClient("127.0.0.1",server.localPort)
        private var job:JSONObject?=null
        private fun makeJob():JSONObject = JSONObject().put("id","force-job").put("operation","force_delete")
            .put("status","needs_confirmation").put("confirmation_id","approval-1")
            .put("items",JSONArray().put(JSONObject().put("path","C:\\data\\a.txt").put("file_id","a".repeat(64))
                .put("status","needs_confirmation").put("blockers",JSONArray().put(JSONObject()
                    .put("application","Document editor").put("pid",42).put("created","133000000000000000")
                    .put("path","C:\\apps\\editor.exe").put("can_close",true)))))
        private val thread=Thread {
            try { server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader(); val output=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(input.readLine()?:break); requests.add(q)
                    val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "storage" -> result.put("home","C:\\data").put("drives",JSONArray())
                        "ls" -> result.put("path","C:\\data").put("items",JSONArray().put(JSONObject().put("name","a.txt").put("dir",false).put("file_id","a".repeat(64))))
                        "capabilities" -> result.put("features",JSONArray().apply { if(supported)put("force_delete") })
                        "file_schedule" -> {
                            if(q.optString("action")=="add") { job=makeJob(); result.put("job",JSONObject().put("id","scheduled")) }
                            else result.put("job",JSONObject().put("status","completed").put("result",JSONObject().put("job",job)))
                        }
                        "file_jobs" -> {
                            when(q.optString("action")) {
                                "create" -> { job=makeJob(); result.put("job",job) }
                                "confirm_force_delete", "cancel" -> {
                                    job?.put("status","completed")?.put("finished",1)
                                    job?.getJSONArray("items")?.getJSONObject(0)?.put("status",if(q.optString("action")=="cancel")"cancelled" else "completed")
                                    result.put("job",job)
                                }
                            }
                            result.put("jobs",JSONArray().apply { job?.let { put(it) } })
                        }
                    }
                    output.write(result.toString()+"\n"); output.flush()
                }
            } } catch(_:IOException) {}
        }.apply { isDaemon=true; start() }
        fun install() { runBlocking { withContext(Dispatchers.IO) { client.connect() } }; ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client) }
        override fun close() { client.close(); server.close(); thread.join(2000) }
    }
}

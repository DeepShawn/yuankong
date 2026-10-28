package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.textfield.TextInputEditText
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
import org.robolectric.RobolectricTestRunner
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessDialogTest {
    private lateinit var stub: Stub
    private lateinit var controller: org.robolectric.android.controller.ActivityController<ControlActivity>
    private lateinit var dialog: AlertDialog
    private val logs = CopyOnWriteArrayList<String>()

    @Before fun prepare() {
        ConnectionManager.stop()
        val field = ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible = true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() }
        stub = Stub().apply { install() }
        controller = Robolectric.buildActivity(ControlActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), ControlActivity::class.java).putExtra("kind", "message")).setup()
    }

    @After fun cleanup() {
        if (::dialog.isInitialized) dialog.dismiss()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        ConnectionManager.stop()
        if (::stub.isInitialized) stub.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show(waitForData: Boolean = true) {
        ProcessDialog(controller.get(), controller.get().lifecycleScope) { text, _ -> logs.add(text) }.show()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        if (waitForData) waitUntil { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Process dialog action did not finish")
    }

    private fun count() = dialog.findViewById<TextView>(R.id.tvCount)!!.text.toString()
    private fun selectAll() { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); shadowOf(Looper.getMainLooper()).idle() }
    private fun refresh() { dialog.findViewById<View>(R.id.btnRefresh)!!.performClick() }

    @Test fun selectAllAndDeselectKeepDialogOpenAndProtectReservedPids() {
        show(); selectAll()
        assertTrue(dialog.isShowing)
        assertTrue(count().contains("已选 2"))
        assertEquals("取消全选", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(stub.killed.isEmpty())
        val view = dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 411, 740)
        val bitmap = Bitmap.createBitmap(411, 740, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/ui-previews/process-selection.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
        selectAll()
        assertTrue(dialog.isShowing); assertFalse(count().contains("已选"))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun endConfirmationCanBeCancelledAndOnlySelectedEligibleProcessesAreSent() {
        show(); selectAll()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotSame(dialog, confirm); assertTrue(dialog.isShowing)
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing); assertTrue(count().contains("已选 2")); assertTrue(stub.killed.isEmpty())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { stub.killed.size == 2 && !dialog.findViewById<View>(R.id.pbLoading)!!.isShown }
        assertEquals(setOf(201, 202), stub.killed.toSet())
        assertTrue(dialog.isShowing); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun filteringAndRefreshRemoveHiddenAndStaleSelections() {
        show(); selectAll()
        val search = dialog.findViewById<TextInputEditText>(R.id.etSearch)!!
        search.setText("one")
        assertTrue(count().contains("已选 1"))
        search.setText("")
        assertTrue(count().contains("已选 1"))
        assertEquals("全选", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        selectAll(); stub.items.removeAll { it.optInt("pid") == 201 }
        refresh()
        waitUntil { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled }
        assertTrue(count().contains("已选 1"))
        assertEquals("取消全选", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
    }

    @Test fun privilegeBannerReflectsActualStatusAndExplainsLocalUac() {
        show()
        val label = dialog.findViewById<TextView>(R.id.tvPrivilege)!!
        assertTrue(label.text.contains("标准用户"))
        stub.admin = true; refresh()
        waitUntil { label.text.contains("电脑权限：管理员") }
        label.performClick()
        val help = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(help.findViewById<TextView>(android.R.id.message)!!.text.contains("UAC"))
        help.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        stub.admin = null; refresh()
        waitUntil { label.text.contains("权限：未知") }
    }

    @Test fun refreshFailureClearsStaleProcessesAndDisablesBatchActions() {
        show(); selectAll(); stub.psError = "模拟读取失败"; refresh()
        waitUntil { dialog.findViewById<TextView>(R.id.tvEmpty)!!.text == "模拟读取失败" }
        assertEquals(0, dialog.findViewById<RecyclerView>(R.id.rvProcess)!!.adapter!!.itemCount)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(stub.killed.isEmpty())
    }

    @Test fun dismissCancelsPendingLoadWithoutLoggingCancellationAsFailure() {
        stub.gate = CountDownLatch(1); show(waitForData = false)
        waitUntil { stub.requests.any { it.optString("cmd") == "ps" } }
        dialog.dismiss()
        stub.gate!!.countDown()
        waitUntil { stub.client.pendingRequests() == 0 }
        assertTrue(logs.isEmpty()); assertTrue(stub.killed.isEmpty())
    }

    private class Stub : Closeable {
        private val server = ServerSocket(0)
        val client = AgentClient("127.0.0.1", server.localPort)
        val requests = CopyOnWriteArrayList<JSONObject>()
        val killed = CopyOnWriteArrayList<Int>()
        @Volatile var admin: Boolean? = false
        @Volatile var psError: String? = null
        @Volatile var gate: CountDownLatch? = null
        val items = CopyOnWriteArrayList<JSONObject>().apply {
            listOf("idle" to 0, "system" to 1, "receiver" to 900, "one.exe" to 201, "two.exe" to 202).forEach { (name, pid) ->
                add(JSONObject().put("name", name).put("pid", pid).put("mem", "1 MB").put("mem_bytes", pid * 1024L))
            }
        }
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val request = JSONObject(line); requests.add(request)
                        val result = JSONObject().put("id", request.getInt("id")).put("ok", true)
                        when (request.optString("cmd")) {
                            "ps" -> {
                                gate?.await(6, TimeUnit.SECONDS)
                                if (psError != null) result.put("ok", false).put("error", psError)
                                else {
                                    result.put("self_pid", 900).put("items", JSONArray(items.toList()))
                                    admin?.let { result.put("admin", it) }
                                }
                            }
                            "kill" -> {
                                val pid = request.getInt("pid"); killed.add(pid)
                                items.removeAll { it.optInt("pid") == pid }
                            }
                        }
                        writer.write(result.toString() + "\n"); writer.flush()
                    }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }

        fun install() {
            runBlocking { withContext(Dispatchers.IO) { client.connect() } }
            ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(ConnectionManager, client)
        }
        override fun close() { gate?.countDown(); client.close(); server.close(); worker.join(2000) }
    }
}

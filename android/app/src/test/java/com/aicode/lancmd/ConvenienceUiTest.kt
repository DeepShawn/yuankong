package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
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
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConvenienceUiTest {
    @Before fun before() = stopConnection()
    @After fun after() = stopConnection()
    private fun stopConnection() {
        ConnectionManager.stop()
        val field = ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible = true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("UI operation did not finish")
    }
    private fun buttons(view: View): List<MaterialButton> = when (view) {
        is MaterialButton -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 411, 700)
        val bitmap = Bitmap.createBitmap(411, 700, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun page() = Robolectric.buildActivity(ControlActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), ControlActivity::class.java).putExtra("kind", "message")).setup()

    @Test fun audioReadsAuthoritativeVolumeAndControlsMuteSeparately() {
        Stub().use { stub ->
            stub.install()
            val controller = page()
            AudioDialog(controller.get()).show()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val slider = dialog.findViewById<SeekBar>(R.id.audioSlider)!!
            waitUntil { slider.isEnabled }
            assertEquals(40, slider.progress)
            buttons(dialog.window!!.decorView).single { it.text == "+10" }.performClick()
            waitUntil { slider.isEnabled && slider.progress == 50 }
            val set = stub.requests.last { it.optString("action") == "set" }
            assertEquals(50, set.getInt("volume")); assertFalse(set.has("muted"))
            dialog.findViewById<View>(R.id.audioMute)!!.performClick()
            waitUntil { slider.isEnabled && dialog.findViewById<MaterialButton>(R.id.audioMute)!!.text == "取消静音" }
            assertFalse(stub.requests.last { it.optString("action") == "set" }.has("volume"))
            render(dialog.window!!.decorView, "audio-controls")
            dialog.dismiss(); controller.pause().stop().destroy()
        }
    }

    @Test fun directoryPickerNavigatesAndSelectsOnlyListedFolder() {
        Stub().use { stub ->
            stub.install()
            val controller = page()
            var selected: String? = null
            RemoteDirectoryPicker(controller.get()).show("") { selected = it }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val choose = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            waitUntil { choose.isEnabled }
            fun list(view: View): ListView? {
                if (view is ListView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) list(view.getChildAt(i))?.let { return it }
                return null
            }
            val folders = list(dialog.window!!.decorView)!!
            assertEquals("Files must not be selectable", 1, folders.adapter.count)
            folders.performItemClick(null, 0, 0)
            waitUntil { choose.isEnabled && stub.requests.any { it.optString("path") == "C:\\Users\\Test\\Documents" } }
            render(dialog.window!!.decorView, "directory-picker")
            choose.performClick()
            assertEquals("C:\\Users\\Test\\Documents", selected)
            assertFalse(dialog.isShowing)
            controller.pause().stop().destroy()
        }
    }

    @Test fun unreadableFolderCannotBeSelectedAndDriveCanRecover() {
        Stub().use { stub ->
            stub.install()
            val controller = page()
            var selected = false
            RemoteDirectoryPicker(controller.get()).show("denied") { selected = true }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            waitUntil { stub.requests.any { it.optString("cmd") == "ls" } && buttons(dialog.window!!.decorView).any { it.text == "C:\\" } }
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertFalse(selected)
            buttons(dialog.window!!.decorView).single { it.text == "C:\\" }.performClick()
            waitUntil { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
            dialog.dismiss(); controller.pause().stop().destroy()
        }
    }

    @Test fun virtualModifiersAreOneShotAndDisablingClearsPendingKeys() {
        val controller = page()
        val sent = ArrayList<List<String>>()
        val keyboard = VirtualKeyboard(controller.get()) { sent.add(it) }
        fun key(name: String) = buttons(keyboard).single { it.text == name }
        key("Ctrl").performClick(); key("Shift").performClick(); key("A").performClick()
        key("B").performClick()
        assertEquals(listOf(listOf("CTRL", "SHIFT", "A"), listOf("B")), sent)
        key("Alt").performClick(); keyboard.isEnabled = false; keyboard.isEnabled = true
        key("F12").performClick(); key(";").performClick()
        assertEquals(listOf("F12"), sent[2]); assertEquals(listOf("OEM_1"), sent[3])
        render(keyboard, "virtual-keyboard")
        controller.pause().stop().destroy()
    }

    @Test fun choosingCommonCommandFillsEditorWithoutExecuting() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        stopConnection()
        Stub().use { stub ->
            stub.install()
            val activity = controller.get()
            activity.findViewById<View>(R.id.btnShellPs).performClick()
            activity.findViewById<View>(R.id.btnCommands).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.listView.performItemClick(null, 4, 4)
            assertEquals("Get-Process", activity.findViewById<EditText>(R.id.etCmd).text.toString())
            assertFalse(stub.requests.any { it.optString("cmd") == "exec" })
            controller.pause().stop().destroy()
        }
    }

    private class Stub : Closeable {
        private val server = ServerSocket(0)
        private val client = AgentClient("127.0.0.1", server.localPort)
        val requests = CopyOnWriteArrayList<JSONObject>()
        private var volume = 40
        private var muted = false
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    while (true) {
                        val request = JSONObject(reader.readLine() ?: break)
                        val result = JSONObject().put("id", request.getInt("id")).put("ok", true)
                        when (request.optString("cmd")) {
                            "audio" -> {
                                if (request.has("volume")) volume = request.getInt("volume")
                                if (request.has("muted")) muted = request.getBoolean("muted")
                                result.put("volume", volume).put("muted", muted)
                            }
                            "storage" -> result.put("drives", JSONArray().put(JSONObject().put("path", "C:\\")))
                            "ls" -> {
                                val path = request.optString("path").ifBlank { "C:\\Users\\Test" }
                                if (path == "denied") result.put("ok", false).put("error", "没有权限读取")
                                else result.put("path", path).put("parent", "C:\\Users\\Test").put("items", JSONArray()
                                    .put(JSONObject().put("name", "Documents").put("dir", true))
                                    .put(JSONObject().put("name", "secret.txt").put("dir", false)))
                            }
                        }
                        requests.add(request)
                        writer.write(result.toString() + "\n"); writer.flush()
                    }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }
        fun install() {
            runBlocking { withContext(Dispatchers.IO) { client.connect() } }
            ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }.set(ConnectionManager, client)
        }
        override fun close() { client.close(); server.close(); worker.join(2000) }
    }
}

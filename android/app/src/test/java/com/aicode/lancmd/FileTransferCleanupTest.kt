package com.aicode.lancmd

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ServerSocket

/** SAF providers can omit SIZE; their upload cache must be removed on every exit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileTransferCleanupTest {
    class UnknownSizeProvider : ContentProvider() {
        lateinit var source: File
        var failOpen = false
        var onOpen: () -> Unit = {}
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("payload.bin", null))
            }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            onOpen()
            if (failOpen) throw FileNotFoundException("provider read failure")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?) = 0
    }

    private lateinit var app: Application
    private lateinit var provider: UnknownSizeProvider
    private val uri = Uri.parse("content://upload-cleanup/payload")

    @Before
    fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        ConnectionManager.stop()
        val field = ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible = true }
        runBlocking { (field.get(ConnectionManager) as? Job)?.join() }
        uploadCaches().forEach { it.delete() }
        provider = UnknownSizeProvider().apply {
            source = File(app.cacheDir, "cleanup-source.bin").apply { writeBytes(ByteArray(32768) { 42 }) }
            attachInfo(app, ProviderInfo().apply { authority = "upload-cleanup" })
        }
        ShadowContentResolver.registerProviderInternal("upload-cleanup", provider)
        assertEquals(-1L, FileTransfer.querySize(app, uri))
    }

    @After
    fun cleanup() {
        ConnectionManager.stop()
        provider.source.delete()
        uploadCaches().forEach { it.delete() }
    }

    private fun uploadCaches(): List<File> = app.cacheDir.listFiles()
        ?.filter { it.name.startsWith("upload-") && it.name.endsWith(".bin") }.orEmpty()

    private fun assertNoCache() = assertTrue("Upload cache must be removed", uploadCaches().isEmpty())

    @Test
    fun disconnectedBeginRemovesUnknownSizeCache() {
        val error = assertThrows(IOException::class.java) {
            runBlocking { FileTransfer.upload(app, uri, "remote.bin") {} }
        }
        assertEquals("未连接", error.message)
        assertNoCache()
    }

    @Test fun boundedUpgradeUploadRejectsAnUnknownSizeProviderBeforeGrowingTheCachePastLimit() {
        val error=assertThrows(IOException::class.java) {
            runBlocking {FileTransfer.uploadRevision(app,uri,"upgrade/test.zip",null,{},maxBytes=1024)}
        }
        assertTrue(error.message.orEmpty().contains("大小上限"));assertNoCache()
    }

    @Test
    fun localReadFailureRemovesUnknownSizeCache() {
        provider.failOpen = true
        assertThrows(IOException::class.java) {
            runBlocking { FileTransfer.upload(app, uri, "remote.bin") {} }
        }
        assertNoCache()
    }

    @Test
    fun cancellationDuringPreparationRemovesCache() = runBlocking {
        var opened = false
        val upload = launch(start = CoroutineStart.LAZY) {
            FileTransfer.upload(app, uri, "remote.bin") {}
        }
        provider.onOpen = { opened = true; upload.cancel() }
        upload.start()
        upload.join()
        assertTrue("The cancellation must happen after cache creation", opened)
        assertTrue(upload.isCancelled)
        assertNoCache()
    }

    @Test
    fun rejectedBeginRemovesUnknownSizeCache() {
        ServerSocket(0).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    val request = JSONObject(reader.readLine())
                    writer.write(JSONObject().put("id", request.getInt("id"))
                        .put("ok", false).put("error", "target denied").toString() + "\n")
                    writer.flush()
                }
            }.apply { isDaemon = true; start() }
            val client = AgentClient("127.0.0.1", server.localPort)
            client.connect()
            val field = ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(ConnectionManager, client)
            try {
                val error = assertThrows(IOException::class.java) {
                    runBlocking { FileTransfer.upload(app, uri, "remote.bin") {} }
                }
                assertEquals("target denied", error.message)
                assertNoCache()
            } finally {
                client.close()
                worker.join(2000)
            }
        }
    }

    @Test
    fun switchingComputerAfterBeginAbortsOriginalWithoutUploadingToReplacement() {
        ServerSocket(0).use { originalServer ->
            ServerSocket(0).use { replacementServer ->
                val original = AgentClient("127.0.0.1", originalServer.localPort)
                val replacement = AgentClient("127.0.0.1", replacementServer.localPort)
                replacement.connect()
                val replacementSocket = replacementServer.accept().apply { soTimeout = 100 }
                val received = java.util.concurrent.CopyOnWriteArrayList<String>()
                val clientField = ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible = true }
                val worker = Thread {
                    originalServer.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        repeat(2) { index ->
                            val request = JSONObject(reader.readLine())
                            received.add(request.getString("action"))
                            if (index == 0) clientField.set(ConnectionManager, replacement)
                            writer.write(JSONObject().put("id", request.getInt("id")).put("ok", true)
                                .put("sid", "original-upload").put("chunk", 16384).toString() + "\n")
                            writer.flush()
                        }
                    }
                }.apply { isDaemon = true; start() }
                original.connect(); clientField.set(ConnectionManager, original)
                try {
                    val error = assertThrows(IOException::class.java) {
                        runBlocking { FileTransfer.uploadTo(app, uri, "original.bin", original) {} }
                    }
                    assertTrue(error.message.orEmpty().contains("连接已切换"))
                    worker.join(2000)
                    assertEquals(listOf("begin", "abort"), received)
                    assertThrows(java.net.SocketTimeoutException::class.java) { replacementSocket.getInputStream().read() }
                    assertNoCache()
                } finally {
                    original.close(); replacement.close(); replacementSocket.close()
                    worker.join(2000)
                }
            }
        }
    }
}

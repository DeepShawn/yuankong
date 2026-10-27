package com.aicode.lancmd

import android.app.Application
import android.content.pm.ProviderInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.os.Bundle
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.TreeDownloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TreeDownloadsTest {
    class Provider:ContentProvider() {
        lateinit var root:File;var failOpen=false
        override fun onCreate()=true
        private val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)
        private fun file(id:String)=if(id=="root")root else File(root,id.removePrefix("root/"))
        private fun id(file:File)="root/"+file.relativeTo(root).invariantSeparatorsPath
        private fun row(cursor:MatrixCursor,file:File) { cursor.addRow(arrayOf(id(file),file.name,if(file.isDirectory)DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream")) }
        override fun getType(uri:Uri)="application/octet-stream"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor =
            queryChildDocuments(DocumentsContract.getDocumentId(uri),projection,sortOrder)
        override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
            val uri=extras!!.getParcelable<Uri>("uri")!!
            val document=DocumentsContract.getDocumentId(uri)
            return when(method) {
                "android:createDocument"->Bundle().apply { putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,createDocument(document,extras.getString("mime_type")!!,extras.getString("_display_name")!!))) }
                "android:deleteDocument"->{deleteDocument(document);Bundle()}
                else->throw UnsupportedOperationException(method)
            }
        }
        override fun openFile(uri:Uri,mode:String)=openDocument(DocumentsContract.getDocumentId(uri),mode,null)
        fun queryDocument(documentId:String,projection:Array<out String>?)=MatrixCursor(columns).apply { row(this,file(documentId)) }
        fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor = MatrixCursor(projection ?: columns).apply {
            file(parentDocumentId).listFiles().orEmpty().forEach { child->
                addRow(columnNames.map { when(it){DocumentsContract.Document.COLUMN_DOCUMENT_ID->id(child);DocumentsContract.Document.COLUMN_DISPLAY_NAME->child.name;else->"application/octet-stream"} }.toTypedArray())
            }
        }
        fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
            val new=File(file(parentDocumentId),displayName)
            if(new.exists())throw IOException("existing target")
            if(mimeType==DocumentsContract.Document.MIME_TYPE_DIR)new.mkdir() else new.createNewFile()
            return id(new)
        }
        fun deleteDocument(documentId:String) { file(documentId).deleteRecursively() }
        fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
            if(failOpen)throw IOException("provider unavailable")
            return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.MODE_READ_WRITE)
        }
        fun isChildDocument(parentDocumentId:String,documentId:String)=documentId.startsWith(parentDocumentId+"/")
    }
    private lateinit var app:Application
    private lateinit var provider:Provider
    private val tree=DocumentsContract.buildTreeDocumentUri("tree-downloads","root")
    @Before fun before() {
        ConnectionManager.stop();runBlocking { (ConnectionManager::class.java.getDeclaredField("loopJob").apply { isAccessible=true }.get(ConnectionManager) as? Job)?.join() }
        app=ApplicationProvider.getApplicationContext()
        provider=Provider().apply {
            root=File(app.cacheDir,"tree-download-test").apply { deleteRecursively();mkdirs() }
            attachInfo(app,ProviderInfo().apply { authority="tree-downloads";exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS" })
        }
        ShadowContentResolver.registerProviderInternal("tree-downloads",provider)
    }
    @After fun after(){ConnectionManager.stop();provider.root.deleteRecursively()}
    private fun item(name:String,dir:Boolean=false)=JSONObject().put("name",name).put("dir",dir).put("file_id","selected-token")
    @Test fun downloadsDirectoryIncludingEmptyFileAndNeverOverwritesExistingRoot() {
        Stub().use { stub->
            stub.install();val downloader=TreeDownloads(app,stub.client)
            runBlocking { downloader.download("C:\\folder",item("folder",true),tree){_,_->} }
            assertEquals("abc",File(provider.root,"folder/a.txt").readText())
            assertEquals(0L,File(provider.root,"folder/empty.txt").length())
            assertTrue(stub.requests.filter { it.optString("action")=="begin" }.all { it.optString("file_id")=="selected-token" })
            assertThrows(IOException::class.java) { runBlocking { downloader.download("C:\\folder",item("folder",true),tree){_,_->} } }
            assertEquals("abc",File(provider.root,"folder/a.txt").readText())
        }
    }
    @Test fun interruptedDownloadAbortsSessionAndRemovesOnlyNewTree() {
        Stub().use { stub->
            stub.truncate=true;stub.install();File(provider.root,"keep.txt").writeText("keep")
            assertThrows(IOException::class.java) { runBlocking { TreeDownloads(app,stub.client).download("C:\\folder",item("folder",true),tree){_,_->} } }
            assertFalse(File(provider.root,"folder").exists());assertEquals("keep",File(provider.root,"keep.txt").readText())
            assertTrue(stub.requests.any { it.optString("action")=="abort" })
        }
    }
    @Test fun localProviderFailureAfterBeginAbortsRemoteSession() {
        Stub().use { stub->
            stub.install();provider.failOpen=true
            assertThrows(IOException::class.java) { runBlocking { TreeDownloads(app,stub.client).download("C:\\a.txt",item("a.txt"),tree){_,_->} } }
            assertFalse(File(provider.root,"a.txt").exists())
            assertEquals(listOf("begin","abort"),stub.requests.filter { it.optString("cmd")=="download" }.map { it.getString("action") })
        }
    }
    @Test fun rejectedDownloadEndIsReportedAndCleansIncompleteDocument() {
        Stub().use { stub->
            stub.rejectEnd=true;stub.install()
            assertThrows(IOException::class.java) { runBlocking { TreeDownloads(app,stub.client).download("C:\\a.txt",item("a.txt"),tree){_,_->} } }
            assertFalse(File(provider.root,"a.txt").exists());assertTrue(stub.requests.any { it.optString("action")=="abort" })
        }
    }
    private class Stub:Closeable {
        private val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        var truncate=false;var rejectEnd=false;private var empty=false
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val worker=Thread {
            try { server.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(reader.readLine()?:break);val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    if(q.optString("cmd")=="ls")r.put("file_id","selected-token").put("items",JSONArray().put(JSONObject().put("name","a.txt").put("file_id","selected-token")).put(JSONObject().put("name","empty.txt").put("file_id","selected-token")))
                    else when(q.optString("action")) {
                        "begin"->{empty=q.getString("path").endsWith("empty.txt");r.put("sid","download").put("size",if(empty)0 else 3)}
                        "chunk"->r.put("data",if(empty || truncate)"" else "YWJj").put("eof",true)
                        "end"->if(rejectEnd)r.put("ok",false).put("error","end failed")
                    }
                    requests.add(q);writer.write(r.toString()+"\n");writer.flush()
                }
            } } catch(_:Exception){}
        }.apply { isDaemon=true;start() }
        fun install(){runBlocking { client.connect() };ConnectionManager::class.java.getDeclaredField("client").apply { isAccessible=true }.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

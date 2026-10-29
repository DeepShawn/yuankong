package com.aicode.lancmd

import android.app.Application
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpgradeUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)};fail("升级状态未更新")}
    private fun confirm(stub:Stub,page:UpgradeActivity){
        waitFor{page.note.startsWith("已读取")}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("校验并安装"))
        compose.onNodeWithText("校验并安装").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertFalse(stub.requests.any{it.optString("action")=="apply"})
        compose.onNodeWithText("开始升级").performSemanticsAction(SemanticsActions.OnClick){it()}
        waitFor{stub.requestId.isNotBlank() && (stub.dropApplyReply || page.note.contains("准备退出旧接收端"))}
    }

    @Test fun installationConfirmsThenTracksTheSameComputerAcrossDisconnectAndRecreation() {
        Stub().use {old ->Stub(version="3.4.2",phase="starting").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup()
            try {
                confirm(old,controller.get());waitFor{controller.get().restarting}
                old.client.close();ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,null)
                fresh.reportId=old.requestId;fresh.install()
                waitFor{controller.get().note.contains("start.bat")}
                controller.recreate();fresh.phase="completed"
                try {waitFor{controller.get().note.contains("升级已完成") && !controller.get().restarting}}
                catch(error:Throwable) {
                    val page=controller.get()
                    throw AssertionError("after recreate note=${page.note}; restarting=${page.restarting}; server phase=${fresh.phase}; closed=${fresh.client.closed}; queries=${fresh.requests.size}",error)
                }
                compose.onNodeWithText("3.4.2").assertExists()
                assertTrue(fresh.requests.any{it.optString("cmd")=="upgrade" && !it.optBoolean("include_packages",true)})
                assertEquals(1,old.requests.count{it.optString("action")=="apply"})
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun reportsFromAnOlderUpgradeCannotCompleteTheCurrentOperation() {
        Stub().use {old ->Stub(version="3.4.2",phase="completed").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,controller.get());fresh.reportId="0".repeat(32);old.client.close();fresh.install()
                waitFor{page.note.contains("等待此次更新")}
                assertTrue(page.restarting)
                fresh.reportId=old.requestId
                waitFor{page.note.contains("升级已完成") && !page.restarting}
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun reconnectionToAnotherComputerNeverReceivesUpgradeQueries() {
        Stub().use {old ->Stub(identity="different-pc").use {other ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,controller.get());old.client.close();other.install()
                waitFor{page.note.contains("另一台电脑")}
                assertTrue(other.requests.isEmpty())
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun lostApplyReplyStillReconnectsAndChecksTheSubmittedRequest() {
        Stub(dropApplyReply=true).use {old ->Stub(version="3.4.2",phase="completed").use {fresh ->
            old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
            try {
                confirm(old,page);fresh.reportId=old.requestId;fresh.install()
                waitFor{page.note.contains("升级已完成") && !page.restarting}
                assertEquals(1,old.requests.count{it.optString("action")=="apply"})
            }finally{controller.pause().stop().destroy()}
        }}
    }
    @Test fun completionUsesTheBuildSequenceWhileShowingTheRequestedDisplayVersion() {
        Stub(version="3.5.3",targetVersion="3.5.4").use {old ->
            Stub(version="3.5.0",phase="completed",buildVersion="3.5.3",targetVersion="3.5.4").use {fresh ->
                old.install();val controller=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=controller.get()
                try {
                    confirm(old,page);fresh.reportId=old.requestId;old.client.close();fresh.install()
                    waitFor{page.note.contains("等待目标版本 3.5.4")}
                    assertTrue(page.restarting)
                    fresh.buildVersion="3.5.4"
                    waitFor{page.note.contains("升级已完成") && !page.restarting}
                    compose.onNodeWithText("3.5.0").assertExists()
                }finally{controller.pause().stop().destroy()}
            }
        }
    }

    private fun document(name:String="LanCmd-Windows-new.zip"):Uri {
        val app=RuntimeEnvironment.getApplication()
        val source=File(app.cacheDir,"selected-upgrade.zip").apply{writeBytes(byteArrayOf(80,75,3,4)+ByteArray(32768){42})}
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/zip"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply{addRow(arrayOf(name,source.length()))}
            override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        }
        provider.attachInfo(app,ProviderInfo().apply{authority="upgrade-upload"})
        ShadowContentResolver.registerProviderInternal("upgrade-upload",provider)
        return Uri.parse("content://upgrade-upload/local.zip")
    }

    @Test fun upgradePagePicksAndUploadsZipThenRefreshesWithoutAutomaticallyInstalling() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=c.get()
            try {
                waitFor{page.note.startsWith("已读取")}
                compose.onNodeWithText("选择并上传 ZIP").performSemanticsAction(SemanticsActions.OnClick){it()}
                val picker=shadowOf(page).nextStartedActivityForResult
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,picker.intent.action)
                shadowOf(page).receiveResult(picker.intent,Activity.RESULT_OK,Intent().setData(document()))
                waitFor{!page.busy && page.note.contains("上传完成")}
                val begin=stub.requests.single{it.optString("cmd")=="upload" && it.optString("action")=="begin"}
                assertEquals("C:\\LanCmd\\upgrade\\LanCmd-Windows-new.zip",begin.getString("path"))
                assertFalse(begin.getBoolean("overwrite"))
                assertTrue(stub.uploaded);assertFalse(stub.requests.any{it.optString("action")=="apply"})
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("LanCmd-Windows-new.zip"))
                compose.onNodeWithText("LanCmd-Windows-new.zip").assertExists()
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun existingPackageIsKeptAndTheNewUploadUsesAnotherName() {
        Stub(collision=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=c.get()
            try {
                waitFor{!page.busy && page.note.startsWith("已读取")};page.uploadPackage(document())
                waitFor{!page.busy && page.note.contains("上传完成")}
                val begins=stub.requests.filter{it.optString("cmd")=="upload" && it.optString("action")=="begin"}
                assertEquals(2,begins.size);assertNotEquals(begins[0].getString("path"),begins[1].getString("path"))
                assertTrue(begins.none{it.optBoolean("overwrite",true)})
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun failedUploadAbortsItsPartialFileAndNeverStartsInstallation() {
        Stub(failChunk=true).use {stub ->
            stub.install();val c=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=c.get()
            try {
                waitFor{!page.busy && page.note.startsWith("已读取")};page.uploadPackage(document())
                waitFor{!page.busy && page.note.contains("磁盘写入失败")}
                assertTrue(stub.requests.any{it.optString("cmd")=="upload" && it.optString("action")=="abort"})
                assertFalse(stub.uploaded);assertFalse(stub.requests.any{it.optString("action")=="apply"})
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun changingComputerWhileThePickerIsOpenNeverUploadsToTheReplacement() {
        Stub().use {old ->Stub(identity="other-pc").use {other ->
            old.install();val c=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=c.get()
            try {
                waitFor{!page.busy && page.note.startsWith("已读取")};other.install();page.uploadPackage(document())
                waitFor{!page.busy && page.note.contains("电脑已切换")}
                assertTrue(other.requests.isEmpty());assertFalse(old.requests.any{it.optString("cmd")=="upload"})
            }finally{c.pause().stop().destroy()}
        }}
    }

    @Test fun pathLikeProviderNamesAreRejectedBeforeAnyUpload() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(UpgradeActivity::class.java).setup();val page=c.get()
            try {
                waitFor{!page.busy && page.note.startsWith("已读取")};page.uploadPackage(document("../outside.zip"))
                waitFor{!page.busy && page.note.contains("文件名有效")}
                assertFalse(stub.requests.any{it.optString("cmd")=="upload"})
            }finally{c.pause().stop().destroy()}
        }
    }

    private class Stub(val identity:String="same-pc",val version:String="3.4.1",phase:String="",val dropApplyReply:Boolean=false,buildVersion:String?=null,val targetVersion:String="3.4.2",val collision:Boolean=false,val failChunk:Boolean=false):Closeable {
        @Volatile var phase=phase
        @Volatile var buildVersion=buildVersion
        val server=ServerSocket(0);val client=AgentClient("127.0.0.1",server.localPort)
        val requests=CopyOnWriteArrayList<JSONObject>()
        @Volatile var requestId=""
        @Volatile var reportId=""
        @Volatile var uploaded=false
        private var uploadName="";private var begins=0
        val thread=Thread {
            try{server.accept().use {socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true){val q=JSONObject(input.readLine()?:break);val result=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    if(q.optString("cmd")=="upload") {
                        when(q.optString("action")) {
                            "begin" -> {begins++;uploadName=q.getString("path").substringAfterLast('\\')
                                if(collision && begins==1)result.put("ok",false).put("error","目标已存在: $uploadName")
                                else result.put("sid","test-upload").put("chunk",16384)}
                            "chunk" -> if(failChunk)result.put("ok",false).put("error","磁盘写入失败")
                            "end" -> {uploaded=true;result.put("size",32772).put("sha256","c".repeat(64))}
                        }
                    }else if(q.optString("action")=="apply") {
                        requestId=q.getString("request_id")
                        if(dropApplyReply){requests.add(q);client.close();break}
                        result.put("restarting",true).put("version",targetVersion).put("request_id",requestId).put("message","准备退出旧接收端")
                    }else {
                        result.put("directory","C:\\LanCmd\\upgrade").put("current",version)
                            .put("items",JSONArray().put(JSONObject().put("name",if(uploaded)uploadName else "LanCmd-Windows-3.4.2.zip").put("size",1234).put("sha256","a".repeat(64))))
                            .put("status",JSONObject().put("status",this@Stub.phase).put("version",targetVersion).put("request_id",reportId))
                        this@Stub.buildVersion?.let{result.put("current_build",it)}
                    }
                    requests.add(q);output.write(result.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){
            val app=RuntimeEnvironment.getApplication()
            Prefs.saveTarget(app,Target("127.0.0.1",server.localPort,"Test PC",identity))
            runBlocking{withContext(Dispatchers.IO){client.connect()}}
            ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)
        }
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileSearchStateUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Before fun before() {
        disconnect()
        RuntimeEnvironment.getApplication().getSharedPreferences("file-operations",0).edit().putInt("delay",0).apply()
    }
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking {(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val deadline=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if(check())return
            Thread.sleep(10)
        }
        fail("搜索上下文没有恢复")
    }
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun rename(page:StorageActivity,name:String) {
        page.renameOrCreate(page.visible.single());shadowOf(Looper.getMainLooper()).idle()
        val dialog=latest()
        all(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(name)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    @Test fun renameAndModifiedDateRefreshKeepTheFilterEvenWhenTheNewNameNoLongerMatches() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                compose.runOnIdle{page.query="readme";page.search()}
                rename(page,"readme-new.txt")
                waitFor{!page.listingBusy && page.note.contains("已重命名") && page.visible.any{it.optString("name")=="readme-new.txt"}}
                assertEquals("readme",page.query);assertEquals(1,page.visible.size)
                page.act("最后修改日期",page.visible.single());shadowOf(Looper.getMainLooper()).idle()
                val date=latest()
                all(date.window!!.decorView).filterIsInstance<EditText>().single().setText("2026-01-02 03:04:05")
                date.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{!page.listingBusy && page.note.contains("修改日期已保存")}
                assertEquals("readme",page.query);assertEquals(1,page.visible.size)
                rename(page,"archive.txt")
                waitFor{!page.listingBusy && stub.document=="archive.txt" && page.visible.isEmpty()}
                assertEquals("readme",page.query);assertEquals("C:\\notes",page.path)
                assertTrue(stub.requests.filter{it.optString("cmd")=="ls"}.all{it.optString("path")=="C:\\notes"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun directoryRefreshAndRecreationKeepFilteredScrollButNavigationStartsAFreshFolder() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();var page=controller.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                compose.runOnIdle{page.query="report";page.search()}
                compose.runOnIdle{page.fileListState.requestScrollToItem(40,9)}
                waitFor{page.fileListState.firstVisibleItemIndex==40}
                stub.revision++
                page.load(page.path,"文件已更新")
                waitFor{!page.listingBusy && page.note.contains("文件已更新")}
                assertEquals("report",page.query);assertEquals(100,page.visible.size)
                assertEquals(40,page.fileListState.firstVisibleItemIndex)
                assertEquals(9,page.fileListState.firstVisibleItemScrollOffset)
                stub.failListing=true;page.load(page.path)
                waitFor{!page.listingBusy && page.note.contains("目录暂未加载")}
                assertEquals("report",page.query);assertEquals(40,page.fileListState.firstVisibleItemIndex)
                stub.failListing=false
                controller.recreate();page=controller.get()
                waitFor{!page.listingBusy && page.visible.size==100 && page.fileListState.firstVisibleItemIndex==40}
                assertEquals("report",page.query);assertEquals(9,page.fileListState.firstVisibleItemScrollOffset)
                page.load("C:\\notes\\Photos")
                waitFor{!page.listingBusy && page.path=="C:\\notes\\Photos"}
                assertEquals("",page.query);assertEquals(0,page.fileListState.firstVisibleItemIndex)
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun savingATextFileAndReturningToItsParentKeepsTheOriginalSearch() {
        Stub().use {stub ->
            stub.install();val parent=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=parent.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                compose.runOnIdle{page.query="readme";page.search()}
                page.act("编辑文本",page.visible.single())
                var launched:android.content.Intent?=null
                waitFor{launched=shadowOf(page).nextStartedActivity ?: launched;launched!=null}
                val child=Robolectric.buildActivity(TextEditorActivity::class.java,launched!!).setup()
                parent.pause().stop()
                try {
                    waitFor{child.get().editable}
                    child.get().edit("updated text");child.get().save(exit=true)
                    waitFor{child.get().isFinishing && stub.text=="updated text"}
                }finally{child.pause().stop().destroy()}
                parent.start().resume().visible()
                page.load(page.path)
                waitFor{!page.listingBusy && page.visible.size==1}
                assertEquals("readme",page.query);assertEquals("C:\\notes",page.path)
            }finally{parent.pause().stop().destroy()}
        }
    }

    @Test fun globalSearchKeepsFiltersPaginationAndScrollAcrossChildReturnAndRecreation() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(FileSearchActivity::class.java).setup();var page=controller.get()
            try {
                page.scopeIndex=1;page.kindIndex=1;page.sortIndex=2;page.typed("report")
                waitFor{page.hasNext}
                page.offsetBy(100);waitFor{page.pageLabel=="101–200 / 201"}
                compose.runOnIdle{page.resultListState.requestScrollToItem(30,7)}
                waitFor{page.resultListState.firstVisibleItemIndex==30}
                val count=stub.requests.count{it.optString("action")=="query"}
                controller.pause().stop();stub.generation++
                controller.start().resume().visible()
                waitFor{stub.requests.count{it.optString("action")=="query"}>count}
                assertEquals("101–200 / 201",page.pageLabel)
                controller.recreate();page=controller.get()
                waitFor{page.pageLabel=="101–200 / 201" && page.resultListState.firstVisibleItemIndex==30}
                assertEquals("report",page.query);assertEquals(7,page.resultListState.firstVisibleItemScrollOffset)
                val request=stub.requests.last{it.optString("action")=="query"}
                assertEquals(100,request.getInt("offset"));assertEquals("path",request.getString("scope"))
                assertEquals("files",request.getString("kind"));assertEquals("modified",request.getString("sort"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun shrinkingSearchResultsClampOnlyTheMissingPageAndKeepTheKeyword() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(FileSearchActivity::class.java).setup();val page=controller.get()
            try {
                page.typed("report");waitFor{page.hasNext}
                page.offsetBy(100);waitFor{page.pageLabel=="101–200 / 201"}
                stub.total=45;stub.generation++
                waitFor{page.pageLabel=="1–45 / 45" && !page.hasPrevious}
                assertEquals("report",page.query)
                assertEquals(0,stub.requests.last{it.optString("action")=="query"}.getInt("offset"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun resizeRequiresConfirmationUsesExactBytesAndRetainsFileSearch() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                compose.runOnIdle{page.query="readme";page.search()}
                assertTrue(page.menuGroups(page.visible.single()).values.flatten().contains("更改实际大小"))
                assertFalse(page.menuGroups(JSONObject().put("dir",true).put("name","Photos")).values.flatten().contains("更改实际大小"))
                page.act("更改实际大小",page.visible.single());shadowOf(Looper.getMainLooper()).idle()
                assertFalse(stub.requests.any{it.optString("operation")=="resize"})
                latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                page.act("更改实际大小",page.visible.single());shadowOf(Looper.getMainLooper()).idle()
                val dialog=latest()
                val input=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                input.setText("500000000001");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing);assertNotNull(input.error)
                assertFalse(stub.requests.any{it.optString("operation")=="resize"})
                input.setText("6");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{!page.operationsBusy && !page.listingBusy && page.note.contains("调整文件大小")}
                val q=stub.requests.single{it.optString("operation")=="resize"}
                assertEquals(6L,q.getLong("size"));assertTrue(q.getBoolean("confirm_truncate"))
                assertEquals("C:\\notes\\readme.txt",q.getJSONArray("items").getJSONObject(0).getString("path"))
                assertEquals("1".repeat(64),q.getJSONArray("items").getJSONObject(0).getString("file_id"))
                assertEquals("readme",page.query);assertEquals(1,page.visible.size)
                assertEquals(6L,page.visible.single().getLong("size"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun resizeRefusesAReceiverWithoutTheFeature() {
        Stub().use {stub ->
            stub.supportsResize=false;stub.install()
            val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                page.act("更改实际大小",page.visible.single{it.optString("name")=="readme.txt"})
                shadowOf(Looper.getMainLooper()).idle()
                latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{page.note.contains("完整更新并重启 Windows")}
                assertFalse(stub.requests.any{it.optString("operation")=="resize"})
            }finally{controller.pause().stop().destroy()}
        }
    }

    @Test fun editableSourceNoteHasItsOwnVersionAndDoesNotWriteBeforeSave() {
        Stub().use {stub ->
            stub.install();val controller=Robolectric.buildActivity(StorageActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.visible.size==102 && !page.listingBusy}
                compose.runOnIdle{page.query="readme";page.search()}
                page.act("文件来源说明",page.visible.single())
                waitFor{(ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing==true}
                val dialog=latest()
                val input=all(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                input.setText("https://example.com/original\n来源备注")
                assertFalse(stub.requests.any{it.optString("operation")=="source"})
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{!page.operationsBusy && !page.listingBusy && page.note.contains("保存来源说明")}
                val q=stub.requests.single{it.optString("operation")=="source"}
                assertEquals("https://example.com/original\n来源备注",q.getString("source_text"))
                assertEquals("0".repeat(64),q.getString("source_revision"))
                assertEquals("readme",page.query);assertEquals(1,page.visible.size)
                assertEquals(10L,page.visible.single().getLong("size"))
            }finally{controller.pause().stop().destroy()}
        }
    }

    private class Stub:Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        @Volatile var document="readme.txt"
        @Volatile var revision=1
        @Volatile var text="original"
        @Volatile var failListing=false
        @Volatile var generation=1700000000.0
        @Volatile var total=201
        @Volatile var supportsResize=true
        @Volatile var fileSize=10L
        @Volatile var resizeJob:JSONObject?=null
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        private fun item(name:String,dir:Boolean=false)=JSONObject().put("name",name).put("dir",dir).put("size",if(name==document)fileSize else 10L)
            .put("mtime",1700000000+revision).put("file_id",(revision%16).toString(16).repeat(64))
            .put("parent","C:\\notes").put("path","C:\\notes\\$name")
        private val thread=Thread {
            try {server.accept().use {socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(input.readLine()?:break);requests.add(q)
                    val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "storage"->r.put("home","C:\\notes").put("drives",JSONArray())
                        "capabilities"->r.put("features",JSONArray().apply{if(supportsResize){put("file_resize");put("file_sources")}})
                        "file_jobs"->if(q.optString("action")=="create") {
                            if(q.optString("operation")=="resize")fileSize=q.getLong("size")
                            revision++
                            resizeJob=JSONObject().put("id","resize-job").put("operation",q.getString("operation")).put("status","completed").put("finished",1)
                                .put("items",JSONArray().put(JSONObject(q.getJSONArray("items").getJSONObject(0).toString()).put("status","completed").put("size",fileSize)))
                            r.put("job",resizeJob)
                        } else r.put("jobs",JSONArray().apply{resizeJob?.let{put(it)}})
                        "ls"->if(failListing)r.put("ok",false).put("error","测试磁盘忙碌") else r.put("path",q.optString("path"))
                            .put("items",JSONArray().apply{if(q.optString("path")=="C:\\notes") {
                                put(item("Photos",true));put(item(document))
                                for(i in 0 until 100)put(item("report-%03d.txt".format(i)))
                            }})
                        "rename"->{document=q.getString("to").substringAfterLast('\\');revision++}
                        "files"->when(q.optString("action")) {
                            "source_read"->r.put("text","").put("source_revision","0".repeat(64)).put("file_id",(revision%16).toString(16).repeat(64))
                            "mtime"->revision++
                            "text_read"->r.put("text",text).put("revision",revision.toString()).put("encoding","utf-8").put("newline","lf").put("size",text.length)
                            "text_write"->{text=q.getString("text");revision++;r.put("revision",revision.toString()).put("size",text.length)}
                        }
                        "file_search"->{
                            val index=JSONObject().put("available",true).put("count",total).put("updated",generation).put("roots",JSONArray().put("C:\\notes"))
                            r.put("index",index)
                            if(q.optString("action")=="query")r.put("total",total).put("items",JSONArray().apply {
                                for(i in q.optInt("offset") until minOf(q.optInt("offset")+100,total))put(item("report-%03d.txt".format(i)))
                            }) else r.put("job",JSONObject.NULL).put("drives",JSONArray().put("C:\\"))
                        }
                    }
                    output.write(r.toString()+"\n");output.flush()
                }
            }}catch(_:IOException){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

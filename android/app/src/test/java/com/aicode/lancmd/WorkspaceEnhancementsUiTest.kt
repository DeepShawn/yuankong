package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.util.Base64
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceEnhancementsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun before() {
        disconnect()
        runBlocking { GestureLibrary(app).load() }
        for(suffix in listOf("",".bak",".new"))File(app.filesDir,"gesture-library.json$suffix").delete()
        app.getSharedPreferences("gestures",0).edit().clear().commit()
    }
    @After fun after() {disconnect();runBlocking {GestureLibrary(app).load()}}
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end) {
            shadowOf(Looper.getMainLooper()).idleFor(10,java.util.concurrent.TimeUnit.MILLISECONDS)
            if(check())return
            Thread.sleep(10)
        }
        fail("新增工作区操作未在预期时间内完成")
    }
    private fun events()=JSONArray().put(JSONObject().put("action","down").put("ms",0).put("x",0.25).put("y",0.5))
        .put(JSONObject().put("action","up").put("ms",100).put("x",0.25).put("y",0.5))
    private fun entry()=JSONObject().put("events",events()).put("name","旧手势").put("aspect",16.0/9).put("drag",true).put("delay","7")

    @Test fun migratedNamedGestureAndDraftSurviveActualPageExitAndNamedSaveIsDurable() {
        app.getSharedPreferences("gestures",0).edit().putString("saved",JSONArray().put(entry()).toString()).commit()
        val first=Robolectric.buildActivity(GesturesActivity::class.java).setup()
        waitFor{first.get().eventCount==2}
        first.get().saveGesture();shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor{first.get().note.contains("手势已保存在手机")}
        first.pause().stop().destroy()
        val data=runBlocking{GestureLibrary(app).load()}
        assertEquals(2,data.getJSONArray("saved").length());assertEquals("7",data.getJSONObject("draft").getString("delay"))
        val reopened=Robolectric.buildActivity(GesturesActivity::class.java).setup()
        try {
            waitFor{reopened.get().eventCount==2}
            assertEquals(events().toString(),reopened.get().board.snapshot().toString())
            reopened.get().board.load(JSONArray())
        }finally{reopened.pause().stop().destroy()}
        val cleared=Robolectric.buildActivity(GesturesActivity::class.java).setup()
        try {
            waitFor{runBlocking{GestureLibrary(app).load()}.getJSONObject("draft").getJSONArray("events").length()==0}
            assertEquals(0,cleared.get().eventCount)
            assertEquals(2,runBlocking{GestureLibrary(app).load()}.getJSONArray("saved").length())
        }finally{cleared.pause().stop().destroy()}
    }

    @Test fun draftFromKilledProcessReleasesHeldFingerBeforePlayback() {
        val pending=entry().put("events",JSONArray().put(events().getJSONObject(0)))
        runBlocking {GestureLibrary(app).draft(pending)}
        val c=Robolectric.buildActivity(GesturesActivity::class.java).setup()
        try {
            waitFor{c.get().eventCount==2}
            assertEquals("up",c.get().board.snapshot().getJSONObject(1).getString("action"))
            assertFalse(c.get().board.recording)
        }finally{c.pause().stop().destroy()}
    }

    @Test fun desktopLastFrameIsDrawnBehindLocalRecordingWithoutSendingPointerEvents() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(GesturesActivity::class.java).setup()
            try {
                waitFor{c.get().board.desktopFrame!=null}
                val board=c.get().board
                assertEquals(320,board.desktopFrame!!.width);assertEquals(180,board.desktopFrame!!.height)
                board.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(300,1073741824));board.layout(0,0,400,300)
                val output=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);board.draw(Canvas(output))
                val color=output.getPixel(200,150)
                assertTrue(Color.red(color)>200 && Color.blue(color)<30)
                board.begin();board.finish()
                assertFalse(stub.requests.any{it.optString("cmd") in listOf("pointer","input","touch")})
                waitFor{stub.requests.any{it.optString("cmd")=="session" && it.optString("action")=="end"}}
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun browserReceivesSharedCurrentWebpageAndSendsOnlyAfterUserAction() {
        assertEquals("https://example.com/page?a=1",SharedWebLink.extract("网页标题\nhttps://example.com/page?a=1"))
        assertNull(SharedWebLink.extract("javascript:alert(1)"))
        assertNull(SharedWebLink.extract("https://user:password@example.com/"))
        Stub().use {stub ->
            stub.install()
            val intent=Intent(app,BrowserActivity::class.java).putExtra("shared_url","https://example.com/page?a=1")
            val c=Robolectric.buildActivity(BrowserActivity::class.java,intent).setup()
            try {
                waitFor{c.get().note.contains("已读取")}
                compose.onNodeWithText("https://example.com/page?a=1").assertExists()
                assertFalse(stub.requests.any{it.optString("cmd")=="browser" && it.optString("action")=="open"})
                compose.onNodeWithText("在电脑打开网址").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{stub.requests.any{it.optString("cmd")=="browser" && it.optString("action")=="open"}}
                assertEquals("https://example.com/page?a=1",stub.requests.single{it.optString("action")=="open"}.getString("url"))
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun shareReceiverStagesUrlAndDoesNotSendToAnUnconnectedComputer() {
        val intent=Intent(app,ShareWebActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"https://example.com/shared")
        val c=Robolectric.buildActivity(ShareWebActivity::class.java,intent).setup()
        try {
            compose.onNodeWithText("https://example.com/shared").assertExists()
            compose.onNodeWithText("选择电脑浏览器并发送").performSemanticsAction(SemanticsActions.OnClick){it()}
            assertTrue(c.get().note.contains("请先连接"))
            assertNull(shadowOf(c.get()).nextStartedActivity)
        }finally{c.pause().stop().destroy()}
    }

    @Test fun runningThirdPartyAndCurrentDesktopFiltersHaveWindowControls() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(ApplicationsActivity::class.java).setup()
            try {
                waitFor{c.get().note.contains("已识别")}
                compose.onNodeWithText("管理范围").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("运行中的第三方软件").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.waitUntil(8000){compose.onAllNodesWithText("Demo Window").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Windows Core").assertDoesNotExist()
                compose.onNodeWithText("最小化窗口").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{stub.requests.any{it.optString("cmd")=="apps" && it.optString("action")=="minimize"}}
                val request=stub.requests.single{it.optString("action")=="minimize"}
                assertEquals("100",request.optString("handle"));assertEquals(10,request.optInt("pid"))
            }finally{c.pause().stop().destroy()}
        }
    }

    @Test fun fileLockConfirmsPasswordAndWindowsLocalExpiryThenUnlocksWithoutSavingSecrets() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(FileLocksActivity::class.java,
                Intent(app,FileLocksActivity::class.java).putExtra("path","C:\\Data\\notes.txt")).setup()
            try {
                waitFor{stub.requests.any{it.optString("cmd")=="file_locks"}}
                compose.onNodeWithText("锁定密码（8–128 字符）").performScrollTo().performTextInput("local secret password")
                compose.onNodeWithText("再次输入密码").performScrollTo().performTextInput("local secret password")
                compose.onNodeWithText("锁定方式").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("定时锁定 · 到时自动解锁").performSemanticsAction(SemanticsActions.OnClick){it()}
                compose.onNodeWithText("电脑解锁时间：yyyy-MM-dd HH:mm:ss").performScrollTo().performTextInput("2027-01-02 03:04:05")
                compose.onNodeWithText("锁定文件").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                assertFalse(stub.requests.any{it.optString("cmd")=="file_locks" && it.optString("action")=="lock"})
                compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{c.get().note=="文件已锁定"}
                val q=stub.requests.single{it.optString("cmd")=="file_locks" && it.optString("action")=="lock"}
                assertEquals("2027-01-02 03:04:05",q.getString("until_local"));assertEquals("local secret password",q.getString("password"))
                compose.onNodeWithText("输入密码解锁").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
                shadowOf(Looper.getMainLooper()).idle()
                val dialog=ShadowDialog.getLatestDialog() as AlertDialog
                fun inputs(v:android.view.View):List<EditText> = if(v is EditText)listOf(v) else if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{inputs(v.getChildAt(it))} else emptyList()
                inputs(dialog.window!!.decorView).single().setText("local secret password")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{c.get().note=="文件已解锁"}
                assertEquals("file-test",stub.requests.single{it.optString("action")=="unlock"}.getString("lock_id"))
            }finally{c.pause().stop().destroy()}
        }
    }

    private class Stub:Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        private var locked=false
        private val jpeg=ByteArrayOutputStream().let {out ->Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}.compress(Bitmap.CompressFormat.JPEG,95,out);Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}
        private val worker=Thread {
            try {server.accept().use {socket ->
                val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val q=JSONObject(input.readLine()?:break);requests.add(JSONObject(q.toString()))
                    val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    when(q.optString("cmd")) {
                        "capabilities"->r.put("features",JSONArray(listOf("gestures","screen","browser","app_runtime","file_locks")))
                        "session"->r.put("sid","screen-test")
                        "screen"->r.put("data",jpeg).put("format","jpeg")
                        "gestures"->r.put("width",320).put("height",180)
                        "browser"->r.put("items",JSONArray())
                        "app_catalog","app_runtime"->{
                            val item=JSONObject().put("id","demo").put("name","Demo App").put("path","C:\\Apps\\demo.exe").put("kind","third_party").put("running",1).put("can_lock",true)
                                .put("windows",JSONArray().put(JSONObject().put("title","Demo Window").put("handle","100").put("pid",10)))
                            r.put("items",JSONArray().put(item).put(JSONObject().put("id","system").put("name","Windows Core").put("kind","windows").put("running",1)))
                        }
                        "app_freeze"->r.put("items",JSONArray())
                        "file_locks"->{
                            if(q.optString("action")=="lock")locked=true
                            if(q.optString("action")=="unlock")locked=false
                            r.put("computer_time","2026-10-30 12:00:00").put("items",JSONArray().apply {
                                if(locked)put(JSONObject().put("id","file-test").put("path","C:\\Data\\notes.txt").put("status","locked").put("until_local","2027-01-02 03:04:05"))
                            })
                        }
                    }
                    output.write(r.toString()+"\n");output.flush()
                }
            }}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

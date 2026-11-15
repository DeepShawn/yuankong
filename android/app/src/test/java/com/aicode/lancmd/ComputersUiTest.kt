package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.aicode.lancmd.net.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComputersUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val ctx get()=RuntimeEnvironment.getApplication()
    @Before fun before(){disconnect();ctx.getSharedPreferences(ComputerBook.FILE,0).edit().clear().commit();Prefs.forget(ctx)}
    @After fun after()=disconnect()
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};fail("电脑列表未完成")}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun latest()=ShadowDialog.getLatestDialog() as AlertDialog
    private fun render(v:View,name:String="lan-computers.png"){
        v.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));v.layout(0,0,411,891)
        val b=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/ui-previews/$name").apply{parentFile!!.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun inventoryAndNameEditsSurviveRecreationWithoutSendingRpcOrClosingTheCurrentClient(){
        ServerSocket(0).use{server->
            val requests=CopyOnWriteArrayList<String>()
            val thread=Thread{runCatching{server.accept().use{s->val input=s.getInputStream().bufferedReader();while(true)requests.add(input.readLine()?:break)}}}.apply{isDaemon=true;start()}
            val client=AgentClient("127.0.0.1",server.localPort)
            runBlocking{withContext(Dispatchers.IO){client.connect()}}
            ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)
            val agents=listOf(AgentInfo("192.168.1.2","PC Original",47823,"Windows",false,"pc-one"))
            val c=Robolectric.buildActivity(ComputersActivity::class.java)
            c.get().discovery={DiscoverResult(agents,false,1)};c.setup();var p=c.get()
            try{
                p.scan();waitFor{!p.scanning && p.book.computers.any{it.agentId=="pc-one"}}
                p.rename(p.book.computers.single{it.agentId=="pc-one"});shadowOf(Looper.getMainLooper()).idle()
                val d=latest();val input=all(d.window!!.decorView).filterIsInstance<EditText>().single()
                input.setText("x".repeat(41));d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(d.isShowing);assertNotNull(input.error)
                input.setText("办公室电脑");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{p.book.computers.any{it.alias=="办公室电脑"}}
                p.query="办公室";render(p.window.decorView)
                c.recreate();p=c.get()
                waitFor{p.book.computers.any{it.alias=="办公室电脑"}}
                assertEquals("办公室",p.query)
                assertEquals("PC Original",p.book.computers.single().reportedName)
                assertSame(client,ConnectionManager.sessionClient());assertTrue(requests.isEmpty())
            }finally{c.pause().stop().destroy()}
            assertFalse(client.closed);assertSame(client,ConnectionManager.sessionClient())
            client.close();thread.join(2000)
        }
    }
    @Test fun clearingHistoryRequiresConfirmationAndLeavesNamesAndCredentials(){
        val t=Target("192.168.1.2",47823,"Original PC","pc-one")
        ComputerBook.rename(ctx,t,"备注");val id=ComputerBook.connected(ctx,t,1000);ComputerBook.ended(ctx,id,2000)
        Prefs.saveToken(ctx,t.host,t.port,"TEST_CREDENTIAL")
        val c=Robolectric.buildActivity(ComputersActivity::class.java).setup();val p=c.get()
        try{
            waitFor{p.book.history.size==1};p.tab=1
            val next=ComputerBook.connected(ctx,t,3000);ComputerBook.ended(ctx,next,4000)
            waitFor{p.book.history.size==2 && p.book.history.first().ended==4000L}
            p.clearHistory();shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(2,ComputerBook.read(ctx).history.size)
            p.clearHistory();shadowOf(Looper.getMainLooper()).idle()
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitFor{p.book.history.isEmpty()}
            assertEquals("备注",p.book.computers.single().alias)
            assertEquals("TEST_CREDENTIAL",Prefs.token(ctx,t.host,t.port))
        }finally{c.pause().stop().destroy()}
    }
    @Test fun twoOnlineComputersCanBeExplicitlySwitchedAndReturningDoesNotDisconnect(){
        SwitchPeer("office-a","办公室电脑").use{a->SwitchPeer("study-b","书房电脑").use{b->
            ConnectionManager.selectTarget(ctx,a.target)
            waitFor{ConnectionManager.snapshot().connected && ConnectionManager.snapshot().port==a.target.port}
            val old=ConnectionManager.sessionClient()
            val intent=Intent(ctx,ComputersActivity::class.java).putExtra(ComputersActivity.SWITCH_MODE,true)
            val controller=Robolectric.buildActivity(ComputersActivity::class.java,intent)
            controller.get().discovery={DiscoverResult(listOf(a.agent().copy(ip="127.0.0.2"),b.agent()),false,1)}
            controller.setup();var page=controller.get()
            try{
                waitFor{!page.scanning && page.book.computers.size==2}
                assertSame(old,ConnectionManager.sessionClient())
                compose.onNodeWithText("当前电脑").assertIsNotEnabled()
                compose.onNodeWithText("切换").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{ConnectionManager.snapshot().connected && ConnectionManager.snapshot().port==b.target.port}
                assertTrue(old.closed);assertEquals("study-b",Prefs.target(ctx)!!.agentId)
                waitFor{page.book.computers.any{it.agentId==b.identity && it.lastConnected>0}}
                compose.waitForIdle()
                compose.onNodeWithText("当前电脑：${b.label} · ${b.target.host}:${b.target.port}").assertExists()
                compose.onNodeWithText("当前电脑：${a.label} · ${a.target.host}:${a.target.port}").assertDoesNotExist()
                render(page.window.decorView,"computer-switch.png")
                controller.recreate();page=controller.get()
                waitFor{page.book.computers.size==2}
                assertFalse(ConnectionManager.sessionClient().closed)
                assertEquals(b.target.port,ConnectionManager.sessionClient().port)
                assertTrue(b.requests.all{it.optString("cmd") in listOf("hello","ping")})
            }finally{controller.pause().stop().destroy()}
            assertFalse(ConnectionManager.sessionClient().closed)
        }}
    }
}

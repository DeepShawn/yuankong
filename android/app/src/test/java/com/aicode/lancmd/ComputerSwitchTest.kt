package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import com.aicode.lancmd.net.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real local sockets, with no discovery or remote actions required to switch. */
internal class SwitchPeer(val identity:String,val label:String=identity,val holdHello:CountDownLatch?=null):Closeable {
    private val server=ServerSocket(0)
    private val sockets=CopyOnWriteArrayList<Socket>()
    val requests=CopyOnWriteArrayList<JSONObject>()
    val target=Target("127.0.0.1",server.localPort,label,identity)
    fun agent()=AgentInfo(target.host,label,target.port,"Windows",true,identity)
    private val thread=Thread {
        try{while(!server.isClosed){
            val socket=server.accept();sockets.add(socket)
            Thread {
                runCatching{socket.use {
                    val input=socket.getInputStream().bufferedReader();val output=socket.getOutputStream().bufferedWriter()
                    while(true){
                        val request=JSONObject(input.readLine()?:break);requests.add(request)
                        if(request.optString("cmd")=="hello")holdHello?.await(6,TimeUnit.SECONDS)
                        val response=JSONObject().put("id",request.getInt("id")).put("ok",true).put("peer",identity)
                        if(request.optString("cmd")=="hello")response.put("agent",label).put("agent_id",identity).put("token","fixture-$identity")
                        output.write(response.toString()+"\n");output.flush()
                    }
                }}
            }.apply{isDaemon=true;start()}
        }}catch(_:Exception){}
    }.apply{isDaemon=true;start()}
    override fun close(){holdHello?.countDown();server.close();sockets.forEach{runCatching{it.close()}};thread.join(1000)}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ComputerSwitchTest {
    private val ctx get()=RuntimeEnvironment.getApplication()
    @Before fun before(){disconnect();Prefs.forget(ctx);ctx.getSharedPreferences(ComputerBook.FILE,0).edit().clear().commit()}
    @After fun after()=disconnect()
    private fun disconnect(){
        val switching=ConnectionManager::class.java.getDeclaredField("switchJob").apply{isAccessible=true}.get(ConnectionManager) as? Job
        ConnectionManager.stop()
        runBlocking{switching?.join();(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean){
        val until=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)}
        fail("电脑连接未到达预期状态：${ConnectionManager.snapshot()}")
    }
    private fun connected(peer:SwitchPeer)=ConnectionManager.snapshot().let{it.connected && it.port==peer.target.port && it.agentId==peer.identity}

    @Test fun switchingClosesOldClientKeepsTokensSeparateAndRejectsOldPageRequests(){
        SwitchPeer("pc-a").use{a->SwitchPeer("pc-b").use{b->
            assertTrue(ConnectionManager.selectTarget(ctx,a.target));waitFor{connected(a)}
            val old=ConnectionManager.sessionClient()
            assertFalse(ConnectionManager.selectTarget(ctx,a.target.copy(host="127.0.0.2")))
            assertSame(old,ConnectionManager.sessionClient())
            assertEquals(a.target.host,Prefs.target(ctx)!!.host)
            assertEquals(1,a.requests.count{it.optString("cmd")=="hello"})
            Prefs.saveToken(ctx,b.target.host,b.target.port,"saved-b")
            assertTrue(ConnectionManager.selectTarget(ctx,b.target));waitFor{connected(b)}
            assertTrue(old.closed);assertNotSame(old,ConnectionManager.sessionClient())
            assertEquals("saved-b",b.requests.first{it.optString("cmd")=="hello"}.getString("token"))
            assertEquals("fixture-pc-a",Prefs.token(ctx,a.target.host,a.target.port))
            assertEquals("pc-b",runBlocking{ConnectionManager.send("ping")}.getString("peer"))
            val count=b.requests.size
            val rejected=runCatching{runBlocking{pinnedCall(old,"ping")}}.exceptionOrNull()
            assertNotNull(rejected);assertTrue(rejected!!.message.orEmpty().contains("切换"))
            assertEquals(count,b.requests.size)
            assertFalse(ConnectionManager.selectTarget(ctx,b.target))
            assertEquals(1,b.requests.count{it.optString("cmd")=="hello"})
            assertTrue(ConnectionManager.selectTarget(ctx,a.target));waitFor{connected(a)}
            assertEquals("fixture-pc-a",a.requests.last{it.optString("cmd")=="hello"}.getString("token"))
            waitFor{ComputerBook.read(ctx).history.size==3}
            assertEquals(2,ComputerBook.read(ctx).history.count{it.ended>0})
            assertTrue(Prefs.explicitlySelected(ctx))
            Prefs.forget(ctx);assertFalse(Prefs.explicitlySelected(ctx))
        }}
    }

    @Test fun latestSelectionWinsWhileAnOlderHandshakeIsStillPending(){
        val release=CountDownLatch(1)
        SwitchPeer("slow-a",holdHello=release).use{a->SwitchPeer("pc-b").use{b->SwitchPeer("pc-c").use{c->
            ConnectionManager.selectTarget(ctx,a.target)
            waitFor{a.requests.any{it.optString("cmd")=="hello"}}
            ConnectionManager.selectTarget(ctx,b.target)
            ConnectionManager.selectTarget(ctx,c.target)
            waitFor{connected(c)}
            val chosen=ConnectionManager.sessionClient()
            release.countDown();Thread.sleep(150);shadowOf(Looper.getMainLooper()).idle()
            assertSame(chosen,ConnectionManager.sessionClient())
            assertEquals("pc-c",Prefs.target(ctx)!!.agentId)
            assertEquals("pc-c",runBlocking{ConnectionManager.send("ping")}.getString("peer"))
        }}}
    }

    @Test fun wrongHandshakeIdentityIsNotInstalledAsTheSelectedComputer(){
        SwitchPeer("wrong-device").use{other->
            ConnectionManager.selectTarget(ctx,other.target.copy(agentId="expected-device"))
            waitFor{ConnectionManager.snapshot().state==ConnectionManager.State.WAITING}
            assertFalse(ConnectionManager.isConnected())
            assertTrue(ConnectionManager.snapshot().message.contains("标识不一致"))
            assertEquals("expected-device",Prefs.target(ctx)!!.agentId)
            assertTrue(other.requests.all{it.optString("cmd")=="hello"})
        }
    }

    @Test fun explicitStopCancelsAnOutstandingSwitch(){
        val release=CountDownLatch(1)
        SwitchPeer("slow",holdHello=release).use{peer->
            ConnectionManager.selectTarget(ctx,peer.target)
            waitFor{peer.requests.isNotEmpty()}
            disconnect();release.countDown();Thread.sleep(100)
            assertFalse(ConnectionManager.isConnected())
            assertEquals(ConnectionManager.State.STOPPED,ConnectionManager.snapshot().state)
        }
    }

    @Test fun selectedPeerMissingFromDiscoveryDoesNotChooseAnotherOnlineComputer(){
        val target=Target("192.168.1.3",47823,"selected","known")
        val unrelated=AgentInfo("192.168.1.2","other",47823,"Windows",true,"other")
        val moved=AgentInfo("192.168.1.20","selected",47823,"Windows",true,"known")
        assertNull(TargetRouting.pick(target,listOf(unrelated),true))
        assertNull(TargetRouting.pick(target,listOf(moved.copy(port=47825)),true))
        assertSame(moved,TargetRouting.pick(target,listOf(unrelated,moved),true))
        assertNull(TargetRouting.pick(target.copy(agentId=""),listOf(unrelated),true))
        assertSame(unrelated,TargetRouting.pick(null,listOf(unrelated),false))
    }
}

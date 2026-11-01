package com.aicode.lancmd

import android.app.Application
import com.aicode.lancmd.net.AgentInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
class ComputerBookTest {
    private val ctx get()=RuntimeEnvironment.getApplication<Application>()
    private fun target(host:String="192.168.1.2",id:String="pc-one")=Target(host,47823,"Original PC",id)
    private fun agent(host:String="192.168.1.2",id:String="pc-one")=AgentInfo(host,"Original PC",47823,"Windows",false,id)
    @Before fun before(){ctx.getSharedPreferences(ComputerBook.FILE,0).edit().clear().commit();Prefs.forget(ctx)}
    @Test fun aliasesFollowAgentIdentityAcrossAddressChangesAndDoNotTransferToAnotherPc(){
        ComputerBook.discovered(ctx,listOf(agent()),100)
        ComputerBook.rename(ctx,target(),"工作电脑")
        val updated=ComputerBook.discovered(ctx,listOf(agent("192.168.1.7")),200)
        assertEquals(1,updated.computers.size);assertEquals("工作电脑",updated.computers.single().label)
        ComputerBook.rename(ctx,target(),"新备注") // An old row must not roll back the latest address.
        assertEquals("192.168.1.7",ComputerBook.read(ctx).computers.single().host)
        val reused=ComputerBook.discovered(ctx,listOf(agent("192.168.1.7","different-pc")),300)
        assertEquals(2,reused.computers.size)
        assertEquals("",reused.computers.single{it.agentId=="different-pc"}.alias)
        assertEquals("新备注",ComputerBook.displayName(ctx,target("192.168.1.7")))
        ComputerBook.rename(ctx,target("192.168.1.7"),"")
        assertEquals("Original PC",ComputerBook.displayName(ctx,target("192.168.1.7")))
    }
    @Test fun legacyTargetMigratesWithoutFabricatingAConnectionAndHistoryNeverStoresTokens(){
        val legacy=target(id="");Prefs.saveTarget(ctx,legacy);Prefs.saveToken(ctx,legacy.host,legacy.port,"TEST_SECRET_CREDENTIAL")
        assertTrue(ComputerBook.seed(ctx).history.isEmpty())
        ComputerBook.rename(ctx,legacy,"旧电脑")
        val id=ComputerBook.connected(ctx,target(),1000)
        val first=ComputerBook.read(ctx)
        assertEquals(1,first.computers.size);assertEquals("旧电脑",first.computers.single().alias)
        assertEquals(1,first.history.size)
        ComputerBook.ended(ctx,id,2000);ComputerBook.ended(ctx,id,3000)
        assertEquals(2000L,ComputerBook.read(ctx).history.single().ended)
        assertFalse(ctx.getSharedPreferences(ComputerBook.FILE,0).getString("book","")!!.contains("TEST_SECRET_CREDENTIAL"))
        ComputerBook.clearHistory(ctx);ComputerBook.ended(ctx,id,4000)
        assertTrue(ComputerBook.read(ctx).history.isEmpty())
        assertEquals("旧电脑",ComputerBook.read(ctx).computers.single().alias)
        assertEquals("TEST_SECRET_CREDENTIAL",Prefs.token(ctx,legacy.host,legacy.port))
    }
    @Test fun concurrentHistoryAndNameChangesPreserveAllCompletedSessions(){
        val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(1..12).map{i->pool.submit{val id=ComputerBook.connected(ctx,target(),i*1000L);ComputerBook.ended(ctx,id,i*1000L+100)}}+
                pool.submit{ComputerBook.rename(ctx,target(),"本地备注")}
            jobs.forEach{it.get()}
        }finally{pool.shutdownNow()}
        val s=ComputerBook.read(ctx)
        assertEquals("本地备注",s.computers.single().alias)
        assertEquals(12,s.history.size);assertTrue(s.history.all{it.ended==it.started+100})
        assertEquals(12_000L,s.computers.single().lastConnected)
    }
    @Test fun boundedHistoryAndCorruptStorageHaveExplicitRecovery(){
        ComputerBook.discovered(ctx,(1..120).map{agent("192.168.1.${it}","pc-$it")},100)
        assertEquals(ComputerBook.MAX_COMPUTERS,ComputerBook.read(ctx).computers.size)
        repeat(ComputerBook.MAX_HISTORY+2){ComputerBook.connected(ctx,target(),it+1000L)}
        val s=ComputerBook.read(ctx)
        assertEquals(ComputerBook.MAX_HISTORY,s.history.size);assertEquals(1201L,s.history.first().started)
        ctx.getSharedPreferences(ComputerBook.FILE,0).edit().putString("book","broken").commit()
        try{ComputerBook.rename(ctx,target(),"lost");fail("Corruption must not silently overwrite history")}catch(_:IllegalStateException){}
        assertEquals("broken",ctx.getSharedPreferences(ComputerBook.FILE,0).getString("book",null))
        assertTrue(ComputerBook.clear(ctx).computers.isEmpty())
    }
    @Test fun invalidNamesAndAddressesAreRejected(){
        for(alias in listOf("x".repeat(41),"line\nbreak","bad\u0000name")){
            try{ComputerBook.rename(ctx,target(),alias);fail("accepted $alias")}catch(_:IllegalArgumentException){}
        }
        val invalid=listOf(agent().copy(port=0),agent().copy(ip="\n"),agent().copy(port=65536))
        assertTrue(ComputerBook.discovered(ctx,invalid).computers.isEmpty())
    }
}

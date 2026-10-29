package com.aicode.lancmd

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
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
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanoramaRecordingUiTest {
    @Before fun before()=disconnect()
    @After fun after()=disconnect()
    private fun disconnect() {
        ConnectionManager.stop()
        runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()}
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun waitFor(check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(10,java.util.concurrent.TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)}
        fail("录制界面未完成预期操作")
    }
    @Test fun panoramaConfirmsAllFourSourcesAndWritesIndependentGainsAndCameraPosition() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(RecordingsActivity::class.java,Intent(RuntimeEnvironment.getApplication(),RecordingsActivity::class.java).putExtra("kind","panorama")).setup()
            val page=c.get()
            try {
                waitFor{!page.busy && page.note.contains("设备已刷新")}
                assertEquals(3,page.modeIndex);assertTrue(page.withSystemAudio);assertTrue(page.withAudio)
                page.systemGain="85";page.microphoneGain="130";page.corner=2
                page.confirmStart();shadowOf(Looper.getMainLooper()).idle()
                assertFalse(stub.requests.any{it.optString("action")=="start"})
                val dialog=ShadowDialog.getLatestDialog() as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{!page.busy && page.records.any{it.mode=="panorama" && it.active}}
                val q=stub.requests.single{it.optString("action")=="start"}
                assertEquals("panorama",q.getString("mode"));assertTrue(q.getBoolean("audio"));assertTrue(q.getBoolean("system_audio"))
                assertEquals(85,q.getInt("system_gain"));assertEquals(130,q.getInt("microphone_gain"));assertEquals(2,q.getInt("corner"))
                assertEquals(2,q.getInt("camera"));assertEquals(-1,q.getInt("microphone"))
                assertTrue(page.records.single().detail.contains("系统声音"));assertTrue(page.records.single().detail.contains("麦克风"))
                page.stop(page.records.single());waitFor{!page.busy && page.records.single().state=="completed"}
                assertEquals("panorama-test",stub.requests.single{it.optString("action")=="stop"}.getString("recording_id"))
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun oldRecorderBinaryCannotStartPanoramaEvenWhenTheReceiverModuleIsNew() {
        Stub(protocol=1).use {stub ->
            stub.install();val c=Robolectric.buildActivity(RecordingsActivity::class.java,Intent(RuntimeEnvironment.getApplication(),RecordingsActivity::class.java).putExtra("kind","panorama")).setup()
            try {
                val page=c.get();waitFor{!page.busy && page.note.contains("设备已刷新")};page.confirmStart()
                assertTrue(page.note.contains("LanCmdRecord.exe"));assertFalse(stub.requests.any{it.optString("action")=="start"})
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun cameraCanRecordOnlySystemAudioAndRejectsInvalidGainBeforeConfirmation() {
        Stub().use {stub ->
            stub.install();val c=Robolectric.buildActivity(RecordingsActivity::class.java).setup()
            try {
                val page=c.get();waitFor{!page.busy && page.note.contains("设备已刷新")}
                page.selectMode(2);page.withSystemAudio=true;page.withAudio=false;page.systemGain="201";page.confirmStart()
                assertTrue(page.note.contains("0–200"));assertFalse(stub.requests.any{it.optString("action")=="start"})
                page.systemGain="75";page.confirmStart();shadowOf(Looper.getMainLooper()).idle()
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitFor{stub.requests.any{it.optString("action")=="start"} && !page.busy}
                val q=stub.requests.single{it.optString("action")=="start"};assertEquals("camera",q.getString("mode"))
                assertTrue(q.getBoolean("system_audio"));assertFalse(q.getBoolean("audio"));assertEquals(75,q.getInt("system_gain"))
            }finally{c.pause().stop().destroy()}
        }
    }
    private class Stub(val protocol:Int=2):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>()
        private val server=ServerSocket(0)
        private val client=AgentClient("127.0.0.1",server.localPort)
        private var recording:JSONObject?=null
        private val worker=Thread {
            try {server.accept().use {socket ->
                val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                while(true) {
                    val line=reader.readLine() ?: break;val q=JSONObject(line);requests.add(q)
                    val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                    if(q.optString("cmd")=="capabilities")r.put("features",JSONArray(listOf("recordings","panorama_recording","system_audio_recording")))
                    else {
                        if(q.optString("action")=="start")recording=JSONObject(q.toString()).put("id","panorama-test").put("status","recording").put("created",1).put("size",4096).put("path","C:\\Recordings\\panorama.mp4")
                        if(q.optString("action")=="stop")recording?.put("status","completed")
                        r.put("protocol",protocol).put("directory","C:\\Recordings")
                            .put("microphones",JSONArray().put(JSONObject().put("id",5).put("name","Microphone")))
                            .put("cameras",JSONArray().put(JSONObject().put("id",2).put("name","Camera")))
                            .put("recordings",JSONArray().apply{recording?.let{put(it)}})
                    }
                    writer.write(r.toString()+"\n");writer.flush()
                }
            }}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();worker.join(2000)}
    }
}

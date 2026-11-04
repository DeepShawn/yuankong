package com.aicode.lancmd

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnouncementEditorTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val ctx get()=RuntimeEnvironment.getApplication()
    @Before fun before(){disconnect();ctx.getSharedPreferences("announcements",0).edit().clear().commit()}
    @After fun after(){Settings.Global.putFloat(ctx.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f);Settings.Global.putFloat(ctx.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f);disconnect()}
    private fun disconnect(){ConnectionManager.stop();runBlocking{(ConnectionManager::class.java.getDeclaredField("loopJob").apply{isAccessible=true}.get(ConnectionManager) as? Job)?.join()};shadowOf(Looper.getMainLooper()).idle()}
    private fun waitFor(check:()->Boolean){val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idleFor(25,java.util.concurrent.TimeUnit.MILLISECONDS);if(check())return;Thread.sleep(10)};fail("预览编辑未完成")}
    private fun custom()=AnnouncementGeometry.custom(announcementDefaults().put("text","本地预览消息"),AnnouncementGeometry.Box(100,200,500,300))
    private fun size(v:View,w:Int=411,h:Int=260){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    private fun drag(v:AnnouncementPreview,x:Float,y:Float,dx:Float,dy:Float){
        listOf(Triple(MotionEvent.ACTION_DOWN,x,y),Triple(MotionEvent.ACTION_MOVE,x+dx,y+dy),Triple(MotionEvent.ACTION_UP,x+dx,y+dy)).forEach{(action,px,py)->
            val e=MotionEvent.obtain(100,200,action,px,py,0);try{assertTrue(v.onTouchEvent(e))}finally{e.recycle()}}
    }
    @Test fun geometryMatchesWindowsForOffsetsDpiAndScreenEdges(){
        val a=AnnouncementGeometry.Area(1920,1040,144,-1920,40)
        assertEquals(AnnouncementGeometry.Box(-1728,248,960,312),AnnouncementGeometry.bounds(custom(),a))
        val r=AnnouncementGeometry.custom(custom(),AnnouncementGeometry.Box(900,900,100,100))
        assertEquals(AnnouncementGeometry.Box(-320,122,320,128),AnnouncementGeometry.bounds(r,AnnouncementGeometry.Area(320,240,192,-320,10)))
        val original=AnnouncementGeometry.Box(100,200,500,300)
        assertEquals(AnnouncementGeometry.Box(500,700,500,300),AnnouncementGeometry.transform(original,5000,5000,0,a))
        for(corner in 1..4){val b=AnnouncementGeometry.transform(original,-5000,5000,corner,a);assertTrue(b.valid())}
        var fractional=AnnouncementGeometry.custom(custom(),AnnouncementGeometry.Box(100,200,338,307))
        repeat(12){val start=AnnouncementGeometry.normalized(fractional,a);val moved=AnnouncementGeometry.transform(start,1,1,0,a)
            fractional=AnnouncementGeometry.custom(fractional,moved);assertEquals(338,moved.width);assertEquals(307,moved.height)}
    }
    @Test fun playbackPausesAndResumesOnMonotonicTimeAndUsesWindowsMotionContract(){
        val clock=AnnouncementPlayback();clock.play(1000);assertEquals(300L,clock.elapsed(1300))
        clock.pause(1400);assertEquals(400L,clock.elapsed(5000));clock.play(6000);assertEquals(600L,clock.elapsed(6200))
        clock.stop();assertEquals(0L,clock.elapsed(9000));assertEquals("stopped",clock.state)
        val f=AnnouncementGeometry.frame("left",300f,60f,200f,30f,1f,100f)
        assertEquals(200f,f.x,.001f);assertEquals(15f,f.y,.001f)
        assertEquals(1,AnnouncementGeometry.frame("left",300f,60f,200f,30f,5.32f,100f).cycles)
        val v=AnnouncementPreview(ctx);v.config=custom().put("motion","static").put("duration",1).put("repeats",0);size(v);v.playPreview()
        shadowOf(Looper.getMainLooper()).idleFor(1200,java.util.concurrent.TimeUnit.MILLISECONDS)
        val bitmap=Bitmap.createBitmap(411,260,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap));bitmap.recycle()
        assertEquals("completed",v.playback.state)
    }
    private fun seewo()=announcementSeewoDefaults().put("text","第一条公告")
        .put("messages",JSONArray().put("第一条公告").put("第二条公告"))
    private fun drawBalanced(view:AnnouncementPreview) {
        val bitmap=Bitmap.createBitmap(411,260,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            val count=canvas.saveCount
            view.draw(canvas)
            assertEquals("预览不得恢复调用者的画布状态",count,canvas.saveCount)
        }finally{bitmap.recycle()}
    }
    @Test fun seewoCanvasRestoresOnlyItsOwnFramesWhenStoppedPlayingAndPaused(){
        val view=AnnouncementPreview(ctx);view.config=seewo();size(view)
        repeat(3){drawBalanced(view)}
        view.playPreview();repeat(3){drawBalanced(view)}
        view.pausePreview();drawBalanced(view)
        assertEquals("paused",view.playback.state)
        view.stopPreview();drawBalanced(view)
        // The classic renderer shares the Canvas but retains its own save/layer stack.
        view.config=custom();drawBalanced(view)
    }
    @Test fun savedSeewoDraftOpensExpandsAndRecreatesWithoutCanvasUnderflow(){
        ctx.getSharedPreferences("announcements",0).edit().putString("draft",seewo().toString()).commit()
        val controller=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup()
        try {
            var page=controller.get()
            size(page.preview);drawBalanced(page.preview)
            page.preview.playback.play(SystemClock.elapsedRealtime()-3500)
            page.preview.pausePreview()
            page.expand(true);compose.waitForIdle()
            size(page.window.decorView,411,891)
            val image=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(image);val count=canvas.saveCount
                page.window.decorView.draw(canvas);assertEquals(count,canvas.saveCount)
                File("build/ui-previews/announcements-seewo-fixed.png").apply{parentFile!!.mkdirs()}
                    .outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally{image.recycle()}
            controller.recreate();page=controller.get()
            assertTrue(page.expanded);assertEquals("seewo",page.config.getString("style"))
            assertEquals(2,SeewoBar.messages(page.config).size)
            size(page.preview);drawBalanced(page.preview)
            page.expand(false);compose.waitForIdle();size(page.preview);drawBalanced(page.preview)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun seewoInfinitePlaybackAndCountedRoundsUseTheSameMessageSchedule(){
        val view=AnnouncementPreview(ctx);size(view)
        val infinite=seewo().put("repeats",0).put("duration",0)
        view.config=infinite
        view.playback.play(SystemClock.elapsedRealtime()-12*60*60*1000L)
        drawBalanced(view);assertEquals("playing",view.playback.state)
        val limited=JSONObject(infinite.toString()).put("repeats",2)
        view.config=limited
        assertEquals("stopped",view.playback.state)
        val band=SeewoBar.layout(view.area,limited)
        val round=SeewoBar.durations(limited,band.font,band.speed,band.viewport.width()).sum()
        view.playback.play(SystemClock.elapsedRealtime()-((round*2-.1f)*1000).toLong())
        drawBalanced(view);assertEquals("playing",view.playback.state)
        view.stopPreview()
        view.playback.play(SystemClock.elapsedRealtime()-((round*2+.1f)*1000).toLong())
        drawBalanced(view);assertEquals("completed",view.playback.state)
    }
    @Test fun scrollRatesScaleTheFullRoundWithoutChangingAppearanceOrContinuingOldPreviewTime(){
        val config=seewo();val area=AnnouncementGeometry.Area()
        val original=SeewoBar.layout(area,config)
        val duration=SeewoBar.durations(config,original.font,original.speed,original.viewport.width()).sum()
        for(rate in listOf(25,50,100,150,200,300)) {
            val changed=JSONObject(config.toString()).put("scroll_rate",rate)
            val band=SeewoBar.layout(area,changed)
            assertEquals(original.copy(speed=band.speed),band)
            assertEquals(original.speed*rate/100f,band.speed,.001f)
            assertEquals(duration*100/rate,SeewoBar.durations(changed,band.font,band.speed,band.viewport.width()).sum(),.001f)
        }
        val view=AnnouncementPreview(ctx);view.config=config;view.playPreview()
        view.config=JSONObject(config.toString()).put("scroll_rate",200)
        assertEquals("stopped",view.playback.state);assertEquals(0L,view.playback.elapsed(SystemClock.elapsedRealtime()))
        size(view);drawBalanced(view)
    }
    @Test fun speedIsSentAsAnIntegerAndConfirmedWithdrawalKeepsTheDraft(){
        Stub(complete=true).use {stub->
            stub.install();val controller=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{stub.requests.any{it.optString("action")=="layout"}}
                page.applySeewo(true);page.updateMessages("第一条公告\n第二条公告")
                page.choosePlaybackMode(1);page.change("repeats",3);page.change("scroll_rate","150")
                page.confirmSend();compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && page.job?.optString("status")=="completed"}
                val sent=stub.requests.single{it.optString("action")=="show"}.getJSONObject("config")
                assertTrue(sent.get("scroll_rate") is Int);assertEquals(150,sent.getInt("scroll_rate"))
                assertEquals(3,sent.getInt("repeats"));assertEquals(0,sent.getInt("duration"))
                assertEquals("循环完成，已自动撤回",page.stateLabel(page.job!!))
                assertEquals(2,SeewoBar.messages(page.config).size)
                assertEquals("播放完成",page.stateLabel(JSONObject().put("status","completed")))
                for(rate in listOf("","24","301")) {
                    page.change("scroll_rate",rate);page.sendNow();assertTrue(page.note.contains("25–300"))
                }
                assertEquals(1,stub.requests.count{it.optString("action")=="show"})
                page.change("scroll_rate",150);controller.recreate()
                assertEquals(150,controller.get().config.getInt("scroll_rate"))
                assertEquals("第一条公告",controller.get().config.getString("text"))
                assertEquals("stopped",controller.get().preview.playback.state)
                assertEquals(1,stub.requests.count{it.optString("action")=="show"})
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun legacyDraftsUseOriginalSpeedAndOldReceiversCannotIgnoreACustomRate(){
        val legacy=seewo().apply{remove("scroll_rate")}
        ctx.getSharedPreferences("announcements",0).edit().putString("draft",legacy.toString()).commit()
        Stub(speed=false).use {stub->
            stub.install();val controller=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{stub.requests.any{it.optString("action")=="layout"}}
                assertEquals(100,page.config.getInt("scroll_rate"))
                assertEquals(SeewoBar.speed(page.preview.area),SeewoBar.layout(page.preview.area,page.config).speed,.001f)
                page.change("scroll_rate",200);page.sendNow()
                assertTrue(page.note.contains("3.18.6"));assertFalse(stub.requests.any{it.optString("action")=="show"})
                page.change("scroll_rate",100);page.sendNow()
                waitFor{!page.busy && stub.requests.any{it.optString("action")=="show"}}
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun loopSelectionClearsOldTimeCapsAndTransmitsIntegersWithoutChangingStyle(){
        Stub().use {stub->
            stub.install();val controller=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val page=controller.get()
            try {
                waitFor{page.preview.area.width==1920}
                page.applySeewo(true);page.updateMessages("第一条公告\n第二条公告")
                page.change("duration",30)
                val appearance=JSONObject(page.config.toString()).apply{remove("duration");remove("repeats")}.toString()
                page.choosePlaybackMode(0)
                assertEquals(0,page.config.getInt("repeats"));assertEquals(0,page.config.getInt("duration"))
                assertFalse(stub.requests.any{it.optString("action")=="show"})
                page.confirmSend();compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.count{it.optString("action")=="show"}==1}
                val forever=stub.requests.single{it.optString("action")=="show"}.getJSONObject("config")
                assertEquals(0,forever.getInt("repeats"));assertEquals(0,forever.getInt("duration"))
                page.choosePlaybackMode(1);page.change("repeats","3")
                page.confirmSend();compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{!page.busy && stub.requests.count{it.optString("action")=="show"}==2}
                val counted=stub.requests.last{it.optString("action")=="show"}.getJSONObject("config")
                assertTrue(counted.get("repeats") is Int);assertEquals(3,counted.getInt("repeats"));assertEquals(0,counted.getInt("duration"))
                for(value in listOf("","0","101")) {
                    page.change("repeats",value);page.sendNow();assertTrue(page.note.contains("1–100"))
                }
                assertEquals(2,stub.requests.count{it.optString("action")=="show"})
                page.choosePlaybackMode(0)
                assertEquals(appearance,JSONObject(page.config.toString()).apply{remove("duration");remove("repeats")}.toString())
                controller.recreate();assertEquals(0,controller.get().playbackMode)
                assertEquals(0,controller.get().config.getInt("repeats"));assertEquals(0,controller.get().config.getInt("duration"))
                assertEquals(2,stub.requests.count{it.optString("action")=="show"})
            }finally{controller.pause().stop().destroy()}
        }
    }
    @Test fun draggingAndCornerResizingChangeDraftButCanvasZoomDoesNotChangeItsGeometry(){
        val v=AnnouncementPreview(ctx);v.config=custom();v.area=AnnouncementGeometry.Area(1920,1040,144)
        size(v);var edits=0;v.onEdit={edits++}
        val first=v.boxOnView();val scale=first.width()/960f
        drag(v,first.centerX(),first.centerY(),1920*.1f*scale,1040*.1f*scale)
        val moved=v.config.getJSONObject("rect");assertEquals(200,moved.getInt("x"));assertEquals(300,moved.getInt("y"))
        v.resizeMode=true;val next=v.boxOnView()
        drag(v,next.right,next.bottom,1920*.1f*scale,1040*.1f*scale)
        assertEquals(600,v.config.getJSONObject("rect").getInt("width"));assertEquals(400,v.config.getJSONObject("rect").getInt("height"))
        val draft=v.config.toString();val before=v.boxOnView().width();v.zoom=3f
        assertEquals(draft,v.config.toString());assertEquals(before*3,v.boxOnView().width(),.01f);assertEquals(2,edits)
    }
    @Test fun localPlayAndDragSendNothingUntilConfirmationThenUseTheSameRectangle(){
        Stub().use{stub->
            stub.install();val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val p=c.get()
            try{
                waitFor{p.preview.area.width==1920 && p.preview.area.height==1040}
                p.change("text","确认后才发送");p.setBox(AnnouncementGeometry.Box(100,200,500,300))
                p.togglePreview();assertEquals("playing",p.preview.playback.state);p.togglePreview();assertEquals("paused",p.preview.playback.state)
                size(p.preview);val b=p.preview.boxOnView();val scale=b.width()/960
                drag(p.preview,b.centerX(),b.centerY(),1920*.1f*scale,0f)
                assertFalse(stub.requests.any{it.optString("action")=="show"})
                val expected=p.config.getJSONObject("rect").toString()
                p.expand(true);compose.onNodeWithText("预览 1×").performSemanticsAction(SemanticsActions.OnClick){it()}
                p.confirmSend();compose.onNodeWithText("确认").performSemanticsAction(SemanticsActions.OnClick){it()}
                waitFor{p.job!=null && !p.busy}
                val sent=stub.requests.single{it.optString("action")=="show"}.getJSONObject("config")
                assertEquals("custom",sent.getString("placement"));assertEquals(expected,sent.getJSONObject("rect").toString());assertFalse(sent.has("zoom"))
                size(p.window.decorView,411,891);val image=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);p.window.decorView.draw(Canvas(image))
                File("build/ui-previews/announcement-placement.png").apply{parentFile!!.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                c.recreate();assertEquals(expected,c.get().config.getJSONObject("rect").toString());assertTrue(c.get().expanded)
            }finally{c.pause().stop().destroy()}
        }
    }
    @Test fun offlinePreviewAndDraftSurviveReopeningWithoutStartingAnyConnection(){
        val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val p=c.get()
        p.change("text","离线草稿");p.setBox(AnnouncementGeometry.Box(120,150,500,250));p.togglePreview()
        assertEquals("playing",p.preview.playback.state);assertFalse(ConnectionManager.isConnected())
        c.pause().stop().destroy()
        val reopened=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup()
        try{assertEquals("离线草稿",reopened.get().config.getString("text"));assertEquals(120,reopened.get().config.getJSONObject("rect").getInt("x"));assertEquals("stopped",reopened.get().preview.playback.state)}
        finally{reopened.pause().stop().destroy()}
    }
    @Test fun oldReceiversCannotSilentlyIgnoreACustomPlacement(){
        Stub(layout=false).use{stub->stub.install();val c=Robolectric.buildActivity(AnnouncementsActivity::class.java).setup();val p=c.get()
            try{waitFor{stub.requests.any{it.optString("cmd")=="capabilities"}};p.change("text","旧版不能忽略位置");p.setBox(AnnouncementGeometry.Box(120,150,500,250));p.sendNow()
                assertTrue(p.note.contains("3.15.0"));assertFalse(stub.requests.any{it.optString("action")=="show"})}
            finally{c.pause().stop().destroy()}}
    }
    @Test fun entryMotionRespectsDisabledSystemAnimationsAndRestoredActivities(){
        Settings.Global.putFloat(ctx.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f);Settings.Global.putFloat(ctx.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)
        assertTrue(EntryMotion.enabled(ctx,false));assertFalse(EntryMotion.enabled(ctx,true))
        Settings.Global.putFloat(ctx.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f);assertFalse(EntryMotion.enabled(ctx,false))
        Settings.Global.putFloat(ctx.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f);Settings.Global.putFloat(ctx.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,0f);assertFalse(EntryMotion.enabled(ctx,false))
        assertTrue(AnimationUtils.loadAnimation(ctx,R.anim.workspace_enter).computeDurationHint() in 1L..300L)
    }
    private class Stub(val layout:Boolean=true,val speed:Boolean=true,val complete:Boolean=false):Closeable {
        val requests=CopyOnWriteArrayList<JSONObject>();private val server=ServerSocket(0);private val client=AgentClient("127.0.0.1",server.localPort)
        private var visible=false
        private val thread=Thread{runCatching{server.accept().use{socket->val input=socket.getInputStream().bufferedReader();val out=socket.getOutputStream().bufferedWriter()
            while(true){val q=JSONObject(input.readLine()?:break);requests.add(q);val r=JSONObject().put("id",q.getInt("id")).put("ok",true)
                when(q.optString("cmd")){
                    "capabilities"->r.put("features",JSONArray().put("announcements").apply{if(layout)put("announcement_layout");if(speed)put("announcement_speed")})
                    "announcements"->if(q.optString("action")=="layout")r.put("area",JSONObject().put("x",80).put("y",24).put("width",1920).put("height",1040).put("dpi",144))
                    else{if(q.optString("action")=="show")visible=true;r.put("job",if(visible)JSONObject().put("id","notice").put("status",if(complete)"completed" else "playing")
                        .apply{if(complete)put("reason","repeats").put("withdrawn",true).put("cycles",3)} else JSONObject.NULL)}
                }
                out.write(r.toString()+"\n");out.flush()
            }}}}.apply{isDaemon=true;start()}
        fun install(){runBlocking{withContext(Dispatchers.IO){client.connect()}};ConnectionManager::class.java.getDeclaredField("client").apply{isAccessible=true}.set(ConnectionManager,client)}
        override fun close(){client.close();server.close();thread.join(2000)}
    }
}

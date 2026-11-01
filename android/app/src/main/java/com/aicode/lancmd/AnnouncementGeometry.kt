package com.aicode.lancmd

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The same integer-permille and DPI contract as Windows announcements.window_rect. */
internal object AnnouncementGeometry {
    data class Area(val width:Int=1920,val height:Int=1080,val dpi:Int=96,val x:Int=0,val y:Int=0) {
        init{require(width in 1..32768 && height in 1..32768 && dpi in 48..768 && x in -1_000_000..1_000_000 && y in -1_000_000..1_000_000)}
        val scale get()=dpi/96.0
    }
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int) {
        fun json()=JSONObject().put("x",x).put("y",y).put("width",width).put("height",height)
        fun valid()=width in 10..1000 && height in 10..1000 && x in 0..1000-width && y in 0..1000-height
    }
    fun fromJson(r:JSONObject)=Box(r.getInt("x"),r.getInt("y"),r.getInt("width"),r.getInt("height"))
    fun bounds(c:JSONObject,a:Area):Box {
        val s=a.scale
        if(c.optString("placement")=="custom") {
            val r=fromJson(c.getJSONObject("rect"));require(r.valid())
            val w=maxOf((160*s).toInt(),(a.width*r.width+500)/1000).coerceAtMost(a.width)
            val h=maxOf((64*s).toInt(),(a.height*r.height+500)/1000).coerceAtMost(a.height)
            return Box(a.x+minOf(a.width-w,(a.width*r.x+500)/1000),a.y+minOf(a.height-h,(a.height*r.y+500)/1000),w,h)
        }
        var w=maxOf(240,(a.width*c.optInt("width",100).coerceIn(30,100)/100.0).toInt())
        val mode=c.optString("mode","ticker")
        var h=when(mode){
            "popup"->{w=minOf(w,(a.width*.8).toInt());minOf((360*s).toInt(),(a.height*.6).toInt())}
            "sticky"->{w=minOf(w,(480*s).toInt());minOf((240*s).toInt(),(a.height*.45).toInt())}
            else->minOf((maxOf(64.0,c.optInt("font_size",28).coerceIn(16,96)*2.2)*s).toInt(),a.height/2)
        }
        w=minOf(w,a.width);h=h.coerceAtLeast(64).coerceAtMost(a.height)
        var x=a.x+(a.width-w)/2
        var y=when(c.optString("position")){"center"->a.y+(a.height-h)/2;"bottom"->a.y+a.height-h;else->a.y}
        if(mode=="sticky"){x=a.x+maxOf(0,a.width-w-(12*s).toInt());y=maxOf(a.y,minOf(y,a.y+a.height-h-(12*s).toInt()))}
        return Box(x,y,w,h)
    }
    fun normalized(c:JSONObject,a:Area):Box {
        val minW=ceil((160*a.scale).toInt()*1000.0/a.width).toInt().coerceIn(10,1000)
        val minH=ceil((64*a.scale).toInt()*1000.0/a.height).toInt().coerceIn(10,1000)
        if(c.optString("placement")=="custom"){
            val r=fromJson(c.getJSONObject("rect"));require(r.valid())
            val w=maxOf(minW,r.width);val h=maxOf(minH,r.height)
            return Box(r.x.coerceAtMost(1000-w),r.y.coerceAtMost(1000-h),w,h)
        }
        val b=bounds(c,a)
        val w=(b.width*1000.0/a.width).roundToInt().coerceIn(minW,1000)
        val h=(b.height*1000.0/a.height).roundToInt().coerceIn(minH,1000)
        return Box(((b.x-a.x)*1000.0/a.width).roundToInt().coerceIn(0,1000-w),
            ((b.y-a.y)*1000.0/a.height).roundToInt().coerceIn(0,1000-h),w,h)
    }
    fun custom(c:JSONObject,b:Box):JSONObject {require(b.valid());return JSONObject(c.toString()).put("placement","custom").put("rect",b.json())}
    /** Corner 0 moves; 1/2/3/4 resize top-left/top-right/bottom-left/bottom-right. */
    fun transform(b:Box,dx:Int,dy:Int,corner:Int,a:Area):Box {
        if(corner==0)return b.copy(x=(b.x+dx).coerceIn(0,1000-b.width),y=(b.y+dy).coerceIn(0,1000-b.height))
        val minW=ceil((160*a.scale).toInt()*1000.0/a.width).toInt().coerceIn(10,1000)
        val minH=ceil((64*a.scale).toInt()*1000.0/a.height).toInt().coerceIn(10,1000)
        val right=b.x+b.width;val bottom=b.y+b.height
        val left=(corner==1 || corner==3);val top=corner==1 || corner==2
        val x=if(left)(b.x+dx).coerceIn(0,maxOf(0,right-minW)) else b.x
        val y=if(top)(b.y+dy).coerceIn(0,maxOf(0,bottom-minH)) else b.y
        val r=if(left)right else (right+dx).coerceIn(minOf(1000,b.x+minW),1000)
        val d=if(top)bottom else (bottom+dy).coerceIn(minOf(1000,b.y+minH),1000)
        return Box(x,y,r-x,d-y)
    }
    data class Frame(val x:Float,val y:Float,val cycles:Int,val alpha:Float=1f)
    fun frame(motion:String,w:Float,h:Float,tw:Float,th:Float,elapsed:Float,speed:Float):Frame {
        val t=elapsed.coerceAtLeast(0f);val cy=(h-th)/2
        return when(motion){
            "left","right"->{val d=w+tw+32;val travel=t*speed;Frame(if(motion=="left")w-travel%d else -tw+travel%d,cy,(travel/d).toInt())}
            "up","down"->{val d=h+th+24;val travel=t*speed;Frame(maxOf(0f,(w-tw)/2),if(motion=="up")h-travel%d else -th+travel%d,(travel/d).toInt())}
            "bounce"->{val span=maxOf(1f,abs(w-tw));val p=t*speed/(2*span);Frame((w-tw)*(1-abs(2*(p%1)-1)),cy,p.toInt())}
            "fade"->{val p=t%5;Frame(maxOf(0f,(w-tw)/2),cy,(t/5).toInt(),minOf(1f,p/.4f,(5-p)/.4f))}
            else->Frame(maxOf(0f,(w-tw)/2),cy,(t/5).toInt())
        }
    }
}

/** Monotonic local playback; pausing and offline editing never contact the receiver. */
internal class AnnouncementPlayback {
    var state="stopped";private set
    private var accumulated=0L
    private var started=0L
    fun elapsed(now:Long)=if(state=="playing")accumulated+(now-started).coerceAtLeast(0) else accumulated
    fun play(now:Long){if(state=="playing")return;if(state=="completed")accumulated=0;started=now;state="playing"}
    fun pause(now:Long){if(state=="playing"){accumulated=elapsed(now);state="paused"}}
    fun stop(){accumulated=0;state="stopped"}
    fun finish(now:Long){accumulated=elapsed(now);state="completed"}
}

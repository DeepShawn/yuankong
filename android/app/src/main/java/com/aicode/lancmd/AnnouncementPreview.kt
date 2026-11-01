package com.aicode.lancmd

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

internal fun announcementDefaults()=JSONObject().put("text","").put("prefix","消息提醒").put("mode","ticker").put("motion","left")
    .put("position","top").put("shape","rectangle").put("background","#081424").put("foreground","#FFFFFF").put("accent","#56B5F1")
    .put("font","Microsoft YaHei UI").put("font_size",28).put("bold",true).put("speed",90).put("opacity",92).put("width",100).put("duration",0).put("repeats",5)
    .put("placement","preset").put("rect",JSONObject.NULL)

/** Local work-area canvas. Playing and editing never send RPCs. */
class AnnouncementPreview(context:Context):View(context) {
    var config=announcementDefaults()
        set(value){val reset=listOf("text","motion","mode").any{field.optString(it)!=value.optString(it)}
            field=JSONObject(value.toString());if(reset)stopPreview();invalidate()}
    internal var area=AnnouncementGeometry.Area()
        set(value){field=value;cancelGesture();invalidate()}
    var resizeMode=false
        set(value){field=value;cancelGesture();invalidate()}
    var zoom=1f
        set(value){
            val b=AnnouncementGeometry.bounds(config,area)
            focusX=((b.x-area.x)+b.width/2f)/area.width;focusY=((b.y-area.y)+b.height/2f)/area.height
            field=value.coerceIn(1f,3f);cancelGesture();invalidate()
        }
    var onEdit:((JSONObject)->Unit)?=null
    var onPlaybackState:((String)->Unit)?=null
    internal val playback=AnnouncementPlayback()
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stage=RectF()
    private var gesture:AnnouncementGeometry.Box?=null
    private var corner=0
    private var downX=0f;private var downY=0f
    private var focusX=.5f;private var focusY=.5f
    private var layoutKey=""
    private var cachedLayout:StaticLayout?=null
    private val unit get()=resources.displayMetrics.density
    init{contentDescription="公告预览编辑画布";isFocusable=true;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    fun playPreview(){playback.play(SystemClock.elapsedRealtime());onPlaybackState?.invoke(playback.state);invalidate()}
    fun pausePreview(){playback.pause(SystemClock.elapsedRealtime());onPlaybackState?.invoke(playback.state);invalidate()}
    fun stopPreview(){playback.stop();onPlaybackState?.invoke(playback.state);invalidate()}
    fun restartPreview(){stopPreview();playPreview()}
    private fun color(key:String,fallback:Int)=runCatching{Color.parseColor(config.optString(key))}.getOrDefault(fallback)
    private fun stageRect():RectF {
        val inset=14*unit;val aw=(width-2*inset).coerceAtLeast(0f);val ah=(height-2*inset).coerceAtLeast(0f)
        val scale=minOf(aw/area.width,ah/area.height)*zoom;val w=area.width*scale;val h=area.height*scale
        val left=if(zoom==1f)(width-w)/2 else width/2-focusX*w
        val top=if(zoom==1f)(height-h)/2 else height/2-focusY*h
        return RectF(left,top,left+w,top+h)
    }
    internal fun boxOnView():RectF {
        val s=stageRect();val b=AnnouncementGeometry.bounds(config,area);val scale=s.width()/area.width
        return RectF(s.left+(b.x-area.x)*scale,s.top+(b.y-area.y)*scale,s.left+(b.x-area.x+b.width)*scale,s.top+(b.y-area.y+b.height)*scale)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);stage.set(stageRect());if(stage.isEmpty)return
        p.shader=LinearGradient(stage.left,stage.top,stage.right,stage.bottom,intArrayOf(0xff102640.toInt(),0xff17476a.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(stage,p);p.shader=null;p.color=0x254e9cb8;p.strokeWidth=unit
        for(i in 1..3){val x=stage.left+stage.width()*i/4;val y=stage.top+stage.height()*i/4;canvas.drawLine(x,stage.top,x,stage.bottom,p);canvas.drawLine(stage.left,y,stage.right,y,p)}
        val b=AnnouncementGeometry.bounds(config,area);val scale=stage.width()/area.width;val box=boxOnView();val s=area.scale.toFloat()
        val elapsed=playback.elapsed(SystemClock.elapsedRealtime())/1000f
        val motion=if(playback.state in listOf("stopped","completed"))"static" else config.optString("motion")
        textPaint.typeface=Typeface.create(if(config.optString("font")=="SimSun")"serif" else "sans-serif",if(config.optBoolean("bold",true))Typeface.BOLD else Typeface.NORMAL)
        textPaint.textSize=config.optInt("font_size",28).coerceIn(16,96)*s
        val margin=16*s;val close=40*s;val header=if(config.optString("mode")=="ticker")0f else maxOf(48*s,textPaint.textSize*1.5f)
        val prefix=config.optString("prefix")
        val prefixWidth=if(header==0f && prefix.isNotBlank())minOf(textPaint.measureText(prefix)+textPaint.textSize*1.6f+margin,b.width*.45f) else 0f
        val vw=(b.width-2*margin-close-prefixWidth).coerceAtLeast(16f);val vh=(b.height-2*margin-header).coerceAtLeast(16f)
        val single=config.optString("motion") in listOf("left","right","bounce")
        val body=config.optString("text").ifBlank{"请在下方输入公告内容"}.let{if(single)it.lines().joinToString("   ") else it}
        val key=body+"|"+vw+"|"+textPaint.textSize+"|"+config.optString("font")+"|"+config.optBoolean("bold")
        if(!single && key!=layoutKey){cachedLayout=StaticLayout.Builder.obtain(body,0,body.length,textPaint,vw.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();layoutKey=key}
        val layout=if(single)null else cachedLayout
        val tw=if(single)textPaint.measureText(body) else (0 until (layout?.lineCount?:0)).maxOfOrNull{layout!!.getLineWidth(it)}?:vw
        val th=layout?.height?.toFloat() ?: (textPaint.fontMetrics.descent-textPaint.fontMetrics.ascent)
        val frame=AnnouncementGeometry.frame(motion,vw,vh,tw,th,elapsed,config.optInt("speed",90).coerceIn(20,400)*s)
        if(playback.state=="playing" && ((config.optInt("duration")>0 && elapsed>=config.optInt("duration")) ||
                    (config.optInt("repeats")>0 && frame.cycles>=config.optInt("repeats")))){
            playback.finish(SystemClock.elapsedRealtime());onPlaybackState?.invoke(playback.state);invalidate()
        }
        canvas.save();canvas.translate(box.left,box.top);canvas.scale(scale,scale)
        val radius=when(config.optString("shape")){"pill"->b.height/2f;"rounded"->12*s;else->0f}
        canvas.clipPath(Path().apply{addRoundRect(0f,0f,b.width.toFloat(),b.height.toFloat(),radius,radius,Path.Direction.CW)})
        canvas.saveLayerAlpha(null,(255*config.optInt("opacity",92).coerceIn(30,100)/100f*frame.alpha).toInt().coerceAtLeast(25))
        p.color=color("background",Color.BLACK);canvas.drawRect(0f,0f,b.width.toFloat(),b.height.toFloat(),p)
        textPaint.color=color("accent",Color.CYAN)
        if(prefix.isNotBlank()){
            canvas.save();canvas.clipRect(margin,0f,if(header==0f)margin+prefixWidth else b.width-close,if(header==0f)b.height.toFloat() else header)
            val yy=if(header==0f)b.height/2f else header/2
            p.color=textPaint.color;val icon=textPaint.textSize*.3f
            canvas.drawRect(margin,yy-icon/2,margin+icon*.5f,yy+icon/2,p)
            canvas.drawPath(Path().apply{moveTo(margin+icon*.5f,yy-icon/2);lineTo(margin+icon*1.4f,yy-icon);lineTo(margin+icon*1.4f,yy+icon);lineTo(margin+icon*.5f,yy+icon/2);close()},p)
            canvas.drawText("$prefix：",margin+textPaint.textSize*.8f,yy-(textPaint.fontMetrics.ascent+textPaint.fontMetrics.descent)/2,textPaint);canvas.restore()
        }
        p.color=if((Color.red(color("background",Color.BLACK))*.299+Color.green(color("background",Color.BLACK))*.587+Color.blue(color("background",Color.BLACK))*.114)>150)Color.BLACK else Color.WHITE
        p.strokeWidth=2*s;val cx=b.width-close/2;val cy=close/2
        canvas.drawLine(cx-5*s,cy-5*s,cx+5*s,cy+5*s,p);canvas.drawLine(cx+5*s,cy-5*s,cx-5*s,cy+5*s,p)
        canvas.save();canvas.clipRect(margin+prefixWidth,margin+header,margin+prefixWidth+vw,margin+header+vh)
        canvas.translate(margin+prefixWidth+frame.x,margin+header+frame.y);textPaint.color=color("foreground",Color.WHITE)
        if(layout!=null)layout.draw(canvas) else canvas.drawText(body,0f,-textPaint.fontMetrics.ascent,textPaint)
        canvas.restore();canvas.restore();canvas.restore()
        p.style=Paint.Style.STROKE;p.strokeWidth=1.5f*unit;p.color=0xff3ee0b5.toInt();canvas.drawRect(box,p);p.style=Paint.Style.FILL
        if(resizeMode)for((x,y) in corners(box))canvas.drawRoundRect(x-5*unit,y-5*unit,x+5*unit,y+5*unit,2*unit,2*unit,p)
        if(isAttachedToWindow && visibility==VISIBLE && playback.state=="playing")postInvalidateDelayed(33)
    }
    private fun corners(b:RectF)=listOf(b.left to b.top,b.right to b.top,b.left to b.bottom,b.right to b.bottom)
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                if(event.pointerCount!=1 || stageRect().isEmpty)return false
                val box=boxOnView()
                if(resizeMode){
                    val nearest=corners(box).mapIndexed{i,p->i+1 to hypot(event.x-p.first,event.y-p.second)}.minBy{it.second}
                    if(nearest.second>28*unit)return false
                    corner=nearest.first
                }else{if(!box.contains(event.x,event.y))return false;corner=0}
                gesture=AnnouncementGeometry.normalized(config,area);downX=event.x;downY=event.y
                parent?.requestDisallowInterceptTouchEvent(true);return true
            }
            MotionEvent.ACTION_MOVE->{
                val start=gesture?:return false;val s=stageRect();if(s.isEmpty)return false
                val dx=((event.x-downX)/s.width()*1000).roundToInt();val dy=((event.y-downY)/s.height()*1000).roundToInt()
                val b=AnnouncementGeometry.transform(start,dx,dy,corner,area)
                config=AnnouncementGeometry.custom(config,b);onEdit?.invoke(JSONObject(config.toString()))
                contentDescription="公告预览编辑画布，左侧 ${b.x/10f}%，顶部 ${b.y/10f}%，宽 ${b.width/10f}%，高 ${b.height/10f}%"
                return true
            }
            MotionEvent.ACTION_UP->{if(gesture==null)return false;cancelGesture();performClick();return true}
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{if(gesture==null)return false;cancelGesture();return true}
        }
        return false
    }
    private fun cancelGesture(){gesture=null;parent?.requestDisallowInterceptTouchEvent(false)}
    override fun performClick():Boolean{super.performClick();return true}
    override fun onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow()}
}

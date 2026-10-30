package com.aicode.lancmd

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import kotlin.math.abs

internal fun announcementDefaults()=JSONObject().put("text","").put("prefix","消息提醒").put("mode","ticker").put("motion","left")
    .put("position","top").put("shape","rectangle").put("background","#081424").put("foreground","#FFFFFF").put("accent","#56B5F1")
    .put("font","Microsoft YaHei UI").put("font_size",28).put("bold",true).put("speed",90).put("opacity",92).put("width",100).put("duration",0).put("repeats",5)

/** Local, illustrative live preview; never sends input or a message to Windows. */
class AnnouncementPreview(context:Context):View(context) {
    var config=announcementDefaults()
        set(value){field=value;started=SystemClock.elapsedRealtime();invalidate()}
    private var started=SystemClock.elapsedRealtime()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription="公告样式动态预览";setLayerType(LAYER_TYPE_SOFTWARE,null)}
    private fun color(key:String,fallback:Int)=runCatching{Color.parseColor(config.optString(key))}.getOrDefault(fallback)
    override fun onDraw(canvas:Canvas) {
        val unit=resources.displayMetrics.density
        paint.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),intArrayOf(0xff102640.toInt(),0xff17476a.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),14*unit,14*unit,paint);paint.shader=null
        val mode=config.optString("mode");val motion=config.optString("motion")
        val boxWidth=width*(config.optInt("width",100).coerceIn(30,100)/100f)*(if(mode=="sticky").65f else if(mode=="popup").88f else 1f)
        val boxHeight=if(mode=="ticker")54*unit else (height*.74f).coerceAtLeast(72*unit)
        val x=if(mode=="sticky")width-boxWidth-8*unit else (width-boxWidth)/2
        val y=when(config.optString("position")){"bottom"->height-boxHeight-8*unit;"center"->(height-boxHeight)/2;else->8*unit}
        val radius=when(config.optString("shape")){"pill"->boxHeight/2;"rounded"->12*unit;else->0f}
        val elapsed=(SystemClock.elapsedRealtime()-started)/1000f
        val fade=if(motion=="fade")minOf(1f,(elapsed%5)/.4f,(5-elapsed%5)/.4f).coerceAtLeast(.1f) else 1f
        canvas.saveLayerAlpha(null,(255*config.optInt("opacity",92).coerceIn(30,100)/100f*fade).toInt())
        paint.color=color("background",Color.BLACK);canvas.drawRoundRect(x,y,x+boxWidth,y+boxHeight,radius,radius,paint)
        paint.typeface=if(config.optBoolean("bold",true))Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize=(config.optInt("font_size",28)*.6f).coerceIn(12f,36f)*unit
        val prefix=config.optString("prefix");val header=if(mode=="ticker")0f else 28*unit
        paint.color=color("accent",Color.CYAN)
        val label=if(prefix.isBlank())"" else "◖ $prefix："
        val prefixWidth=if(header==0f)minOf(paint.measureText(label),boxWidth*.42f) else 0f
        if(label.isNotBlank())canvas.drawText(label,x+10*unit,y+(if(header==0f)boxHeight*.63f else 21*unit),paint)
        val closeSize=24*unit
        paint.color=if((Color.red(color("background",Color.BLACK))*.299+Color.green(color("background",Color.BLACK))*.587+Color.blue(color("background",Color.BLACK))*.114)>150)Color.BLACK else Color.WHITE
        paint.strokeWidth=2*unit
        canvas.drawLine(x+boxWidth-16*unit,y+8*unit,x+boxWidth-8*unit,y+16*unit,paint)
        canvas.drawLine(x+boxWidth-8*unit,y+8*unit,x+boxWidth-16*unit,y+16*unit,paint)
        val left=x+10*unit+prefixWidth;val top=y+header;val vw=(boxWidth-prefixWidth-closeSize-16*unit).coerceAtLeast(10f);val vh=boxHeight-header
        val body=config.optString("text").ifBlank{"请同学们做好课前准备。"}.replace('\n',' ')
        val tw=paint.measureText(body);val speed=config.optInt("speed",90)*unit*.5f
        val offset=when(motion){"left"->vw-(elapsed*speed)%(vw+tw+32*unit);"right"->-tw+(elapsed*speed)%(vw+tw+32*unit)
            "bounce"->(vw-tw)*(1-abs(2*((elapsed*speed/(2*abs(vw-tw).coerceAtLeast(1f)))%1)-1));else->((vw-tw)/2).coerceAtLeast(0f)}
        val yy=when(motion){"up"->vh-(elapsed*speed)%(vh+paint.textSize+24*unit);"down"->-paint.textSize+(elapsed*speed)%(vh+paint.textSize+24*unit);else->vh*.63f}
        canvas.save();canvas.clipRect(left,top,left+vw,top+vh);paint.color=color("foreground",Color.WHITE);canvas.drawText(body,left+offset,top+yy,paint);canvas.restore()
        canvas.restore()
        if(isAttachedToWindow && visibility==VISIBLE)postInvalidateDelayed(33)
    }
}

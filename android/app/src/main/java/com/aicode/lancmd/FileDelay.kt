package com.aicode.lancmd

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.AgentClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException

/** Windows owns remote countdowns; transfers and phone app launches need a live phone. */
object FileDelay {
    fun seconds(context:Context)=context.getSharedPreferences("file-operations",Context.MODE_PRIVATE).getInt("delay",0).coerceIn(0,86400)
    fun choose(context:Context,changed:()->Unit={}) {
        val input=EditText(context).apply { inputType=InputType.TYPE_CLASS_NUMBER;setSingleLine();setText(seconds(context).toString());contentDescription="文件操作延迟秒数" }
        val dialog=WorkspaceDialog.Builder(context).setTitle("文件操作延迟（秒）")
            .setMessage("0–86400 秒，0 立即执行。此设置用于后续文件操作；电脑端由 Windows 计时，上传、下载、手机 WPS 需要手机保持连接。")
            .setView(input).setNegativeButton("取消",null).setNeutralButton("跟随全局") { _,_ ->
                context.getSharedPreferences("file-operations",Context.MODE_PRIVATE).edit().remove("delay").apply();changed()
            }.setPositiveButton("保存",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=input.text.toString().toIntOrNull()
            if(value==null || value !in 0..86400)input.error="请输入 0–86400 的整数秒"
            else { context.getSharedPreferences("file-operations",Context.MODE_PRIVATE).edit().putInt("delay",value).apply();changed();dialog.dismiss() }
        } };dialog.show()
    }
    suspend fun waitLocal(context:Context,client:AgentClient?,notice:(String)->Unit={}) {
        val count=if(context.getSharedPreferences("file-operations",Context.MODE_PRIVATE).contains("delay"))seconds(context)
            else runCatching { pinnedCall(client,"delays",JSONObject().put("action","settings")).optJSONObject("settings")?.optInt("operation") ?: 0 }.getOrDefault(0)
        for(left in count downTo 1) { pinnedCall(client,"ping");notice("将在 $left 秒后开始，需要手机保持连接");delay(1000) }
    }
    private fun announce(context:Context,notice:(String)->Unit,message:String) { notice(message) }
    suspend fun call(context:Context,client:AgentClient?,command:String,params:JSONObject,timeout:Long=30_000,notice:(String)->Unit={}):JSONObject {
        val count=seconds(context)
        if(count==0) {
            val explicit=context.getSharedPreferences("file-operations",Context.MODE_PRIVATE).contains("delay")
            return pinnedCall(client,command,if(explicit)JSONObject(params.toString()).put("delay_override",0) else params,timeout)
        }
        val response=pinnedCall(client,"file_schedule",JSONObject().put("action","add").put("command",command).put("params",params).put("delay",count))
        val id=response.getJSONObject("job").getString("id")
        while(true) {
            val job=pinnedCall(client,"file_schedule",JSONObject().put("action","get").put("job_id",id)).getJSONObject("job")
            when(job.getString("status")) {
                "completed"->return job.getJSONObject("result").put("ok",true)
                "failed"->throw IOException(job.optString("error","延迟文件操作失败"))
                "cancelled"->throw IOException("延迟文件操作已取消")
                "waiting"->announce(context,notice,"电脑将在 ${((job.optDouble("due")*1000-System.currentTimeMillis())/1000).toInt().coerceAtLeast(0)} 秒后执行 · 可在文件计划取消")
                else->announce(context,notice,"电脑正在执行文件操作…")
            }
            delay(800)
        }
    }
}

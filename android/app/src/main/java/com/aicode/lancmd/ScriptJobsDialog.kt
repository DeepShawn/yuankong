package com.aicode.lancmd

import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

object ScriptJobsDialog {
    fun show(activity: AppCompatActivity,client: AgentClient?,startPath: String?=null) {
        if(startPath!=null) {
            AlertDialog.Builder(activity).setTitle("运行电脑脚本？").setMessage("$startPath\n\n以接收端当前权限运行，最长 300 秒。运行器隐藏窗口，输出和错误在手机查看。脚本自身打开应用的行为仍会执行。")
                .setNegativeButton("取消",null).setPositiveButton("运行") { _,_-> open(activity,client,startPath) }.show()
        } else open(activity,client,null)
    }
    private fun open(activity: AppCompatActivity,client: AgentClient?,startPath: String?) {
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        val status=TextView(activity).apply { text="正在读取…";setTextColor(Themes.color(activity,R.color.dim));textSize=12f }
        val list=ListView(activity)
        content.addView(status);content.addView(list,LinearLayout.LayoutParams(-1,(320*activity.resources.displayMetrics.density).toInt()))
        val dialog=AlertDialog.Builder(activity).setTitle("脚本运行记录").setMessage("关闭此窗口不会停止脚本；可选择任务查看结果或停止运行。接收端退出会停止仍在运行的任务。")
            .setView(content).setNegativeButton("关闭",null).create()
        var items=listOf<JSONObject>();var poll:Job?=null
        fun state(item:JSONObject)=when(item.optString("status")) { "starting"->"准备运行";"running"->"运行中";"completed"->"已完成";"cancelled"->"已取消";"timed_out"->"已超时";else->"失败" }
        list.setOnItemClickListener { _,_,index,_->
            val id=items[index].getString("id")
            val output=TextView(activity).apply { setTextIsSelectable(true);textSize=13f;setTextColor(Themes.color(activity,R.color.text));setPadding(24,12,24,12) }
            val detail=AlertDialog.Builder(activity).setTitle("脚本结果").setView(ScrollView(activity).apply { addView(output) }).setNegativeButton("关闭",null)
                .setPositiveButton("停止脚本",null).create()
            var updater:Job?=null
            var actionError=""
            detail.setOnShowListener {
                detail.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    activity.lifecycleScope.launch {
                        runCatching { pinnedCall(client,"scripts",JSONObject().put("action","cancel").put("job_id",id)) }
                            .onSuccess { actionError="" }.onFailure { actionError="停止失败：${it.message}";status.text=it.message }
                    }
                }
                updater=activity.lifecycleScope.launch { while(isActive && detail.isShowing) {
                    val item=items.firstOrNull { it.optString("id")==id }
                    if(item!=null) {
                        val value="${item.getString("path")}\n${state(item)} · 退出码 ${if(item.has("code"))item.optInt("code") else "—"}\n\n${item.optString("out")}\n${item.optString("err")}${if(item.optBoolean("truncated"))"\n（输出已截断，各最多 8 KB）" else ""}${if(actionError.isNotEmpty())"\n\n$actionError" else ""}"
                        if(output.text.toString()!=value)output.text=value
                        detail.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=item.optString("status") in listOf("starting","running")
                    };delay(500)
                } }
            }
            detail.setOnDismissListener { updater?.cancel() };detail.show()
        }
        dialog.setOnShowListener {
            poll=activity.lifecycleScope.launch {
                if(startPath!=null) {
                    try { pinnedCall(client,"scripts",JSONObject().put("action","run").put("path",startPath));status.text="脚本任务已提交" }
                    catch(e:Exception){status.text=e.message;return@launch}
                }
                while(isActive && dialog.isShowing) {
                    try {
                        val result=pinnedCall(client,"scripts");val array=result.getJSONArray("items")
                        items=(0 until array.length()).map { array.getJSONObject(it) }
                        list.adapter=ArrayAdapter(activity,android.R.layout.simple_list_item_1,items.map { "${it.getString("path").substringAfterLast('\\').substringAfterLast('/')} · ${state(it)}" })
                        status.text="${items.size} 条任务 · 点击查看输出"
                    } catch(e:Exception){status.text=e.message}
                    delay(2000)
                }
            }
        }
        dialog.setOnDismissListener { poll?.cancel() };dialog.show()
    }
}

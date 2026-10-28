package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.IOException

/** Inventory reads and process operations have independent, visible progress. */
class ApplicationsActivity:MiuixActivity() {
    private var apps by mutableStateOf<List<JSONObject>>(emptyList())
    private var freezes by mutableStateOf<List<JSONObject>>(emptyList())
    private var query by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var catalogLoading by mutableStateOf(false)
    private var operationText by mutableStateOf("")
    private var pending by mutableStateOf<Pair<String,JSONObject>?>(null)
    private var jobsSupported=false
    private var closeSupported by mutableStateOf(false)
    private var catalogPoll:Job?=null
    private var activeJob=""
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);query=state?.getString("query").orEmpty();activeJob=state?.getString("job").orEmpty()
        content { Screen() }
        task {
            val features=pinnedCall(origin,"capabilities",timeout=6000).optJSONArray("features")
            fun supports(name:String)=features!=null && (0 until features.length()).any{features.optString(it)==name}
            jobsSupported=supports("app_freeze_jobs");closeSupported=supports("app_close")
            refresh()
            try {
                val result=pinnedCall(origin,"app_freeze",JSONObject().put("action",if(jobsSupported)"state" else "list"),6000)
                freezes=array(result,"items")
                if(jobsSupported && activeJob.isBlank())activeJob=array(result,"jobs").firstOrNull()?.optString("id").orEmpty()
                if(activeJob.isNotBlank()) {
                    busy=true
                    try {awaitJob(activeJob,"处理应用操作")}finally{busy=false}
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){status("冻结状态读取失败：${e.message}，应用列表仍可使用",true)}
        }
    }
    private fun array(result:JSONObject,key:String):List<JSONObject> {
        val items=result.optJSONArray(key) ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }
    private fun refresh(force:Boolean=false) {
        catalogPoll?.cancel()
        catalogLoading=true
        catalogPoll=task {
            try {
                var forceNext=force
                do {
                    val result=pinnedCall(origin,"app_catalog",JSONObject().put("refresh",forceNext).put("background",true),6000)
                    forceNext=false
                    apps=array(result,"items")
                    catalogLoading=result.optBoolean("loading")
                    if(result.optString("error").isNotBlank())throw IOException(result.optString("error"))
                    if(!busy)status(if(catalogLoading)"正在后台识别应用，已显示的条目仍可操作…" else "已识别 ${apps.size} 个应用 · 本手机冻结 ${freezes.size} 个")
                    if(catalogLoading)delay(1000)
                }while(catalogLoading)
            }finally{catalogLoading=false}
        }
    }
    @Composable private fun Screen() = FixedPage(if(intent.getBooleanExtra("selectForLock",false))"选择要锁定的应用" else "应用中心") {
        val shown=remember(apps,query) {apps.filter{(it.optString("name")+it.optString("path")+it.optString("publisher")).contains(query,true)}}
        SectionCard {
            Field("名称、路径或发布者",query,{query=it})
            ActionRow {
                Action("刷新应用列表",enabled=!catalogLoading,modifier=Modifier.weight(1f)){refresh(true)}
                Note("${shown.size} 个应用 · 冻结 ${freezes.size}",modifier=Modifier.weight(1f))
            }
        }
        if(operationText.isNotBlank())Note(operationText)
        if(catalogLoading)Note("应用列表正在后台加载，不会阻止解冻或返回。")
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(freezes,key={"freeze:"+it.optString("id")}) {item ->SectionCard("本手机的冻结记录") {
                InfoItem(item.optString("path"),"已暂停 ${item.optJSONArray("processes")?.length() ?: 0} 个进程")
                Action("解冻",enabled=!busy){operate("resume",item)}
                val errors=item.optJSONArray("errors");if(errors!=null && errors.length()>0)Note(errors.toString())
            }}
            if(shown.isEmpty())item{EmptyNote(if(catalogLoading)"正在识别应用；返回与已有冻结记录仍可使用。" else "没有匹配的应用")}
            items(shown,key={"app:"+it.optString("id")}) {item ->SectionCard {
                InfoItem(item.optString("name"),listOf(item.optString("publisher"),item.optString("version"),item.optString("path")).filter{it.isNotBlank()}.joinToString("\n"))
                if(!item.optBoolean("can_lock"))Note(item.optString("reason"))
                ActionRow {
                    Action(if(intent.getBooleanExtra("selectForLock",false))"选择" else "应用锁",enabled=!busy && item.optBoolean("can_lock"),modifier=Modifier.weight(1f)) {
                        if(intent.getBooleanExtra("selectForLock",false))task {pinnedCall(origin,"capabilities");setResult(RESULT_OK,Intent().putExtra("path",item.optString("path")));finish()}
                        else startActivity(Intent(this@ApplicationsActivity,AppLocksActivity::class.java).putExtra("path",item.optString("path")))
                    }
                    Action("启动",enabled=!busy && item.optString("path").isNotBlank(),modifier=Modifier.weight(1f)){pending="launch" to item}
                    Action("冻结",enabled=!busy && item.optBoolean("can_lock") && item.optInt("running")>0,modifier=Modifier.weight(1f)){pending="freeze" to item}
                    Action("关闭",enabled=!busy && closeSupported && item.optBoolean("can_lock") && item.optInt("running")>0,modifier=Modifier.weight(1f)){pending="close" to item}
                }
            }}
            item {Note("冻结仅暂停现有进程；应用锁禁止再次启动。关闭会结束该 EXE 的进程，未保存数据可能丢失。关键进程和接收端自身会跳过。")}
            if(!closeSupported)item{Note("应用关闭与后台任务需要完整更新 Windows 3.4.1 接收端。")}
        }
        pending?.let { (action,item) ->ConfirmAction(when(action){"freeze"->"暂停此应用的进程？";"close"->"关闭此应用？";else->"启动此应用？"},
            item.optString("name")+"\n"+item.optString("path")+when(action){
                "freeze"->"\n暂停后窗口可能暂时无响应，解冻后继续。系统关键进程与接收端自身会跳过；接收端退出或冻结控制失联时独立恢复进程会尝试解冻。"
                "close"->"\n结束此 EXE 当前正在运行的进程，未保存的数据可能丢失。将验证进程身份并跳过关键进程与接收端自身。"
                else->"\n将在电脑当前桌面打开。"},
            onDismiss={pending=null},onConfirm={pending=null;operate(action,item)})}
    }
    private fun showResult(action:String,result:JSONObject) {
        if(result.has("items"))freezes=array(result,"items")
        val errors=result.optJSONArray("errors")
        val partial=result.optBoolean("partial") || !result.optBoolean("resumed",true)
        val notice=if(partial)"部分进程未处理：${errors?.toString() ?: "请查看冻结记录"}"
        else when(action){"freeze"->"应用进程已暂停";"resume"->"应用进程已恢复";"close"->"已关闭 ${result.optInt("closed")} 个进程";else->"已向电脑发送启动请求"}
        operationText=notice;status(notice+" · 本手机冻结 ${freezes.size} 个",partial)
    }
    private suspend fun awaitJob(id:String,label:String):JSONObject {
        activeJob=id
        try {
            while(true) {
                val job=pinnedCall(origin,"app_freeze",JSONObject().put("action","job").put("job_id",id),6000).getJSONObject("job")
                when(job.optString("status")) {
                    "completed"->{val result=job.getJSONObject("result");showResult(job.optString("operation"),result);return result}
                    "failed"->throw IOException(job.optString("error","应用操作失败"))
                    else->{operationText="$label · Windows 正在处理，可以返回，稍后继续查看结果";status(operationText)}
                }
                delay(500)
            }
        }finally{activeJob=""}
    }
    private fun operate(action:String,item:JSONObject) {
        if(busy)return
        busy=true
        val label=when(action){"freeze"->"正在冻结";"resume"->"正在解冻";"close"->"正在关闭应用";else->"正在启动应用"}
        operationText=label;status(label)
        task {
            try {
                val params=JSONObject().put("path",item.optString("path")).apply{if(action=="resume")put("freeze_id",item.optString("id"))}
                if(action!="launch" && jobsSupported) {
                    params.put("action","submit").put("operation",action)
                    val job=pinnedCall(origin,"app_freeze",params,6000).getJSONObject("job")
                    awaitJob(job.getString("id"),label)
                }else {
                    val result=if(action=="launch")pinnedCall(origin,"files",params.put("action","open"),60_000)
                        else pinnedCall(origin,"app_freeze",params.put("action",action),30_000)
                    showResult(action,result)
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){operationText="操作失败：${e.message}";status(operationText,true)}
            finally{busy=false}
        }
    }
    override fun onSaveInstanceState(state:Bundle){state.putString("query",query);state.putString("job",activeJob);super.onSaveInstanceState(state)}
}

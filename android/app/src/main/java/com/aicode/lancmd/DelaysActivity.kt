package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class DelaysActivity:MiuixActivity() {
    private var commandSeconds by mutableStateOf("0")
    private var operationSeconds by mutableStateOf("0")
    internal var jobs by mutableStateOf<List<JSONObject>>(emptyList())
    private var supported by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var poll:Job?=null
    override fun onCreate(state:Bundle?){
        super.onCreate(state);content{Screen()}
        task {
            val caps=pinnedCall(origin,"capabilities").optJSONArray("features")
            supported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="global_delays"}
            check(supported){"请完整更新 Windows 接收端以使用全局延迟"}
            val settings=pinnedCall(origin,"delays",JSONObject().put("action","settings")).getJSONObject("settings")
            commandSeconds=settings.optInt("command").toString();operationSeconds=settings.optInt("operation").toString()
            refresh();ready=true;startPoll();status("设置只影响本手机后续提交的请求；Windows 计时，手机断线继续。")
        }
    }
    @Composable private fun Screen()=FixedPage("全局命令与操作延迟") {
        if(!ready)Note("正在从电脑读取全局延迟设置…")
        if(ready)SectionCard {
            Field("全局命令延迟（0–86400 秒）",commandSeconds,{commandSeconds=it.filter(Char::isDigit).take(5)})
            Field("全局操作延迟（0–86400 秒）",operationSeconds,{operationSeconds=it.filter(Char::isDigit).take(5)})
            ActionRow {
                Action("保存设置",enabled=supported && !busy,primary=true,modifier=Modifier.weight(1f)){save()}
                Action("刷新任务",enabled=supported && !busy,modifier=Modifier.weight(1f)){task{refresh()}}
            }
            Note("命令：控制台命令、脚本；命令列表仍以每条明确的延迟为准。操作：文件修改、应用、设备、网络、注册表和消息等。单项延迟优先，不叠加。")
            Note("画面／状态读取、触控、键盘、音量、传输分块、认证、恢复、停止／取消和接收端升级保持即时。手机上传、下载和 WPS 等待需要手机在线。接收端退出会取消尚未执行的全局任务。")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if(jobs.isEmpty())item{EmptyNote("暂无全局延迟任务")}
            items(jobs,key={it.optString("id")}){job->SectionCard {
                InfoItem("${job.optString("command")} · ${job.optString("action")}",when(job.optString("status")){
                    "waiting"->"等待执行 · 剩余 ${job.optInt("remaining")} 秒";"running"->"Windows 正在执行";"completed"->"执行完成";"cancelled"->"已取消";else->"失败：${job.optString("error")}"})
                if(job.optString("status")=="waiting")Action("取消此任务"){task{pinnedCall(origin,"delays",JSONObject().put("action","cancel").put("job_id",job.getString("id")));refresh();status("已取消尚未执行的任务")}}
            }}
        }
    }
    private fun save(){
        val c=commandSeconds.toIntOrNull();val o=operationSeconds.toIntOrNull()
        if(c==null || o==null || c !in 0..86400 || o !in 0..86400){status("请输入 0–86400 的整数秒",true);return}
        busy=true;task{try{pinnedCall(origin,"delays",JSONObject().put("action","configure").put("command",c).put("operation",o));status("全局延迟已保存在电脑，仅用于本手机的新请求")}finally{busy=false}}
    }
    private suspend fun refresh(){val values=pinnedCall(origin,"delays").getJSONArray("jobs");jobs=(0 until values.length()).map{values.getJSONObject(it)}}
    private fun startPoll(){poll?.cancel();if(supported)poll=task{while(true){delay(1000);if(!busy)refresh()}}}
    override fun onStart(){super.onStart();startPoll()}
    override fun onStop(){poll?.cancel();super.onStop()}
}

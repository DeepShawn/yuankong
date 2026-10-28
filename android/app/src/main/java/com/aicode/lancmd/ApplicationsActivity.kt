package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject

/** Installed application inventory, selection for launch locks and reversible process freeze. */
class ApplicationsActivity:MiuixActivity() {
    private var apps by mutableStateOf<List<JSONObject>>(emptyList())
    private var freezes by mutableStateOf<List<JSONObject>>(emptyList())
    private var query by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var pending by mutableStateOf<Pair<String,JSONObject>?>(null)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);query=state?.getString("query").orEmpty()
        content { Screen() };refresh()
    }
    private fun array(result:JSONObject,key:String):List<JSONObject> {
        val items=result.optJSONArray(key) ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }
    private fun refresh(force:Boolean=false,notice:String?=null) {
        if(busy)return
        busy=true
        task { try {
            apps=array(pinnedCall(origin,"app_catalog",JSONObject().put("refresh",force),60_000),"items")
            freezes=array(pinnedCall(origin,"app_freeze",timeout=30_000),"items")
            status(listOfNotNull(notice,"已识别 ${apps.size} 个应用条目 · 本手机冻结 ${freezes.size} 个应用").joinToString("\n"))
        } finally {busy=false} }
    }
    @Composable private fun Screen() {
        val shown=remember(apps,query) { apps.filter { (it.optString("name")+it.optString("path")+it.optString("publisher")).contains(query,true) } }
        ListPage(if(intent.getBooleanExtra("selectForLock",false))"选择要锁定的应用" else "应用中心") {
            item { SectionCard("查找应用") {
                Field("名称、路径或发布者",query,{query=it})
                Action("刷新应用列表",enabled=!busy) { refresh(true) }
                Note("自动读取已注册桌面程序与运行进程；未找到普通 EXE 的条目不能直接锁定。冻结只暂停现有进程，应用锁负责禁止再次启动。")
            } }
            if(freezes.isNotEmpty())item { SectionCard("本手机的冻结记录") {
                freezes.forEach { item ->
                    InfoItem(item.optString("path"),"本次冻结 ${item.optJSONArray("processes")?.length() ?: 0} 个进程")
                    Action("解冻",enabled=!busy) { operate("resume",item) }
                    val errors=item.optJSONArray("errors");if(errors!=null && errors.length()>0)Note(errors.toString())
                }
            } }
            if(shown.isEmpty())item { EmptyNote(if(busy)"正在读取应用…" else "没有匹配的应用") }
            items(shown,key={it.optString("id")}) { item -> SectionCard {
                InfoItem(item.optString("name"),listOf(item.optString("publisher"),item.optString("version"),item.optString("path")).filter { it.isNotBlank() }.joinToString("\n"))
                if(!item.optBoolean("can_lock"))Note(item.optString("reason"))
                ActionRow {
                    Action(if(intent.getBooleanExtra("selectForLock",false))"选择" else "应用锁",enabled=!busy && item.optBoolean("can_lock"),modifier=Modifier.weight(1f)) {
                        if(intent.getBooleanExtra("selectForLock",false)) {
                            task { pinnedCall(origin,"capabilities");setResult(RESULT_OK,Intent().putExtra("path",item.optString("path")));finish() }
                        } else startActivity(Intent(this@ApplicationsActivity,AppLocksActivity::class.java).putExtra("path",item.optString("path")))
                    }
                    Action("启动",enabled=!busy && item.optString("path").isNotBlank(),modifier=Modifier.weight(1f)) { pending="launch" to item }
                    Action("冻结",enabled=!busy && item.optBoolean("can_lock") && item.optInt("running")>0,modifier=Modifier.weight(1f)) { pending="freeze" to item }
                }
            } }
        }
        pending?.let { (action,item) -> ConfirmAction(if(action=="freeze")"暂停此应用的进程？" else "启动此应用？",
            item.optString("name")+"\n"+item.optString("path")+if(action=="freeze")"\n暂停后窗口可能暂时无响应，解冻后继续。系统关键进程与接收端自身会被跳过，接收端退出时恢复进程会尝试解冻。" else "\n将在电脑当前桌面打开。",
            onDismiss={pending=null},onConfirm={pending=null;operate(action,item)}) }
    }
    private fun operate(action:String,item:JSONObject) {
        if(busy)return
        busy=true
        task {var notice="";try {
            val result=when(action) {
                "launch" -> pinnedCall(origin,"files",JSONObject().put("action","open").put("path",item.optString("path")),60_000)
                "freeze" -> pinnedCall(origin,"app_freeze",JSONObject().put("action","freeze").put("path",item.optString("path")),30_000)
                "resume" -> pinnedCall(origin,"app_freeze",JSONObject().put("action","resume").put("freeze_id",item.optString("id")),30_000)
                else -> error("无效的应用操作")
            }
            notice=if(result.optBoolean("partial") || !result.optBoolean("resumed",true))"部分进程未处理，请查看冻结记录中的原因"
                else when(action){"freeze"->"应用进程已暂停";"resume"->"应用进程已恢复";else->"已向电脑发送启动请求"}
            status(notice)
        }finally {busy=false};refresh(true,notice)}
    }
    override fun onSaveInstanceState(state:Bundle) { state.putString("query",query);super.onSaveInstanceState(state) }
}

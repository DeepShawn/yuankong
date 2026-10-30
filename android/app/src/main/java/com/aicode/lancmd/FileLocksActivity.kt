package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.*
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Passwords exist only in masked, unsaved state and are cleared before RPC. */
class FileLocksActivity : MiuixActivity() {
    private var path by mutableStateOf("")
    private var password by mutableStateOf("")
    private var repeatPassword by mutableStateOf("")
    private var until by mutableStateOf("")
    private var timed by mutableStateOf(false)
    private var items by mutableStateOf<List<JSONObject>>(emptyList())
    private var computerTime by mutableStateOf("")
    private var supported by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var confirmLock by mutableStateOf(false)
    private var poll: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        path=state?.getString("path") ?: intent.getStringExtra("path").orEmpty()
        until=state?.getString("until").orEmpty();timed=state?.getBoolean("timed") ?: false
        content { Screen() }
        task {
            val caps=pinnedCall(origin,"capabilities").optJSONArray("features")
            supported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="file_locks"}
            check(supported){"请完整更新 Windows 接收端以使用文件锁"}
            refresh();startPoll()
        }
    }
    @Composable private fun Screen() = Page("文件密码锁") {
        SectionCard("锁定一个文件") {
            Field("电脑文件完整路径",path,{path=it})
            Field("锁定密码（8–128 字符）",password,{password=it.take(128)},password=true)
            Field("再次输入密码",repeatPassword,{repeatPassword=it.take(128)},password=true)
            ChoiceField(listOf("长期锁定 · 密码解锁","定时锁定 · 到时自动解锁"),"锁定方式",if(timed)1 else 0){timed=it==1}
            if(timed) {
                Note("电脑当前时间：$computerTime")
                Field("电脑解锁时间：yyyy-MM-dd HH:mm:ss",until,{until=it.take(19)})
            }
            Action("锁定文件",enabled=supported && !busy,primary=true) {
                when {
                    password.length !in 8..128 -> status("密码需为 8–128 个字符",true)
                    password!=repeatPassword -> status("两次输入的密码不同",true)
                    path.isBlank() -> status("请先选择文件",true)
                    timed && until.isBlank() -> status("请填写电脑当地的解锁时间",true)
                    else -> confirmLock=true
                }
            }
            Note("Windows 普通程序读取、编辑、执行此文件会被权限拒绝；需在本页输入密码解锁。已打开的文件句柄不会被撤销，请先关闭文件。此功能不是内容加密，管理员可用本机恢复命令解锁。")
            Note("定时解锁由 Windows 系统时间判断，手机断线仍有效；接收端须运行，关闭期间的到期锁在重新启动后恢复。长期锁不设自动到期，仍可密码解锁。")
        }
        SectionCard("电脑上的文件锁") {
            Action("刷新文件锁",enabled=supported && !busy){task{refresh()}}
            if(items.isEmpty())EmptyNote("暂无文件锁记录")
            items.forEach {item ->
                val state=item.optString("status")
                InfoItem(item.optString("path"),
                    "状态："+when(state){"locked"->"已锁定";"unlocked"->"已解锁";"missing"->"文件缺失";"replaced"->"文件已替换";else->"权限变化，保留恢复记录"}+
                    (if(item.optString("until_local").isNotBlank())"\n自动解锁：${item.optString("until_local")}" else "\n长期锁定")+
                    item.optString("error").let{if(it.isNotBlank())"\n$it" else ""})
                if(state=="locked")Action("输入密码解锁",enabled=!busy){askUnlock(item)}
                if(state=="unlocked")Action("移除已解锁记录",enabled=!busy){operate(JSONObject().put("action","forget").put("lock_id",item.getString("id")))}
            }
        }
        if(confirmLock)ConfirmAction("确认锁定此文件？",path+"\n"+(if(timed)"按电脑时间 $until 自动解锁，也可提前输入密码解锁。" else "长期锁定，需输入密码或使用本机管理员恢复。")+
            "\n文件内容不变；先关闭正在使用该文件的程序。",onDismiss={confirmLock=false},onConfirm={
                confirmLock=false
                val secret=password;password="";repeatPassword=""
                operate(JSONObject().put("action","lock").put("path",path).put("password",secret).put("until_local",if(timed)until else ""))
            })
    }
    private fun askUnlock(item:JSONObject) {
        val input=dialogInput().apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        WorkspaceDialog.Builder(this).setTitle("输入文件锁密码").setMessage(item.optString("path")).setView(input).focusInput(input)
            .setNegativeButton("取消",null).setPositiveButton("解锁") { _,_ ->
                val secret=input.text.toString();input.setText("")
                operate(JSONObject().put("action","unlock").put("lock_id",item.getString("id")).put("password",secret))
            }.show()
    }
    private suspend fun refresh() {
        val result=pinnedCall(origin,"file_locks",timeout=15_000)
        computerTime=result.optString("computer_time")
        val values=result.getJSONArray("items")
        items=(0 until values.length()).map{values.getJSONObject(it)}
    }
    private fun operate(params:JSONObject) {
        if(busy)return
        busy=true;status("Windows 正在处理文件锁…")
        task {
            try {
                pinnedCall(origin,"file_locks",params,20_000)
                refresh()
                status(when(params.optString("action")){"lock"->"文件已锁定";"unlock"->"文件已解锁";else->"已移除解锁记录"})
            }finally{params.remove("password");busy=false}
        }
    }
    private fun startPoll() {
        poll?.cancel()
        if(!supported)return
        poll=task{while(true){delay(5000);if(!busy)refresh()}}
    }
    override fun onStart(){super.onStart();startPoll()}
    override fun onStop(){poll?.cancel();password="";repeatPassword="";super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("path",path);out.putString("until",until);out.putBoolean("timed",timed);super.onSaveInstanceState(out)}
}

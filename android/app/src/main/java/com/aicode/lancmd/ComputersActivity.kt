package com.aicode.lancmd

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.DiscoverResult
import com.aicode.lancmd.net.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Inventory only: browsing, names and history do not connect or switch computers. */
class ComputersActivity:MiuixActivity(),ConnectionManager.Listener {
    internal var book by mutableStateOf(ComputerBook.Snapshot())
    internal var scanning by mutableStateOf(false)
    internal var query by mutableStateOf("")
    internal var tab by mutableIntStateOf(0)
    private var current by mutableStateOf(ConnectionManager.snapshot())
    private var found by mutableStateOf(setOf<String>())
    private var lastScan by mutableStateOf(0L)
    private var scanJob:Job?=null
    internal var discovery:suspend ()->DiscoverResult={withContext(Dispatchers.IO){Discovery.discover(applicationContext)}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        query=state?.getString("query").orEmpty();tab=state?.getInt("tab")?.coerceIn(0,1)?:0
        content{Screen()}
    }
    override fun onStart(){
        super.onStart();ConnectionManager.addListener(this)
        task{book=withContext(Dispatchers.IO){ComputerBook.seed(this@ComputersActivity)}}
    }
    override fun onStop(){scanJob?.cancel();ConnectionManager.removeListener(this);super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("query",query);out.putInt("tab",tab);super.onSaveInstanceState(out)}
    override fun onStatus(status:ConnectionManager.Status){current=status}
    override fun onEvent(text:String,kind:ConnectionManager.LogKind){}
    private fun time(value:Long)=if(value>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(value)) else "暂无记录"
    private fun connected(c:ComputerBook.Computer):Boolean {
        if(!current.connected || current.host!=c.host || current.port!=c.port)return false
        val target=Prefs.target(this)
        return c.agentId.isBlank() || target?.agentId==c.agentId
    }
    @androidx.compose.runtime.Composable private fun Screen()=FixedPage("局域网电脑与历史") {
        SectionCard {
            ActionRow {listOf("电脑列表","连接历史").forEachIndexed{i,label->
                SelectionAction(label,selected=tab==i,modifier=Modifier.weight(1f)){tab=i}
            }}
            Field("搜索备注、原机名或地址",query,{query=it.take(100)})
            ActionRow {
                Action(if(scanning)"正在发现…" else "刷新局域网",enabled=!scanning,primary=true,modifier=Modifier.weight(1f)){scan()}
                Action("清空历史",enabled=book.history.isNotEmpty(),modifier=Modifier.weight(1f)){clearHistory()}
            }
            Note(if(lastScan>0)"上次发现：${time(lastScan)}。仅显示响应 LanCmd 发现协议的接收端；未发现不代表电脑关机。"
                else "点刷新查看同一局域网内运行接收端的电脑。备注和连接历史仅保存在本手机。")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if(tab==0){
                val list=book.computers.filter{(it.label+it.reportedName+it.host+it.agentId).contains(query,true)}
                    .sortedWith(compareByDescending<ComputerBook.Computer>{connected(it)}.thenByDescending{it.key in found}.thenByDescending{it.lastConnected}.thenBy{it.label})
                if(list.isEmpty())item{EmptyNote(if(query.isBlank())"暂无电脑，点击刷新局域网" else "没有匹配的电脑")}
                items(list,key={it.key}){c->SectionCard {
                    val state=if(connected(c))"已连接" else if(c.key in found)"本次发现" else if(lastScan>0)"本次未发现" else "已保存"
                    InfoItem(c.label,"$state · ${c.host}:${c.port}\n原机名：${c.reportedName.ifBlank{"未提供"}}\n最近成功连接：${time(c.lastConnected)}")
                    ActionRow {
                        Action("修改备注名",modifier=Modifier.weight(1f)){rename(c)}
                        Action("详情",modifier=Modifier.weight(1f)){details(c)}
                    }
                }}
            }else{
                val list=book.history.filter{v->val c=book.computers.firstOrNull{it.key==v.computerKey};((c?.label?:v.reportedName)+v.reportedName+v.host).contains(query,true)}
                if(list.isEmpty())item{EmptyNote("暂无匹配的连接记录；只记录成功建立的连接")}
                items(list,key={it.id}){v->SectionCard {
                    val c=book.computers.firstOrNull{it.key==v.computerKey}
                    InfoItem(c?.label?:v.reportedName,"${v.host}:${v.port}\n连接：${time(v.started)}\n"+
                        (if(v.ended>0)"结束：${time(v.ended)}" else "结束时间未记录（或仍在连接）"))
                    Action("删除此条记录"){task{book=withContext(Dispatchers.IO){ComputerBook.removeVisit(this@ComputersActivity,v.id)};status("已删除此条本地记录")}}
                }}
            }
            item{SectionCard{Action("清空电脑记录与备注"){clearBook()}}}
        }
    }
    internal fun scan(){
        if(scanning)return
        scanning=true
        scanJob=task{
            try {
                val result=discovery()
                val observed=System.currentTimeMillis()
                book=withContext(Dispatchers.IO){ComputerBook.discovered(this@ComputersActivity,result.agents,observed)}
                found=result.agents.filter{it.ip.isNotBlank() && it.port in 1..65535}.map{ComputerBook.key(Target(it.ip,it.port,it.name,it.id))}.toSet()
                lastScan=observed
                status("本次发现 ${found.size} 台接收端"+(if(result.vpnActive)"；检测到 VPN，已使用现有局域网发现方式" else ""))
            }finally{scanning=false}
        }
    }
    internal fun rename(c:ComputerBook.Computer){
        val input=dialogInput(text=c.alias,hint=c.reportedName.ifBlank{c.host})
        val dialog=WorkspaceDialog.Builder(this).setTitle("修改电脑备注名")
            .setMessage("${c.host}:${c.port}\n原机名：${c.reportedName}\n\n只更改手机显示名称，最多 40 字；留空恢复原机名。电脑系统名称保持原样。")
            .setView(input).focusInput(input,selectAll=true).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val name=input.text.toString()
            if(name.length>40 || name.any{it.isISOControl()}){input.error="最多 40 字，不能包含换行或控制字符";return@setOnClickListener}
            dialog.dismiss();task{book=withContext(Dispatchers.IO){ComputerBook.rename(this@ComputersActivity,c.target(),name)};status("电脑备注已保存")}
        }}
        dialog.show()
    }
    private fun details(c:ComputerBook.Computer){WorkspaceDialog.Builder(this).setTitle(c.label)
        .setMessage("原机名：${c.reportedName}\n地址：${c.host}:${c.port}\n接收端标识：${c.agentId.ifBlank{"旧版未提供；按地址区分"}}\n最近发现：${time(c.lastSeen)}\n最近连接：${time(c.lastConnected)}\n\n发现信息由接收端提供，备注不代表已授权或可信设备。")
        .setPositiveButton("关闭",null).show()}
    internal fun clearHistory(){WorkspaceDialog.Builder(this).setTitle("清空连接历史？").setMessage("只删除本手机的连接历史，保留电脑备注和配对凭据，当前连接继续。")
        .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->task{book=withContext(Dispatchers.IO){ComputerBook.clearHistory(this@ComputersActivity)};status("连接历史已清空")}}.show()}
    private fun clearBook(){WorkspaceDialog.Builder(this).setTitle("清空电脑记录与备注？").setMessage("删除本手机保存的电脑信息、备注和连接历史。配对凭据与当前连接保留，下次刷新会重新列出接收端。")
        .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->scanJob?.cancel();task{book=withContext(Dispatchers.IO){ComputerBook.clear(this@ComputersActivity)};found=emptySet();status("电脑记录、备注与历史已清空")}}.show()}
}

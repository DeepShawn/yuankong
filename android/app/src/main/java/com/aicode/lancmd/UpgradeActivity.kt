package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import org.json.JSONObject

/** User-supplied ZIPs only. Reconnection never changes the selected computer. */
class UpgradeActivity:MiuixActivity() {
    private var packages by mutableStateOf<List<JSONObject>>(emptyList())
    private var directory by mutableStateOf("")
    private var current by mutableStateOf("")
    private var report by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var selected by mutableStateOf<JSONObject?>(null)
    private var restarting by mutableStateOf(false)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        content { ListPage("Windows 本地升级") {
            item { SectionCard("手动放入升级包") {
                InfoItem("接收端版本",current)
                InfoItem("电脑上的 upgrade 文件夹",directory.ifBlank{"正在读取…"})
                Note("将完整的新版本 Windows ZIP 放入此文件夹，再刷新列表。本功能只读取本机文件，不下载升级包。")
                Action("刷新本地升级包",enabled=!busy && !restarting){refresh()}
                if(report.isNotBlank())Note(report)
                if(restarting)Action("升级后重新进入此页"){finish()}
            } }
            if(packages.isEmpty())item{EmptyNote("尚未发现 ZIP 升级包")}
            items(packages,key={it.optString("name")}) { item ->SectionCard {
                InfoItem(item.optString("name"),FileTransfer.humanSize(item.optLong("size"))+"\nSHA256: "+item.optString("sha256"))
                Action("校验并安装",enabled=!busy && !restarting && item.optString("sha256").length==64,primary=true){selected=item}
            } }
        }
            selected?.let { item ->ConfirmAction("安装本地 Windows 升级包？",item.optString("name")+"\n接收端会校验清单并尝试解冻应用。录制、传输、脚本和延迟任务须先完成或取消。安装时连接会暂时断开，失败时尝试回退；请保留电脑上的升级备份。",confirm="开始升级",onDismiss={selected=null},onConfirm={selected=null;install(item)}) }
        };refresh()
    }
    private fun refresh() {if(busy)return;busy=true;task{try {
        val result=pinnedCall(origin,"upgrade",timeout=60_000);val items=result.getJSONArray("items")
        packages=(0 until items.length()).map{items.getJSONObject(it)};directory=result.optString("directory");current=result.optString("current")
        report=result.optJSONObject("status")?.let { job ->
            val label=when(job.optString("status")){"completed"->"上次升级已完成";"rolled_back"->"上次升级失败，已尝试回退";"failed"->"上次升级失败，请检查电脑上的备份与日志";"cancelled"->"上次升级已取消";"installing","prepared"->"上次升级尚未确认完成，请检查备份目录";else->""}
            label+if(job.optString("error").isNotBlank())"\n"+job.optString("error") else ""
        }.orEmpty()
        status("已读取 ${packages.size} 个本地 ZIP")
    }finally{busy=false}} }
    private fun install(item:JSONObject) {if(busy)return;busy=true;task{try {
        val result=pinnedCall(origin,"upgrade",JSONObject().put("action","apply").put("name",item.optString("name")).put("sha256",item.optString("sha256")),120_000)
        restarting=result.optBoolean("restarting");status(result.optString("message"))
        // The connection manager reconnects to its existing identity. This page
        // deliberately keeps its old pin; reopening binds only after reconnection.
    }finally{busy=false}} }
}

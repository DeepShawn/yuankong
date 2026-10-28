package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject

/** User-supplied ZIPs only. Rebind after an upgrade only to the same computer. */
class UpgradeActivity:MiuixActivity(),ConnectionManager.Listener {
    private var packages by mutableStateOf<List<JSONObject>>(emptyList())
    private var directory by mutableStateOf("")
    private var current by mutableStateOf("")
    private var report by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var selected by mutableStateOf<JSONObject?>(null)
    internal var restarting by mutableStateOf(false)
    private var owner:AgentClient?=null
    private var peerId=""
    private var peerHost=""
    private var peerPort=0
    private var targetVersion=""
    private var requestId=""
    private var monitor:Job?=null
    private var resumedUpdate=false

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        owner=origin
        peerId=state?.getString("peer_id") ?: Prefs.target(this)?.agentId.orEmpty()
        peerHost=state?.getString("peer_host") ?: origin?.host.orEmpty()
        peerPort=state?.getInt("peer_port") ?: origin?.port ?: 0
        restarting=state?.getBoolean("restarting") ?: false;resumedUpdate=restarting
        targetVersion=state?.getString("target_version").orEmpty();requestId=state?.getString("request_id").orEmpty()
        current=state?.getString("current").orEmpty();directory=state?.getString("directory").orEmpty()
        content {
            ListPage("Windows 本地升级") {
                item {SectionCard("手动放入升级包") {
                    InfoItem("接收端版本",current)
                    InfoItem("电脑上的 upgrade 文件夹",directory.ifBlank{"正在读取…"})
                    Note("将完整的新版本 Windows ZIP 放入此文件夹，再刷新列表。仅使用本机文件，不下载升级包。")
                    Action("刷新本地升级包",enabled=!busy && !restarting){refresh()}
                    if(report.isNotBlank())Note(report)
                    if(restarting) {
                        Note("更新会先断开连接，退出旧接收端，再解压替换并运行 start.bat；启动 CMD 自动退出。手机会等待原电脑重新连接并显示结果。")
                        Action("重新检查连接"){ConnectionManager.retryNow();watchUpgrade()}
                    }
                }}
                if(packages.isEmpty() && !restarting)item{EmptyNote("尚未发现 ZIP 升级包")}
                items(packages,key={it.optString("name")}) {item ->SectionCard {
                    InfoItem(item.optString("name"),FileTransfer.humanSize(item.optLong("size"))+"\nSHA256: "+item.optString("sha256"))
                    Action("校验并安装",enabled=!busy && !restarting && item.optString("sha256").length==64,primary=true){selected=item}
                }}
            }
            selected?.let {item ->ConfirmAction("安装本地 Windows 升级包？",item.optString("name")+
                "\n先校验完整包并尝试解冻应用，然后断开当前连接、退出旧接收端，解压替换原目录并自动运行 start.bat，启动 CMD 自动退出。\n录制、传输、脚本和延迟任务须先完成或取消。失败时尝试回退，请保留备份。",
                confirm="开始升级",onDismiss={selected=null},onConfirm={selected=null;install(item)})}
        }
        if(restarting)watchUpgrade() else refresh()
    }

    private fun showReport(result:JSONObject):String {
        directory=result.optString("directory",directory);current=result.optString("current",current)
        val job=result.optJSONObject("status") ?: JSONObject()
        val phase=job.optString("status")
        val label=when(phase) {
            "completed"->"升级已完成，接收端已重新连接"
            "rolled_back"->"升级失败，已恢复旧文件并重新启动"
            "failed"->"升级失败，请检查备份和日志"
            "cancelled"->"升级已取消，原文件未替换"
            "prepared"->"完整包已校验，准备断开连接"
            "disconnecting"->"正在断开连接并退出旧接收端"
            "extracting"->"旧接收端已退出，正在解压完整包"
            "installing"->"正在替换安装目录中的文件"
            "starting"->"正在通过 start.bat 启动新版，CMD 将自动关闭"
            else->""
        }
        report=label+job.optString("error").takeIf{it.isNotBlank()}.let{if(it==null)"" else "\n$it"}+
            job.optString("backup").takeIf{it.isNotBlank()}.let{if(it==null)"" else "\n备份：$it"}
        return phase
    }

    private fun refresh() {
        if(busy)return
        busy=true
        task {try {
            val result=pinnedCall(owner,"upgrade",timeout=60_000)
            val list=result.getJSONArray("items");packages=(0 until list.length()).map{list.getJSONObject(it)}
            showReport(result);status("已读取 ${packages.size} 个本地 ZIP")
        }finally{busy=false}}
    }

    internal fun install(item:JSONObject) {
        if(busy || restarting)return
        busy=true;restarting=true;requestId=java.util.UUID.randomUUID().toString().replace("-","")
        status("正在校验本地完整包，当前接收端会在校验通过后退出…")
        task {try {
            val result=pinnedCall(owner,"upgrade",JSONObject().put("action","apply").put("name",item.optString("name")).put("sha256",item.optString("sha256")).put("request_id",requestId),120_000)
            restarting=result.optBoolean("restarting");targetVersion=result.optString("version");requestId=result.optString("request_id")
            status(result.optString("message"))
            if(restarting)watchUpgrade()
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            if(owner?.closed==true || e.message?.startsWith("请求超时")==true) {
                status("提交后连接中断，正在等待原电脑并核对此次更新…")
                watchUpgrade()
            }else {restarting=false;status(e.message ?: "升级请求失败",true)}
        }
        finally{busy=false}}
    }

    private fun sameComputer(client:AgentClient):Boolean {
        val id=Prefs.target(this)?.agentId.orEmpty()
        return if(peerId.isNotBlank())id==peerId else client.host==peerHost && client.port==peerPort
    }

    private fun watchUpgrade() {
        if(monitor?.isActive==true)return
        monitor=task {
            val original=owner
            val deadline=System.currentTimeMillis()+150_000
            var disconnected=resumedUpdate || original?.closed!=false
            while(restarting && System.currentTimeMillis()<deadline) {
                val connected=runCatching{ConnectionManager.sessionClient()}.getOrNull()
                if(connected==null) {
                    disconnected=true;status("电脑正在更新，连接已断开，等待原电脑重新启动…")
                }else if(!sameComputer(connected)) {
                    status("连接已切换到另一台电脑；停止跟踪此次升级，未向新电脑发送请求",true)
                    return@task
                }else {
                    if(disconnected || connected!==original) {
                        owner=connected
                        try {
                            val result=pinnedCall(connected,"upgrade",JSONObject().put("include_packages",false),6000)
                            val job=result.optJSONObject("status")
                            if(requestId.isNotBlank() && job?.optString("request_id")!=requestId) {
                                status("已连接原电脑，等待此次更新的结果…")
                                delay(1000);continue
                            }
                            val phase=showReport(result)
                            if(phase=="completed" && targetVersion.isNotBlank() && current!=targetVersion) {
                                status("原电脑仍在运行 $current，等待目标版本 $targetVersion 启动…")
                                delay(1000);continue
                            }
                            if(phase in listOf("completed","rolled_back","failed","cancelled")) {
                                restarting=false;packages=emptyList()
                                status(report,phase!="completed")
                                return@task
                            }
                            status(report.ifBlank{"已重新连接，正在确认升级结果…"})
                        }catch(e:CancellationException){throw e}
                        catch(_:Exception){status("已连接原电脑，正在等待更新状态…")}
                    }
                }
                delay(1000)
            }
            if(restarting)status("尚未确认更新完成，请检查电脑的 .upgrade 备份目录；可重新检查连接",true)
        }
    }

    override fun onStart(){super.onStart();ConnectionManager.addListener(this)}
    override fun onStop(){ConnectionManager.removeListener(this);super.onStop()}
    override fun onStatus(status:ConnectionManager.Status){if(restarting && status.connected)watchUpgrade()}
    override fun onEvent(text:String,kind:ConnectionManager.LogKind)=Unit
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("peer_id",peerId);state.putString("peer_host",peerHost);state.putInt("peer_port",peerPort)
        state.putBoolean("restarting",restarting);state.putString("target_version",targetVersion);state.putString("request_id",requestId)
        state.putString("current",current);state.putString("directory",directory)
        super.onSaveInstanceState(state)
    }
}

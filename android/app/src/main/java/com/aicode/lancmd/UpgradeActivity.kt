package com.aicode.lancmd

import android.os.Bundle
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** User-supplied ZIPs only. Rebind after an upgrade only to the same computer. */
class UpgradeActivity:MiuixActivity(),ConnectionManager.Listener {
    private var packages by mutableStateOf<List<JSONObject>>(emptyList())
    private var directory by mutableStateOf("")
    private var current by mutableStateOf("")
    private var currentBuild = ""
    private var report by mutableStateOf("")
    internal var busy by mutableStateOf(false)
    internal var uploading by mutableStateOf(false)
    private var picking by mutableStateOf(false)
    private var uploadProgress by mutableStateOf<FileTransfer.Progress?>(null)
    private var uploadJob:Job?=null
    private val pickPackage=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        picking=false
        if(uri!=null)uploadPackage(uri) else status("已取消选择升级包")
    }
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
        currentBuild=state?.getString("current_build") ?: current
        picking=state?.getBoolean("picking") ?: false
        content {
            ListPage("Windows 本地升级") {
                item {SectionCard("本地 Windows 升级包") {
                    InfoItem("接收端版本",current)
                    InfoItem("电脑上的 upgrade 文件夹",directory.ifBlank{"正在读取…"})
                    Note("直接选择手机里的完整 Windows ZIP，上传到电脑 upgrade 文件夹；也可手动放入后刷新。上传完成后再校验并安装。")
                    Action("选择并上传 ZIP",enabled=!busy && !restarting && !picking,primary=true){choosePackage()}
                    Action("刷新本地升级包",enabled=!busy && !restarting && !picking){refresh()}
                    if(uploading) {
                        uploadProgress?.let {p ->
                            Note("正在上传 · ${p.percent}% · ${FileTransfer.humanSize(p.done)} / ${FileTransfer.humanSize(p.total)}")
                            Progress(p.percent/100f)
                        }
                        Action("取消上传"){uploadJob?.cancel()}
                    }
                    Note("仅上传所选本地文件，不在线下载。新版确认启动后自动清理已用和旧版升级 ZIP，失败包及回退备份保留。")
                    if(report.isNotBlank())Note(report)
                    if(restarting) {
                        Note("更新会先断开连接，退出旧接收端，再解压替换并运行 start.bat；启动 CMD 自动退出。手机会等待原电脑重新连接并显示结果。")
                        Action("重新检查连接"){resumedUpdate=true;ConnectionManager.retryNow();watchUpgrade()}
                    }
                }}
                if(packages.isEmpty() && !restarting)item{EmptyNote("尚未发现 ZIP 升级包")}
                items(packages,key={it.optString("name")}) {item ->SectionCard {
                    InfoItem(item.optString("name"),FileTransfer.humanSize(item.optLong("size"))+"\nSHA256: "+item.optString("sha256"))
                    Action("校验并安装",enabled=!busy && !restarting && !picking && item.optString("sha256").length==64,primary=true){selected=item}
                }}
            }
            selected?.let {item ->ConfirmAction("安装本地 Windows 升级包？",item.optString("name")+
                "\n先校验完整包并尝试解冻应用，然后断开当前连接、退出旧接收端，解压替换原目录并自动运行 start.bat，启动 CMD 自动退出。\n录制、传输、脚本和延迟任务须先完成或取消。失败时尝试回退，请保留备份。",
                confirm="开始升级",onDismiss={selected=null},onConfirm={selected=null;install(item)})}
        }
        if(restarting)watchUpgrade() else if(!picking)refresh()
    }

    internal fun choosePackage() {
        if(busy || restarting || picking)return
        picking=true
        try {pickPackage.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}
        catch(e:Exception){picking=false;status(e.message ?: "无法打开文件选择器",true)}
    }

    internal fun uploadPackage(uri:Uri) {
        if(busy || restarting)return
        busy=true;uploading=true;uploadProgress=null
        uploadJob=task {try {
            val source=owner ?: error("请先连接电脑")
            check(sameComputer(source)){"电脑已切换，请重新打开本地升级页；未上传到新电脑"}
            val name=withContext(Dispatchers.IO){FileTransfer.displayName(this@UpgradeActivity,uri)}
            check(validUpgradeZipName(name)){"请选择文件名有效的 .zip 升级包"}
            val latest=pinnedCall(source,"upgrade",timeout=60_000)
            showReport(latest)
            check(directory.isNotBlank()){"电脑未返回 upgrade 文件夹，请刷新后重试"}
            var uploadedName=name
            suspend fun send()=FileTransfer.uploadRevision(this@UpgradeActivity,uri,
                directory.trimEnd('\\','/')+"\\"+uploadedName,source,{p ->runOnUiThread{if(!isDestroyed)uploadProgress=p}},
                maxBytes=512L*1024*1024,overwrite=false)
            val uploaded=try {send()} catch(e:java.io.IOException) {
                if(e.message?.startsWith("目标已存在:")!=true)throw e
                uploadedName=name.dropLast(4).take(210)+"-"+java.util.UUID.randomUUID().toString().take(8)+".zip"
                status("同名升级包已保留，另存为 $uploadedName")
                send()
            }
            val result=pinnedCall(source,"upgrade",timeout=60_000)
            renderPackages(result)
            // Older receivers return only the first five directory entries.
            // Keep the completed upload selectable even when their list omits it.
            val item=JSONObject().put("name",uploadedName).put("size",uploaded.optLong("size"))
                .put("sha256",uploaded.optString("sha256"))
            packages=listOf(item)+packages.filter{it.optString("name")!=uploadedName}.take(4)
            status("上传完成：$uploadedName；请选择「校验并安装」")
        }catch(e:CancellationException){status("上传已取消，未完成文件已请求清理");throw e}
        finally{uploading=false;busy=false;uploadProgress=null}}
    }

    private fun renderPackages(result:JSONObject) {
        val list=result.getJSONArray("items");packages=(0 until list.length()).map{list.getJSONObject(it)}
        showReport(result)
    }

    private fun showReport(result:JSONObject):String {
        directory=result.optString("directory",directory);current=result.optString("current",current)
        currentBuild=result.optString("current_build",current)
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
        job.optJSONObject("cleanup")?.let {cleanup ->
            report+=if(cleanup.optString("status")=="running")"\n正在后台清理旧升级 ZIP" else "\n升级 ZIP 清理：${cleanup.optInt("deleted")} 个"+
                if(cleanup.optInt("failed")>0)"；${cleanup.optInt("failed")} 个未清理，可稍后手动处理" else ""
        }
        return phase
    }

    private fun refresh() {
        if(busy)return
        busy=true
        task {try {
            val result=pinnedCall(owner,"upgrade",timeout=60_000)
            renderPackages(result);status("已读取 ${packages.size} 个本地 ZIP")
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
                            if(phase=="completed" && targetVersion.isNotBlank() && currentBuild!=targetVersion) {
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
        state.putString("current_build",currentBuild)
        state.putBoolean("picking",picking)
        super.onSaveInstanceState(state)
    }
}

internal fun validUpgradeZipName(name:String):Boolean = name.length in 5..255 && name.endsWith(".zip",true) &&
    name.none{it.code<32 || it in "\\/:*?\"<>|"} &&
    !Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)").containsMatchIn(name)

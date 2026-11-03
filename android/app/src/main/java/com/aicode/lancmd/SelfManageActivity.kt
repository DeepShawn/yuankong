package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * 接收端自我维护：安装文件夹改名、自我删除、批量修改最后修改日期。
 *
 * 改名和删除由电脑上的独立助手完成：先结束本进程，再改目录或删文件，
 * 最后按需从新路径重新启动。手机在这次操作后会短暂断线，重连即代表完成。
 */
class SelfManageActivity : MiuixActivity() {
    internal class Record(
        val id: String, val target: String, val files: Int,
        val applied: Int, val restored: Int, val failed: Int
    )

    internal var directory by mutableStateOf("")
    internal var pending by mutableStateOf<List<String>>(emptyList())
    internal var records by mutableStateOf<List<Record>>(emptyList())
    internal var busy by mutableStateOf(false)
    internal var supported by mutableStateOf(false)
    internal var diagnosticLoading by mutableStateOf(false)
    private var diagnosticReady by mutableStateOf(false)
    private var diagnosticJob:Job?=null
    private var diagnosticClient:AgentClient?=null

    internal var paths by mutableStateOf("")
    internal var mask by mutableStateOf("*")
    internal var time by mutableStateOf("")
    internal var start by mutableStateOf("")
    internal var end by mutableStateOf("")
    internal var recursive by mutableStateOf(true)
    internal var includeSelf by mutableStateOf(false)
    internal var useRange by mutableStateOf(false)

    internal var newName by mutableStateOf("")
    internal var keepConfig by mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status("维护状态将独立读取；读取失败时可取消、重试或返回，不会自动执行维护")
        content { Screen() }
        load()
    }

    @Composable
    private fun Screen() = Page("接收端自我维护") {
        SectionCard("当前安装") {
            InfoItem("接收端目录", directory.ifBlank { "正在读取…" })
            if (pending.isNotEmpty()) {
                pending.forEach { InfoItem("未完成的维护任务", it) }
                Note("上一次改名或删除没有留下完成记录：先查看电脑上的 LanCmdMaintenance 日志，再决定是否重试。")
            }
            Action("刷新状态", enabled = !busy) { load() }
            if(diagnosticLoading)Action("取消状态读取"){cancelDiagnostics()}
        }
        SectionCard("批量修改最后修改日期") {
            Field("路径（每行一个文件或文件夹）", paths, { paths = it }, singleLine = false, maxLines = 4)
            Field("通配符（例如 *.txt，默认全部）", mask, { mask = it })
            SelectionRow("包含子文件夹", "递归遍历所选文件夹", recursive, { recursive = it })
            SelectionRow("同时修改接收端自己的文件", "把接收端安装目录加进这次修改", includeSelf, { includeSelf = it })
            SelectionRow("使用时间范围", "在起止时间内随机取秒，避免整批时间完全一样", useRange, { useRange = it })
            if (useRange) {
                Field("开始时间", start, { start = it })
                Field("结束时间", end, { end = it })
            } else {
                Field("目标时间", time, { time = it })
            }
            ActionRow {
                Action("预览匹配文件", enabled = !busy && diagnosticReady, modifier = Modifier.weight(1f)) { preview() }
                Action("执行修改", enabled = !busy && diagnosticReady, primary = true, modifier = Modifier.weight(1f)) { confirmApply() }
            }
            Note("时间格式 YYYY-MM-DD HH:MM:SS，按电脑本地时间。每个文件都会先写入恢复记录再修改；文件系统不能精确保存时自动恢复原日期。")
        }
        SectionCard("日期修改记录") {
            if (records.isEmpty()) EmptyNote("还没有日期修改记录。")
            records.forEach { record ->
                InfoItem("${record.target.ifBlank { record.id }}",
                    "共 ${record.files} 个 · 已改 ${record.applied} · 已恢复 ${record.restored} · 失败 ${record.failed}")
                ActionRow {
                    Action("恢复原日期", enabled = !busy && diagnosticReady, modifier = Modifier.weight(1f)) { restore(record) }
                    Action("删除记录", enabled = !busy && diagnosticReady, modifier = Modifier.weight(1f)) { dropRecord(record) }
                }
            }
        }
        SectionCard("安装文件夹改名") {
            Field("新文件夹名称", newName, { newName = it })
            Action("改名并重新启动接收端", enabled = !busy && diagnosticReady && newName.isNotBlank(), primary = true) { confirmRename() }
            Note("新名称只能在同一个父目录内；改名前会先结束接收端和本目录里的其他进程，成功后更新当前用户自启动项。")
        }
        SectionCard("自我删除") {
            SelectionRow("保留配置和日志", "只删程序文件，保留 %APPDATA%\\LanCmd 里的记录", keepConfig, { keepConfig = it })
            Action("删除接收端", enabled = !busy && diagnosticReady) { confirmDelete() }
            Note("需要输入 DELETE 确认。删除后手机连接会断开，需要重新解压发布包才能再次使用；日期与改名记录也会随配置一起删除，除非保留配置。")
        }
    }

    private fun load() {
        if (busy) return
        busy = true
        diagnosticLoading=true;diagnosticReady=false
        status("正在读取维护状态，最多等待 8 秒；可以取消或返回")
        diagnosticJob=task {
            var feature:AgentClient?=null
            try {
                withTimeout(8000) {
                    val source=origin?:throw IOException("当前未连接电脑")
                    val client=AgentClient(source.host,source.port)
                    feature=client;diagnosticClient=client
                    ConnectionManager.connectFeatureClient(client,source)
                    suspend fun query(command:String,params:JSONObject=JSONObject()):JSONObject {
                        check(command in setOf("capabilities","maintenance_diagnostics"))
                        if(source.closed || ConnectionManager.sessionClient()!==source)throw IOException("电脑连接已改变，请重新打开此页")
                        val response=client.call(command,params,2000)
                        if(!response.optBoolean("ok"))throw IOException(response.optString("error","状态读取失败"))
                        if(source.closed || ConnectionManager.sessionClient()!==source)throw IOException("电脑连接已改变，请重新打开此页")
                        return response
                    }
                    val caps=query("capabilities").optJSONArray("features")
                    supported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="maintenance_diagnostics"}
                    if(!supported)throw IOException("请完整更新 Windows 接收端以使用限时状态诊断")
                    var first=true
                    while(true) {
                        val info=query("maintenance_diagnostics",JSONObject().put("refresh",first));first=false
                        directory=info.optString("root")
                        val list=info.optJSONArray("pending")?:JSONArray()
                        pending=(0 until list.length()).map {index->
                            val item=list.getJSONObject(index)
                            "${item.optString("operation")} · ${item.optString("plan")}"
                        }
                        val history=info.optJSONArray("items")?:JSONArray()
                        records=(0 until history.length()).map {index->
                            val item=history.getJSONObject(index)
                            Record(item.optString("id"),item.optString("target"),item.optInt("files"),item.optInt("applied"),item.optInt("restored"),item.optInt("failed"))
                        }
                        if(!info.optBoolean("loading")) {
                            val warnings=info.optJSONArray("warnings")?:JSONArray()
                            diagnosticReady=!info.optBoolean("partial")
                            status(if(warnings.length()>0)(0 until warnings.length()).joinToString("；"){warnings.optString(it)}
                                else "维护状态已读取，记录仅作诊断；未执行维护操作",warnings.length()>0)
                            break
                        }
                        delay(250)
                    }
                }
            } catch(e:TimeoutCancellationException) {
                status("状态读取超时；主连接未关闭，可以刷新重试",true)
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {status("状态读取失败：${e.message}；可以刷新重试",true)}
            finally {feature?.close();diagnosticClient=null;diagnosticLoading=false;busy=false}
        }
    }

    internal fun cancelDiagnostics() {
        diagnosticJob?.cancel();diagnosticClient?.close()
        status("已取消状态读取，可以刷新重试")
    }

    override fun onStop() {
        if(diagnosticLoading){diagnosticJob?.cancel();diagnosticClient?.close()}
        super.onStop()
    }

    private suspend fun refreshRecords() {
        val result = pinnedCall(origin, "dates_batch", JSONObject().put("action", "records"))
        val list = result.optJSONArray("items") ?: JSONArray()
        records = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            Record(item.optString("id"), item.optString("target"), item.optInt("files"),
                item.optInt("applied"), item.optInt("restored"), item.optInt("failed"))
        }
    }

    private fun selection(): JSONArray {
        val items = JSONArray()
        paths.split('\n', '\r').map { it.trim() }.filter { it.isNotBlank() }.forEach { items.put(it) }
        return items
    }

    private fun body(action: String): JSONObject = JSONObject()
        .put("action", action).put("paths", selection()).put("mask", mask.ifBlank { "*" })
        .put("recursive", recursive).put("include_self", includeSelf)

    private fun preview() {
        if (busy) return
        if (selection().length() == 0 && !includeSelf) { status("请先填写路径，或勾选包含接收端自己的文件", true); return }
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "dates_batch", body("preview"))
                val sample = result.optJSONArray("sample") ?: JSONArray()
                val first = if (sample.length() > 0) sample.getJSONObject(0).optString("path") else ""
                status("匹配 ${result.optInt("count")} 个文件，共 ${result.optInt("bytes") / 1024} KB${if (first.isBlank()) "" else "，例如 $first"}")
            } finally { busy = false }
        }
    }

    private fun confirmApply() {
        if (selection().length() == 0 && !includeSelf) { status("请先填写路径，或勾选包含接收端自己的文件", true); return }
        val detail = if (useRange) "$start ~ $end" else time
        if (detail.isBlank()) { status("请填写目标时间或时间范围", true); return }
        WorkspaceDialog.Builder(this)
            .setTitle("修改最后修改日期？")
            .setMessage("时间：$detail\n通配符：${mask.ifBlank { "*" }}\n递归：${if (recursive) "是" else "否"}\n包含接收端文件：${if (includeSelf) "是" else "否"}\n\n只修改最后修改时间，不改内容、创建时间和访问时间。")
            .setNegativeButton("取消", null)
            .setPositiveButton("执行") { _, _ -> apply() }
            .show()
    }

    private fun apply() {
        if (busy) return
        busy = true
        task {
            try {
                val params = body("apply")
                if (useRange) params.put("start", start).put("end", end) else params.put("time", time)
                val result = pinnedCall(origin, "dates_batch", params, timeout = 120_000)
                val errors = result.optJSONArray("errors") ?: JSONArray()
                val first = if (errors.length() > 0) errors.getJSONObject(0).optString("error") else ""
                status("已修改 ${result.optInt("applied")} 个，失败 ${result.optInt("failed")} 个${if (first.isBlank()) "" else "：$first"}",
                    result.optInt("failed") > 0)
                refreshRecords()
            } finally { busy = false }
        }
    }

    private fun restore(record: Record) {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "dates_batch",
                    JSONObject().put("action", "restore").put("id", record.id), timeout = 120_000)
                status("已恢复 ${result.optInt("restored")} 个，跳过 ${result.optInt("skipped")} 个",
                    result.optInt("skipped") > 0)
                refreshRecords()
            } finally { busy = false }
        }
    }

    private fun dropRecord(record: Record) {
        if (busy) return
        busy = true
        task {
            try {
                pinnedCall(origin, "dates_batch", JSONObject().put("action", "delete_record").put("id", record.id))
                status("记录已删除，已修改的文件保持当前日期")
                refreshRecords()
            } finally { busy = false }
        }
    }

    private fun confirmRename() {
        val name = newName.trim()
        if (name.isBlank()) { status("请输入新的文件夹名称", true); return }
        WorkspaceDialog.Builder(this)
            .setTitle("把安装文件夹改名为「$name」？")
            .setMessage("接收端会先退出，目录改名完成后自动从新路径启动，手机需要等待约十秒重新连接。\n\n失败时助手会停止，日志留在 %LOCALAPPDATA%\\LanCmdMaintenance。")
            .setNegativeButton("取消", null)
            .setPositiveButton("改名") { _, _ -> request("rename", JSONObject().put("name", name).put("confirm", "RENAME")) }
            .show()
    }

    private fun confirmDelete() {
        val input = dialogInput(hint = "输入 DELETE 确认")
        val dialog = WorkspaceDialog.Builder(this)
            .setTitle("删除接收端程序文件？")
            .setMessage(if (keepConfig) "接收端目录会被删除，配置和日志保留。之后手机立即断开连接。"
                        else "接收端目录、配置、日志和日期记录都会被删除。之后手机立即断开连接。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("删除", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text.toString().trim()
                if (typed != "DELETE") { input.error = "请输入 DELETE"; return@setOnClickListener }
                dialog.dismiss()
                request("delete", JSONObject().put("keep_config", keepConfig).put("confirm", "DELETE"))
            }
        }
        dialog.show()
    }

    private fun request(action: String, params: JSONObject) {
        if (busy) return
        busy = true
        task {
            try {
                val result = pinnedCall(origin, "self_manage", params.put("action", action))
                status(result.optString("note", "已交给电脑上的助手执行"))
            } finally { busy = false }
        }
    }
}

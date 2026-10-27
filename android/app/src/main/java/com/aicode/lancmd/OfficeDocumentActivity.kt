package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.parentOf
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Office edits stay on the phone until the user explicitly uploads a revision. */
class OfficeDocumentActivity:FeatureActivity() {
    companion object {
        val extensions=setOf("doc","docx","docm","dot","dotx","rtf","ppt","pptx","pptm","pps","ppsx","xls","xlsx","xlsm","xlsb","csv","et","dps","wps","pdf","odt","ods","odp")
        fun supports(path:String)=path.substringAfterLast('.').lowercase() in extensions
    }
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private var remote=""
    private var revision=""
    private var local:File?=null
    private var busy=false
    private var wrongSource=false
    private val sourceKey by lazy { Prefs.target(this)?.agentId.orEmpty()+"@${origin?.host}:${origin?.port}" }
    private val actions=ArrayList<MaterialButton>()
    private val wpsPackages=listOf("cn.wps.moffice_eng","cn.wps.moffice_i18n","cn.wps.moffice")
    private val pickEdited=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) {
            val target=local ?: File(File(filesDir,"office-documents/${UUID.randomUUID()}").apply { mkdirs() },remote.substringAfterLast('\\').substringAfterLast('/'))
            task {
                if(busy)return@task
                setBusy(true)
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input->
                            val stage=File(target.parentFile,".importing")
                            try { stage.outputStream().use { input.copyTo(it) };check(stage.renameTo(target)) { "无法保存手机副本" } }
                            finally { stage.delete() }
                        } ?: error("无法读取所选文件")
                    }
                    local=target;status("已导入 WPS 另存文件，检查后点击回传；电脑原件尚未更改")
                } finally { setBusy(false) }
            }
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);origin
        wrongSource=state?.getString("source")?.let { it!=sourceKey } ?: false
        remote=state?.getString("remote") ?: intent.getStringExtra("path").orEmpty()
        revision=state?.getString("revision") ?: intent.getStringExtra("file_id").orEmpty()
        local=state?.getString("local")?.let { File(it) }?.takeIf { it.isFile && it.canonicalPath.startsWith(File(filesDir,"office-documents").canonicalPath+File.separator) }
        setup("Office 文档","先下载手机副本，用 WPS 查看或编辑；回传前校验电脑原件，冲突时保留手机修改。",scrollable=true)
        body.addView(label(remote,13f).apply { setTextIsSelectable(true) })
        fun action(name:String,block:()->Unit) { val b=button(name,block);actions.add(b);body.addView(b) }
        action("手机 WPS 预览") { phone(false) }
        action("手机 WPS 编辑") { phone(true) }
        action("回传覆盖电脑原件") { confirmUpload() }
        action("选择 WPS 另存文件") { if(!busy)pickEdited.launch(arrayOf("*/*")) }
        action("电脑 WPS 打开") { computer(false) }
        action("电脑 WPS 在当前窗口下方打开") { computer(true) }
        action("设置文件操作延迟") { FileDelay.choose(this) }
        body.addView(label("手机编辑不会自动覆盖电脑。WPS 若使用另存为，请选择另存文件再回传。电脑 WPS 的登录、文档修复或系统安全提示由应用和系统管理；后台打开是尽量保留当前窗口焦点。",12f,R.color.dim))
        local?.let { status("手机编辑副本已保留，可继续打开或回传：${it.name}") }
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("source",sourceKey);out.putString("remote",remote);out.putString("revision",revision);out.putString("local",local?.absolutePath);super.onSaveInstanceState(out) }
    private fun checkSource() { check(!wrongSource) { "电脑已切换，手机副本保留；请重新从原电脑文件管理打开" } }
    private fun setBusy(value:Boolean) { busy=value;actions.forEach { it.isEnabled=!value } }
    private fun phone(edit:Boolean) {
        if(busy)return
        setBusy(true)
        task {
            try {
                checkSource()
                FileDelay.waitLocal(this@OfficeDocumentActivity,origin) { status(it) }
                if(local?.isFile!=true) {
                    checkSource()
                    check(revision.isNotBlank()) { "缺少电脑文件版本，请更新接收端并刷新文件管理" }
                    val target=File(File(filesDir,"office-documents/${UUID.randomUUID()}").apply { mkdirs() },remote.substringAfterLast('\\').substringAfterLast('/'))
                    try {
                        FileTransfer.downloadToStream(remote,origin,{target.outputStream()},{p->runOnUiThread { status("正在下载手机副本 ${p.percent}%") }},revision)
                        local=target
                    } catch(e:Throwable){target.delete();throw e}
                }
                val target=local!!
                val uri=FileProvider.getUriForFile(this@OfficeDocumentActivity,"$packageName.officefiles",target)
                val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.extension.lowercase()) ?: "application/octet-stream"
                val intent=Intent(if(edit)Intent.ACTION_EDIT else Intent.ACTION_VIEW).setDataAndType(uri,mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or if(edit)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                intent.clipData=android.content.ClipData.newRawUri("Office 文档",uri)
                val installed=wpsPackages.firstOrNull { runCatching { packageManager.getPackageInfo(it,0) }.isSuccess }
                if(installed!=null)intent.setPackage(installed)
                startActivity(if(installed!=null)intent else Intent.createChooser(intent,"选择 WPS 或 Office 应用"))
                status("手机副本已打开；编辑保存并返回后，点击回传覆盖电脑原件")
            } finally { setBusy(false) }
        }
    }
    private fun confirmUpload() {
        if(busy)return
        val source=local
        if(source?.isFile!=true){status("请先下载或选择手机编辑副本",true);return}
        AlertDialog.Builder(this).setTitle("回传覆盖电脑原件？").setMessage("$remote\n\n电脑原件若已变化将拒绝覆盖，手机副本保留。请先在 WPS 中保存。")
            .setNegativeButton("取消",null).setPositiveButton("回传") { _,_->
                setBusy(true)
                task { try {
                    check(revision.isNotBlank()) { "缺少原件版本，请重新从文件管理打开" }
                    FileDelay.waitLocal(this@OfficeDocumentActivity,origin) { status(it) }
                    val uri=FileProvider.getUriForFile(this@OfficeDocumentActivity,"$packageName.officefiles",source)
                    val result=FileTransfer.uploadRevision(this@OfficeDocumentActivity,uri,remote,origin,{p->runOnUiThread { status("回传 ${p.percent}%") }},revision)
                    revision=result.getString("file_id");status("已保存到电脑；手机副本继续保留")
                } finally { setBusy(false) } }
            }.show()
    }
    private fun computer(background:Boolean) {
        if(busy)return
        setBusy(true)
        task { try {
            checkSource()
            val r=FileDelay.call(this@OfficeDocumentActivity,origin,"files",JSONObject().put("action","open").put("path",remote).put("application","wps").put("background",background)) { status(it) }
            status(r.optString("notice","已向电脑发送打开请求"))
        } finally { setBusy(false) } }
    }
}

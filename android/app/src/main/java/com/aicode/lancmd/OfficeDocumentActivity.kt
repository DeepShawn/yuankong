package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.aicode.lancmd.net.FileTransfer
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 手机上的 Office 编辑不会自动覆盖电脑原件，必须显式回传。界面使用 miuix。 */
class OfficeDocumentActivity : MiuixActivity() {
    companion object {
        val extensions = setOf(
            "doc", "docx", "docm", "dot", "dotx", "rtf", "ppt", "pptx", "pptm", "pps", "ppsx",
            "xls", "xlsx", "xlsm", "xlsb", "csv", "et", "dps", "wps", "pdf", "odt", "ods", "odp"
        )

        fun supports(path: String) = path.substringAfterLast('.').lowercase() in extensions
    }

    private val wpsPackages = listOf("cn.wps.moffice_eng", "cn.wps.moffice_i18n", "cn.wps.moffice")
    private val sourceKey by lazy { Prefs.target(this)?.agentId.orEmpty() + "@${origin?.host}:${origin?.port}" }

    private var remote by mutableStateOf("")
    private var revision = ""
    private var local: File? = null
    internal var busy by mutableStateOf(false)
    private var wrongSource = false

    private val pickEdited = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importEdited(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wrongSource = savedInstanceState?.getString("source")?.let { it != sourceKey } ?: false
        remote = savedInstanceState?.getString("remote") ?: intent.getStringExtra("path").orEmpty()
        revision = savedInstanceState?.getString("revision") ?: intent.getStringExtra("file_id").orEmpty()
        local = savedInstanceState?.getString("local")?.let { File(it) }?.takeIf {
            it.isFile && it.canonicalPath.startsWith(File(filesDir, "office-documents").canonicalPath + File.separator)
        }
        status("先下载手机副本，用 WPS 查看或编辑；回传前校验电脑原件，冲突时保留手机修改。")
        content { Screen() }
        local?.let { status("手机编辑副本已保留，可继续打开或回传：${it.name}") }
    }

    @Composable
    private fun Screen() = Page("Office 文档") {
        SectionCard {
            InfoItem(remote.substringAfterLast('\\').substringAfterLast('/'), remote)
        }
        SectionCard("在手机上") {
            ActionRow {
                Action("手机 WPS 预览", enabled = !busy, modifier = Modifier.weight(1f)) { phone(false) }
                Action("手机 WPS 编辑", enabled = !busy, primary = true, modifier = Modifier.weight(1f)) { phone(true) }
            }
            ActionRow {
                Action("回传覆盖电脑原件", enabled = !busy, modifier = Modifier.weight(1f)) { confirmUpload() }
                Action("选择 WPS 另存文件", enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (!busy) pickEdited.launch(arrayOf("*/*"))
                }
            }
        }
        SectionCard("在电脑上") {
            ActionRow {
                Action("电脑 WPS 打开", enabled = !busy, modifier = Modifier.weight(1f)) { computer(false) }
                Action("电脑 WPS 在当前窗口下方打开", enabled = !busy, modifier = Modifier.weight(1f)) { computer(true) }
            }
            Action("设置文件操作延迟", enabled = !busy) { FileDelay.choose(this@OfficeDocumentActivity) }
        }
        Note(
            "手机编辑不会自动覆盖电脑。WPS 若使用另存为，请选择另存文件再回传。" +
                "电脑 WPS 的登录、文档修复或系统安全提示由应用和系统管理；后台打开是尽量保留当前窗口焦点。"
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", sourceKey)
        outState.putString("remote", remote)
        outState.putString("revision", revision)
        outState.putString("local", local?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    private fun checkSource() =
        check(!wrongSource) { "电脑已切换，手机副本保留；请重新从原电脑文件管理打开" }

    private fun copyName() = remote.substringAfterLast('\\').substringAfterLast('/')

    private fun newCopy() =
        File(File(filesDir, "office-documents/${UUID.randomUUID()}").apply { mkdirs() }, copyName())

    private fun importEdited(uri: android.net.Uri) {
        val target = local ?: newCopy()
        task {
            if (busy) return@task
            busy = true
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val stage = File(target.parentFile, ".importing")
                        try {
                            stage.outputStream().use { input.copyTo(it) }
                            check(stage.renameTo(target)) { "无法保存手机副本" }
                        } finally { stage.delete() }
                    } ?: error("无法读取所选文件")
                }
                local = target
                status("已导入 WPS 另存文件，检查后点击回传；电脑原件尚未更改")
            } finally { busy = false }
        }
    }

    internal fun phone(edit: Boolean) {
        if (busy) return
        busy = true
        task {
            try {
                checkSource()
                FileDelay.waitLocal(this@OfficeDocumentActivity, origin) { status(it) }
                if (local?.isFile != true) {
                    checkSource()
                    check(revision.isNotBlank()) { "缺少电脑文件版本，请更新接收端并刷新文件管理" }
                    val target = newCopy()
                    try {
                        FileTransfer.downloadToStream(
                            remote, origin, { target.outputStream() },
                            { p -> runOnUiThread { status("正在下载手机副本 ${p.percent}%") } }, revision
                        )
                        local = target
                    } catch (e: Throwable) { target.delete(); throw e }
                }
                val target = local!!
                val uri = FileProvider.getUriForFile(this@OfficeDocumentActivity, "$packageName.officefiles", target)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.extension.lowercase())
                    ?: "application/octet-stream"
                val intent = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            if (edit) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
                    )
                intent.clipData = android.content.ClipData.newRawUri("Office 文档", uri)
                val installed = wpsPackages.firstOrNull {
                    runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
                }
                if (installed != null) {
                    intent.setPackage(installed)
                    if (edit && intent.resolveActivity(packageManager) == null) intent.action = Intent.ACTION_VIEW
                    intent.putExtra("OpenMode", if (edit) "Normal" else "ReadOnly")
                }
                startActivity(if (installed != null) intent else Intent.createChooser(intent, "选择 WPS 或 Office 应用"))
                status("手机副本已打开；编辑保存并返回后，点击回传覆盖电脑原件")
            } finally { busy = false }
        }
    }

    internal fun confirmUpload() {
        if (busy) return
        val source = local
        if (source?.isFile != true) { status("请先下载或选择手机编辑副本", true); return }
        AlertDialog.Builder(this).setTitle("回传覆盖电脑原件？")
            .setMessage("$remote\n\n电脑原件若已变化将拒绝覆盖，手机副本保留。请先在 WPS 中保存。")
            .setNegativeButton("取消", null)
            .setPositiveButton("回传") { _, _ ->
                busy = true
                task {
                    try {
                        check(revision.isNotBlank()) { "缺少原件版本，请重新从文件管理打开" }
                        FileDelay.waitLocal(this@OfficeDocumentActivity, origin) { status(it) }
                        val uri = FileProvider.getUriForFile(
                            this@OfficeDocumentActivity, "$packageName.officefiles", source
                        )
                        val result = FileTransfer.uploadRevision(
                            this@OfficeDocumentActivity, uri, remote, origin,
                            { p -> runOnUiThread { status("回传 ${p.percent}%") } }, revision
                        )
                        revision = result.getString("file_id")
                        status("已保存到电脑；手机副本继续保留")
                    } finally { busy = false }
                }
            }.show()
    }

    internal fun computer(background: Boolean) {
        if (busy) return
        busy = true
        task {
            try {
                checkSource()
                val result = FileDelay.call(
                    this@OfficeDocumentActivity, origin, "files",
                    JSONObject().put("action", "open").put("path", remote)
                        .put("application", "wps").put("background", background)
                ) { status(it) }
                status(result.optString("notice", "已向电脑发送打开请求"))
            } finally { busy = false }
        }
    }
}

package com.aicode.lancmd

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.io.File
import java.security.MessageDigest

/** 读取并保存电脑上的文本文件，带手机草稿与版本冲突检查。界面使用 miuix。 */
class TextEditorActivity : MiuixActivity() {
    companion object {
        val ENCODINGS = listOf("utf-8", "utf-8-bom", "gbk", "gb18030", "utf-16-le", "utf-16-be")
        val ENCODING_NAMES = listOf("UTF-8", "UTF-8 BOM", "GBK", "GB18030", "UTF-16 LE", "UTF-16 BE")
        val NEWLINES = listOf("preserve", "crlf", "lf", "cr")
        val NEWLINE_NAMES = listOf("保留换行", "Windows CRLF", "Unix LF", "Mac CR")
    }

    private val computer by lazy {
        Prefs.target(this)?.agentId?.takeIf { it.isNotBlank() } ?: "${origin?.host}:${origin?.port}"
    }

    private var path by mutableStateOf("")
    internal var body by mutableStateOf("")
    private var encodingIndex by mutableIntStateOf(0)
    private var newlineIndex by mutableIntStateOf(1)
    internal var autoSave by mutableStateOf(false)
    internal var editable by mutableStateOf(false)

    private var revision = ""
    private var baseline = ""
    private var savedEncoding = "utf-8"
    private var savedNewline = "crlf"
    private var loaded = false
    private var settingsExpanded by mutableStateOf(false)
    private var setting = false
    private var saving = false
    private var loading = false
    private var autosaveJob: Job? = null

    private val draftFile get(): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((computer + "\n" + path).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(File(filesDir, "editor-drafts").apply { mkdirs() }, "$hash.json")
    }

    private fun selectedEncoding() = ENCODINGS[encodingIndex.coerceAtLeast(0)]
    private fun selectedNewline() = NEWLINES[newlineIndex.coerceAtLeast(0)]
    private fun dirty() = loaded &&
        (body != baseline || selectedEncoding() != savedEncoding || selectedNewline() != savedNewline)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        computer
        path = savedInstanceState?.getString("path") ?: intent.getStringExtra("path").orEmpty()
        status("读取电脑文本文件，修改后保存；可选自动保存，文件变更冲突时停止覆盖。")
        content { Screen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })
        load(restoreDraft = true, selected = intent.getStringExtra("encoding") ?: "auto")
    }

    @Composable
    private fun Screen() = FixedPage("文本编辑") {
        SectionCard {
            InfoItem(path.substringAfterLast('\\').substringAfterLast('/'), path)
            SelectionAction("编码 / 换行 / 自动保存",selected=settingsExpanded){settingsExpanded=!settingsExpanded}
            if(settingsExpanded) {
            ChoiceField(
                items = ENCODING_NAMES, title = "编码",
                selectedIndex = encodingIndex,
                onSelectedIndexChange = { encodingIndex = it; changed() }
            )
            ChoiceField(
                items = NEWLINE_NAMES, title = "换行",
                selectedIndex = newlineIndex,
                onSelectedIndexChange = { newlineIndex = it; changed() }
            )
            }
        }
        SectionCard("内容",modifier=Modifier.weight(1f)) {
            BasicTextField(
                value = body,
                onValueChange = { edit(it) },
                enabled=editable,
                singleLine = false,
                textStyle=TextStyle(color=MiuixTheme.colorScheme.onSurface,fontSize=16.sp,lineHeight=23.sp),
                cursorBrush=SolidColor(MiuixTheme.colorScheme.primary),
                modifier=Modifier.weight(1f).fillMaxWidth().background(MiuixTheme.colorScheme.surfaceVariant,RoundedCornerShape(16.dp))
                    .padding(14.dp).semantics{contentDescription="电脑文件文本内容"}
            )
            if(settingsExpanded)SuperSwitch(
                checked = autoSave,
                onCheckedChange = { autoSave = it; changed() },
                title = "自动保存",
                summary = "停止输入 1 秒后保存",
                enabled = editable
            )
            ActionRow {
                Action("保存", enabled = editable, primary = true, modifier = Modifier.weight(1f)) { save() }
                Action("重新加载", modifier = Modifier.weight(1f)) { confirmReload() }
                Action("另存为", enabled = editable, modifier = Modifier.weight(1f)) { saveAs() }
            }
        }
    }

    override fun onBack() = confirmExit()

    private fun confirmExit() {
        if (saving) { status("正在保存，请稍候"); return }
        if (!dirty()) { persistDraft(); finish(); return }
        val retained = persistDraft()
        WorkspaceDialog.Builder(this).setTitle("有未保存的修改")
            .setMessage(
                if (retained) "草稿已保存在手机；可保存到电脑后退出，或保留草稿稍后继续。"
                else "手机草稿保存失败，请继续编辑或先保存到电脑。"
            )
            .setNegativeButton("继续编辑", null)
            .apply { if (retained) setNeutralButton("保留草稿退出") { _, _ -> finish() } }
            .setPositiveButton("保存并退出") { _, _ -> save(exit = true) }.show()
    }

    /** 文本输入；和旧版的文本监听一致地触发自动保存。 */
    internal fun edit(text: String) {
        body = text.take(512 * 1024)
        changed()
    }

    private fun changed() {
        if (setting || !loaded) return
        autosaveJob?.cancel()
        if (dirty()) status("有未保存修改 · ${body.length} 字符")
        if (autoSave && dirty() && !saving) autosaveJob = lifecycleScope.launch { delay(1000); save() }
    }

    internal fun load(restoreDraft: Boolean = false, selected: String = "auto") {
        if (saving || loading || path.isBlank()) return
        autosaveJob?.cancel()
        autoSave = false
        // 电脑不可达时也要能继续用手机草稿。
        if (restoreDraft && this.restoreDraft()) return
        loading = true
        editable = false
        task {
            try {
                val result = FileDelay.call(
                    this@TextEditorActivity, origin, "files",
                    JSONObject().put("action", "text_read").put("path", path).put("encoding", selected)
                )
                setting = true
                revision = result.getString("revision")
                baseline = result.getString("text")
                savedEncoding = result.getString("encoding")
                savedNewline = if (result.optBoolean("mixed")) "preserve" else result.getString("newline")
                body = baseline
                encodingIndex = ENCODINGS.indexOf(savedEncoding).coerceAtLeast(0)
                newlineIndex = NEWLINES.indexOf(savedNewline).coerceAtLeast(0)
                loaded = true
                draftFile.delete()
                status(
                    if (result.optBoolean("mixed")) "已读取，包含混合换行；默认保留原有换行"
                    else "已读取 · ${result.optInt("size")} 字节"
                )
            } finally {
                setting = false
                loading = false
                editable = loaded
            }
        }
    }

    private fun restoreDraft(): Boolean {
        val draft = runCatching { if (draftFile.isFile) JSONObject(draftFile.readText()) else null }
            .getOrNull() ?: return false
        if (draft.optString("path") != path || draft.optString("computer") != computer ||
            draft.optString("encoding") !in ENCODINGS || draft.optString("savedEncoding") !in ENCODINGS ||
            draft.optString("newline") !in NEWLINES || draft.optString("savedNewline") !in NEWLINES ||
            draft.optString("text").length > 512 * 1024 ||
            draft.optString("baseline").length > 512 * 1024 ||
            draft.optString("revision").isBlank()
        ) return false
        setting = true
        try {
            body = draft.getString("text")
            baseline = draft.getString("baseline")
            revision = draft.getString("revision")
            savedEncoding = draft.getString("savedEncoding")
            savedNewline = draft.getString("savedNewline")
            encodingIndex = ENCODINGS.indexOf(draft.getString("encoding"))
            newlineIndex = NEWLINES.indexOf(draft.getString("newline"))
            loaded = true
            editable = true
            status("已恢复手机草稿；保存时会检查电脑文件是否变更")
        } finally { setting = false }
        return true
    }

    internal fun confirmReload() {
        if (saving || loading) return
        val selected = selectedEncoding()
        WorkspaceDialog.Builder(this).setTitle("重新读取电脑文件？")
            .setMessage("将按 $selected 编码重新读取，当前未保存修改会被替换。也可先另存为保留修改。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重新读取") { _, _ -> persistDraft(); load(selected = selected) }.show()
    }

    internal fun save(exit: Boolean = false) {
        if (!loaded || saving || loading) return
        autosaveJob?.cancel()
        val value = body
        val format = selectedEncoding()
        val ending = selectedNewline()
        saving = true
        task {
            try {
                val result = FileDelay.call(
                    this@TextEditorActivity, origin, "files",
                    JSONObject().put("action", "text_write").put("path", path).put("text", value)
                        .put("encoding", format).put("newline", ending).put("revision", revision)
                )
                revision = result.getString("revision")
                baseline = value
                savedEncoding = format
                savedNewline = ending
                if (dirty()) persistDraft() else draftFile.delete()
                status(
                    if (dirty()) "当前版本已保存，仍有新的未保存修改"
                    else "已保存到电脑 · ${result.optInt("size")} 字节"
                )
                if (exit && !dirty()) finish()
            } catch (e: Exception) { autoSave = false; persistDraft(); throw e }
            finally { saving = false; if (autoSave && dirty()) changed() }
        }
    }

    internal fun saveAs() {
        if (!loaded || saving || loading) return
        val target = dialogInput(text = "$path.copy.txt")
        WorkspaceDialog.Builder(this).setTitle("另存为电脑文件")
            .setMessage("目标必须不存在，不覆盖已有文件。").setView(target)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存副本") { _, _ ->
                val destination = target.text.toString().trim()
                val value = body
                val format = selectedEncoding()
                val ending = selectedNewline()
                task {
                    FileDelay.call(
                        this@TextEditorActivity, origin, "files",
                        JSONObject().put("action", "text_create").put("path", destination)
                            .put("text", value).put("encoding", format).put("newline", ending)
                    )
                    status("副本已保存：$destination")
                }
            }.show()
    }

    private fun persistDraft(): Boolean {
        if (!loaded) return false
        if (!dirty()) return runCatching { !draftFile.exists() || draftFile.delete() }.getOrDefault(false)
        return runCatching {
            val value = JSONObject().put("path", path).put("computer", computer).put("text", body)
                .put("baseline", baseline).put("revision", revision)
                .put("encoding", selectedEncoding()).put("newline", selectedNewline())
                .put("savedEncoding", savedEncoding).put("savedNewline", savedNewline)
            val file = draftFile
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(value.toString())
            if (!temp.renameTo(file)) { temp.delete(); error("无法保存手机草稿") }
            true
        }.onFailure { status("手机草稿保存失败：${it.message}", true) }.getOrDefault(false)
    }

    override fun onPause() {
        autosaveJob?.cancel(); persistDraft(); super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistDraft(); outState.putString("path", path); super.onSaveInstanceState(outState)
    }
}

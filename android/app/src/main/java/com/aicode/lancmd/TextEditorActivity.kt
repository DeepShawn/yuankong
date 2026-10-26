package com.aicode.lancmd

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class TextEditorActivity : FeatureActivity() {
    companion object {
        val ENCODINGS = listOf("utf-8", "utf-8-bom", "gbk", "gb18030", "utf-16-le", "utf-16-be")
        val ENCODING_NAMES = listOf("UTF-8", "UTF-8 BOM", "GBK", "GB18030", "UTF-16 LE", "UTF-16 BE")
        val NEWLINES = listOf("preserve", "crlf", "lf", "cr")
    }
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val computer by lazy { Prefs.target(this)?.agentId?.takeIf { it.isNotBlank() } ?: "${origin?.host}:${origin?.port}" }
    private lateinit var text: EditText
    private lateinit var encoding: Spinner
    private lateinit var newline: Spinner
    private lateinit var auto: CheckBox
    private lateinit var pathLabel: android.widget.TextView
    private lateinit var saveButton: com.google.android.material.button.MaterialButton
    private var path = ""
    private var revision = ""
    private var baseline = ""
    private var savedEncoding = "utf-8"
    private var savedNewline = "crlf"
    private var loaded = false
    private var setting = false
    private var saving = false
    private var loading = false
    private var autosave: Job? = null
    private val draftFile get(): File {
        val hash = MessageDigest.getInstance("SHA-256").digest((computer + "\n" + path).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(File(filesDir, "editor-drafts").apply { mkdirs() }, "$hash.json")
    }
    private fun selectedEncoding() = ENCODINGS[encoding.selectedItemPosition.coerceAtLeast(0)]
    private fun selectedNewline() = NEWLINES[newline.selectedItemPosition.coerceAtLeast(0)]
    private fun dirty() = loaded && (text.text.toString() != baseline || selectedEncoding() != savedEncoding || selectedNewline() != savedNewline)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origin; computer
        path = savedInstanceState?.getString("path") ?: intent.getStringExtra("path").orEmpty()
        setup("文本编辑", "读取电脑文本文件，修改后保存；可选自动保存，文件变更冲突时停止覆盖。")
        pathLabel = label(path, 12f, R.color.dim).apply { setTextIsSelectable(true); maxLines = 3 }
        body.addView(pathLabel)
        val formats = LinearLayout(this)
        encoding = Spinner(this).apply { adapter = ArrayAdapter(this@TextEditorActivity, android.R.layout.simple_spinner_dropdown_item, ENCODING_NAMES) }
        newline = Spinner(this).apply { adapter = ArrayAdapter(this@TextEditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("保留换行", "Windows CRLF", "Unix LF", "Mac CR")) }
        formats.addView(encoding, LinearLayout.LayoutParams(0,-2,1f)); formats.addView(newline, LinearLayout.LayoutParams(0,-2,1f)); body.addView(formats)
        text = EditText(this).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = Typeface.MONOSPACE; textSize = 14f; isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(512 * 1024)); setTextColor(color(R.color.text)); setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(12),dp(12),dp(12),dp(12)); contentDescription = "电脑文件文本内容"; isEnabled = false
        }
        body.addView(text, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin = dp(6) })
        auto = styleCheck(CheckBox(this).apply { this.text = "自动保存（停止输入 1 秒后）"; isEnabled = false })
        body.addView(auto)
        val buttons = LinearLayout(this)
        saveButton = primary(button("保存") { save() }).apply { isEnabled = false }
        buttons.addView(saveButton, LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(button("重新加载") { confirmReload() }, LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(button("另存为") { saveAs() }, LinearLayout.LayoutParams(0,-2,1f))
        body.addView(buttons)
        ViewCompat.setOnApplyWindowInsetsListener(text) { _, insets ->
            val compact = insets.isVisible(WindowInsetsCompat.Type.ime())
            listOf(pathLabel,formats,statusText,auto).forEach { it.visibility = if (compact) View.GONE else View.VISIBLE }
            insets
        }
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed() }
        }
        encoding.onItemSelectedListener = listener; newline.onItemSelectedListener = listener
        auto.setOnCheckedChangeListener { _, _ -> changed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (saving) { status("正在保存，请稍候"); return }
                if (!dirty()) { persistDraft(); finish(); return }
                val retained = persistDraft()
                AlertDialog.Builder(this@TextEditorActivity).setTitle("有未保存的修改")
                    .setMessage(if (retained) "草稿已保存在手机；可保存到电脑后退出，或保留草稿稍后继续。" else "手机草稿保存失败，请继续编辑或先保存到电脑。")
                    .setNegativeButton("继续编辑",null)
                    .apply { if (retained) setNeutralButton("保留草稿退出") { _,_ -> finish() } }
                    .setPositiveButton("保存并退出") { _,_ -> save(exit=true) }.show()
            }
        })
        load(restoreDraft = true,selected=intent.getStringExtra("encoding")?:"auto")
    }

    private fun changed() {
        if (setting || !loaded) return
        autosave?.cancel()
        if (dirty()) status("有未保存修改 · ${text.length()} 字符")
        if (auto.isChecked && dirty() && !saving) autosave = lifecycleScope.launch { delay(1000); save() }
    }

    private fun load(restoreDraft: Boolean = false, selected: String = "auto") {
        if (saving || loading || path.isBlank()) return
        autosave?.cancel(); auto.isChecked = false
        // A local draft must remain available even when the PC cannot be reached.
        if (restoreDraft && this.restoreDraft()) return
        loading = true; text.isEnabled = false; saveButton.isEnabled = false; auto.isEnabled = false; encoding.isEnabled = false; newline.isEnabled = false
        task {
            try {
                val result = pinnedCall(origin,"files",JSONObject().put("action","text_read").put("path",path).put("encoding",selected))
                setting = true
                revision = result.getString("revision"); baseline = result.getString("text")
                savedEncoding = result.getString("encoding"); savedNewline = if (result.optBoolean("mixed")) "preserve" else result.getString("newline")
                text.setText(baseline); encoding.setSelection(ENCODINGS.indexOf(savedEncoding).coerceAtLeast(0)); newline.setSelection(NEWLINES.indexOf(savedNewline).coerceAtLeast(0))
                loaded = true
                draftFile.delete()
                status(if (result.optBoolean("mixed")) "已读取，包含混合换行；默认保留原有换行" else "已读取 · ${result.optInt("size")} 字节")
            } finally {
                setting = false; loading = false
                text.isEnabled = loaded; saveButton.isEnabled = loaded; auto.isEnabled = loaded
                encoding.isEnabled = true; newline.isEnabled = true
            }
        }
    }

    private fun restoreDraft(): Boolean {
        val draft = runCatching { if (draftFile.isFile) JSONObject(draftFile.readText()) else null }.getOrNull() ?: return false
        if (draft.optString("path") != path || draft.optString("computer") != computer ||
            draft.optString("encoding") !in ENCODINGS || draft.optString("savedEncoding") !in ENCODINGS ||
            draft.optString("newline") !in NEWLINES || draft.optString("savedNewline") !in NEWLINES ||
            draft.optString("text").length > 512 * 1024 || draft.optString("baseline").length > 512 * 1024 || draft.optString("revision").isBlank()) return false
        setting = true
        try {
            text.setText(draft.getString("text")); baseline = draft.getString("baseline")
            revision = draft.getString("revision"); savedEncoding = draft.getString("savedEncoding"); savedNewline = draft.getString("savedNewline")
            encoding.setSelection(ENCODINGS.indexOf(draft.getString("encoding"))); newline.setSelection(NEWLINES.indexOf(draft.getString("newline")))
            loaded = true; text.isEnabled = true; saveButton.isEnabled = true; auto.isEnabled = true
            status("已恢复手机草稿；保存时会检查电脑文件是否变更")
        } finally { setting = false }
        return true
    }

    private fun confirmReload() {
        if (saving || loading) return
        val selected = selectedEncoding()
        AlertDialog.Builder(this).setTitle("重新读取电脑文件？")
            .setMessage("将按 $selected 编码重新读取，当前未保存修改会被替换。也可先另存为保留修改。")
            .setNegativeButton("取消",null).setPositiveButton("重新读取") { _,_ -> persistDraft(); load(selected=selected) }.show()
    }

    private fun save(exit: Boolean = false) {
        if (!loaded || saving || loading) return
        autosave?.cancel()
        val value = text.text.toString(); val format = selectedEncoding(); val ending = selectedNewline()
        saving = true; saveButton.isEnabled = false
        task {
            try {
                val result = pinnedCall(origin,"files",JSONObject().put("action","text_write").put("path",path).put("text",value)
                    .put("encoding",format).put("newline",ending).put("revision",revision))
                revision = result.getString("revision"); baseline = value; savedEncoding = format; savedNewline = ending
                if (dirty()) persistDraft() else draftFile.delete()
                status(if (dirty()) "当前版本已保存，仍有新的未保存修改" else "已保存到电脑 · ${result.optInt("size")} 字节")
                if (exit && !dirty()) finish()
            } catch (e: Exception) { auto.isChecked = false; persistDraft(); throw e }
            finally { saving = false; saveButton.isEnabled = true; if (auto.isChecked && dirty()) changed() }
        }
    }

    private fun saveAs() {
        if (!loaded || saving || loading) return
        val target = styleInput(EditText(this).apply { setSingleLine(); setText(path + ".copy.txt") })
        AlertDialog.Builder(this).setTitle("另存为电脑文件").setMessage("目标必须不存在，不覆盖已有文件。").setView(target)
            .setNegativeButton("取消",null).setPositiveButton("保存副本") { _,_ ->
                val destination = target.text.toString().trim(); val value = text.text.toString(); val format = selectedEncoding(); val ending = selectedNewline()
                task { pinnedCall(origin,"files",JSONObject().put("action","text_create").put("path",destination).put("text",value).put("encoding",format).put("newline",ending)); status("副本已保存：$destination") }
            }.show()
    }

    private fun persistDraft(): Boolean {
        if (!loaded) return false
        if (!dirty()) return runCatching { !draftFile.exists() || draftFile.delete() }.getOrDefault(false)
        return runCatching {
            val value = JSONObject().put("path",path).put("computer",computer).put("text",text.text.toString()).put("baseline",baseline).put("revision",revision)
                .put("encoding",selectedEncoding()).put("newline",selectedNewline()).put("savedEncoding",savedEncoding).put("savedNewline",savedNewline)
            val file = draftFile; val temp = File(file.parentFile,file.name + ".tmp"); temp.writeText(value.toString()); if (!temp.renameTo(file)) { temp.delete(); error("无法保存手机草稿") }
            true
        }.onFailure { status("手机草稿保存失败：${it.message}",true) }.getOrDefault(false)
    }
    override fun onPause() { autosave?.cancel(); persistDraft(); super.onPause() }
    override fun navigateBack() { onBackPressedDispatcher.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) { persistDraft(); outState.putString("path",path); super.onSaveInstanceState(outState) }
}

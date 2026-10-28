package com.aicode.lancmd

import android.os.Bundle
import android.text.InputFilter
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

/** 浏览与编辑电脑注册表，并可恢复修改备份。界面使用 miuix。 */
class RegistryActivity : MiuixActivity() {
    private val roots = listOf("HKCU", "HKLM", "HKCR", "HKU", "HKCC")
    private val types = listOf("REG_SZ", "REG_EXPAND_SZ", "REG_DWORD", "REG_QWORD", "REG_MULTI_SZ", "REG_BINARY")

    internal class Value(val name: String, val type: String, val preview: String)

    private var rootIndex by mutableIntStateOf(0)
    private var viewIndex by mutableIntStateOf(0)
    private var pathInput by mutableStateOf("")
    private var location by mutableStateOf("")
    internal var keys by mutableStateOf<List<String>>(emptyList())
    internal var values by mutableStateOf<List<Value>>(emptyList())
    private var moreKeysAvailable by mutableStateOf(false)
    private var moreValuesAvailable by mutableStateOf(false)
    internal var busy by mutableStateOf(false)

    private var current = JSONObject().put("root", "HKCU").put("path", "").put("view", 64)
    private var keyOffset = 0
    private var valueOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status("直接管理电脑注册表；写入使用接收端已有权限，不自动提升权限。修改值前会备份到电脑。")
        content { Screen() }
        browse(current)
    }

    @Composable
    private fun Screen() = Page("注册表编辑器") {
        SectionCard("位置") {
            ChoiceField(
                items = roots,
                title = "根键",
                selectedIndex = rootIndex,
                onSelectedIndexChange = { rootIndex = it }
            )
            ChoiceField(
                items = listOf("64 位视图", "32 位视图"),
                title = "注册表视图",
                selectedIndex = viewIndex,
                onSelectedIndexChange = { viewIndex = it }
            )
            Field(label = "子项路径，如 Software", value = pathInput, onValueChange = { pathInput = it })
            ActionRow {
                Action("打开", enabled = !busy, primary = true, modifier = Modifier.weight(1f)) {
                    browse(
                        JSONObject().put("root", roots[rootIndex])
                            .put("path", pathInput.trim().trim('\\'))
                            .put("view", if (viewIndex == 0) 64 else 32)
                    )
                }
                Action("上一级", enabled = !busy, modifier = Modifier.weight(1f)) {
                    browse(
                        JSONObject(current.toString())
                            .put("path", current.optString("path").substringBeforeLast('\\', ""))
                    )
                }
                Action("刷新", enabled = !busy, modifier = Modifier.weight(1f)) { browse(current) }
            }
            if (location.isNotBlank()) Note(location)
            ActionRow {
                Action("新建子项", enabled = !busy, modifier = Modifier.weight(1f)) { createKey() }
                Action("删除空项", enabled = !busy, modifier = Modifier.weight(1f)) { deleteKey() }
            }
            ActionRow {
                Action("新建值", enabled = !busy, modifier = Modifier.weight(1f)) { editValue(null) }
                Action("修改备份", enabled = !busy, modifier = Modifier.weight(1f)) { history() }
            }
        }
        SectionCard("子项") {
            if (keys.isEmpty()) EmptyNote("此项下没有子项。")
            keys.forEach { name ->
                RowItem(name, enabled = !busy) {
                    browse(
                        JSONObject(current.toString()).put(
                            "path",
                            listOf(current.getString("path"), name).filter { it.isNotEmpty() }.joinToString("\\")
                        )
                    )
                }
            }
            if (moreKeysAvailable) Action("加载更多子项", enabled = !busy) { browse(current, moreKeys = true) }
        }
        SectionCard("值") {
            if (values.isEmpty()) EmptyNote("此项下没有值。")
            values.forEach { item ->
                RowItem(
                    title = "${item.name.ifEmpty { "(默认)" }} · ${item.type}",
                    summary = item.preview,
                    enabled = !busy
                ) { editValue(item.name, JSONObject(current.toString())) }
            }
            if (moreValuesAvailable) Action("加载更多值", enabled = !busy) { browse(current, moreValues = true) }
        }
    }

    internal fun browse(target: JSONObject, moreKeys: Boolean = false, moreValues: Boolean = false) {
        if (busy) return
        busy = true
        val request = JSONObject(target.toString())
        if (!moreKeys && !moreValues) { keyOffset = 0; valueOffset = 0 }
        request.put("key_offset", keyOffset).put("value_offset", valueOffset)
        task {
            try {
                val result = pinnedCall(origin, "registry", request)
                current = JSONObject().put("root", result.getString("root"))
                    .put("path", result.getString("path")).put("view", result.getInt("view"))
                rootIndex = roots.indexOf(current.getString("root")).coerceAtLeast(0)
                viewIndex = if (current.getInt("view") == 32) 1 else 0
                pathInput = current.getString("path")
                location = "${current.getString("root")}\\${current.getString("path")} · ${current.getInt("view")} 位"
                if (!moreValues) {
                    val list = result.getJSONArray("keys")
                    val page = (0 until list.length()).map { list.getString(it) }
                    keys = if (moreKeys) keys + page else page
                    keyOffset = result.getInt("key_offset") + list.length()
                    moreKeysAvailable = keyOffset < result.getInt("key_count")
                }
                if (!moreKeys) {
                    val list = result.getJSONArray("values")
                    val page = (0 until list.length()).map { index ->
                        val item = list.getJSONObject(index)
                        Value(item.getString("name"), item.getString("type"), item.getString("preview"))
                    }
                    values = if (moreValues) values + page else page
                    valueOffset = result.getInt("value_offset") + list.length()
                    moreValuesAvailable = valueOffset < result.getInt("value_count")
                }
                status("${result.getInt("key_count")} 个子项 · ${result.getInt("value_count")} 个值")
            } finally { busy = false }
        }
    }

    private fun createKey() {
        val target = JSONObject(current.toString())
        val name = dialogInput(hint = "子项名称")
        WorkspaceDialog.Builder(this).setTitle("新建注册表子项")
            .setMessage("${target.getString("root")}\\${target.getString("path")}")
            .setView(name)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                task {
                    pinnedCall(origin, "registry", target.put("action", "create_key").put("child", name.text.toString()))
                    browse(current)
                }
            }.show()
    }

    private fun deleteKey() {
        val target = JSONObject(current.toString())
        WorkspaceDialog.Builder(this).setTitle("删除当前空项？")
            .setMessage("${target.getString("root")}\\${target.getString("path")}\n\n只允许删除不含子项和值的空项，不递归删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                task {
                    pinnedCall(origin, "registry", target.put("action", "delete_key"))
                    browse(
                        JSONObject(current.toString())
                            .put("path", current.getString("path").substringBeforeLast('\\', ""))
                    )
                }
            }.show()
    }

    internal fun editValue(existing: String?, target: JSONObject = JSONObject(current.toString())) {
        if (busy) return
        task {
            val loaded = if (existing != null)
                pinnedCall(origin, "registry", JSONObject(target.toString()).put("action", "get").put("name", existing))
            else null
            if (loaded != null && loaded.optString("type") !in types) {
                status("不支持编辑 ${loaded.optString("type")} 类型", true)
                return@task
            }
            val box = dialogBox()
            val name = dialogInput(
                hint = "值名称（留空为默认值）",
                text = existing.orEmpty(),
                enabled = existing == null
            )
            val type = dialogSpinner(types, types.indexOf(loaded?.optString("type")).coerceAtLeast(0))
            val value = dialogInput(singleLine = false).apply {
                gravity = android.view.Gravity.TOP
                minLines = 3
                maxLines = 10
                filters = arrayOf(InputFilter.LengthFilter(128 * 1024))
                contentDescription = "注册表值数据"
                hint = "整数可用 0x；二进制用十六进制；多字符串每行一项"
                setText(
                    if (loaded?.optString("type") == "REG_MULTI_SZ")
                        loaded.getJSONArray("data").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }
                    else loaded?.optString("data").orEmpty()
                )
            }
            box.addView(name); box.addView(type); box.addView(value)
            val dialog = WorkspaceDialog.Builder(this@RegistryActivity)
                .setTitle(if (existing == null) "新建注册表值" else "编辑注册表值")
                .setMessage("${target.getString("root")}\\${target.getString("path")}\n请核对路径、类型和数据；修改会先保存恢复备份。")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .apply {
                    if (existing != null) setNeutralButton("删除这个值") { _, _ ->
                        WorkspaceDialog.Builder(this@RegistryActivity).setTitle("删除注册表值？")
                            .setMessage(existing.ifEmpty { "(默认)" })
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                task {
                                    pinnedCall(
                                        origin, "registry",
                                        JSONObject(target.toString()).put("action", "delete_value")
                                            .put("name", existing).put("revision", loaded!!.getString("revision"))
                                    )
                                    browse(current)
                                }
                            }.show()
                    }
                }.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val format = types[type.selectedItemPosition]
                    val payload = JSONObject().put("type", format).put(
                        "data",
                        if (format == "REG_MULTI_SZ")
                            JSONArray(value.text.toString().split('\n').dropLastWhile { it.isEmpty() })
                        else value.text.toString()
                    )
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    task {
                        try {
                            pinnedCall(
                                origin, "registry",
                                JSONObject(target.toString()).put("action", "set")
                                    .put("name", name.text.toString()).put("value", payload)
                                    .put("revision", loaded?.getString("revision") ?: "missing")
                            )
                            dialog.dismiss()
                            browse(current)
                        } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                    }
                }
            }
            dialog.show()
        }
    }

    internal fun history() = task {
        val result = pinnedCall(origin, "registry", JSONObject().put("action", "history"))
        val items = result.getJSONArray("items")
        if (items.length() == 0) { status("暂无值修改备份"); return@task }
        val labels = (0 until items.length()).map {
            val record = items.getJSONObject(it)
            val date = java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date((record.optDouble("created") * 1000).toLong()))
            "${record.getString("root")}\\${record.getString("path")}\n" +
                "${record.getString("name").ifEmpty { "(默认)" }} · ${record.getInt("view")} 位\n" +
                "$date · ${record.getString("id").takeLast(6)}"
        }
        WorkspaceDialog.Builder(this@RegistryActivity).setTitle("最近 100 次值修改备份")
            .setItems(labels.toTypedArray()) { _, index ->
                val item = items.getJSONObject(index)
                WorkspaceDialog.Builder(this@RegistryActivity).setTitle("恢复到该次修改前？")
                    .setMessage("${labels[index]}\n当前值必须仍与该次修改结果相同，外部变化时停止恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复") { _, _ ->
                        task {
                            pinnedCall(
                                origin, "registry",
                                JSONObject().put("action", "restore").put("backup_id", item.getString("id"))
                            )
                            browse(current)
                        }
                    }.show()
            }.show()
    }
}

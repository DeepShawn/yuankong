package com.aicode.lancmd

import android.os.Bundle
import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONArray
import org.json.JSONObject

class RegistryActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val roots=listOf("HKCU","HKLM","HKCR","HKU","HKCC")
    private val types=listOf("REG_SZ","REG_EXPAND_SZ","REG_DWORD","REG_QWORD","REG_MULTI_SZ","REG_BINARY")
    private lateinit var root: Spinner
    private lateinit var view: Spinner
    private lateinit var path: EditText
    private lateinit var currentLabel: android.widget.TextView
    private lateinit var keys: LinearLayout
    private lateinit var values: LinearLayout
    private var current=JSONObject().put("root","HKCU").put("path","").put("view",64)
    private var keyOffset=0
    private var valueOffset=0
    private var busy=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("注册表编辑器","直接管理电脑注册表；写入使用接收端已有权限，不自动提升权限。修改值前会备份到电脑。",scrollable=true)
        val locate=panel("位置")
        val controls=LinearLayout(this)
        root=styleSpinner(Spinner(this).apply { adapter=ArrayAdapter(this@RegistryActivity,android.R.layout.simple_spinner_dropdown_item,roots) })
        view=styleSpinner(Spinner(this).apply { adapter=ArrayAdapter(this@RegistryActivity,android.R.layout.simple_spinner_dropdown_item,listOf("64 位视图","32 位视图")) })
        controls.addView(root,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(4) })
        controls.addView(view,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(4) })
        locate.addView(fieldRow("根键与视图",controls))
        path=styleInput(EditText(this).apply { hint="子项路径，如 Software";setSingleLine();contentDescription="注册表子项路径" })
        locate.addView(fieldRow("子项路径",path))
        row(locate,listOf("打开" to { browse(JSONObject().put("root",roots[root.selectedItemPosition]).put("path",path.text.toString().trim().trim('\\')).put("view",if(view.selectedItemPosition==0)64 else 32)) },
            "上一级" to { browse(JSONObject(current.toString()).put("path",current.optString("path").substringBeforeLast('\\',""))) },"刷新" to { browse(current) }))
        currentLabel=label("",Ui.TEXT_CAPTION,R.color.accent).apply {
            setTextIsSelectable(true);setBackgroundResource(R.drawable.status_bg);setPadding(dp(12),dp(8),dp(12),dp(8))
        }
        locate.addView(currentLabel)
        row(locate,listOf("新建子项" to { createKey() },"删除空项" to { deleteKey() }))
        row(locate,listOf("新建值" to { editValue(null) },"修改备份" to { history() }))
        keys=panel();values=panel()
        browse(current)
    }
    private fun row(parent: LinearLayout,items: List<Pair<String,()->Unit>>) {
        val row=LinearLayout(this)
        items.forEach { (name,action)->row.addView(button(name,action).apply { textSize=12f;minWidth=0 },LinearLayout.LayoutParams(0,-2,1f)) };parent.addView(row)
    }
    private fun browse(location: JSONObject,moreKeys: Boolean=false,moreValues: Boolean=false) {
        if(busy)return
        busy=true
        val target=JSONObject(location.toString())
        if(!moreKeys && !moreValues){keyOffset=0;valueOffset=0}
        target.put("key_offset",keyOffset).put("value_offset",valueOffset)
        task { try {
            val result=pinnedCall(origin,"registry",target)
            current=JSONObject().put("root",result.getString("root")).put("path",result.getString("path")).put("view",result.getInt("view"))
            root.setSelection(roots.indexOf(current.getString("root")).coerceAtLeast(0))
            view.setSelection(if(current.getInt("view")==32)1 else 0)
            path.setText(current.getString("path"))
            currentLabel.text="${current.getString("root")}\\${current.getString("path")} · ${current.getInt("view")} 位"
            if(!moreKeys && !moreValues){keys.removeAllViews();values.removeAllViews();keys.addView(label("子项",16f));values.addView(label("值",16f))}
            if(moreKeys)itRemoveLast(keys)
            if(moreValues)itRemoveLast(values)
            if(!moreValues) {
                val list=result.getJSONArray("keys")
                for(i in 0 until list.length()) {
                    val name=list.getString(i);val destination=JSONObject(current.toString()).put("path",listOf(current.getString("path"),name).filter { it.isNotEmpty() }.joinToString("\\"))
                    keys.addView(button(name) { browse(destination) })
                }
                keyOffset=result.getInt("key_offset")+list.length()
                if(keyOffset<result.getInt("key_count"))keys.addView(button("加载更多子项") { browse(current,moreKeys=true) })
            }
            if(!moreKeys) {
                val list=result.getJSONArray("values")
                for(i in 0 until list.length()) {
                    val item=list.getJSONObject(i)
                    val name=item.getString("name")
                    val location=JSONObject(current.toString())
                    values.addView(button("${name.ifEmpty { "(默认)" }} · ${item.getString("type")}\n${item.getString("preview")}") { editValue(name,location) }.apply { maxLines=4;textSize=12f;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL })
                }
                valueOffset=result.getInt("value_offset")+list.length()
                if(valueOffset<result.getInt("value_count"))values.addView(button("加载更多值") { browse(current,moreValues=true) })
            }
            status("${result.getInt("key_count")} 个子项 · ${result.getInt("value_count")} 个值")
        } finally { busy=false } }
    }
    private fun itRemoveLast(parent: LinearLayout) { if(parent.childCount>0)parent.removeViewAt(parent.childCount-1) }
    private fun createKey() {
        val location=JSONObject(current.toString());val name=styleInput(EditText(this).apply { setSingleLine();hint="子项名称" })
        AlertDialog.Builder(this).setTitle("新建注册表子项").setMessage("${location.getString("root")}\\${location.getString("path")}").setView(name)
            .setNegativeButton("取消",null).setPositiveButton("创建") { _,_-> task {
                pinnedCall(origin,"registry",location.put("action","create_key").put("child",name.text.toString()));browse(current)
            } }.show()
    }
    private fun deleteKey() {
        val location=JSONObject(current.toString())
        AlertDialog.Builder(this).setTitle("删除当前空项？").setMessage("${location.getString("root")}\\${location.getString("path")}\n\n只允许删除不含子项和值的空项，不递归删除。")
            .setNegativeButton("取消",null).setPositiveButton("删除") { _,_-> task {
                pinnedCall(origin,"registry",location.put("action","delete_key"));browse(JSONObject(current.toString()).put("path",current.getString("path").substringBeforeLast('\\',"")))
            } }.show()
    }
    private fun editValue(existing: String?, location: JSONObject=JSONObject(current.toString())) {
        if(busy)return
        task {
            val loaded=if(existing!=null)pinnedCall(origin,"registry",JSONObject(location.toString()).put("action","get").put("name",existing)) else null
            val content=LinearLayout(this@RegistryActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
            val name=styleInput(EditText(this@RegistryActivity).apply { hint="值名称（留空为默认值）";setSingleLine();setText(existing.orEmpty());isEnabled=existing==null })
            val type=styleSpinner(Spinner(this@RegistryActivity).apply { adapter=ArrayAdapter(this@RegistryActivity,android.R.layout.simple_spinner_dropdown_item,types);setSelection(types.indexOf(loaded?.optString("type")).coerceAtLeast(0)) })
            if(loaded!=null && loaded.optString("type") !in types){status("不支持编辑 ${loaded.optString("type")} 类型",true);return@task}
            val value=styleInput(EditText(this@RegistryActivity).apply {
                gravity=android.view.Gravity.TOP;minLines=3;maxLines=10;filters=arrayOf(InputFilter.LengthFilter(128*1024));contentDescription="注册表值数据"
                hint="整数可用 0x；二进制用十六进制；多字符串每行一项"
                setText(if(loaded?.optString("type")=="REG_MULTI_SZ")loaded.getJSONArray("data").let { a->(0 until a.length()).joinToString("\n") { a.getString(it) } } else loaded?.optString("data").orEmpty())
            })
            content.addView(name);content.addView(type);content.addView(value)
            val dialog=AlertDialog.Builder(this@RegistryActivity).setTitle(if(existing==null)"新建注册表值" else "编辑注册表值")
                .setMessage("${location.getString("root")}\\${location.getString("path")}\n请核对路径、类型和数据；修改会先保存恢复备份。").setView(content)
                .setNegativeButton("取消",null).setPositiveButton("保存",null)
                .apply { if(existing!=null)setNeutralButton("删除这个值") { _,_->
                    AlertDialog.Builder(this@RegistryActivity).setTitle("删除注册表值？").setMessage(existing.ifEmpty { "(默认)" }).setNegativeButton("取消",null).setPositiveButton("删除") { _,_->
                        task { pinnedCall(origin,"registry",JSONObject(location.toString()).put("action","delete_value").put("name",existing).put("revision",loaded!!.getString("revision")));browse(current) }
                    }.show()
                } }.create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val format=types[type.selectedItemPosition]
                val payload=JSONObject().put("type",format).put("data",if(format=="REG_MULTI_SZ")JSONArray(value.text.toString().split('\n').dropLastWhile { it.isEmpty() }) else value.text.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                task { try {
                    pinnedCall(origin,"registry",JSONObject(location.toString()).put("action","set").put("name",name.text.toString()).put("value",payload).put("revision",loaded?.getString("revision")?:"missing"))
                    dialog.dismiss();browse(current)
                } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true } }
            } };dialog.show()
        }
    }
    private fun history()=task {
        val result=pinnedCall(origin,"registry",JSONObject().put("action","history"));val items=result.getJSONArray("items")
        if(items.length()==0){status("暂无值修改备份");return@task}
        val labels=(0 until items.length()).map {
            val r=items.getJSONObject(it)
            val date=java.text.DateFormat.getDateTimeInstance().format(java.util.Date((r.optDouble("created")*1000).toLong()))
            "${r.getString("root")}\\${r.getString("path")}\n${r.getString("name").ifEmpty { "(默认)" }} · ${r.getInt("view")} 位\n$date · ${r.getString("id").takeLast(6)}"
        }
        AlertDialog.Builder(this@RegistryActivity).setTitle("最近 100 次值修改备份").setItems(labels.toTypedArray()) { _,index->
            val item=items.getJSONObject(index)
            AlertDialog.Builder(this@RegistryActivity).setTitle("恢复到该次修改前？").setMessage("${labels[index]}\n当前值必须仍与该次修改结果相同，外部变化时停止恢复。")
                .setNegativeButton("取消",null).setPositiveButton("恢复") { _,_-> task {
                    pinnedCall(origin,"registry",JSONObject().put("action","restore").put("backup_id",item.getString("id")));browse(current)
                } }.show()
        }.show()
    }
}

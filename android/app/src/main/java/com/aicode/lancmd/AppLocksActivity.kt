package com.aicode.lancmd

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject

class AppLocksActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var path: EditText
    private lateinit var entries: LinearLayout
    private lateinit var lockButton: com.google.android.material.button.MaterialButton
    private lateinit var refreshButton: com.google.android.material.button.MaterialButton
    private var busy=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("应用锁","锁定所选 EXE，限制当前 Windows 用户启动；已运行的程序继续运行。",scrollable=true)
        val box=panel("锁定新程序")
        path=styleInput(EditText(this).apply { hint="例如 C:\\Program Files\\App\\app.exe";setSingleLine();setText(intent.getStringExtra("path").orEmpty());contentDescription="应用锁 EXE 路径" })
        box.addView(fieldRow("EXE 完整路径",path))
        lockButton=primary(button("锁定这个 EXE") {
            if(busy)return@button
            val selected=path.text.toString().trim()
            AlertDialog.Builder(this).setTitle("限制此程序启动？").setMessage("$selected\n\n通过文件执行权限限制当前 Windows 用户。原权限保存在电脑，解锁时恢复。不会结束正在运行的程序；不能锁定 Windows 系统目录和 LanCmd 自身。")
                .setNegativeButton("取消",null).setPositiveButton("锁定") { _,_-> mutate(JSONObject().put("action","lock").put("path",selected)) }.show()
        })
        box.addView(lockButton)
        box.addView(caption("需要修改文件权限的权限，通常需管理员接收端。限制针对所选文件；复制、重装或替换后的程序需重新选择。尝试启动被锁文件时，Windows 可能显示拒绝访问提示。"))
        refreshButton=button("刷新锁定列表") { refresh() }
        body.addView(refreshButton,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(Ui.GAP) })
        entries=panel();refresh()
    }
    private fun mutate(params: JSONObject) {
        if(busy)return
        setBusy(true)
        task { try { pinnedCall(origin,"app_locks",params,60_000);status("操作完成") } finally { setBusy(false) };refresh() }
    }
    private fun refresh() {
        if(busy)return
        setBusy(true)
        task { try {
            val result=pinnedCall(origin,"app_locks",timeout=60_000)
            entries.removeAllViews()
            val items=result.getJSONArray("items")
            entries.addView(Ui.sectionLabel(this@AppLocksActivity,"已记录的应用锁"))
            if(items.length()==0)entries.addView(emptyState("暂无应用锁，可在文件管理的 EXE 菜单中选择「应用锁」。"))
            for(i in 0 until items.length()) {
                val item=items.getJSONObject(i);val state=item.getString("status")
                val description=when(state) { "locked"->"已限制启动";"unlocked"->"已恢复原权限";"missing"->"文件已不存在";"replaced"->"文件已被替换";else->"权限发生外部变化" }
                if(i>0)entries.addView(divider(),Ui.dividerParams(this@AppLocksActivity))
                entries.addView(listRow(item.getString("path").substringAfterLast('\\'),item.getString("path"),icon=R.drawable.ic_lock,
                    trailing=Ui.text(this@AppLocksActivity,description,Ui.TEXT_LABEL,if(state=="locked")R.color.warn else R.color.dim).apply {
                        setBackgroundResource(R.drawable.pill_bg);setPadding(dp(10),dp(4),dp(10),dp(4))
                    }))
                if(item.has("error"))entries.addView(label(item.getString("error"),Ui.TEXT_CAPTION,R.color.danger))
                entries.addView(actions(
                    button("解锁") { mutate(JSONObject().put("action","unlock").put("lock_id",item.getString("id"))) }.apply { isEnabled=state=="locked" },
                    button("移除记录") { mutate(JSONObject().put("action","forget").put("lock_id",item.getString("id"))) }.apply { isEnabled=state in listOf("unlocked","missing","replaced") }))
            }
            entries.addView(divider(),Ui.dividerParams(this@AppLocksActivity))
            entries.addView(label("恢复备份：${result.getString("backup")}\n锁定期间请保留此文件。权限被外部修改时会停止恢复。",Ui.TEXT_LABEL,R.color.dim).apply { setTextIsSelectable(true) })
        } finally { setBusy(false) } }
    }
    private fun setBusy(value: Boolean) {
        busy=value
        lockButton.isEnabled=!value;refreshButton.isEnabled=!value
    }
}

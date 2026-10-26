package com.aicode.lancmd

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class AppsActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var refresh: MaterialButton
    private lateinit var items: LinearLayout
    private var busy = false
    private val actions = ArrayList<MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup("运行中的桌面程序", "查看有窗口的桌面应用，切换、最小化或正常关闭", scrollable = true)
        origin
        refresh = button("刷新程序列表") { load() }
        body.addView(refresh)
        items = panel()
        load()
    }

    private fun load(params: JSONObject = JSONObject()) {
        if (busy) return
        busy = true; refresh.isEnabled = false; actions.forEach { it.isEnabled = false }
        task {
            try {
                if (params.length() > 0) pinnedCall(origin, "apps", params)
                val result = pinnedCall(origin, "apps")
                items.removeAllViews(); actions.clear()
                val list = result.getJSONArray("items")
                for (i in 0 until list.length()) {
                    val app = list.getJSONObject(i)
                    items.addView(label(app.optString("title"), 15f).apply { setPadding(0, dp(14), 0, 0) })
                    items.addView(label("${app.optString("name")} · PID ${app.optInt("pid")}" +
                        if (app.optBoolean("minimized")) " · 已最小化" else "", 12f, R.color.dim))
                    val row = LinearLayout(this@AppsActivity)
                    listOf("切换到前台" to "activate", "最小化" to "minimize", "关闭" to "close").forEach { (title, action) ->
                        val change = JSONObject().put("action", action).put("handle", app.getString("handle")).put("pid", app.getInt("pid"))
                        val key = button(title) {
                            if (action == "close") AlertDialog.Builder(this@AppsActivity).setTitle("关闭 ${app.optString("title")}？")
                                .setMessage("向此窗口发送正常关闭请求，未保存内容由该程序处理。")
                                .setNegativeButton("取消", null).setPositiveButton("关闭") { _, _ -> load(change) }.show()
                            else load(change)
                        }.apply { textSize = 12f; minWidth = 0 }
                        actions.add(key); row.addView(key, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    items.addView(row)
                }
                status(if (list.length() == 0) "没有可管理的桌面窗口" else "${list.length()} 个桌面窗口；关闭请求不会强制结束进程")
            } finally { busy = false; refresh.isEnabled = true }
        }
    }
}

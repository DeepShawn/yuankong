package com.aicode.lancmd

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONArray
import org.json.JSONObject

class BrowserActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val prefs by lazy { getSharedPreferences("browser_links",MODE_PRIVATE) }
    private lateinit var address: EditText
    private lateinit var browser: Spinner
    private lateinit var links: LinearLayout
    private var ids=listOf("default")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("电脑浏览器","发送 HTTP / HTTPS 网址，由电脑浏览器打开。浏览器窗口会按打开网页的正常行为显示。",scrollable=true)
        val box=panel("发送网址")
        address=styleInput(EditText(this).apply { hint="https://example.com";setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;contentDescription="电脑浏览器网址" })
        browser=styleSpinner(Spinner(this).apply { adapter=ArrayAdapter(this@BrowserActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Windows 默认浏览器")) })
        box.addView(fieldRow("网址",address))
        box.addView(fieldRow("使用浏览器",browser))
        box.addView(primary(button("在电脑打开网址") { open(address.text.toString()) }))
        box.addView(actions(
            button("刷新已安装浏览器") { discover() },
            button("保存当前网址") { saveLink() }))
        links=panel();render();discover()
    }
    private fun discover()=task {
        val result=pinnedCall(origin,"browser");val items=result.getJSONArray("items")
        ids=listOf("default")+(0 until items.length()).map { items.getJSONObject(it).getString("id") }
        browser.adapter=ArrayAdapter(this@BrowserActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Windows 默认浏览器")+(0 until items.length()).map { items.getJSONObject(it).getString("name") })
        status("已读取 ${items.length()} 个注册浏览器")
    }
    private fun normalized(value: String): String {
        val input=value.trim()
        return if(input.contains("://"))input else "https://$input"
    }
    private fun open(value: String) {
        val url=normalized(value)
        task { pinnedCall(origin,"browser",JSONObject().put("action","open").put("url",url).put("browser",ids.getOrElse(browser.selectedItemPosition){"default"}));status("已发送到电脑：$url") }
    }
    private fun saved()=runCatching { JSONArray(prefs.getString("links","[]")) }.getOrElse { JSONArray() }
    private fun saveLink() {
        val value=normalized(address.text.toString())
        val parsed=android.net.Uri.parse(value)
        if(parsed.scheme !in listOf("https","http") || parsed.host.isNullOrEmpty()) { address.error="请输入 HTTP / HTTPS 网址";return }
        val data=saved();if(data.length()>=50){status("最多保存 50 个网址",true);return}
        val name=styleInput(EditText(this).apply { setSingleLine();setText(parsed.host) })
        AlertDialog.Builder(this).setTitle("网址名称").setView(name).setNegativeButton("取消",null).setPositiveButton("保存") { _,_->
            data.put(JSONObject().put("name",name.text.toString().take(80)).put("url",value));prefs.edit().putString("links",data.toString()).apply();render()
        }.show()
    }
    private fun render() {
        links.removeAllViews();links.addView(Ui.sectionLabel(this,"手机保存的网址"))
        val data=saved()
        for(i in 0 until data.length()) {
            val item=data.getJSONObject(i)
            if(i>0)links.addView(divider(),Ui.dividerParams(this))
            links.addView(listRow(item.getString("name"),item.getString("url"),icon=R.drawable.ic_browser))
            links.addView(actions(
                button("打开") { address.setText(item.getString("url"));open(item.getString("url")) },
                danger(button("删除") { data.remove(i);prefs.edit().putString("links",data.toString()).apply();render() })))
        }
        if(data.length()==0)links.addView(emptyState("暂无保存的网址，可在上方填入后点「保存当前网址」。"))
    }
}

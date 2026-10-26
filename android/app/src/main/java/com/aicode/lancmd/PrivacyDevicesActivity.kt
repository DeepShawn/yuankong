package com.aicode.lancmd

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import org.json.JSONObject

class PrivacyDevicesActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var items: LinearLayout
    private lateinit var refreshButton: com.google.android.material.button.MaterialButton
    private val controls=ArrayList<Pair<com.google.android.material.button.MaterialButton,Boolean>>()
    private var busy=false
    private var admin=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("隐私设备 · 摄像头与麦克风","管理设备启停及麦克风静音。设备启用仍受 Windows 隐私权限、物理开关和其他策略限制。",scrollable=true)
        refreshButton=button("刷新设备状态") { refresh() };body.addView(refreshButton)
        body.addView(label("停用影响电脑所有应用，正在录制时可能导致录制失败；设备启停需管理员权限，停用状态可在重启后保留。系统隐私提示和设备指示灯继续由系统及硬件管理。",12f,R.color.dim))
        items=panel();refresh()
    }
    private fun operate(params:JSONObject) {
        if(busy)return
        setBusy(true)
        task { try { pinnedCall(origin,"privacy_devices",params,45_000);status("操作完成") } finally { setBusy(false) };refresh() }
    }
    private fun refresh() {
        if(busy)return
        setBusy(true)
        task { try {
            val result=pinnedCall(origin,"privacy_devices",timeout=45_000);admin=result.optBoolean("admin");val list=result.getJSONArray("items")
            controls.clear();items.removeAllViews();items.addView(label(if(admin)"接收端：管理员" else "接收端：标准权限（可尝试麦克风静音，启停需管理员）",12f,R.color.dim))
            if(list.length()==0)items.addView(label("未找到可管理的摄像头或麦克风端点。请检查驱动、设备连接和物理开关。",13f,R.color.dim))
            for(i in 0 until list.length()) {
                val item=list.getJSONObject(i);val enabled=item.optBoolean("enabled");val mic=item.getString("kind")=="microphone"
                items.addView(label("${if(mic)"麦克风" else "摄像头"} · ${item.getString("name")}",15f))
                items.addView(label("${if(enabled)"设备已启用" else "设备未启用"} · 驱动问题代码 ${item.optInt("problem")}",12f,if(enabled)R.color.accent else R.color.dim))
                if(item.has("error"))items.addView(label(item.getString("error"),12f,R.color.danger))
                val row=LinearLayout(this@PrivacyDevicesActivity)
                row.addView(control(if(enabled)"停用设备" else "启用设备",admin && item.optInt("problem") in listOf(0,22)) {
                    AlertDialog.Builder(this@PrivacyDevicesActivity).setTitle(if(enabled)"停用此设备？" else "启用此设备？")
                        .setMessage("${item.getString("name")}\n\n${if(enabled)"将影响所有应用，正在使用设备的录制可能失败。" else "启用后仍需符合 Windows 隐私权限；不会自动授予应用访问权限。"}")
                        .setNegativeButton("取消",null).setPositiveButton("确认") { _,_->operate(JSONObject().put("action","set_enabled").put("device_id",item.getString("id")).put("enabled",!enabled)) }.show()
                },LinearLayout.LayoutParams(0,-2,1f))
                if(mic)row.addView(control(if(item.optBoolean("muted"))"取消静音" else "静音麦克风",enabled && item.has("muted")) {
                    operate(JSONObject().put("action","mute").put("device_id",item.getString("id")).put("muted",!item.optBoolean("muted")))
                },LinearLayout.LayoutParams(0,-2,1f))
                items.addView(row)
                if(item.has("audio_error"))items.addView(label(item.getString("audio_error"),12f,R.color.dim))
                items.addView(label("",7f))
            }
            status("已读取 ${list.length()} 个设备")
        } finally { setBusy(false) } }
    }
    private fun control(name:String,allowed:Boolean,action:()->Unit)=button(name,action).apply {
        controls.add(this to allowed);isEnabled=allowed && !busy
    }
    private fun setBusy(value:Boolean) {
        busy=value;refreshButton.isEnabled=!value
        controls.forEach { (button,allowed)->button.isEnabled=allowed && !value }
    }
}

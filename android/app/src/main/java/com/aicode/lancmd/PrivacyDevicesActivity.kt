package com.aicode.lancmd

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class PrivacyDevicesActivity : FeatureActivity() {
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var items:LinearLayout
    private lateinit var summary:android.widget.TextView
    private lateinit var refreshButton:MaterialButton
    private val controls=ArrayList<Pair<MaterialButton,Boolean>>()
    private val feedback=linkedMapOf<String,Pair<String,Boolean>>()
    private val filterButtons=ArrayList<MaterialButton>()
    private var devices=listOf<JSONObject>()
    private var selectedKind="all"
    private var busy=false
    private var admin=false
    private var lastNotice=""
    private var lastNoticeError=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);origin
        selectedKind=savedInstanceState?.getString("kind") ?: "all"
        setup("隐私设备 · 摄像头与麦克风","读取设备状态…",scrollable=true)
        val row=LinearLayout(this)
        listOf("全部" to "all","摄像头" to "camera","麦克风" to "microphone").forEach { (name,kind)->
            row.addView(button(name) { selectedKind=kind;render() }.apply { contentDescription="筛选$name";filterButtons.add(this) },LinearLayout.LayoutParams(0,-2,1f))
        };body.addView(row)
        refreshButton=button("刷新设备状态") { refresh() };body.addView(refreshButton)
        summary=label("",12f,R.color.dim);body.addView(summary)
        body.addView(label("停用前会释放 LanCmd 的相关共享和录制；仍会影响其他应用。设备启停需管理员，驱动可能要求重启。系统隐私提示与指示灯由系统和硬件管理。",12f,R.color.dim).apply { setPadding(dp(4),dp(8),dp(4),dp(12)) })
        items=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };body.addView(items)
        refresh()
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("kind",selectedKind);super.onSaveInstanceState(out) }
    private fun operate(params:JSONObject) {
        if(busy)return
        val id=params.getString("device_id")
        feedback[id]="正在执行，请稍候…" to false;render();setBusy(true)
        task {
            try {
                val result=pinnedCall(origin,"privacy_devices",params,90_000)
                val released=result.optJSONObject("released")
                val count=(released?.optInt("camera_sessions") ?: 0)+(released?.optInt("recordings") ?: 0)
                val message=(if(count>0)"已释放 $count 项共享/录制；" else "")+result.optString("notice",if(result.optBoolean("unchanged"))"设备已处于所选状态" else "操作完成")
                lastNoticeError=result.optBoolean("pending_reboot");feedback[id]=message to lastNoticeError;lastNotice=message
            } catch(e:CancellationException){throw e}
            catch(e:Exception){feedback[id]=(e.message ?: "设备操作失败") to true;lastNotice=e.message.orEmpty();lastNoticeError=true}
            finally { setBusy(false);render() }
            refresh()
        }
    }
    private fun refresh() {
        if(busy)return
        setBusy(true)
        task { try {
            val result=pinnedCall(origin,"privacy_devices",timeout=45_000);admin=result.optBoolean("admin")
            val list=result.getJSONArray("items");devices=(0 until list.length()).map { list.getJSONObject(it) }
            render();status(lastNotice.ifBlank { "已读取 ${list.length()} 个设备" },lastNoticeError)
        } finally { setBusy(false) } }
    }
    private fun render() {
        if(!::items.isInitialized)return
        controls.clear();items.removeAllViews()
        val cameras=devices.count { it.optString("kind")=="camera" };val microphones=devices.size-cameras
        summary.text="${if(admin)"管理员接收端" else "标准权限 · 启停需要管理员"} · $cameras 个摄像头 / $microphones 个麦克风"
        filterButtons.forEachIndexed { i,b->b.strokeColor=android.content.res.ColorStateList.valueOf(color(if(listOf("all","camera","microphone")[i]==selectedKind)R.color.accent else R.color.outline_soft)) }
        val shown=devices.filter { selectedKind=="all" || it.optString("kind")==selectedKind }
        if(shown.isEmpty())items.addView(label("此分类没有可管理的设备，请刷新或检查驱动与连接。",13f,R.color.dim))
        for(item in shown) {
            val enabled=item.optBoolean("enabled");val mic=item.optString("kind")=="microphone";val id=item.getString("id")
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));setBackgroundResource(R.drawable.action_bg) }
            card.addView(label("${if(mic)"麦克风" else "摄像头"} · ${item.getString("name")}",15f))
            card.addView(label(when { item.optInt("problem")==22->"已停用";enabled->"已启用";else->"驱动状态异常 · ${item.optInt("problem")}" },12f,if(enabled)R.color.accent else R.color.dim).apply { setPadding(0,dp(6),0,dp(8)) })
            val row=LinearLayout(this)
            row.addView(control(if(enabled)"停用设备" else "启用设备",admin && !item.has("error") && item.optInt("problem") in listOf(0,22)) {
                AlertDialog.Builder(this).setTitle(if(enabled)"停用此设备？" else "启用此设备？")
                    .setMessage("${item.getString("name")}\n\n"+if(enabled)"将先停止 LanCmd 相关共享/录制并等待释放设备，再停用选中的设备；会影响其他应用。不会停用父控制器。" else "启用后仍受 Windows 隐私权限与物理开关限制，不会自动授予应用权限。")
                    .setNegativeButton("取消",null).setPositiveButton("确认") { _,_->operate(JSONObject().put("action","set_enabled").put("device_id",id).put("enabled",!enabled)) }.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            if(mic)row.addView(control(if(item.optBoolean("muted"))"取消静音" else "静音麦克风",enabled && item.has("muted")) {
                operate(JSONObject().put("action","mute").put("device_id",id).put("muted",!item.optBoolean("muted")))
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            feedback[id]?.let { (message,failed)->card.addView(label(message,12f,if(failed)R.color.danger else R.color.accent).apply { setTextIsSelectable(true);setPadding(dp(4),dp(8),dp(4),dp(8));contentDescription="设备操作结果 $id" }) }
            val detail=label("设备编号：$id\n驱动问题代码：${item.optInt("problem")}\n${item.optString("error")}\n${item.optString("audio_error")}",12f,R.color.dim).apply { visibility=View.GONE;setTextIsSelectable(true) }
            card.addView(button("设备详情") { detail.visibility=if(detail.visibility==View.VISIBLE)View.GONE else View.VISIBLE }.apply { textSize=12f })
            card.addView(detail);items.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
    }
    private fun control(name:String,allowed:Boolean,action:()->Unit)=button(name,action).apply { controls.add(this to allowed);isEnabled=allowed && !busy }
    private fun setBusy(value:Boolean) { busy=value;refreshButton.isEnabled=!value;controls.forEach { (button,allowed)->button.isEnabled=allowed && !value } }
}

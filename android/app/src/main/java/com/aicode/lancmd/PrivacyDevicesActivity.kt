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
        val group=listOf("全部" to "all","摄像头" to "camera","麦克风" to "microphone").map { (name,kind)->
            button(name) { selectedKind=kind;render() }.apply { contentDescription="筛选$name";filterButtons.add(this) }
        }
        body.addView(actions(*group.toTypedArray()))
        refreshButton=button("刷新设备状态") { refresh() }
        body.addView(refreshButton,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        summary=label("",Ui.TEXT_CAPTION,R.color.dim).apply {
            setBackgroundResource(R.drawable.status_bg);setPadding(dp(12),dp(8),dp(12),dp(8))
        }
        body.addView(summary,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(Ui.GAP) })
        body.addView(caption("停用前会释放 LanCmd 的相关共享和录制；仍会影响其他应用。设备启停需管理员，驱动可能要求重启。系统隐私提示与指示灯由系统和硬件管理。"),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(Ui.GAP) })
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
        filterButtons.forEachIndexed { i,b->
            val chosen=listOf("all","camera","microphone")[i]==selectedKind
            b.strokeColor=android.content.res.ColorStateList.valueOf(color(if(chosen)R.color.accent else R.color.outline_soft))
            b.backgroundTintList=android.content.res.ColorStateList.valueOf(color(if(chosen)R.color.accent_dim else R.color.surface_variant))
            b.setTextColor(color(if(chosen)R.color.accent else R.color.dim))
        }
        val shown=devices.filter { selectedKind=="all" || it.optString("kind")==selectedKind }
        if(shown.isEmpty())items.addView(emptyState("此分类没有可管理的设备，请刷新或检查驱动与连接。"))
        for(item in shown) {
            val enabled=item.optBoolean("enabled");val mic=item.optString("kind")=="microphone";val id=item.getString("id")
            val card=Ui.Column(this).apply { setPadding(dp(Ui.PAD_CARD),dp(14),dp(Ui.PAD_CARD),dp(14));setBackgroundResource(R.drawable.action_bg) }
            val state=when { item.optInt("problem")==22->"已停用";enabled->"已启用";else->"驱动异常 ${item.optInt("problem")}" }
            card.addView(listRow(item.getString("name"),if(mic)"麦克风" else "摄像头",
                icon=if(mic)R.drawable.ic_message else R.drawable.ic_camera,
                trailing=Ui.text(this,state,Ui.TEXT_LABEL,if(enabled)R.color.ok else R.color.dim).apply {
                    setBackgroundResource(R.drawable.pill_bg);setPadding(dp(10),dp(4),dp(10),dp(4))
                }))
            val keys=ArrayList<MaterialButton>()
            keys.add(control(if(enabled)"停用设备" else "启用设备",admin && !item.has("error") && item.optInt("problem") in listOf(0,22)) {
                AlertDialog.Builder(this).setTitle(if(enabled)"停用此设备？" else "启用此设备？")
                    .setMessage("${item.getString("name")}\n\n"+if(enabled)"将先停止 LanCmd 相关共享/录制并等待释放设备，再停用选中的设备；会影响其他应用。不会停用父控制器。" else "启用后仍受 Windows 隐私权限与物理开关限制，不会自动授予应用权限。")
                    .setNegativeButton("取消",null).setPositiveButton("确认") { _,_->operate(JSONObject().put("action","set_enabled").put("device_id",id).put("enabled",!enabled)) }.show()
            }.also { if(enabled)danger(it) })
            if(mic)keys.add(control(if(item.optBoolean("muted"))"取消静音" else "静音麦克风",enabled && item.has("muted")) {
                operate(JSONObject().put("action","mute").put("device_id",id).put("muted",!item.optBoolean("muted")))
            })
            card.addView(actions(*keys.toTypedArray()))
            feedback[id]?.let { (message,failed)->card.addView(label(message,Ui.TEXT_CAPTION,if(failed)R.color.danger else R.color.accent).apply {
                setTextIsSelectable(true);setBackgroundResource(R.drawable.status_bg);setPadding(dp(10),dp(8),dp(10),dp(8));contentDescription="设备操作结果 $id"
            }) }
            val detail=label("设备编号：$id\n驱动问题代码：${item.optInt("problem")}\n${item.optString("error")}\n${item.optString("audio_error")}",Ui.TEXT_CAPTION,R.color.dim).apply { visibility=View.GONE;setTextIsSelectable(true) }
            card.addView(button("设备详情") { detail.visibility=if(detail.visibility==View.VISIBLE)View.GONE else View.VISIBLE }.apply { textSize=Ui.TEXT_CAPTION })
            card.addView(detail);items.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
    }
    private fun control(name:String,allowed:Boolean,action:()->Unit)=button(name,action).apply { controls.add(this to allowed);isEnabled=allowed && !busy }
    private fun setBusy(value:Boolean) { busy=value;refreshButton.isEnabled=!value;controls.forEach { (button,allowed)->button.isEnabled=allowed && !value } }
}

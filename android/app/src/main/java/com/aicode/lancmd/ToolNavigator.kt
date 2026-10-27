package com.aicode.lancmd

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/** Shared destinations and search terms for the home menu and every feature page. */
object ToolNavigator {
    data class Tool(val group:String,val title:String,val detail:String,val icon:Int,val target:Class<*>,val kind:String="")
    val tools=listOf(
        Tool("共享与控制","远程桌面","屏幕、鼠标、触控与键盘",R.drawable.ic_desktop,DesktopActivity::class.java),
        Tool("共享与控制","摄像头共享","查看电脑摄像头画面",R.drawable.ic_camera,MediaActivity::class.java,"camera"),
        Tool("共享与控制","虚拟键盘","组合键、Win 键与文字输入",R.drawable.ic_keyboard,ControlActivity::class.java,"input"),
        Tool("共享与控制","发送消息","自定义标题和内容",R.drawable.ic_message,ControlActivity::class.java,"message"),
        Tool("文件与设备","文件管理","文档、WPS、多选、最后修改日期与占位文件",R.drawable.ic_folder,StorageActivity::class.java),
        Tool("文件与设备","U盘与移动设备","访问策略、安全弹出、断开与恢复",R.drawable.ic_usb,DevicesActivity::class.java),
        Tool("文件与设备","摄像头与麦克风","设备启停、麦克风静音与驱动状态",R.drawable.ic_camera,PrivacyDevicesActivity::class.java),
        Tool("系统与应用","任务管理器","多选结束进程、内存和 CPU",R.drawable.ic_process,TaskManagerActivity::class.java),
        Tool("系统与应用","桌面窗口","切换、最小化和正常关闭窗口",R.drawable.ic_desktop,AppsActivity::class.java),
        Tool("系统与应用","应用锁","限制指定应用启动与恢复",R.drawable.ic_lock,AppLocksActivity::class.java),
        Tool("系统与应用","注册表编辑器","浏览、编辑和恢复备份",R.drawable.ic_terminal,RegistryActivity::class.java),
        Tool("系统与应用","浏览器","向电脑发送网址",R.drawable.ic_browser,BrowserActivity::class.java),
        Tool("录制与自动化","录音与录屏","麦克风、屏幕和摄像头视频录制",R.drawable.ic_camera,RecordingsActivity::class.java),
        Tool("录制与自动化","手势回放","手势板、保存和延迟播放",R.drawable.ic_keyboard,GesturesActivity::class.java),
        Tool("录制与自动化","延迟命令","按顺序执行命令列表",R.drawable.ic_history,CommandsActivity::class.java)
    )
    fun show(activity:AppCompatActivity) {
        fun dp(v:Int)=(v*activity.resources.displayMetrics.density).toInt()
        val dialog=BottomSheetDialog(activity)
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(16));setBackgroundColor(Themes.color(activity,R.color.bg)) }
        body.addView(TextView(activity).apply { text="全部工具";textSize=20f;setTextColor(Themes.color(activity,R.color.text));setPadding(dp(4),0,0,dp(12)) })
        val search=EditText(activity).apply { hint="搜索工具、文档、设备或操作";contentDescription="搜索全部工具";setSingleLine();textSize=14f;minHeight=dp(48);setTextColor(Themes.color(activity,R.color.text));setHintTextColor(Themes.color(activity,R.color.dim));setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundResource(R.drawable.input_bg) }
        body.addView(search)
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        body.addView(ScrollView(activity).apply { addView(list);isFillViewport=false },LinearLayout.LayoutParams(-1,0,1f))
        fun render() {
            list.removeAllViews();val query=search.text.toString().trim()
            val matches=tools.filter { (it.group+it.title+it.detail).contains(query,true) }
            for((group,items) in matches.groupBy { it.group }) {
                list.addView(TextView(activity).apply { text=group;textSize=12f;setTextColor(Themes.color(activity,R.color.accent));setPadding(dp(4),dp(14),0,dp(4));androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
                for(tool in items)list.addView(MaterialButton(activity).apply {
                    text=tool.title+"\n"+tool.detail;textSize=13f;isAllCaps=false;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight=dp(64);cornerRadius=dp(14);setPadding(dp(14),dp(8),dp(10),dp(8));setIconResource(tool.icon);iconTint=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.accent))
                    backgroundTintList=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.surface_variant));setTextColor(Themes.color(activity,R.color.text));contentDescription=tool.title
                    setOnClickListener { dialog.dismiss();activity.startActivity(Intent(activity,tool.target).putExtra("kind",tool.kind)) }
                },LinearLayout.LayoutParams(-1,-2))
            }
            if(matches.isEmpty())list.addView(TextView(activity).apply { text="没有匹配的工具，请换个关键词";setTextColor(Themes.color(activity,R.color.dim));setPadding(dp(8),dp(20),dp(8),dp(20)) })
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:Editable?){}
        })
        body.addView(MaterialButton(activity).apply { text="文件操作延迟 · ${FileDelay.seconds(activity)} 秒";minHeight=dp(48);isAllCaps=false;backgroundTintList=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.surface_variant));setTextColor(Themes.color(activity,R.color.accent));setOnClickListener { FileDelay.choose(activity) { text="文件操作延迟 · ${FileDelay.seconds(activity)} 秒" } } })
        val height=(activity.resources.displayMetrics.heightPixels*.82f).toInt()
        dialog.setContentView(body,android.view.ViewGroup.LayoutParams(-1,height));dialog.behavior.skipCollapsed=true;dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        render();dialog.show()
    }
}

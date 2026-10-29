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
        Tool("文件与设备","文件搜索","全盘与文件夹索引，名称、路径和通配符搜索",R.drawable.ic_folder,FileSearchActivity::class.java),
        Tool("文件与设备","U盘与移动设备","访问策略、安全弹出、断开与恢复",R.drawable.ic_usb,DevicesActivity::class.java),
        Tool("文件与设备","摄像头与麦克风","设备启停、麦克风静音与驱动状态",R.drawable.ic_camera,PrivacyDevicesActivity::class.java),
        Tool("系统与应用","任务管理器","多选结束进程、内存和 CPU",R.drawable.ic_process,TaskManagerActivity::class.java),
        Tool("系统与应用","桌面窗口","切换、最小化和正常关闭窗口",R.drawable.ic_desktop,AppsActivity::class.java),
        Tool("系统与应用","应用锁","限制指定应用启动与恢复",R.drawable.ic_lock,AppLocksActivity::class.java),
        Tool("系统与应用","应用中心","自动识别已安装程序、应用锁、冻结和解冻",R.drawable.ic_process,ApplicationsActivity::class.java),
        Tool("系统与应用","桌面背景","更换电脑壁纸和图片显示方式",R.drawable.ic_desktop,WallpaperActivity::class.java),
        Tool("系统与应用","本地升级","安装 upgrade 文件夹中的 Windows ZIP",R.drawable.ic_history,UpgradeActivity::class.java),
        Tool("系统与应用","启动与网络","开机自启、移动热点、局域网端口与重启还原说明",R.drawable.ic_history,StartupNetworkActivity::class.java),
        Tool("系统与应用","网络与蓝牙","热点密码与客户端、Wi-Fi 连接、蓝牙配对和网卡 DNS",R.drawable.ic_history,NetworkManagementActivity::class.java),
        Tool("系统与应用","注册表编辑器","浏览、编辑和恢复备份",R.drawable.ic_terminal,RegistryActivity::class.java),
        Tool("系统与应用","浏览器","向电脑发送网址",R.drawable.ic_browser,BrowserActivity::class.java),
        Tool("录制与自动化","录音与录屏","麦克风、屏幕和摄像头视频录制",R.drawable.ic_camera,RecordingsActivity::class.java),
        Tool("录制与自动化","手势回放","手势板、保存和延迟播放",R.drawable.ic_keyboard,GesturesActivity::class.java),
        Tool("录制与自动化","延迟命令","按顺序执行命令列表",R.drawable.ic_history,CommandsActivity::class.java)
    )
    fun show(activity:AppCompatActivity) {
        fun dp(v:Int)=(v*activity.resources.displayMetrics.density).toInt()
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(8)) }
        val dialog=WorkspaceDialog.Builder(activity).setTitle("工具库").setView(body).setNegativeButton("关闭",null).create()
        val search=EditText(activity).apply { hint="搜索工具、文档、设备或操作";contentDescription="搜索全部工具";setSingleLine();textSize=14f;minHeight=dp(48);setTextColor(Themes.color(activity,R.color.text));setHintTextColor(Themes.color(activity,R.color.dim));setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundResource(R.drawable.input_bg) }
        body.addView(search)
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        body.addView(ScrollView(activity).apply { addView(list);isFillViewport=false },LinearLayout.LayoutParams(-1,dp(280)))
        fun render() {
            list.removeAllViews();val query=search.text.toString().trim()
            val matches=tools.filter { (it.group+it.title+it.detail).contains(query,true) }
            for((group,items) in matches.groupBy { it.group }) {
                list.addView(TextView(activity).apply { text=group;textSize=11.5f;letterSpacing=0.05f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);setTextColor(Themes.color(activity,R.color.accent));setPadding(dp(4),dp(16),0,dp(6));androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
                for(tool in items)list.addView(MaterialButton(activity).apply {
                    text=android.text.SpannableString(tool.title+"\n"+tool.detail).apply {
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,tool.title.length,0)
                        setSpan(android.text.style.RelativeSizeSpan(0.85f),tool.title.length+1,length,0)
                        setSpan(android.text.style.ForegroundColorSpan(Themes.color(activity,R.color.dim)),tool.title.length+1,length,0)
                    };textSize=15f;isAllCaps=false;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight=dp(64);cornerRadius=dp(16);setPadding(dp(14),dp(10),dp(12),dp(10));setIconResource(tool.icon);iconTint=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.accent));iconSize=dp(22)
                    backgroundTintList=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.surface_variant));setTextColor(Themes.color(activity,R.color.text))
                    strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.outline_soft));rippleColor=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.accent_glow));contentDescription=tool.title
                    setOnClickListener { dialog.dismiss();activity.startActivity(Intent(activity,tool.target).putExtra("kind",tool.kind)) }
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
            }
            if(matches.isEmpty())list.addView(TextView(activity).apply { text="没有匹配的工具，请换个关键词";setTextColor(Themes.color(activity,R.color.dim));setPadding(dp(8),dp(20),dp(8),dp(20)) })
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:Editable?){}
        })
        body.addView(MaterialButton(activity).apply { text="文件操作延迟 · ${FileDelay.seconds(activity)} 秒";minHeight=dp(48);isAllCaps=false;cornerRadius=dp(14);setIconResource(R.drawable.ic_history);iconTint=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.accent));backgroundTintList=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.surface_variant));strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(Themes.color(activity,R.color.outline_soft));setTextColor(Themes.color(activity,R.color.accent));setOnClickListener { FileDelay.choose(activity) { text="文件操作延迟 · ${FileDelay.seconds(activity)} 秒" } } },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        render();dialog.show()
    }
}

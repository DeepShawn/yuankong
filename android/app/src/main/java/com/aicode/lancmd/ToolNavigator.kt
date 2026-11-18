package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aicode.lancmd.glass.MiuixTheme

/** Shared destinations and search terms for the home menu and every feature page. */
object ToolNavigator {
    data class Tool(val group:String,val title:String,val detail:String,val icon:Int,val target:Class<*>,val kind:String="")
    val tools=listOf(
        Tool("连接与记录","局域网电脑","接收端列表、备注名与本地连接历史",R.drawable.ic_desktop,ComputersActivity::class.java),
        Tool("共享与控制","远程桌面","屏幕、鼠标、触控与键盘",R.drawable.ic_desktop,DesktopActivity::class.java),
        Tool("共享与控制","摄像头共享","查看电脑摄像头画面",R.drawable.ic_camera,MediaActivity::class.java,"camera"),
        Tool("共享与控制","虚拟键盘","组合键、Win 键与文字输入",R.drawable.ic_keyboard,ControlActivity::class.java,"input"),
        Tool("共享与控制","发送消息","自定义标题和内容",R.drawable.ic_message,ControlActivity::class.java,"message"),
        Tool("共享与控制","应用快捷控制","希沃白板、WPS、QQ 翻页、切换工具、发送消息与窗口隐形",R.drawable.ic_message,AppControlActivity::class.java),
        Tool("共享与控制","滚动公告","消息本地预览、拖动缩放、颜色与播放动效",R.drawable.ic_message,AnnouncementsActivity::class.java),
        Tool("录制与自动化","全局延迟","命令／操作默认延迟、待执行任务和取消",R.drawable.ic_history,DelaysActivity::class.java),
        Tool("文件与设备","文件管理","文档、WPS、多选、最后修改日期与占位文件",R.drawable.ic_folder,StorageActivity::class.java),
        Tool("文件与设备","文件密码锁","长期密码锁与按电脑时间自动解锁",R.drawable.ic_lock,FileLocksActivity::class.java),
        Tool("文件与设备","文件搜索","全盘与文件夹索引，名称、路径和通配符搜索",R.drawable.ic_search,FileSearchActivity::class.java),
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
        Tool("系统与应用","接收端自我维护","安装目录改名、自我删除与批量修改最后修改日期",R.drawable.ic_process,SelfManageActivity::class.java),
        Tool("系统与应用","公网联机","frpc 内网穿透，手机用移动网络也能连",R.drawable.ic_terminal,TunnelActivity::class.java),
        Tool("系统与应用","浏览器","向电脑发送网址",R.drawable.ic_browser,BrowserActivity::class.java),
        Tool("录制与自动化","录音与录屏","麦克风、系统声音、屏幕、摄像头和全景录制",R.drawable.ic_camera,RecordingsActivity::class.java),
        Tool("录制与自动化","全景录制","桌面、摄像头小窗、系统声音与麦克风",R.drawable.ic_camera,RecordingsActivity::class.java,"panorama"),
        Tool("录制与自动化","手势回放","手势板、保存和延迟播放",R.drawable.ic_keyboard,GesturesActivity::class.java),
        Tool("录制与自动化","延迟命令","按顺序执行命令列表",R.drawable.ic_history,CommandsActivity::class.java)
    )
    fun show(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, ToolLibraryActivity::class.java))
    }
}

class ToolLibraryActivity : MiuixActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content { Screen() }
    }

    @Composable
    private fun Screen() = FixedPage("工具库") {
        var query by rememberSaveable { mutableStateOf("") }
        var fileDelay by remember { mutableStateOf(FileDelay.label(this@ToolLibraryActivity)) }
        val listState = rememberLazyListState()
        val groups = remember(query) {
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            ToolNavigator.tools.filter { tool ->
                val searchable = listOf(tool.group, tool.title, tool.detail).joinToString(" ")
                terms.all { searchable.contains(it, ignoreCase = true) }
            }.groupBy { it.group }
        }

        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Field(
                    label = "搜索全部工具",
                    value = query,
                    onValueChange = {
                        query = it
                        listState.requestScrollToItem(0)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotEmpty()) Action("清空") {
                    query = ""
                    listState.requestScrollToItem(0)
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            if (groups.isEmpty()) item { EmptyNote("没有匹配的工具，请换个关键词。") }
            groups.forEach { (group, tools) ->
                item(key = group) {
                    SectionCard(group) {
                        tools.forEachIndexed { index, tool ->
                            if (index > 0) {
                                Spacer(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    .height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
                            }
                            RowItem(tool.title, tool.detail, leadingIcon = tool.icon) {
                                startActivity(Intent(this@ToolLibraryActivity, tool.target).putExtra("kind", tool.kind))
                                finish()
                            }
                        }
                    }
                }
            }
        }

        Action("文件操作延迟 · $fileDelay", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            FileDelay.choose(this@ToolLibraryActivity) { fileDelay = FileDelay.label(this@ToolLibraryActivity) }
        }
    }
}

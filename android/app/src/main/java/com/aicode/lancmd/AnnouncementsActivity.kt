package com.aicode.lancmd

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperSwitch
import java.math.BigDecimal

class AnnouncementsActivity:MiuixActivity() {
    private val prefs by lazy{getSharedPreferences("announcements",MODE_PRIVATE)}
    internal var config by mutableStateOf(announcementDefaults())
    private var overrideDelay by mutableStateOf("")
    internal lateinit var preview:AnnouncementPreview
    internal var job by mutableStateOf<JSONObject?>(null)
    internal var busy by mutableStateOf(false)
    private var supported by mutableStateOf(false)
    private var confirmation by mutableStateOf(false)
    private var poll:Job?=null
    private var tab by mutableIntStateOf(0)
    internal var expanded by mutableStateOf(false)
    private var previewState by mutableStateOf("stopped")
    private var previewZoom by mutableIntStateOf(1)
    private var resize by mutableStateOf(false)
    private var layoutSupported by mutableStateOf(false)
    private var layoutReady by mutableStateOf(false)
    private var areaLoading by mutableStateOf(false)
    private var areaLabel by mutableStateOf("示意画布 1920 × 1080；连接新版接收端后读取主屏可用区域")
    private lateinit var back:OnBackPressedCallback
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        config=runCatching{JSONObject(state?.getString("config") ?: prefs.getString("draft",null) ?: announcementDefaults().toString())}.getOrDefault(announcementDefaults())
        if(config.optString("placement")=="custom" && !runCatching{AnnouncementGeometry.fromJson(config.getJSONObject("rect")).valid()}.getOrDefault(false)){
            config.put("placement","preset").put("rect",JSONObject.NULL)
        }
        overrideDelay=state?.getString("delay") ?: prefs.getString("delay","").orEmpty()
        expanded=state?.getBoolean("expanded")?:false
        tab=state?.getInt("tab")?.coerceIn(0,3)?:0
        resize=expanded && state?.getBoolean("resize")==true
        preview=AnnouncementPreview(this).apply{
            config=this@AnnouncementsActivity.config
            resizeMode=resize
            onEdit={this@AnnouncementsActivity.config=it}
            onPlaybackState={previewState=it}
        }
        back=object:OnBackPressedCallback(expanded){override fun handleOnBackPressed(){expand(false)}}
        onBackPressedDispatcher.addCallback(this,back)
        status("先在手机预览；发送后才会在 Windows 显示。电脑端可点 × 或按 Esc 关闭。")
        content{Screen()}
        task {
            if(origin==null){status("当前离线：可在手机编辑和播放预览，连接电脑后再发送");return@task}
            val caps=pinnedCall(origin,"capabilities").optJSONArray("features")
            supported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="announcements"}
            check(supported){"请完整更新 Windows 接收端以使用滚动公告"}
            layoutSupported=caps!=null && (0 until caps.length()).any{caps.optString(it)=="announcement_layout"}
            if(layoutSupported)try{readArea()}catch(e:CancellationException){throw e}catch(e:Exception){status("未读到电脑尺寸，当前为示意画布；请重试读取尺寸",true)}
            refresh();startPoll()
        }
    }
    internal fun change(key:String,value:Any){config=JSONObject(config.toString()).put(key,value);preview.config=config}
    internal fun preset(index:Int) {
        val text=config.optString("text")
        config=announcementDefaults().put("text",text)
        if(index==1)config.put("mode","popup").put("motion","static").put("position","center").put("shape","rounded").put("background","#14344E").put("duration",30).put("repeats",0)
        if(index==2)config.put("mode","sticky").put("motion","static").put("position","bottom").put("shape","rounded").put("background","#FFF8E1").put("foreground","#2A303B").put("accent","#B56C16").put("repeats",0)
        preview.config=config
    }
    @Composable private fun Screen(){
        if(expanded)Editor() else Compact()
        if(confirmation)ConfirmAction("在电脑显示此公告？",config.optString("text")+"\n将使用已编辑的位置与尺寸，替换本手机当前公告；电脑端可以关闭。",onDismiss={confirmation=false},onConfirm={confirmation=false;sendNow()})
    }
    @Composable private fun Compact()=FixedPage("滚动消息与公告") {
        SectionCard {
            HostedView(preview,Modifier.fillMaxWidth().height(175.dp))
            ActionRow {
                Action(if(previewState=="playing")"暂停预览" else "播放预览",modifier=Modifier.weight(1f)){togglePreview()}
                Action("停止预览",modifier=Modifier.weight(1f)){preview.stopPreview()}
                Action("展开编辑",modifier=Modifier.weight(1f)){expand(true)}
            }
            Note("按住消息移动；展开可四角缩放。$areaLabel。字体渲染以 Windows 为准。")
            job?.let{InfoItem("电脑公告状态",stateLabel(it)+" · 已播放 ${it.optInt("cycles")} 次")}
            ActionRow {
                Action("发送／更新",enabled=supported && !busy,primary=true,modifier=Modifier.weight(1f)){confirmSend()}
                Action(if(job?.optString("status")=="paused")"继续" else "暂停",enabled=!busy && job?.optString("status") in listOf("playing","paused"),modifier=Modifier.weight(1f)){
                    control(if(job?.optString("status")=="paused")"resume" else "pause")}
                Action("撤回",enabled=!busy && job?.optString("status") in listOf("starting","playing","paused"),modifier=Modifier.weight(1f)){control("stop")}
            }
            ChoiceField(listOf("内容与预设","位置与尺寸","颜色与形状","播放与延迟"),"设置分区",tab){tab=it}
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                item {
                    if(tab==0)SectionCard("内容与预设") {
                        ActionRow {listOf("经典跑马灯","全局弹窗","桌面常驻").forEachIndexed{i,label->Action(label,modifier=Modifier.weight(1f)){preset(i)}}}
                        Field("消息前缀（可留空）",config.optString("prefix"),{change("prefix",it.take(30))})
                        Field("公告内容",config.optString("text"),{change("text",it.take(2000))},singleLine=false,maxLines=6)
                        ChoiceField(listOf("跑马灯","全局弹窗","桌面常驻"),"显示方式",listOf("ticker","popup","sticky").indexOf(config.optString("mode")).coerceAtLeast(0)){
                            change("mode",listOf("ticker","popup","sticky")[it])}
                        Note("经典预设参考公开校园通知样式；不附加 LanCmd 字样。草稿与样式在手机保存，不会自动重新发送。")
                    }
                    if(tab==1)SectionCard("位置与尺寸") {
                        InfoItem("当前布局",boxLabel())
                        ChoiceField(listOf("顶部","居中","底部"),"恢复预设位置",listOf("top","center","bottom").indexOf(config.optString("position")).coerceAtLeast(0)){
                            presetPosition(listOf("top","center","bottom")[it])}
                        if(config.optString("placement","preset")!="custom")numeric("预设宽度百分比（30–100）","width")
                        ActionRow {
                            Action("展开拖动缩放",modifier=Modifier.weight(1f)){expand(true)}
                            Action("精确位置与大小",modifier=Modifier.weight(1f)){editBounds()}
                        }
                        Action("读取电脑尺寸",enabled=layoutSupported && !busy && !areaLoading){task{readArea()}}
                        Note("使用电脑主屏的可用区域，不覆盖任务栏。尺寸至少为 160 × 64 个 Windows 逻辑像素；窗口和关闭按钮始终留在可用区域内。")
                    }
                    if(tab==2)SectionCard("颜色与形状") {
                        for((key,label) in listOf("background" to "背景颜色","foreground" to "文字颜色","accent" to "图标／前缀颜色"))Field("$label（#RRGGBB）",config.optString(key),{change(key,it.uppercase().take(7))})
                        ActionRow {listOf("深蓝" to "#081424","蓝色" to "#1769AA","红色" to "#A62B36","米白" to "#FFF8E1").forEach{(name,color)->Action(name,modifier=Modifier.weight(1f)){change("background",color);change("foreground",if(name=="米白")"#2A303B" else "#FFFFFF")}}}
                        ChoiceField(listOf("直角横条","圆角矩形","胶囊"),"形状",listOf("rectangle","rounded","pill").indexOf(config.optString("shape")).coerceAtLeast(0)){change("shape",listOf("rectangle","rounded","pill")[it])}
                        numeric("字号（16–96）","font_size");numeric("整体不透明度（30–100）","opacity")
                        ChoiceField(listOf("微软雅黑 UI","微软雅黑","黑体","宋体","Segoe UI"),"电脑字体",listOf("Microsoft YaHei UI","Microsoft YaHei","SimHei","SimSun","Segoe UI").indexOf(config.optString("font")).coerceAtLeast(0)){
                            change("font",listOf("Microsoft YaHei UI","Microsoft YaHei","SimHei","SimSun","Segoe UI")[it])}
                        SuperSwitch(checked=config.optBoolean("bold"),onCheckedChange={change("bold",it)},title="粗体")
                    }
                    if(tab==3)SectionCard("播放与延迟") {
                        ChoiceField(listOf("向左滚动","向右滚动","向上滚动","向下滚动","往返移动","静止显示","淡入淡出"),"流动方式",listOf("left","right","up","down","bounce","static","fade").indexOf(config.optString("motion")).coerceAtLeast(0)){
                            change("motion",listOf("left","right","up","down","bounce","static","fade")[it])}
                        numeric("速度（20–400 像素／秒，按系统缩放）","speed")
                        numeric("播放次数（0 不限，最多 100）","repeats");numeric("显示秒数（0 不限，最多 3600）","duration")
                        Field("本条延迟秒数（留空跟随全局，0 立即）",overrideDelay,{overrideDelay=it.filter(Char::isDigit).take(5)})
                        Note("次数和时长先到者结束；静止／淡入淡出每 5 秒算一次。暂停不计播放时长。手机断线后继续，接收端退出会结束公告；修改样式后点发送／更新才应用到电脑。")
                    }
                }
            }
        }
    }
    @Composable private fun Editor()=FixedPage("公告预览编辑") {
        if(LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE)Row(Modifier.fillMaxWidth().weight(1f)){
            HostedView(preview,Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState())){EditorControls()}
        }else{
            HostedView(preview,Modifier.fillMaxWidth().weight(1f))
            EditorControls()
        }
    }
    @Composable private fun EditorControls()=SectionCard {
        ActionRow {
            SelectionAction("移动消息",selected=!resize,modifier=Modifier.weight(1f)){resize=false;preview.resizeMode=false}
            SelectionAction("四角缩放",selected=resize,modifier=Modifier.weight(1f)){resize=true;preview.resizeMode=true}
        }
        InfoItem("${if(previewState=="playing")"本地播放中" else if(previewState=="paused")"本地已暂停" else if(previewState=="completed")"预览完成" else "位置编辑"}",boxLabel())
        ActionRow {
            Action(if(previewState=="playing")"暂停预览" else "播放预览",modifier=Modifier.weight(1f)){togglePreview()}
            Action("停止预览",modifier=Modifier.weight(1f)){preview.stopPreview()}
        }
        ActionRow {
            Action("从头播放",modifier=Modifier.weight(1f)){preview.restartPreview()}
            Action("预览 ${previewZoom}×",modifier=Modifier.weight(1f)){previewZoom=if(previewZoom==3)1 else previewZoom+1;preview.zoom=previewZoom.toFloat()}
        }
        ActionRow {
            Action("精确位置",modifier=Modifier.weight(1f)){editBounds()}
            Action("读取电脑尺寸",enabled=layoutSupported && !areaLoading && !busy,modifier=Modifier.weight(1f)){task{readArea()}}
        }
        ActionRow {
            Action("完成编辑",primary=true,modifier=Modifier.weight(1f)){expand(false)}
            Action("发送／更新",enabled=supported && !busy,modifier=Modifier.weight(1f)){confirmSend()}
        }
        Note("$areaLabel。预览放大只影响手机显示。移动、缩放和播放不发送消息，确认发送后才显示到电脑。")
    }
    internal fun expand(value:Boolean){
        expanded=value;back.isEnabled=value
        if(!value){resize=false;preview.resizeMode=false;previewZoom=1;preview.zoom=1f}
    }
    override fun onBack(){if(expanded)expand(false)else super.onBack()}
    internal fun togglePreview(){if(previewState=="playing")preview.pausePreview()else preview.playPreview()}
    internal fun setBox(box:AnnouncementGeometry.Box){config=AnnouncementGeometry.custom(config,box);preview.config=config}
    private fun presetPosition(position:String){config=JSONObject(config.toString()).put("position",position).put("placement","preset").put("rect",JSONObject.NULL);preview.config=config}
    private fun boxLabel():String {
        val b=AnnouncementGeometry.normalized(config,preview.area)
        return "左 ${b.x/10f}% · 顶 ${b.y/10f}% · 宽 ${b.width/10f}% · 高 ${b.height/10f}%"
    }
    private suspend fun readArea(){
        if(areaLoading)return
        areaLoading=true;layoutReady=false
        try{
            val a=pinnedCall(origin,"announcements",JSONObject().put("action","layout")).getJSONObject("area")
            preview.area=AnnouncementGeometry.Area(a.getInt("width"),a.getInt("height"),a.getInt("dpi"),a.getInt("x"),a.getInt("y"))
            preview.zoom=1f;previewZoom=1
            layoutReady=true;areaLabel="主屏可用区域 ${preview.area.width} × ${preview.area.height} · ${preview.area.dpi} DPI"
        }catch(e:Exception){areaLabel="未确认当前电脑尺寸，暂用示意画布";throw e}finally{areaLoading=false}
    }
    private fun editBounds(){
        val b=AnnouncementGeometry.normalized(config,preview.area)
        val values=listOf(b.x,b.y,b.width,b.height)
        val labels=listOf("左侧位置（%）","顶部位置（%）","宽度（%）","高度（%）")
        val inputs=labels.mapIndexed{i,label->dialogInput(hint=label,text=(values[i]/10f).toString()).apply{
            contentDescription=label;inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters=arrayOf(android.text.InputFilter.LengthFilter(6))}}
        val box=dialogBox();inputs.forEach{box.addView(it)}
        val d=WorkspaceDialog.Builder(this).setTitle("精确位置与尺寸").setMessage("按电脑可用区域的百分比填写，最多 1 位小数；窗口必须完整留在区域内。")
            .setView(box).focusInput(inputs.first(),selectAll=true).setNegativeButton("取消",null).setPositiveButton("应用到预览",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val parsed=inputs.map{runCatching{BigDecimal(it.text.toString().trim()).multiply(BigDecimal.TEN).intValueExact()}.getOrNull()}
            if(parsed.any{it==null}){inputs.first().error="请输入有效百分比，最多 1 位小数";return@setOnClickListener}
            val result=AnnouncementGeometry.Box(parsed[0]!!,parsed[1]!!,parsed[2]!!,parsed[3]!!)
            if(!result.valid()){inputs.first().error="位置与尺寸超出可用区域，最小值为 1%";return@setOnClickListener}
            setBox(result);d.dismiss()
        }};d.show()
    }
    @Composable private fun numeric(label:String,key:String){Field(label,config.optString(key),{change(key,it.filter(Char::isDigit).take(5))})}
    internal fun confirmSend(){if(config.optString("text").isBlank()){status("请输入公告内容",true);return};confirmation=true}
    internal fun sendNow() {
        if(busy)return
        if(config.optString("placement")=="custom" && (!layoutSupported || !layoutReady)){
            status("自定义位置需要完整更新 Windows 3.15.0 或更新版，并成功读取电脑尺寸",true);return
        }
        val params=JSONObject().put("action","show").put("config",JSONObject(config.toString()).apply {
            for(key in listOf("font_size","width","opacity","speed","repeats","duration"))put(key,optString(key).toIntOrNull() ?: -1)
        })
        val ranges=mapOf("font_size" to 16..96,"width" to 30..100,"opacity" to 30..100,"speed" to 20..400,"repeats" to 0..100,"duration" to 0..3600)
        val normalized=params.getJSONObject("config")
        if(ranges.any{(key,range)->normalized.optInt(key,-1) !in range} || listOf("background","foreground","accent").any{!Regex("#[0-9a-fA-F]{6}").matches(config.optString(it))}) {
            status("请检查字号、颜色、速度、次数和时长的范围",true);return
        }
        if(overrideDelay.isNotBlank()) {
            val value=overrideDelay.toIntOrNull()
            if(value==null || value !in 0..86400){status("本条延迟须为 0–86400 秒",true);return}
            params.put("delay_override",value)
        }
        busy=true
        task{try{job=pinnedCall(origin,"announcements",params).getJSONObject("job");status("公告已提交，正在等待 Windows 显示")}finally{busy=false}}
    }
    internal fun control(action:String) {
        val ident=job?.optString("id") ?: return
        task{pinnedCall(origin,"announcements",JSONObject().put("action",action).put("job_id",ident));refresh()}
    }
    private fun stateLabel(value:JSONObject)=when(value.optString("status")){"playing"->"播放中";"paused"->"已暂停";"starting"->"正在启动";"completed"->"播放完成";"cancelled"->"已关闭";"failed"->"失败：${value.optString("error")}";else->"正在撤回"}
    private suspend fun refresh(){val result=pinnedCall(origin,"announcements");job=result.optJSONObject("job");if(result.optBoolean("occupied"))status("其他手机正在显示公告，可在电脑端关闭",true)}
    private fun startPoll(){poll?.cancel();if(supported)poll=task{while(true){delay(1000);if(!busy)refresh()}}}
    override fun onStart(){super.onStart();startPoll()}
    override fun onStop(){poll?.cancel();preview.pausePreview();prefs.edit().putString("draft",config.toString()).putString("delay",overrideDelay).apply();super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("config",config.toString());out.putString("delay",overrideDelay);out.putBoolean("expanded",expanded);out.putInt("tab",tab);out.putBoolean("resize",resize);super.onSaveInstanceState(out)}
}

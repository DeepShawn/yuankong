package com.aicode.lancmd

import android.os.Bundle
import androidx.compose.runtime.*
import org.json.JSONObject

class WallpaperActivity:MiuixActivity() {
    private var path by mutableStateOf("")
    private var current by mutableStateOf("")
    private var style by mutableStateOf("fill")
    private var busy by mutableStateOf(false)
    private var confirm by mutableStateOf(false)
    private val styles=listOf("fill","fit","stretch","center","span")
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);path=state?.getString("path") ?: intent.getStringExtra("path").orEmpty();style=state?.getString("style") ?: "fill"
        content { Page("桌面背景") {
            SectionCard("电脑当前背景") { InfoItem("当前图片",current.ifBlank{"正在读取…"}) }
            SectionCard("选择新背景") {
                Field("电脑上的 JPG、PNG 或 BMP 完整路径",path,{path=it})
                Action("浏览电脑文件夹") { RemoteDirectoryPicker(this@WallpaperActivity,origin).show("") { directory ->
                    startActivity(android.content.Intent(this@WallpaperActivity,StorageActivity::class.java).putExtra("path",directory))
                } }
                ChoiceField(listOf("填充","适应","拉伸","居中","跨屏"),"显示方式",styles.indexOf(style).coerceAtLeast(0)) { style=styles[it] }
                Action("更换电脑背景",enabled=!busy && path.isNotBlank(),primary=true){confirm=true}
                Note("也可在文件管理的图片菜单中选择「设为桌面背景」。图片会复制到电脑的壁纸目录，原文件之后移动不会影响背景。")
            }
        }
            if(confirm)ConfirmAction("更换电脑桌面背景？",path,onDismiss={confirm=false},onConfirm={confirm=false;apply()})
        };refresh()
    }
    private fun refresh()=task {
        val result=pinnedCall(origin,"wallpaper");current=result.optString("path")
    }
    private fun apply() { if(busy)return;busy=true;task {try {
        val result=pinnedCall(origin,"wallpaper",JSONObject().put("action","set").put("path",path.trim()).put("style",style))
        current=result.optString("path");status("桌面背景已更新")
    }finally{busy=false}} }
    override fun onSaveInstanceState(state:Bundle){state.putString("path",path);state.putString("style",style);super.onSaveInstanceState(state)}
}

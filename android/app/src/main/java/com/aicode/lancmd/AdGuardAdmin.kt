package com.aicode.lancmd

import android.content.Intent
import android.net.Uri
import java.net.URI

/** Only an external HTTPS admin entry; no API credentials or traffic data. */
internal object AdGuardAdmin {
    fun address(input:String):String {
        val value=input.trim()
        require(value.length in 1..2048 && value.none{it.isWhitespace() || it.isISOControl()} && '\\' !in value){"请填写有效的 HTTPS 管理地址"}
        val uri=runCatching{URI(value)}.getOrElse{throw IllegalArgumentException("管理地址格式无效")}
        require(uri.scheme.equals("https",ignoreCase=true) && !uri.host.isNullOrBlank()){"仅支持 HTTPS，请先在电脑配置管理页和受信任证书"}
        require(uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null){"地址中不能包含用户名、密码、查询参数或片段；请在浏览器登录"}
        require(uri.port == -1 || uri.port in 1..65535){"管理端口须为 1–65535"}
        require(uri.host !in listOf("0.0.0.0","[::]","::")){"请填写电脑实际地址，不能使用监听通配地址"}
        return uri.normalize().toASCIIString()
    }

    fun intent(input:String)=Intent(Intent.ACTION_VIEW,Uri.parse(address(input))).addCategory(Intent.CATEGORY_BROWSABLE)
}

package com.aicode.lancmd

import android.app.Application
import com.aicode.lancmd.net.ConnectionManager

/** 连接状态放在 Application 里，Activity 重建或退到后台都不会中断重连。 */
class LanCmdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 最先安装，后续任何崩溃都能留下记录
        CrashLog.install(this)
        ConnectionManager.start(this)
        // 常驻前台服务，避免后台被冻结导致掉线
        LanCmdService.start(this)
    }
}

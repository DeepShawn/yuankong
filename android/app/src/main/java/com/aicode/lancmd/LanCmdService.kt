package com.aicode.lancmd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status

/**
 * 常驻前台服务，保证 App 退到后台后长连接不被系统冻结。
 * 通知为最低优先级、可折叠，不打扰使用。
 */
class LanCmdService : Service(), ConnectionManager.Listener {

    companion object {
        private const val CHANNEL_ID = "lancmd_conn"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, LanCmdService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LanCmdService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var lastText = "正在连接…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ConnectionManager.addListener(this)
        ConnectionManager.start(this)
        startForeground(NOTIF_ID, buildNotification(lastText))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(lastText))
        return START_STICKY
    }

    /**
     * Android 15+ 对 dataSync 类型前台服务有每日时长上限，超时会回调这里。
     * 不实现会被系统按崩溃处理，所以这里主动降级：
     * 停掉前台状态但保留连接，通知栏提示原因。
     */
    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        lastText = "后台保活已达系统时限，连接可能被挂起"
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(lastText))
        stopForeground(STOP_FOREGROUND_DETACH)
        isRunning = false
        // 连接本身不动：App 在前台时照常可用，用户可手动重开保活
    }

    override fun onDestroy() {
        isRunning = false
        ConnectionManager.removeListener(this)
        super.onDestroy()
    }

    override fun onStatus(status: Status) {
        lastText = when (status.state) {
            State.CONNECTED -> "${status.name} · ${status.host}"
            State.CONNECTING -> "正在连接 ${status.name.ifEmpty { status.host }}…"
            State.WAITING -> "已掉线，自动重连中…"
            State.STOPPED -> "已断开"
            State.IDLE -> "正在查找电脑…"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(lastText))
    }

    override fun onEvent(text: String, kind: LogKind) {
        if (kind == LogKind.ERROR) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIF_ID, buildNotification(lastText))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "连接状态",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "保持与电脑的连接，掉线时自动重连"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LanCmd")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

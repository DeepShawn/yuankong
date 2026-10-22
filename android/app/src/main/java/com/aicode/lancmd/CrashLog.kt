package com.aicode.lancmd

import android.content.Context
import android.os.Build
import com.aicode.lancmd.net.ConnectionManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃记录。
 *
 * 侧载分发拿不到 logcat，出问题只能靠用户描述。这里把未捕获异常连同
 * 设备信息、连接状态写到 filesDir/crash/ 下，用户可以直接把文件发回来。
 * 保留最近 5 份，避免无限增长。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val KEEP = 5

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (_: Throwable) {
                // 记录失败也不能再抛，否则会掩盖原始崩溃
            }
            prev?.uncaughtException(thread, error)
        }
    }

    private fun write(app: Context, thread: Thread, error: Throwable) {
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "crash-$stamp.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== LanCmd 崩溃报告 ===")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("线程: ${thread.name}")
            pw.println("包版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("机型: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
            pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            pw.println()
            val st = ConnectionManager.snapshot()
            pw.println("连接状态: ${st.state} name=${st.name} host=${st.host}:${st.port}")
            pw.println("  重连次数=${st.reconnects} 尝试=${st.attempt} vpn=${st.vpnHint}")
            pw.println()
            pw.println("=== 堆栈 ===")
            pw.println(Log.getStackTraceString(error))
            // 双保险：手动展开 cause 链，防止某些 ROM 截断
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 5) {
                pw.println()
                pw.println("--- cause ${depth + 1}: ${cause.javaClass.name} ---")
                pw.println(Log.getStackTraceString(cause))
                cause = cause.cause
                depth++
            }
        }

        target.writeText(sw.toString())
        trim(dir)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.name.startsWith("crash-") }?.sortedBy { it.name }
            ?: return
        if (files.size > KEEP) {
            files.take(files.size - KEEP).forEach { runCatching { it.delete() } }
        }
    }

    /** 最近一份崩溃内容，用于界面展示。 */
    fun latest(ctx: Context): String? {
        val dir = File(ctx.filesDir, DIR)
        return dir.listFiles()
            ?.filter { it.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }
            ?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, DIR).listFiles()?.forEach { runCatching { it.delete() } }
    }
}

/** 只为 CrashLog 里的 getStackTraceString，避免额外引用 android.util.Log 的静态方法。 */
private object Log {
    fun getStackTraceString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }
}

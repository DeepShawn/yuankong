package com.aicode.lancmd

import android.os.Build
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

/**
 * 在 JVM 上真实跑 Activity，逐个状态驱动 UI。
 *
 * 重点是 CONNECTING：用户反馈「显示正在连接时」闪退，
 * 这条路径会在重连循环里反复触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
// 容器是 aarch64，conscrypt 只带 x86_64 的 native .so，必须关掉
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivityStateTest {

    private fun launch(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun st(
        state: State,
        name: String = "DESKTOP-TEST",
        host: String = "192.168.1.5",
        latency: Long = 12,
        attempt: Int = 0,
        retry: Int = 0,
        message: String = "",
        paired: Boolean = true,
        reconnects: Int = 0,
        vpn: Boolean = false
    ) = Status(
        state = state, name = name, host = host, port = 47823,
        latencyMs = latency, attempt = attempt, nextRetrySec = retry,
        message = message, paired = paired, reconnects = reconnects, vpnHint = vpn
    )

    @Test
    fun connectingStateDoesNotCrash() {
        val c = launch()
        val a = c.get()
        assertNotNull(a)
        // 反复进入 CONNECTING：重连循环里会一直走这条分支
        repeat(5) {
            a.onStatus(st(State.CONNECTING, latency = -1, message = "正在连接…"))
            a.onStatus(st(State.WAITING, latency = -1, attempt = it + 1, retry = 3, message = "连接失败"))
        }
        // 各种 name/host 组合，含空串
        a.onStatus(st(State.CONNECTING, name = "", host = "", latency = -1))
        a.onStatus(st(State.CONNECTING, name = "中文主机名", latency = -1))
        a.onStatus(st(State.CONNECTING, host = "fe80::1", latency = -1))
        c.pause().stop().destroy()
    }

    @Test
    fun allStatesRenderWithoutCrash() {
        val c = launch()
        val a = c.get()
        a.onStatus(st(State.CONNECTED))
        a.onStatus(st(State.CONNECTED, latency = -1))
        a.onStatus(st(State.CONNECTED, name = ""))
        a.onStatus(st(State.WAITING, latency = -1, attempt = 3, retry = 5, message = "连接失败"))
        a.onStatus(st(State.WAITING, latency = -1, reconnects = 2))
        a.onStatus(st(State.STOPPED, latency = -1))
        a.onStatus(st(State.IDLE, latency = -1))
        a.onStatus(st(State.CONNECTED))
        c.pause().stop().destroy()
    }

    @Test
    fun eventLoggingDoesNotCrash() {
        val c = launch()
        val a = c.get()
        for (kind in LogKind.values()) {
            a.onEvent("测试 $kind", kind)
            a.onEvent("", kind)
            a.onEvent("x".repeat(5000), kind)
            a.onEvent("多行\n内容\r\n带回车", kind)
            a.onEvent("emoji 🎉 和特殊字符 %s %d", kind)
        }
        c.pause().stop().destroy()
    }

    @Test
    fun statusAfterDestroyDoesNotCrash() {
        val c = launch()
        val a = c.get()
        a.onStatus(st(State.CONNECTING, latency = -1))
        c.pause().stop().destroy()
        // 后台线程仍可能推状态过来
        a.onStatus(st(State.CONNECTING, latency = -1))
        a.onStatus(st(State.WAITING, latency = -1, retry = 3))
        a.onEvent("销毁后的事件", LogKind.ERROR)
    }

    @Test
    fun recreateSurvivesPendingReconnect() {
        // 旋转屏幕/进程重建：onStart 会重新注册 listener 并拉一次快照
        val c = launch()
        c.get().onStatus(st(State.WAITING, latency = -1, attempt = 2, retry = 4))
        c.pause().stop()
        c.start().resume()
        c.get().onStatus(st(State.CONNECTING, latency = -1))
        c.pause().stop().destroy()
    }

    @Test
    fun vpnHintRenders() {
        val c = launch()
        val a = c.get()
        a.onStatus(st(State.WAITING, latency = -1, retry = 8,
            message = "没有发现电脑：VPN 可能拦截了广播", vpn = true))
        a.onStatus(st(State.CONNECTED, vpn = true))
        c.pause().stop().destroy()
    }
}

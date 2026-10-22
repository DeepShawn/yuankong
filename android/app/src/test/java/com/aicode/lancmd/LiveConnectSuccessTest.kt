package com.aicode.lancmd

import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 正向路径：接收端在线时，应能走到 CONNECTED，且全程无未捕获异常。
 * 接收端没跑时自动跳过（Assume）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveConnectSuccessTest {

    private val crashes = CopyOnWriteArrayList<Throwable>()
    private var prev: Thread.UncaughtExceptionHandler? = null

    private fun reachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 47823), 800) }
        true
    } catch (e: Exception) {
        false
    }

    @After
    fun done() {
        prev?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        ConnectionManager.stop()
    }

    @Test
    fun connectsAndReachesConnected() {
        assumeTrue("接收端未运行，跳过", reachable())
        prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            crashes.add(e)
            println("=== 未捕获异常 ${t.name}: $e")
            e.stackTrace.take(8).forEach { println("    at $it") }
        }

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Prefs.saveTarget(ctx, Target("127.0.0.1", 47823, "local", ""))

        val seen = CopyOnWriteArrayList<State>()
        val listener = object : ConnectionManager.Listener {
            override fun onStatus(status: Status) { seen.add(status.state) }
            override fun onEvent(text: String, kind: LogKind) {}
        }
        ConnectionManager.addListener(listener)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ConnectionManager.start(ctx)

        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(150)
        }
        shadowOf(Looper.getMainLooper()).idle()

        ConnectionManager.removeListener(listener)
        controller.pause().stop().destroy()

        assertTrue("应经过 CONNECTING", seen.contains(State.CONNECTING))
        assertTrue("最终应连上: $seen", seen.contains(State.CONNECTED))
        assertTrue("不应有未捕获异常: ${crashes.map { it.toString() }}", crashes.isEmpty())
    }
}

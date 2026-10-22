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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 真实连接循环 + Activity 联动。
 *
 * 用户反馈「显示正在连接时闪退」，所以要跑的是：
 * 后台线程不断 publish(CONNECTING/WAITING) → 主线程 render，
 * 而不是手工构造一个 Status 丢进去。
 *
 * 接收端没在跑时这条路径同样会走（连不上 → 无限重连），
 * 正是最容易暴露问题的场景。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveReconnectTest {

    private val crashes = CopyOnWriteArrayList<Throwable>()
    private var prevHandler: Thread.UncaughtExceptionHandler? = null

    private fun captureCrashes() {
        prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            crashes.add(e)
            println("=== 未捕获异常 线程=${t.name} ===")
            println(e.toString())
            e.stackTrace.take(12).forEach { println("    at $it") }
        }
    }

    @After
    fun restore() {
        prevHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        ConnectionManager.stop()
    }

    private fun idle() {
        // 让主线程把排队的 render 全部执行完
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(120)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun activityFollowsLiveReconnectLoop() {
        captureCrashes()
        val ctx = ApplicationProvider.getApplicationContext<Application>()

        // 指向一个没有接收端的地址：会不断 CONNECTING → WAITING → 退避重连
        Prefs.saveTarget(ctx, Target("127.0.0.1", 47899, "no-such-agent", ""))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        ConnectionManager.start(ctx)

        // 跑够几轮重连
        idle()
        Thread.sleep(2500)
        idle()
        Thread.sleep(2500)
        idle()

        val states = CopyOnWriteArrayList<State>()
        val probe = object : ConnectionManager.Listener {
            override fun onStatus(status: Status) { states.add(status.state) }
            override fun onEvent(text: String, kind: LogKind) {}
        }
        ConnectionManager.addListener(probe)
        idle()
        ConnectionManager.removeListener(probe)

        controller.pause().stop().destroy()

        assertTrue("不应出现未捕获异常: ${crashes.map { it.toString() }}", crashes.isEmpty())
    }

    @Test
    fun activityDestroyedMidReconnect() {
        captureCrashes()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Prefs.saveTarget(ctx, Target("127.0.0.1", 47899, "no-such-agent", ""))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ConnectionManager.start(ctx)
        idle()

        // 重连进行中直接销毁界面
        controller.pause().stop().destroy()
        idle()
        Thread.sleep(2000)
        idle()

        assertTrue("销毁后不应崩: ${crashes.map { it.toString() }}", crashes.isEmpty())
    }
}

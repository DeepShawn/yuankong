package com.aicode.lancmd

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.aicode.lancmd.net.ConnectionManager
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

/**
 * 服务与应用启动路径。
 *
 * 「显示正在连接时闪退」也可能发生在 MainActivity 之外：
 * 前台服务每次状态变化都会重建通知，服务被系统拒绝启动时的降级路径也很容易崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ServiceLifecycleTest {

    @Test
    fun applicationOnCreateSurvives() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app)
    }

    @Test
    fun serviceStartAndStop() {
        val controller = Robolectric.buildService(LanCmdService::class.java)
        val svc = controller.create().get()
        assertNotNull(svc)
        controller.startCommand(0, 0)
        controller.startCommand(0, 1)
        controller.destroy()
    }

    @Test
    fun serviceStopWithoutStart() {
        val controller = Robolectric.buildService(LanCmdService::class.java)
        controller.create().destroy()
    }

    @Test
    fun connectionManagerStartIsIdempotent() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        repeat(5) { ConnectionManager.start(ctx) }
        val st = ConnectionManager.snapshot()
        assertNotNull(st)
        // 不留后台线程干扰其他测试
        ConnectionManager.stop()
    }

    @Test
    fun connectionManagerRetryWithoutTarget() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ConnectionManager.start(ctx)
        repeat(3) { ConnectionManager.retryNow() }
        ConnectionManager.stop()
    }

    @Test
    fun snapshotAlwaysAvailable() {
        val st = ConnectionManager.snapshot()
        assertNotNull(st)
        assertNotNull(st.state)
    }
}

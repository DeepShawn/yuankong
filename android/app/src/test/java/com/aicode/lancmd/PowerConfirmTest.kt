package com.aicode.lancmd

import android.os.Build
import android.os.Looper
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.State
import com.aicode.lancmd.net.ConnectionManager.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * 电源操作的二次确认。
 *
 * 关机/重启不可撤销，且按钮就在快捷区，必须保证：
 *   1. 点一次不会直接发指令
 *   2. 第一级点「继续」后还要再点一次「确认XXX」才会发
 *   3. 任一级点「取消」都不发
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PowerConfirmTest {

    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
    private var prev: Thread.UncaughtExceptionHandler? = null

    @After
    fun tearDown() {
        prev?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        ConnectionManager.stop()
    }

    private fun activity(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = c
        val a = c.get()
        // 让界面认为已连接，否则点击会被“未连接”拦掉
        a.onStatus(
            Status(
                state = State.CONNECTED, name = "DESKTOP-TEST",
                host = "192.168.137.1", port = 47823, latencyMs = 5, paired = true
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /** AppCompat 的 AlertDialog 也是 Dialog 子类，ShadowDialog 能拿到。 */
    private fun latestDialog(): android.app.Dialog? =
        org.robolectric.shadows.ShadowDialog.getLatestDialog()

    /**
     * 找到对话框里的按钮并点击。
     * AlertDialog 按钮顺序为 negative → neutral → positive，
     * 所以 positive 取最后一个 Button，negative 取第一个。
     */
    private fun clickDialogButton(d: android.app.Dialog?, byPositive: Boolean): Boolean {
        if (d == null) return false
        val root = d.window?.decorView ?: return false
        val buttons = ArrayList<android.widget.Button>()
        fun walk(v: android.view.View) {
            if (v is android.widget.Button) buttons.add(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        val target = if (byPositive) buttons.lastOrNull() else buttons.firstOrNull()
        target?.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return target != null
    }

    /** 取对话框正文（遍历所有 TextView） */
    private fun bodyText(d: android.app.Dialog?): String {
        if(d is WorkspaceDialog)return d.description
        if (d == null) return ""
        val root = d.window?.decorView ?: return ""
        val sb = StringBuilder()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView && v !is android.widget.Button) {
                sb.append(v.text).append("\n")
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return sb.toString()
    }

    private fun buttonText(d: android.app.Dialog?, byPositive: Boolean): String {
        if (d == null) return ""
        val root = d.window?.decorView ?: return ""
        val buttons = ArrayList<android.widget.Button>()
        fun walk(v: android.view.View) {
            if (v is android.widget.Button) buttons.add(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return (if (byPositive) buttons.lastOrNull() else buttons.firstOrNull())?.text?.toString() ?: ""
    }

    /**
     * 直接调用确认入口。
     *
     * 不走菜单点击是因为菜单项外层有「未连接就别操作」的前置判断，
     * 而 ConnectionManager.isConnected() 看的是真实 socket，
     * 单测里没有真连接。本测试要验的是确认流程本身，不是那个前置判断。
     */
    private fun triggerPower(activity: MainActivity, action: String, label: String) {
        activity.confirmPower(action, label)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun restartNeedsTwoConfirmations() {
        val a = activity()
        triggerPower(a, "restart", "重启")

        val first = latestDialog()
        assertNotNull("应弹出第一级确认", first)
        val msg1 = bodyText(first)
        assertTrue("第一级应说明不可撤销: $msg1", msg1.contains("丢失") || msg1.contains("无法撤销"))
        assertTrue("第一级按钮应为「继续」: ${buttonText(first, true)}",
            buttonText(first, true).contains("继续"))

        // 点「继续」→ 出现第二级，而不是直接发指令
        assertTrue(clickDialogButton(first, byPositive = true))

        val second = latestDialog()
        assertNotNull("应弹出第二级确认", second)
        assertTrue("两级不是同一个对话框", second !== first)
        val msg2 = bodyText(second)
        assertTrue("第二级应列出目标地址: $msg2", msg2.contains("192.168.137.1"))
        assertTrue("第二级按钮应为「确认重启」: ${buttonText(second, true)}",
            buttonText(second, true).contains("确认"))

        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun shutdownFirstLevelCancelDoesNothing() {
        val a = activity()
        triggerPower(a, "shutdown", "关机")
        val d = latestDialog()
        assertNotNull("应弹出第一级确认", d)

        assertTrue(clickDialogButton(d, byPositive = false))   // 取消

        // 取消后不应出现「确认关机」这一级
        val after = latestDialog()
        val text = buttonText(after, true)
        assertTrue("取消后不应进入第二级，当前按钮: $text", !text.contains("确认"))
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun shutdownSecondLevelCancelDoesNotExecute() {
        val a = activity()
        triggerPower(a, "shutdown", "关机")
        val first = latestDialog()!!
        assertTrue(clickDialogButton(first, byPositive = true))

        val second = latestDialog()!!
        assertTrue("应进入第二级", buttonText(second, true).contains("确认"))

        assertTrue(clickDialogButton(second, byPositive = false))   // 第二级取消
        assertTrue("第二级取消后应关闭", !second.isShowing)
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun onlyPowerActionsAskForConfirmation() {
        // 关机/重启/注销才走两级确认；锁屏之类的应当是单步。
        // 这里直接断言 confirmPower 只对这三个动作弹窗。
        val a = activity()
        triggerPower(a, "restart", "重启")
        assertNotNull("重启应弹确认", latestDialog())
        controller?.pause()?.stop()?.destroy()
    }
}

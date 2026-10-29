package com.aicode.lancmd

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemotePointerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composeMouseHoverHidesOnlyThePictureAndSurvivesFullscreenRehosting() {
        val screen = RemoteScreenView(RuntimeEnvironment.getApplication())
        val fullscreen = mutableStateOf(false)
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            if (fullscreen.value) {
                HostedView(screen, Modifier.fillMaxSize().testTag("picture"))
            } else Column(Modifier.fillMaxSize()) {
                HostedView(screen, Modifier.fillMaxWidth().height(200.dp).testTag("picture"))
                Box(Modifier.fillMaxWidth().height(100.dp).testTag("other"))
            }
        }
        val hidden = PointerIcon.getSystemIcon(screen.context, PointerIcon.TYPE_NULL)
        val normal = PointerIcon.getSystemIcon(screen.context, PointerIcon.TYPE_DEFAULT)
        compose.onNodeWithTag("picture").performMouseInput { enter(center) }
        compose.runOnIdle { assertEquals(hidden, host.pointerIcon) }
        compose.onNodeWithTag("other").performMouseInput { moveTo(center) }
        compose.runOnIdle { assertEquals("Leaving the picture must restore the normal UI pointer", normal, host.pointerIcon) }
        compose.onNodeWithTag("other").performMouseInput { exit() }
        compose.runOnIdle { fullscreen.value = true }
        compose.onNodeWithTag("picture").performMouseInput { enter(center) }
        compose.runOnIdle { assertEquals(hidden, host.pointerIcon) }
        compose.onNodeWithTag("picture").performMouseInput { exit() }
        compose.runOnIdle { fullscreen.value = false }
        compose.onNodeWithTag("picture").performMouseInput { enter(center) }
        compose.runOnIdle { assertEquals(hidden, host.pointerIcon) }
    }

    @Test fun composeResolvesTheStylusHoverIconAsEmpty() {
        val screen = RemoteScreenView(RuntimeEnvironment.getApplication())
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            HostedView(screen, Modifier.fillMaxSize().testTag("picture"))
        }
        val point = compose.onNodeWithTag("picture").fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = point.x; y = point.y
            })
            fun event(action: Int) = MotionEvent.obtain(time, time, action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            event(MotionEvent.ACTION_HOVER_ENTER).also {
                host.dispatchGenericMotionEvent(it)
                assertEquals(PointerIcon.getSystemIcon(screen.context, PointerIcon.TYPE_NULL),
                    host.onResolvePointerIcon(it, 0))
                it.recycle()
            }
            event(MotionEvent.ACTION_HOVER_EXIT).also { host.dispatchGenericMotionEvent(it); it.recycle() }
        }
    }
}

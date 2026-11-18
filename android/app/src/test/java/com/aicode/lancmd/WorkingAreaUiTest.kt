package com.aicode.lancmd

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class WorkingAreaUiTest {
    @get:Rule val compose=createComposeRule()
    private fun height(node:SemanticsNodeInteraction)=node.getUnclippedBoundsInRoot().let{it.bottom-it.top}
    @Test fun bothPanesRemainUsableAndDividerCanEnlargeEitherPane() {
        compose.setContent {LanCmdMiuixTheme {
            val context=LocalContext.current
            val view=androidx.compose.runtime.remember(context) { View(context) }
            WorkingArea(view,Modifier.size(360.dp,600.dp)) { Action("控制操作"){} }
        }}
        val picture=compose.onNodeWithTag("working-picture");val controls=compose.onNodeWithTag("working-controls")
        val initial=height(picture)
        assertTrue(initial>200.dp);assertTrue("操作区默认至少占一半可用高度",height(controls)>=290.dp)
        val divider=compose.onNodeWithContentDescription("调整画面与操作栏比例")
        val enlargePicture=divider.fetchSemanticsNode().config[SemanticsActions.CustomActions].single{it.label=="增大画面"}.action
        compose.runOnIdle{assertTrue(enlargePicture())}
        assertTrue(height(picture)>initial)
        val enlargeControls=divider.fetchSemanticsNode().config[SemanticsActions.CustomActions].single{it.label=="增大操作栏"}.action
        compose.runOnIdle{repeat(2){assertTrue(enlargeControls())}}
        assertTrue(height(picture)<initial)
        assertTrue(height(controls)>180.dp)
    }
    @Test fun landscapeDividerCanExpandTheControlSidebar() {
        compose.setContent {LanCmdMiuixTheme {
            val context=LocalContext.current;val view=androidx.compose.runtime.remember(context){View(context)}
            WorkingArea(view,Modifier.size(700.dp,300.dp),horizontal=true){Action("控制操作") {}}
        }}
        val controls=compose.onNodeWithTag("working-controls")
        fun width()=controls.getUnclippedBoundsInRoot().let{it.right-it.left}
        val before=width()
        val enlarge=compose.onNodeWithContentDescription("调整画面与操作栏比例").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single{it.label=="增大操作栏"}.action
        compose.runOnIdle{enlarge()}
        assertTrue(width()>before)
    }
    @Test fun visuallyCompactSelectionKeepsItsTouchTarget() {
        compose.setContent {LanCmdMiuixTheme {SelectionAction("触控",selected=true){}}}
        compose.onNodeWithText("触控").assertIsSelected().assertHeightIsAtLeast(48.dp)
    }
    @Test fun actionGroupsWrapAndKeepTouchTargetsOnNarrowLargeTextLayouts() {
        var clicked = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.5f)) {
                LanCmdMiuixTheme {
                    Column(Modifier.width(300.dp)) {
                        ActionRow {
                            listOf("切换电脑", "工具库", "外观主题", "更多").forEach { label ->
                                Action(label, modifier = Modifier.weight(1f)) { clicked = label }
                            }
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithText("切换电脑").assertHeightIsAtLeast(48.dp)
        val last = compose.onNodeWithText("更多").assertHeightIsAtLeast(48.dp)
        assertTrue(last.getUnclippedBoundsInRoot().top > first.getUnclippedBoundsInRoot().top)
        last.performClick()
        assertEquals("更多", clicked)
    }
    @Test fun dividerStillDragsAfterKeyboardChangesTheDefaultPaneSize() {
        val fraction=androidx.compose.runtime.mutableFloatStateOf(.36f)
        compose.setContent {LanCmdMiuixTheme {
            val context=LocalContext.current;val view=androidx.compose.runtime.remember(context){View(context)}
            WorkingArea(view,Modifier.size(360.dp,600.dp),controlFraction=fraction.floatValue){Action("键盘操作") {}}
        }}
        compose.runOnIdle{fraction.floatValue=.66f}
        val controls=compose.onNodeWithTag("working-controls");val before=height(controls)
        compose.onNodeWithContentDescription("调整画面与操作栏比例").performTouchInput{swipeUp(durationMillis=200)}
        assertTrue(height(controls)>before)
    }
}

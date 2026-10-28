package com.aicode.lancmd

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
        assertTrue(initial>200.dp);assertTrue(height(controls)>180.dp)
        val divider=compose.onNodeWithContentDescription("调整画面与操作栏比例")
        val enlargePicture=divider.fetchSemanticsNode().config[SemanticsActions.CustomActions].single{it.label=="增大画面"}.action
        compose.runOnIdle{assertTrue(enlargePicture())}
        assertTrue(height(picture)>initial)
        val enlargeControls=divider.fetchSemanticsNode().config[SemanticsActions.CustomActions].single{it.label=="增大操作栏"}.action
        compose.runOnIdle{repeat(2){assertTrue(enlargeControls())}}
        assertTrue(height(picture)<initial)
        assertTrue(height(controls)>180.dp)
    }
    @Test fun visuallyCompactSelectionKeepsItsTouchTarget() {
        compose.setContent {LanCmdMiuixTheme {SelectionAction("触控",selected=true){}}}
        compose.onNodeWithText("触控").assertIsSelected().assertHeightIsAtLeast(48.dp)
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

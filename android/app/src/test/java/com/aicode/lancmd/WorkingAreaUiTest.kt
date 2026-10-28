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
    @Test fun bothPanesRemainUsableAndDividerCanEnlargeEitherPane() {
        compose.setContent {LanCmdMiuixTheme {
            val context=LocalContext.current
            val view=androidx.compose.runtime.remember(context) { View(context) }
            WorkingArea(view,Modifier.size(360.dp,600.dp)) { Action("控制操作"){} }
        }}
        val picture=compose.onNodeWithTag("working-picture");val controls=compose.onNodeWithTag("working-controls")
        val initial=picture.getUnclippedBoundsInRoot().height
        assertTrue(initial>200.dp);assertTrue(controls.getUnclippedBoundsInRoot().height>180.dp)
        val divider=compose.onNodeWithContentDescription("调整画面与操作栏比例")
        divider.performSemanticsAction(SemanticsActions.CustomActions){actions->actions.single{it.label=="增大画面"}.action()}
        assertTrue(picture.getUnclippedBoundsInRoot().height>initial)
        divider.performSemanticsAction(SemanticsActions.CustomActions){actions->repeat(2){actions.single{it.label=="增大操作栏"}.action()}}
        assertTrue(picture.getUnclippedBoundsInRoot().height<initial)
        assertTrue(controls.getUnclippedBoundsInRoot().height>180.dp)
    }
    @Test fun visuallyCompactSelectionKeepsItsTouchTarget() {
        compose.setContent {LanCmdMiuixTheme {SelectionAction("触控",selected=true){}}}
        compose.onNodeWithText("触控").assertIsSelected().assertHeightIsAtLeast(48.dp)
    }
}

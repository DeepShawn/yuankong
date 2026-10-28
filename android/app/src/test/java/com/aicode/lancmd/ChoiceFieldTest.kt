package com.aicode.lancmd

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChoiceFieldTest {
    @get:Rule val compose=createComposeRule()

    @Test fun browserChoiceOpensAndChangesWithoutClosingTheScreen() {
        val selected=mutableIntStateOf(0)
        compose.setContent {
            LanCmdMiuixTheme { ChoiceField(listOf("默认浏览器","Firefox"),"使用浏览器",selected.intValue) { selected.intValue=it } }
        }
        compose.onNodeWithText("使用浏览器").performClick()
        compose.onNodeWithText("Firefox").performClick()
        compose.runOnIdle { assertEquals(1,selected.intValue) }
        compose.onNodeWithText("使用浏览器").performClick()
        compose.onNodeWithText("默认浏览器").performClick()
        compose.runOnIdle { assertEquals(0,selected.intValue) }
    }

    @Test fun refreshedShorterListCannotCrashOnAStaleSelectedIndex() {
        val items=mutableStateOf(listOf("默认","A","B"))
        val selected=mutableIntStateOf(2)
        compose.setContent { LanCmdMiuixTheme { ChoiceField(items.value,"浏览器",selected.intValue) { selected.intValue=it } } }
        compose.runOnIdle { items.value=listOf("默认") }
        compose.onNodeWithText("浏览器").performClick()
        compose.onNodeWithText("默认").performClick()
        compose.runOnIdle { assertEquals(0,selected.intValue) }
    }
}

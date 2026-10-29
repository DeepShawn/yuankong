package com.aicode.lancmd

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,qualifiers="w720dp-h891dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardLayoutUiTest {
    @get:Rule val compose=createComposeRule()
    private fun buttons(view:View):List<MaterialButton> = when(view) {
        is MaterialButton->listOf(view)
        is ViewGroup->(0 until view.childCount).flatMap{buttons(view.getChildAt(it))}
        else->emptyList()
    }
    @Test fun hostedKeyboardMeasuresAllRowsAndKeepsBackspaceAndDeleteOnEveryPage() {
        val context=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_LanCmd)
        val sent=ArrayList<List<String>>()
        val keyboard=VirtualKeyboard(context){sent.add(it)}
        val width=mutableIntStateOf(360)
        lateinit var scroll:androidx.compose.foundation.ScrollState
        compose.setContent {LanCmdMiuixTheme {
            scroll=rememberScrollState()
            Column(Modifier.width(width.intValue.dp).height(260.dp).verticalScroll(scroll)) {
                HostedView(keyboard,Modifier.fillMaxWidth().testTag("keyboard"))
            }
        }}
        for(size in listOf(280,360,700)) {
            compose.runOnIdle{width.intValue=size};compose.waitForIdle()
            for(section in listOf("字母","符号","功能")) {
                compose.runOnIdle{buttons(keyboard).single{it.text==section}.performClick()};compose.waitForIdle()
                compose.runOnIdle {
                    assertTrue("The former 300dp host must measure the complete keyboard",keyboard.height>=400)
                    assertTrue("The workspace must be able to scroll to the lower rows",scroll.maxValue>=keyboard.height-260)
                    for(label in listOf("退格","删除","Enter","→")) {
                        val key=buttons(keyboard).single{it.text==label}
                        val bounds=android.graphics.Rect();key.getDrawingRect(bounds);keyboard.offsetDescendantRectToMyCoords(key,bounds)
                        assertTrue("$size / $section / $label clipped: $bounds height=${keyboard.height}",bounds.top>=0 && bounds.bottom<=keyboard.height)
                    }
                    buttons(keyboard).single{it.text=="退格"}.performClick()
                    buttons(keyboard).single{it.text=="删除"}.performClick()
                    assertEquals(listOf("BACKSPACE"),sent[sent.size-2]);assertEquals(listOf("DELETE"),sent.last())
                }
            }
        }
    }
}

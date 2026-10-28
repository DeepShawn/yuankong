package com.aicode.lancmd

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The picture and controls share all remaining space; neither is below a page scroll. */
@Composable
fun WorkingArea(view:View,modifier:Modifier=Modifier,controlFraction:Float=.42f,onResize:()->Unit={},controls:@Composable ColumnScope.()->Unit) {
    var fraction by rememberSaveable { mutableFloatStateOf(controlFraction) }
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val density=LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        if(landscape)Row(Modifier.fillMaxSize()) {
            HostedView(view,Modifier.weight(1f).fillMaxHeight().testTag("working-picture"))
            Column(Modifier.width((maxWidth*.36f).coerceIn(220.dp,340.dp)).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("working-controls"),content=controls)
        } else Column(Modifier.fillMaxSize()) {
            HostedView(view,Modifier.fillMaxWidth().weight(1f).testTag("working-picture"))
            val heightPixels=with(density){maxHeight.toPx()}.coerceAtLeast(1f)
            fun resize(value:Float) {onResize();fraction=value.coerceIn(.25f,.70f)}
            Box(Modifier.fillMaxWidth().height(24.dp).semantics {
                contentDescription="调整画面与操作栏比例"
                customActions=listOf(
                    CustomAccessibilityAction("增大画面"){resize(fraction-.1f);true},
                    CustomAccessibilityAction("增大操作栏"){resize(fraction+.1f);true})
            }.pointerInput(heightPixels){detectVerticalDragGestures(onDragStart={onResize()}) { change,amount ->
                change.consume();fraction=(fraction-amount/heightPixels).coerceIn(.25f,.70f)
            }},contentAlignment=Alignment.Center) {
                Spacer(Modifier.width(44.dp).height(4.dp).background(MiuixTheme.colorScheme.outline,RoundedCornerShape(2.dp)))
            }
            Column(Modifier.fillMaxWidth().height(maxHeight*fraction).verticalScroll(rememberScrollState()).testTag("working-controls"),content=controls)
        }
    }
}

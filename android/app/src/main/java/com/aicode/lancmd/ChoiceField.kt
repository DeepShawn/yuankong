package com.aicode.lancmd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A bounded, Activity-local miuix selector; choices may refresh while it is open. */
@Composable
fun ChoiceField(
    items: List<String>, title: String, selectedIndex: Int,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    summary: String? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val latestItems by rememberUpdatedState(items.toList())
    val onSelect by rememberUpdatedState(onSelectedIndexChange)
    val value = items.getOrNull(selectedIndex) ?: items.firstOrNull() ?: "暂无可选项"
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled && items.isNotEmpty()){expanded=true}.padding(horizontal=8.dp,vertical=6.dp),
            horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,fontSize=13.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,modifier=Modifier.weight(1f),maxLines=2)
            Text(value,fontSize=14.sp,color=if(enabled)MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface,
                modifier=Modifier.weight(1.5f),maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("⌄",fontSize=16.sp,color=MiuixTheme.colorScheme.primary)
        }
        if(!summary.isNullOrBlank())Text(summary,fontSize=11.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,modifier=Modifier.padding(start=8.dp,end=8.dp,bottom=6.dp))
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }) {
        Card(modifier = modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.padding(20.dp))
            Column(Modifier.weight(1f,fill=false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, value ->
                    RowItem(if (index == selectedIndex) "✓ $value" else value) {
                        expanded = false
                        // A stale tap cannot index or select a different refreshed item.
                        if (latestItems.getOrNull(index) == value) onSelect?.invoke(index)
                    }
                }
            }
            Action("取消", modifier = Modifier.fillMaxWidth()) { expanded = false }
        }
    }
}

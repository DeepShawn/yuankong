package com.aicode.lancmd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun ConfirmAction(title:String,message:String,confirm:String="确认",onDismiss:()->Unit,onConfirm:()->Unit) {
    Dialog(onDismissRequest=onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(title)
                Note(message)
                ActionRow {
                    Action("取消",modifier=Modifier.weight(1f),onClick=onDismiss)
                    Action(confirm,primary=true,modifier=Modifier.weight(1f),onClick=onConfirm)
                }
            }
        }
    }
}

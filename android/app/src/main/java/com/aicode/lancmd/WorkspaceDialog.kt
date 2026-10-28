package com.aicode.lancmd

import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.DialogInterface.BUTTON_NEGATIVE
import android.content.DialogInterface.BUTTON_NEUTRAL
import android.graphics.Color as NativeColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.button.MaterialButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Compose dialog surface with native editing/list adapters kept for existing controllers.
 * The real buttons remain accessible to validation and asynchronous enable/disable logic.
 */
class WorkspaceDialog private constructor(private val spec:Builder):AlertDialog(spec.ctx) {
    private val buttons=linkedMapOf<Int,Button>()
    private var choices:ListView?=null
    private var composition:ComposeView?=null
    internal val description:String get()=listOfNotNull(spec.title,spec.message).joinToString("\n")
    override fun getButton(whichButton:Int):Button=buttons.getValue(whichButton)
    override fun getListView():ListView?=choices
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val density=context.resources.displayMetrics.density
        fun px(value:Int)=(value*density).toInt()
        spec.buttons.forEach { (which,item) -> buttons[which]=MaterialButton(context).apply {
            text=item.first;isAllCaps=false;minHeight=px(48);cornerRadius=px(16)
            setTextColor(Themes.color(context,if(which==BUTTON_POSITIVE)R.color.on_accent else R.color.text))
            backgroundTintList=android.content.res.ColorStateList.valueOf(Themes.color(context,if(which==BUTTON_POSITIVE)R.color.accent else R.color.surface_variant))
            setOnClickListener { item.second?.onClick(this@WorkspaceDialog,which);dismiss() }
        } }
        spec.items?.let { items -> choices=ListView(context).apply {
            divider=ColorDrawable(Themes.color(context,R.color.outline_soft));dividerHeight=px(1)
            adapter=object:ArrayAdapter<CharSequence>(context,android.R.layout.simple_list_item_1,items) {
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View =
                    (super.getView(position,convertView,parent) as TextView).apply {
                        setTextColor(Themes.color(context,R.color.text));textSize=15f
                        setPadding(px(20),px(16),px(20),px(16));minHeight=px(56)
                    }
            }
            setOnItemClickListener { _,_,index,_ -> spec.selected?.onClick(this@WorkspaceDialog,index);dismiss() }
        } }
        var owner:Context=spec.ctx
        while(owner is ContextWrapper && owner !is AppCompatActivity)owner=owner.baseContext
        val activity=owner as? AppCompatActivity ?: error("对话框需要页面上下文")
        composition=ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity);setViewTreeSavedStateRegistryOwner(activity)
            setContent { LanCmdMiuixTheme {
                Card(cornerRadius=28.dp,insideMargin=PaddingValues(20.dp)) {
                    Text(spec.title?.toString().orEmpty(),fontSize=21.sp,color=MiuixTheme.colorScheme.onSurface)
                    Column(Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                        if(!spec.message.isNullOrBlank())AndroidView(factory={ TextView(it).apply {
                            id=android.R.id.message;text=spec.message;textSize=14f
                            setTextColor(Themes.color(context,R.color.dim));setLineSpacing(px(3).toFloat(),1f)
                            setTextIsSelectable(true)
                        } },modifier=Modifier.fillMaxWidth().padding(vertical=14.dp))
                        spec.body?.let { body -> AndroidView(factory={ (body.parent as? ViewGroup)?.removeView(body);body },modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) }
                        choices?.let { list -> AndroidView(factory={list},modifier=Modifier.fillMaxWidth().height((spec.items!!.size*64).coerceAtMost(360).coerceAtLeast(64).dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Vertical actions retain room for long translated labels and large fonts.
                    listOf(BUTTON_NEGATIVE,BUTTON_NEUTRAL,BUTTON_POSITIVE).forEach { key ->
                        buttons[key]?.let { button -> AndroidView(factory={button},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) }
                    }
                }
            } }
        }
        setContentView(composition!!)
        setCancelable(spec.cancelable);setCanceledOnTouchOutside(spec.cancelable)
        window?.setBackgroundDrawable(ColorDrawable(NativeColor.TRANSPARENT))
        window?.setLayout((context.resources.displayMetrics.widthPixels-px(40)).coerceAtMost(px(560)),ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    override fun dismiss() { super.dismiss();composition?.disposeComposition() }
    class Builder(val ctx:Context) {
        internal var title:CharSequence?=null;internal var message:CharSequence?=null
        internal var body:View?=null;internal var items:Array<out CharSequence>?=null
        internal var selected:DialogInterface.OnClickListener?=null;internal var cancelable=true
        internal val buttons=linkedMapOf<Int,Pair<CharSequence,DialogInterface.OnClickListener?>>()
        fun setTitle(value:CharSequence?)=apply{title=value}
        fun setTitle(value:Int)=setTitle(ctx.getString(value))
        fun setMessage(value:CharSequence?)=apply{message=value}
        fun setMessage(value:Int)=setMessage(ctx.getString(value))
        fun setView(value:View)=apply{body=value}
        fun setItems(value:Array<out CharSequence>,listener:DialogInterface.OnClickListener?)=apply{items=value;selected=listener}
        fun setCancelable(value:Boolean)=apply{cancelable=value}
        fun setPositiveButton(text:CharSequence,listener:DialogInterface.OnClickListener?)=apply{buttons[BUTTON_POSITIVE]=text to listener}
        fun setPositiveButton(text:Int,listener:DialogInterface.OnClickListener?)=setPositiveButton(ctx.getString(text),listener)
        fun setNegativeButton(text:CharSequence,listener:DialogInterface.OnClickListener?)=apply{buttons[BUTTON_NEGATIVE]=text to listener}
        fun setNegativeButton(text:Int,listener:DialogInterface.OnClickListener?)=setNegativeButton(ctx.getString(text),listener)
        fun setNeutralButton(text:CharSequence,listener:DialogInterface.OnClickListener?)=apply{buttons[BUTTON_NEUTRAL]=text to listener}
        fun setNeutralButton(text:Int,listener:DialogInterface.OnClickListener?)=setNeutralButton(ctx.getString(text),listener)
        fun create()=WorkspaceDialog(this)
        fun show()=create().apply{show()}
    }
}

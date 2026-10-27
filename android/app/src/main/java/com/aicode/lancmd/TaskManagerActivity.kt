package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.parentOf
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class TaskManagerActivity : FeatureActivity() {
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private val sourceKey by lazy { Prefs.target(this)?.agentId.orEmpty()+"@${origin?.host}:${origin?.port}" }
    private lateinit var search:EditText
    private lateinit var sort:Spinner
    private lateinit var automatic:CheckBox
    private lateinit var selectMode:MaterialButton
    private lateinit var selectionBar:LinearLayout
    private lateinit var selectionCount:TextView
    private lateinit var endSelected:MaterialButton
    private lateinit var cancel:MaterialButton
    private val selection=linkedMapOf<String,JSONObject>()
    private var restoreKeys=setOf<String>()
    private var multi=false
    private var busy=false
    private var batchBusy=false
    private var cancelRequested=false
    private var poll:Job?=null
    private var lastResult:String?=null
    private var lastError=false
    private var items=listOf<JSONObject>()
    private var shown=listOf<JSONObject>()
    private val adapter=Processes()
    private fun key(item:JSONObject)="${item.optInt("pid")}:${item.optString("created")}"

    override fun onCreate(state:Bundle?) {
        super.onCreate(state);origin
        setup("任务管理器","点击查看详情，长按或点击多选可批量结束；关键进程和 LanCmd 自身将跳过。")
        statusText.maxLines=5;statusText.ellipsize=android.text.TextUtils.TruncateAt.END
        if(state?.getString("source")==sourceKey) {
            restoreKeys=state.getStringArrayList("selection").orEmpty().toSet();multi=state.getBoolean("multi")
        }
        search=styleInput(EditText(this).apply { hint="搜索名称、PID 或路径";setSingleLine();contentDescription="搜索电脑进程" });body.addView(search)
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()}
            override fun afterTextChanged(s:Editable?){}
        })
        val controls=LinearLayout(this).apply { setPadding(0,dp(6),0,0) }
        sort=styleSpinner(Spinner(this).apply { adapter=ArrayAdapter(this@TaskManagerActivity,android.R.layout.simple_spinner_dropdown_item,listOf("内存排序","CPU 排序","名称排序")) })
        controls.addView(sort,LinearLayout.LayoutParams(0,-2,2f))
        controls.addView(button("刷新") { refresh() }.apply { minWidth=0;textSize=12f },LinearLayout.LayoutParams(0,-2,1f))
        selectMode=button("多选") { if(!batchBusy){multi=!multi;if(!multi)selection.clear();updateSelection();adapter.notifyDataSetChanged()} }.apply { minWidth=0;textSize=12f;setPadding(dp(4),0,dp(4),0);setSingleLine() }
        controls.addView(selectMode,LinearLayout.LayoutParams(0,-2,1f));body.addView(controls)
        automatic=styleCheck(CheckBox(this).apply { text="每 3 秒刷新（选择按 PID 和创建时间保留）";isChecked=true });body.addView(automatic)
        selectionBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        selectionCount=label("",12f,R.color.accent);selectionBar.addView(selectionCount)
        val choices=LinearLayout(this)
        listOf("全选结果" to { chooseAll() },"反选" to { invert() },"清空" to { selection.clear();updateSelection();adapter.notifyDataSetChanged() }).forEach { (name,action)->
            choices.addView(button(name) { if(!batchBusy)action() }.apply { minWidth=0;textSize=12f },LinearLayout.LayoutParams(0,-2,1f))
        };selectionBar.addView(choices)
        val actions=LinearLayout(this)
        endSelected=primary(button("结束所选") { confirmTerminateMany(selection.values.map { JSONObject(it.toString()) }) })
        cancel=button("停止后续操作") { cancelRequested=true;status("已请求停止，当前请求返回后不再结束后续进程") }
        actions.addView(endSelected,LinearLayout.LayoutParams(0,-2,1f));actions.addView(cancel,LinearLayout.LayoutParams(0,-2,1f));selectionBar.addView(actions);body.addView(selectionBar)
        sort.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){filter()}
        }
        body.addView(RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@TaskManagerActivity);adapter=this@TaskManagerActivity.adapter },LinearLayout.LayoutParams(-1,0,1f))
        updateSelection()
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("source",sourceKey);out.putBoolean("multi",multi);out.putStringArrayList("selection",ArrayList(selection.keys+restoreKeys));super.onSaveInstanceState(out)
    }
    private fun refresh() {
        if(busy || batchBusy)return
        busy=true
        task { try {
            val data=pinnedCall(origin,"tasks");val list=data.getJSONArray("items")
            items=(0 until list.length()).map { list.getJSONObject(it) }
            val current=items.associateBy { key(it) }
            selection.keys.retainAll(current.keys)
            for(id in selection.keys.toList())selection[id]=current.getValue(id)
            for(id in restoreKeys)current[id]?.let { selection[id]=it };restoreKeys=emptySet()
            filter();status(lastResult ?: "${items.size} 个进程 · 点击详情，长按多选",lastError)
        } finally { busy=false } }
    }
    private fun filter() {
        if(!::search.isInitialized || !::sort.isInitialized)return
        val query=search.text.toString().trim()
        val matches=items.filter { (it.optString("name")+" "+it.optInt("pid")+" "+it.optString("path")).contains(query,true) }
        shown=when(sort.selectedItemPosition) { 1->matches.sortedByDescending { it.optDouble("cpu",-1.0) };2->matches.sortedBy { it.optString("name").lowercase() };else->matches.sortedByDescending { it.optLong("memory") } }
        adapter.notifyDataSetChanged();if(::selectMode.isInitialized)updateSelection()
    }
    private fun toggle(item:JSONObject) {
        if(batchBusy)return
        val id=key(item)
        if(selection.containsKey(id))selection.remove(id)
        else if(selection.size>=100){status("每批最多选择 100 个进程，请先筛选",true);adapter.notifyDataSetChanged();return}
        else selection[id]=item
        updateSelection();adapter.notifyDataSetChanged()
    }
    private fun chooseAll() {
        if((selection.keys+shown.map { key(it) }).toSet().size>100){status("超过 100 个进程，请先筛选或分批选择",true);return}
        shown.forEach { selection[key(it)]=it };updateSelection();adapter.notifyDataSetChanged()
    }
    private fun invert() {
        val next=selection.toMutableMap()
        shown.forEach { if(next.containsKey(key(it)))next.remove(key(it)) else next[key(it)]=it }
        if(next.size>100){status("反选后超过 100 个进程，请先筛选",true);return}
        selection.clear();selection.putAll(next);updateSelection();adapter.notifyDataSetChanged()
    }
    private fun updateSelection() {
        if(!::selectionBar.isInitialized)return
        selectionBar.visibility=if(multi || batchBusy)View.VISIBLE else View.GONE
        selectMode.text=if(multi)"退出多选" else "多选";selectMode.isEnabled=!batchBusy
        val protectedCount=selection.values.count { !it.optBoolean("can_terminate") }
        selectionCount.text="已选 ${selection.size} 项 · $protectedCount 项受保护/受限 · 当前显示 ${shown.size} 项"
        endSelected.isEnabled=!batchBusy && selection.values.any { it.optBoolean("can_terminate") }
        cancel.visibility=if(batchBusy)View.VISIBLE else View.GONE
    }
    private fun details(item:JSONObject) {
        val path=item.optString("path")
        val message="PID ${item.getInt("pid")} · 父进程 ${item.optInt("parent")}\n内存 ${FileTransfer.humanSize(item.optLong("memory"))}\n$path\n\n${if(item.optBoolean("can_terminate"))"结束会丢失未保存内容。" else "此进程受到保护或当前权限不足。"}"
        val dialog=AlertDialog.Builder(this).setTitle(item.optString("name")).setMessage(message).setNegativeButton("关闭",null)
            .setNeutralButton("文件 / 应用锁") { _,_->
                if(path.isNotEmpty())AlertDialog.Builder(this).setItems(arrayOf("在文件管理中定位","应用锁")) { _,index->
                    task { pinnedCall(origin,"capabilities");startActivity(Intent(this@TaskManagerActivity,if(index==0)StorageActivity::class.java else AppLocksActivity::class.java).putExtra("path",if(index==0)parentOf(path) else path)) }
                }.show()
            }.setPositiveButton("结束进程") { _,_->confirmTerminate(item) }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=item.optBoolean("can_terminate");dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled=path.isNotEmpty() };dialog.show()
    }
    private fun confirmTerminate(item:JSONObject)=confirmTerminateMany(listOf(JSONObject(item.toString())))
    private fun confirmTerminateMany(targets:List<JSONObject>) {
        if(batchBusy || targets.isEmpty())return
        AlertDialog.Builder(this).setTitle(if(targets.size==1)"结束 ${targets[0].optString("name")}？" else "结束所选 ${targets.size} 项？")
            .setMessage(targets.take(8).joinToString("\n") { "${it.optString("name")} · PID ${it.optInt("pid")}" }+"\n\n受保护进程自动跳过。未保存内容可能丢失；停止批量操作只停止后续请求，不能撤销已结束的进程。结果直接显示在页面顶部。")
            .setNegativeButton("取消",null).setPositiveButton("结束") { _,_->runTerminate(targets) }.show()
    }
    private fun runTerminate(targets:List<JSONObject>) {
        if(batchBusy)return
        batchBusy=true;cancelRequested=false;updateSelection();adapter.notifyDataSetChanged()
        clearTopResults()
        task {
            val results=ArrayList<String>();var success=0;var failed=0;var skipped=0
            try {
                for((index,item) in targets.withIndex()) {
                    val label="${item.optString("name")} (PID ${item.optInt("pid")})"
                    if(cancelRequested){skipped++;results.add("未执行 · $label");continue}
                    if(!item.optBoolean("can_terminate")){skipped++;results.add("跳过 · $label：受保护或权限不足");continue}
                    status("正在结束 ${index+1}/${targets.size} · $label")
                    try {
                        pinnedCall(origin,"tasks",JSONObject().put("action","terminate").put("pid",item.getInt("pid")).put("created",item.getString("created")))
                        success++;selection.remove(key(item));results.add("成功 · 已结束 $label")
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        failed++;results.add("失败 · $label：${e.message}")
                        if(origin?.closed!=false || runCatching { ConnectionManager.sessionClient() }.getOrNull()!==origin)cancelRequested=true
                    }
                }
                lastResult="结束进程：成功 $success，失败 $failed，跳过/未执行 $skipped";lastError=failed>0
                reportTopResults(lastResult!!,results,lastError)
                if(failed>0)automatic.isChecked=false
            } finally { batchBusy=false;updateSelection();adapter.notifyDataSetChanged() }
            refresh()
        }
    }
    override fun onStart() { super.onStart();poll=lifecycleScope.launch { refresh();while(isActive){delay(3000);if(automatic.isChecked)refresh()} } }
    override fun onStop() { poll?.cancel();super.onStop() }
    private inner class Processes:RecyclerView.Adapter<Processes.Holder>() {
        inner class Holder(val row:LinearLayout,val name:TextView,val info:TextView,val check:CheckBox):RecyclerView.ViewHolder(row)
        override fun getItemCount()=shown.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val row=LinearLayout(this@TaskManagerActivity).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(6),dp(10));setBackgroundResource(R.drawable.action_bg);layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(6) } }
            val labels=LinearLayout(this@TaskManagerActivity).apply { orientation=LinearLayout.VERTICAL }
            val name=label("",14f);val info=label("",12f,R.color.dim);labels.addView(name);labels.addView(info);row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            val check=styleCheck(CheckBox(this@TaskManagerActivity));row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)));return Holder(row,name,info,check)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val item=shown[position];val cpu=if(item.isNull("cpu"))"采样中" else String.format(java.util.Locale.US,"%.1f%%",item.optDouble("cpu"))
            holder.name.text="${item.optString("name")} · ${item.optInt("pid")}";holder.info.text="CPU $cpu · ${FileTransfer.humanSize(item.optLong("memory"))}${if(!item.optBoolean("can_terminate"))" · 受保护 / 受限" else ""}"
            holder.check.setOnCheckedChangeListener(null);holder.check.visibility=if(multi)View.VISIBLE else View.GONE;holder.check.isChecked=selection.containsKey(key(item));holder.check.isEnabled=!batchBusy
            holder.check.contentDescription="选择进程 ${item.optString("name")} PID ${item.optInt("pid")}"
            holder.check.setOnCheckedChangeListener { _,_->toggle(item) }
            holder.row.setOnClickListener { if(!batchBusy){if(multi)toggle(item) else details(item)} }
            holder.row.setOnLongClickListener { if(!batchBusy){multi=true;toggle(item)};true }
        }
    }
}

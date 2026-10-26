package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import com.aicode.lancmd.net.parentOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class TaskManagerActivity : FeatureActivity() {
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var search: EditText
    private lateinit var sort: Spinner
    private lateinit var automatic: CheckBox
    private var poll: Job?=null
    private var busy=false
    private var items=listOf<JSONObject>()
    private var shown=listOf<JSONObject>()
    private val adapter=Processes()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("任务管理器","查看电脑进程、CPU 与内存，按需结束所选进程；关键进程和 LanCmd 自身受到保护。")
        search=EditText(this).apply { hint="搜索名称、PID 或路径";setSingleLine();contentDescription="搜索电脑进程" }
        body.addView(search)
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()}
            override fun afterTextChanged(s:Editable?){}
        })
        val controls=LinearLayout(this)
        sort=Spinner(this).apply { adapter=ArrayAdapter(this@TaskManagerActivity,android.R.layout.simple_spinner_dropdown_item,listOf("内存排序","CPU 排序","名称排序")) }
        controls.addView(sort,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("刷新") { refresh() },LinearLayout.LayoutParams(0,-2,1f));body.addView(controls)
        automatic=CheckBox(this).apply { text="每 3 秒刷新（CPU 需两次采样）";isChecked=true;setTextColor(color(R.color.text)) };body.addView(automatic)
        sort.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long){filter()}
        }
        body.addView(RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@TaskManagerActivity);adapter=this@TaskManagerActivity.adapter },LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun refresh() {
        if(busy)return
        busy=true
        task { try {
            val data=pinnedCall(origin,"tasks");val list=data.getJSONArray("items")
            items=(0 until list.length()).map { list.getJSONObject(it) };filter();status("${items.size} 个进程 · 点击查看详情和操作")
        } finally { busy=false } }
    }
    private fun filter() {
        if(!::search.isInitialized || !::sort.isInitialized)return
        val query=search.text.toString().trim()
        val matches=items.filter { (it.optString("name")+" "+it.optInt("pid")+" "+it.optString("path")).contains(query,true) }
        shown=when(sort.selectedItemPosition) { 1->matches.sortedByDescending { it.optDouble("cpu",-1.0) };2->matches.sortedBy { it.optString("name").lowercase() };else->matches.sortedByDescending { it.optLong("memory") } }
        adapter.notifyDataSetChanged()
    }
    private fun details(item: JSONObject) {
        val path=item.optString("path")
        val message="PID ${item.getInt("pid")} · 父进程 ${item.optInt("parent")}\n内存 ${FileTransfer.humanSize(item.optLong("memory"))}\n$path\n\n${if(item.optBoolean("can_terminate"))"结束会丢失未保存内容。" else "此进程受到保护或当前权限不足。"}"
        val dialog=AlertDialog.Builder(this).setTitle(item.optString("name")).setMessage(message).setNegativeButton("关闭",null)
            .setNeutralButton("文件 / 应用锁") { _,_->
                if(path.isNotEmpty()) AlertDialog.Builder(this).setItems(arrayOf("在文件管理中定位","应用锁")) { _,index->
                    task { pinnedCall(origin,"capabilities");startActivity(Intent(this@TaskManagerActivity,if(index==0)StorageActivity::class.java else AppLocksActivity::class.java)
                        .putExtra("path",if(index==0)parentOf(path) else path)) }
                }.show()
            }.setPositiveButton("结束进程") { _,_->
                AlertDialog.Builder(this).setTitle("结束 ${item.optString("name")}？").setMessage("PID ${item.getInt("pid")}\n未保存内容可能丢失。")
                    .setNegativeButton("取消",null).setPositiveButton("结束") { _,_-> task {
                        pinnedCall(origin,"tasks",JSONObject().put("action","terminate").put("pid",item.getInt("pid")).put("created",item.getString("created")));refresh()
                    } }.show()
            }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=item.optBoolean("can_terminate");dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled=path.isNotEmpty() }
        dialog.show()
    }
    override fun onStart() { super.onStart();poll=lifecycleScope.launch { refresh();while(isActive){delay(3000);if(automatic.isChecked)refresh()} } }
    override fun onStop() { poll?.cancel();super.onStop() }
    private inner class Processes:RecyclerView.Adapter<Processes.Holder>() {
        inner class Holder(val row:LinearLayout,val name:android.widget.TextView,val info:android.widget.TextView):RecyclerView.ViewHolder(row)
        override fun getItemCount()=shown.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val row=LinearLayout(this@TaskManagerActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundResource(R.drawable.action_bg);layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(6) } }
            val name=label("",14f);val info=label("",12f,R.color.dim);row.addView(name);row.addView(info);return Holder(row,name,info)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val item=shown[position];val cpu=if(item.isNull("cpu"))"采样中" else String.format(java.util.Locale.US,"%.1f%%",item.optDouble("cpu"))
            holder.name.text="${item.optString("name")} · ${item.optInt("pid")}"
            holder.info.text="CPU $cpu · ${FileTransfer.humanSize(item.optLong("memory"))}${if(!item.optBoolean("can_terminate"))" · 受保护 / 受限" else ""}"
            holder.row.setOnClickListener { details(item) }
        }
    }
}

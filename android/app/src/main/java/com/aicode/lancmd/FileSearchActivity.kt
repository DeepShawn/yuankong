package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.FileTransfer
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class FileSearchActivity:FeatureActivity() {
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var input:EditText
    private lateinit var scope:Spinner
    private lateinit var kind:Spinner
    private lateinit var sorting:Spinner
    private lateinit var info:TextView
    private lateinit var previous:MaterialButton
    private lateinit var next:MaterialButton
    private lateinit var page:TextView
    private lateinit var build:MaterialButton
    private lateinit var cancel:MaterialButton
    private val adapter=Results()
    private var items=listOf<JSONObject>()
    private var roots=listOf<String>()
    private var drives=listOf<String>()
    private var offset=0
    private var total=0
    private var updated=0.0
    private var jobId=""
    private var poll:Job?=null
    private var searching:Job?=null
    private var querySequence=0
    private var statusBusy=false
    private fun checkSource() { check(origin!=null && origin?.closed==false && runCatching { ConnectionManager.sessionClient() }.getOrNull()===origin) { "电脑连接已改变，请重新打开文件搜索" } }
    private suspend fun call(q:JSONObject=JSONObject()):JSONObject { checkSource();return pinnedCall(origin,"file_search",q,45_000).also { checkSource() } }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);origin
        setup("文件搜索","首次建立索引后可快速搜索文件名和路径；文件变动后请更新索引。")
        statusText.maxLines=3
        input=styleInput(EditText(this).apply { hint="文件名、关键词或 *.docx";setSingleLine();contentDescription="全盘文件搜索关键词";imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;setText(state?.getString("query").orEmpty()) });body.addView(input)
        val filters=LinearLayout(this)
        fun selector(labels:List<String>):Spinner=styleSpinner(Spinner(this).apply { adapter=ArrayAdapter(this@FileSearchActivity,android.R.layout.simple_spinner_dropdown_item,labels) }).also { filters.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }
        scope=selector(listOf("文件名","完整路径"));kind=selector(listOf("全部类型","仅文件","仅文件夹"));sorting=selector(listOf("名称排序","路径排序","日期排序","大小排序"));body.addView(filters)
        val tools=LinearLayout(this)
        tools.addView(button("索引范围") { chooseRoots() }.apply { textSize=12f;setPadding(dp(4),0,dp(4),0) },LinearLayout.LayoutParams(0,-2,1f))
        build=button("建立索引") { if(roots.isEmpty())chooseRoots() else confirmBuild(roots) }.apply { textSize=12f;setPadding(dp(4),0,dp(4),0) };tools.addView(build,LinearLayout.LayoutParams(0,-2,1f))
        cancel=button("取消建立") { task { call(JSONObject().put("action","cancel").put("job_id",jobId));status("已请求取消；旧索引保留") } }.apply { textSize=12f;setPadding(dp(4),0,dp(4),0);isEnabled=false };tools.addView(cancel,LinearLayout.LayoutParams(0,-2,1f));body.addView(tools)
        info=label("读取索引状态…",12f,R.color.dim).apply { maxLines=3;setPadding(dp(4),dp(4),dp(4),dp(6)) };body.addView(info)
        val pages=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        previous=button("上一页") { offset=(offset-100).coerceAtLeast(0);search(false) }.apply { textSize=12f;isEnabled=false }
        next=button("下一页") { offset+=100;search(false) }.apply { textSize=12f;isEnabled=false }
        page=label("0 项",12f).apply { gravity=android.view.Gravity.CENTER }
        pages.addView(previous,LinearLayout.LayoutParams(0,-2,1f));pages.addView(page,LinearLayout.LayoutParams(0,-2,1f));pages.addView(next,LinearLayout.LayoutParams(0,-2,1f));body.addView(pages)
        body.addView(RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@FileSearchActivity);adapter=this@FileSearchActivity.adapter },LinearLayout.LayoutParams(-1,0,1f))
        val listener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){search(true)}
        }
        scope.onItemSelectedListener=listener;kind.onItemSelectedListener=listener;sorting.onItemSelectedListener=listener
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){search(true)}
            override fun afterTextChanged(s:Editable?){}
        })
        input.setOnEditorActionListener { _,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search(true);true}else false }
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("query",input.text.toString());super.onSaveInstanceState(out) }
    override fun onStart() { super.onStart();poll=lifecycleScope.launch { while(isActive){refreshStatus();delay(1500)} } }
    override fun onStop() { poll?.cancel();super.onStop() }
    private suspend fun refreshStatus() {
        if(statusBusy)return
        statusBusy=true
        try {
            val response=call();val index=response.getJSONObject("index");val job=response.optJSONObject("job")
            val disks=response.optJSONArray("drives");drives=if(disks==null)emptyList() else (0 until disks.length()).map { disks.getString(it) }
            val saved=index.optJSONArray("roots");roots=if(saved==null)emptyList() else (0 until saved.length()).map { saved.getString(it) }
            val active=job?.optString("status")=="building";jobId=job?.optString("id").orEmpty()
            build.isEnabled=!active;cancel.isEnabled=active && response.optBoolean("can_cancel")
            build.text=if(index.optBoolean("available"))"更新索引" else "建立索引"
            info.text=when {
                active->(if(job?.optString("phase")=="optimizing")"正在整理索引" else "正在扫描 · ${job?.optLong("scanned")} 项 · 跳过 ${job?.optLong("skipped")} 项")+"\n"+job?.optString("current").orEmpty()
                job?.optString("status")=="failed"->"索引失败：${job.optString("error")}；旧索引保留"
                index.optBoolean("available")->"已索引 ${index.optLong("count")} 项 · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date((index.optDouble("updated")*1000).toLong()))}"+(if(index.optBoolean("incomplete"))" · 达到收录上限，请缩小范围" else "")+"\n范围："+roots.joinToString(" · ")
                else->"尚未建立索引。先选择全部本地磁盘或指定文件夹；仅收录文件名与路径，不读取内容。"
            }
            val generation=index.optDouble("updated",0.0)
            if(generation!=updated || index.optBoolean("available") && searching==null) { updated=generation;search(true) }
        } catch(e:CancellationException){throw e}
        catch(e:Exception){info.text=e.message;status(e.message ?: "无法读取搜索状态",true)}
        finally { statusBusy=false }
    }
    private fun chooseRoots() {
        AlertDialog.Builder(this).setTitle("选择索引范围").setItems(arrayOf("全部本地固定磁盘","选择一个文件夹（含子文件夹）","沿用上次范围")) { _,index->
            when(index) {
                0->if(drives.isEmpty())status("未找到本地固定磁盘，请选择文件夹",true) else confirmBuild(drives)
                1->RemoteDirectoryPicker(this,origin).show(roots.firstOrNull().orEmpty()) { confirmBuild(listOf(it)) }
                else->if(roots.isEmpty())status("没有上次范围，请先选择磁盘或文件夹",true) else confirmBuild(roots)
            }
        }.setNegativeButton("取消",null).show()
    }
    private fun confirmBuild(selected:List<String>) {
        AlertDialog.Builder(this).setTitle("建立 / 更新文件索引？").setMessage(selected.joinToString("\n")+"\n\n首次扫描可能需要较长时间；无权限目录和链接会跳过。最多收录 200 万项，至少保留 1 GB 空闲。手机断线后继续，取消或失败保留旧索引。文件变动后请手动更新。")
            .setNegativeButton("取消",null).setPositiveButton("开始") { _,_-> task { call(JSONObject().put("action","build").put("roots",JSONArray(selected)));status("索引已开始，电脑端无需确认弹窗");refreshStatus() } }.show()
    }
    private fun search(reset:Boolean) {
        if(!::page.isInitialized)return
        if(reset)offset=0
        val sequence=++querySequence;val query=input.text.toString();val start=offset
        searching?.cancel();searching=lifecycleScope.launch {
            delay(250)
            try {
                val result=call(JSONObject().put("action","query").put("query",query).put("scope",if(scope.selectedItemPosition==1)"path" else "name").put("kind",listOf("all","files","folders")[kind.selectedItemPosition.coerceAtLeast(0)]).put("sort",listOf("name","path","modified","size")[sorting.selectedItemPosition.coerceAtLeast(0)]).put("offset",start).put("limit",100))
                if(sequence!=querySequence)return@launch
                total=result.getInt("total");val list=result.getJSONArray("items");items=(0 until list.length()).map { list.getJSONObject(it) };adapter.notifyDataSetChanged()
                page.text=if(total==0)"0 项" else "${start+1}–${start+items.size} / $total"
                previous.isEnabled=start>0;next.isEnabled=start+items.size<total
                status(if(!result.getJSONObject("index").optBoolean("available"))"请先建立文件索引" else if(total==0)"没有匹配结果；文件变动后请更新索引" else "找到 $total 项 · 点击结果打开操作菜单")
            } catch(e:CancellationException){throw e}
            catch(e:Exception){status(e.message ?: "搜索失败",true)}
        }
    }
    private fun actions(item:JSONObject,anchor:View) {
        try { checkSource() } catch(e:Exception){status(e.message.orEmpty(),true);return}
        val path=item.getString("path");val office=OfficeDocumentActivity.supports(path)
        val menu=PopupMenu(this,anchor);menu.menu.add("在文件管理中定位");menu.menu.add("在电脑打开")
        if(!item.optBoolean("dir")){menu.menu.add("预览");menu.menu.add(if(office)"手机 WPS 编辑" else "编辑文本")}
        menu.setOnMenuItemClickListener {
            when(it.title.toString()) {
                "在文件管理中定位"->startActivity(Intent(this,StorageActivity::class.java).putExtra("path",item.getString("parent")).putExtra("highlight",item.getString("name")))
                "在电脑打开"->AlertDialog.Builder(this).setTitle("在电脑打开？").setMessage(path).setNegativeButton("取消",null).setPositiveButton("打开") { _,_->task { val response=FileDelay.call(this@FileSearchActivity,origin,"files",JSONObject().put("action","open").put("path",path).put("file_id",item.getString("file_id")));status(response.optString("notice","打开请求已发送")) } }.show()
                "预览","手机 WPS 编辑"->if(office)startActivity(Intent(this,OfficeDocumentActivity::class.java).putExtra("path",path).putExtra("file_id",item.getString("file_id"))) else FilePreview.show(this,origin,path)
                "编辑文本"->startActivity(Intent(this,TextEditorActivity::class.java).putExtra("path",path))
            };true
        };menu.show()
    }
    private inner class Results:RecyclerView.Adapter<Results.Holder>() {
        inner class Holder(val row:LinearLayout,val name:TextView,val path:TextView,val detail:TextView):RecyclerView.ViewHolder(row)
        override fun getItemCount()=items.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val row=LinearLayout(this@FileSearchActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundResource(R.drawable.action_bg);layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(7) } }
            val name=label("",14f).apply { maxLines=2 };val path=label("",11f,R.color.dim).apply { maxLines=2 };val detail=label("",11f,R.color.accent)
            row.addView(name);row.addView(path);row.addView(detail);return Holder(row,name,path,detail)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val item=items[position];holder.name.text=(if(item.optBoolean("dir"))"📁 " else "")+item.getString("name");holder.path.text=item.getString("parent")
            holder.detail.text=(if(item.optBoolean("dir"))"文件夹" else FileTransfer.humanSize(item.optLong("size")))+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date((item.optDouble("mtime")*1000).toLong()))
            holder.row.contentDescription="搜索结果 ${item.getString("name")}";holder.row.setOnClickListener { actions(item,holder.row) };holder.row.setOnLongClickListener { actions(item,holder.row);true }
        }
    }
}

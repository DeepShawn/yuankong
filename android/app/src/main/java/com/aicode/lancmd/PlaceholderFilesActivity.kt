package com.aicode.lancmd

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.joinPath
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.math.BigDecimal

class PlaceholderFilesActivity : FeatureActivity() {
    override val inlineResults=false
    private val origin by lazy { runCatching { ConnectionManager.sessionClient() }.getOrNull() }
    private lateinit var target: EditText
    private lateinit var size: EditText
    private lateinit var mode: Spinner
    private lateinit var create: MaterialButton
    private lateinit var refresh: MaterialButton
    private lateinit var jobs: LinearLayout
    private var busy=false
    private var polling: Job?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);origin
        setup("隐藏系统占位文件","实际占用磁盘空间 · 0–500 GB · 1 GB = 1,000,000,000 字节",scrollable=true)
        val box=panel()
        box.addView(label("新文件完整路径",12f,R.color.dim))
        target=styleInput(EditText(this).apply {
            setSingleLine();contentDescription="占位文件完整路径"
            setText(savedInstanceState?.getString("target") ?: joinPath(intent.getStringExtra("directory").orEmpty(),"LanCmd-placeholder.dat"))
        });box.addView(target)
        box.addView(label("占用大小（GB，支持小数，0 表示空文件）",12f,R.color.dim))
        size=styleInput(EditText(this).apply {
            setSingleLine();inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            contentDescription="占位文件大小 GB";setText(savedInstanceState?.getString("size") ?: "0")
        });box.addView(size)
        mode=styleSpinner(Spinner(this).apply {
            contentDescription="占位文件生成模式"
            adapter=ArrayAdapter(this@PlaceholderFilesActivity,android.R.layout.simple_spinner_dropdown_item,listOf("稳定（推荐，优先响应）","快速（优先生成速度）"))
            setSelection(savedInstanceState?.getInt("modeIndex",0)?.coerceIn(0,1) ?: 0)
        })
        box.addView(fieldRow("生成模式",mode))
        box.addView(caption("稳定模式适度让行前台操作；快速模式优先生成速度。两种模式都优先预分配，响应变慢时自动减小批次。"))
        box.addView(label("创建隐藏＋系统属性的普通文件，优先自适应分批预分配真实空间，不支持时逐块写入；不覆盖同名文件，保留至少 1 GB 可用空间。慢速 U 盘会自动缩小批次。",12f,R.color.dim))
        create=primary(button("创建占位文件") { confirmCreate() });box.addView(create)
        body.addView(label("Windows 资源管理器默认隐藏这类文件；开启显示受保护的操作系统文件后仍可查看。安卓文件管理始终列出，可直接删除释放空间。手机断线继续创建；取消、失败或接收端退出会清理未完成文件。",12f,R.color.dim))
        refresh=button("刷新占位任务") { refreshJobs() };body.addView(refresh)
        jobs=panel()
    }

    override fun onStart() {
        super.onStart()
        polling=task { while(true) {
            if(!busy)try { refreshNow() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { status(e.message ?: "读取占位任务失败",true) }
            delay(1500)
        } }
    }
    override fun onStop() { polling?.cancel();polling=null;super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("target",target.text.toString());outState.putString("size",size.text.toString());outState.putInt("modeIndex",mode.selectedItemPosition);super.onSaveInstanceState(outState)
    }
    private fun confirmCreate() {
        if(busy)return
        val path=target.text.toString().trim()
        if(path.isEmpty()) { target.error="请输入新文件完整路径";return }
        val bytes=runCatching { parseSize(size.text.toString()) }.getOrNull()
        if(bytes==null) { size.error="请输入 0–500 GB，最多精确到 1 字节";return }
        val selectedMode=if(mode.selectedItemPosition==1)"fast" else "stable"
        val modeName=if(selectedMode=="stable")"稳定" else "快速"
        AlertDialog.Builder(this).setTitle("创建并实际占用空间？")
            .setMessage("$path\n\n大小：${size.text} GB（$bytes 字节）\n模式：$modeName\n将实际分配磁盘空间，至少保留 1 GB 可用空间。可在任务中取消；已完成文件可在文件管理删除。")
            .setNegativeButton("取消",null).setPositiveButton("创建") { _,_->
                if(!busy) task {
                    setBusy(true)
                    try {
                        val features=pinnedCall(origin,"capabilities").optJSONArray("features")
                        check(features!=null && (0 until features.length()).any { features.optString(it)=="placeholders_modes" }) { "请先停止并完整更新 Windows 3.2.2 接收端，旧版不支持稳定模式" }
                        FileDelay.call(this@PlaceholderFilesActivity,origin,"placeholders",JSONObject().put("action","create").put("path",path).put("size",bytes).put("mode",selectedMode))
                        status("创建任务已提交，进度显示在下方")
                    } finally { setBusy(false) }
                    refreshNow()
                }
            }.show()
    }
    private fun setBusy(value:Boolean) { busy=value;create.isEnabled=!value;refresh.isEnabled=!value }
    private fun refreshJobs() { if(!busy)task { refreshNow() } }
    private suspend fun refreshNow() {
        if(busy)return
        setBusy(true)
        try {
            val result=pinnedCall(origin,"placeholders")
            jobs.removeAllViews()
            val items=result.getJSONArray("items")
            if(items.length()==0)jobs.addView(label("本次接收端运行期间暂无占位任务。重启后可在目标目录管理已完成文件。",13f,R.color.dim))
            for(i in 0 until items.length()) {
                val item=items.getJSONObject(i);val state=item.optString("status")
                val active=state in listOf("queued","checking","allocating","writing","flushing","verifying","cancelling","cleaning","committing")
                val title=when(state) { "queued"->"等待创建";"checking"->"正在检查目录与磁盘容量";"allocating"->"正在快速分配真实空间";"writing"->"正在占用空间";"flushing"->"正在刷新写入缓存";"verifying"->"正在核实磁盘分配";"cancelling"->"正在取消，等待磁盘返回并清理";"cleaning"->"正在关闭文件并清理";"committing"->"正在完成文件";"completed"->"创建完成";"cancelled"->"已取消并清理";else->"创建失败" }
                jobs.addView(label(title,15f,if(state=="failed")R.color.danger else R.color.accent))
                if(item.has("mode"))jobs.addView(caption(if(item.optString("mode")=="stable")"稳定模式" else "快速模式"))
                jobs.addView(label(item.getString("path"),12f,R.color.dim).apply { setTextIsSelectable(true) })
                val total=item.optLong("size");val written=item.optLong("written")
                jobs.addView(label("已完成 ${gb(written)} GB / ${gb(total)} GB"+(if(state=="completed")" · 实际分配 ${gb(item.optLong("allocated"))} GB" else ""),12f,R.color.dim))
                jobs.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate=active && state!="writing"
                    max=1000;progress=if(total==0L){if(state=="completed")1000 else 0}else (written.toDouble()/total*1000).toInt().coerceIn(0,1000)
                    progressTintList=android.content.res.ColorStateList.valueOf(color(R.color.accent))
                },LinearLayout.LayoutParams(-1,dp(8)).apply { topMargin=dp(8);bottomMargin=dp(8) })
                if(item.has("error"))jobs.addView(label(item.getString("error"),12f,R.color.danger))
                if(active && item.optLong("idle_seconds")>=5)jobs.addView(label("正在等待磁盘响应，进度暂未更新；取消会在当前磁盘调用返回后清理。",12f,R.color.dim))
                if(active && item.optBoolean("can_cancel",state !in listOf("cancelling","cleaning","committing")))jobs.addView(button("取消创建") {
                    if(!busy)task {
                        setBusy(true)
                        try {
                            val cancelled=pinnedCall(origin,"placeholders",JSONObject().put("action","cancel").put("job_id",item.getString("id")))
                            status(if(cancelled.optBoolean("cancel_requested",true))"已请求取消，等待磁盘返回并清理完成" else "任务已进入完成阶段，请等待结果")
                        }
                        finally { setBusy(false) }
                        refreshNow()
                    }
                })
                else if(!active)jobs.addView(button("在文件管理定位") {
                    task {
                        // Check the originating connection before navigating.
                        pinnedCall(origin,"capabilities")
                        startActivity(android.content.Intent(this@PlaceholderFilesActivity,StorageActivity::class.java).putExtra("path",com.aicode.lancmd.net.parentOf(item.getString("path"))))
                    }
                })
            }
        } finally { setBusy(false) }
    }
    companion object {
        internal fun parseSize(value:String):Long {
            val text=value.trim()
            require(Regex("(?:[0-9]{1,3}(?:\\.[0-9]{0,9})?|\\.[0-9]{1,9})").matches(text))
            val gb=BigDecimal(text)
            require(gb>=BigDecimal.ZERO && gb<=BigDecimal("500"))
            return gb.multiply(BigDecimal("1000000000")).longValueExact()
        }
        private fun gb(bytes:Long)=BigDecimal.valueOf(bytes).divide(BigDecimal("1000000000")).stripTrailingZeros().toPlainString()
    }
}

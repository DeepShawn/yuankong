package com.aicode.lancmd

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        box.addView(label("创建隐藏＋系统属性的普通文件，逐块写入并核实实际分配空间，不覆盖同名文件。创建后至少保留 1 GB 可用空间；500 GB 可能需要较长时间。",12f,R.color.dim))
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
        outState.putString("target",target.text.toString());outState.putString("size",size.text.toString());super.onSaveInstanceState(outState)
    }
    private fun confirmCreate() {
        if(busy)return
        val path=target.text.toString().trim()
        if(path.isEmpty()) { target.error="请输入新文件完整路径";return }
        val bytes=runCatching { parseSize(size.text.toString()) }.getOrNull()
        if(bytes==null) { size.error="请输入 0–500 GB，最多精确到 1 字节";return }
        AlertDialog.Builder(this).setTitle("创建并实际占用空间？")
            .setMessage("$path\n\n大小：${size.text} GB（$bytes 字节）\n将实际写入磁盘，至少保留 1 GB 可用空间。可在任务中取消；已完成文件可在文件管理删除。")
            .setNegativeButton("取消",null).setPositiveButton("创建") { _,_->
                if(!busy) task {
                    setBusy(true)
                    try {
                        FileDelay.call(this@PlaceholderFilesActivity,origin,"placeholders",JSONObject().put("action","create").put("path",path).put("size",bytes))
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
                val active=state in listOf("queued","writing","verifying")
                val title=when(state) { "queued"->"等待创建";"writing"->"正在占用空间";"verifying"->"正在核实磁盘分配";"completed"->"创建完成";"cancelled"->"已取消并清理";else->"创建失败" }
                jobs.addView(label(title,15f,if(state=="failed")R.color.danger else R.color.accent))
                jobs.addView(label(item.getString("path"),12f,R.color.dim).apply { setTextIsSelectable(true) })
                val total=item.optLong("size");val written=item.optLong("written")
                jobs.addView(label("已写入 ${gb(written)} GB / ${gb(total)} GB"+(if(state=="completed")" · 实际分配 ${gb(item.optLong("allocated"))} GB" else ""),12f,R.color.dim))
                jobs.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=1000;progress=if(total==0L){if(state=="completed")1000 else 0}else (written.toDouble()/total*1000).toInt().coerceIn(0,1000)
                    progressTintList=android.content.res.ColorStateList.valueOf(color(R.color.accent))
                },LinearLayout.LayoutParams(-1,dp(8)).apply { topMargin=dp(8);bottomMargin=dp(8) })
                if(item.has("error"))jobs.addView(label(item.getString("error"),12f,R.color.danger))
                if(active)jobs.addView(button("取消创建") {
                    if(!busy)task {
                        setBusy(true)
                        try { pinnedCall(origin,"placeholders",JSONObject().put("action","cancel").put("job_id",item.getString("id")));status("已请求取消，等待清理完成") }
                        finally { setBusy(false) }
                        refreshNow()
                    }
                })
                else jobs.addView(button("在文件管理定位") {
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

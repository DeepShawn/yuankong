package com.aicode.lancmd

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aicode.lancmd.net.joinPath
import com.aicode.lancmd.net.parentOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject
import top.yukonga.miuix.kmp.extra.SuperDropdown
import java.math.BigDecimal

/** 创建隐藏＋系统属性的占位文件，实际占用磁盘空间。界面使用 miuix。 */
class PlaceholderFilesActivity : MiuixActivity() {
    private val modes = listOf("稳定（推荐，优先响应）", "快速（优先生成速度）")

    /** 一条占位任务在界面上的样子。 */
    internal class Item(
        val id: String,
        val title: String,
        val path: String,
        val modeName: String?,
        val sizeLine: String,
        val progress: Float?,
        val error: String?,
        val waitingDisk: Boolean,
        val active: Boolean,
        val canCancel: Boolean,
        val failed: Boolean
    )

    private var targetPath by mutableStateOf("")
    internal var sizeText by mutableStateOf("0")
    internal var modeIndex by mutableIntStateOf(0)
    internal var items by mutableStateOf<List<Item>>(emptyList())
    internal var busy by mutableStateOf(false)
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPath = savedInstanceState?.getString("target")
            ?: joinPath(intent.getStringExtra("directory").orEmpty(), "LanCmd-placeholder.dat")
        sizeText = savedInstanceState?.getString("size") ?: "0"
        modeIndex = savedInstanceState?.getInt("modeIndex", 0)?.coerceIn(0, 1) ?: 0
        status("实际占用磁盘空间 · 0–500 GB · 1 GB = 1,000,000,000 字节")
        content { Screen() }
    }

    @Composable
    private fun Screen() = Page("隐藏系统占位文件") {
        SectionCard("新占位文件") {
            Field(label = "新文件完整路径", value = targetPath, onValueChange = { targetPath = it })
            Field(
                label = "占用大小（GB，支持小数，0 表示空文件）",
                value = sizeText,
                onValueChange = { sizeText = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            SuperDropdown(
                items = modes,
                title = "生成模式",
                selectedIndex = modeIndex,
                onSelectedIndexChange = { modeIndex = it }
            )
            Note("稳定模式适度让行前台操作；快速模式优先生成速度。两种模式都优先预分配，响应变慢时自动减小批次。")
            Note(
                "创建隐藏＋系统属性的普通文件，优先自适应分批预分配真实空间，不支持时逐块写入；" +
                    "不覆盖同名文件，保留至少 1 GB 可用空间。慢速 U 盘会自动缩小批次。"
            )
            Action("创建占位文件", enabled = !busy, primary = true) { confirmCreate() }
        }
        SectionCard("占位任务") {
            Action("刷新占位任务", enabled = !busy) { refreshJobs() }
            if (items.isEmpty()) EmptyNote("本次接收端运行期间暂无占位任务。重启后可在目标目录管理已完成文件。")
            items.forEach { item ->
                InfoItem(item.title, listOfNotNull(item.modeName, item.path, item.sizeLine).joinToString("\n"))
                item.progress?.let { Progress(it) }
                item.error?.let { Note(it) }
                if (item.waitingDisk) Note("正在等待磁盘响应，进度暂未更新；取消会在当前磁盘调用返回后清理。")
                if (item.canCancel) Action("取消创建", enabled = !busy) { cancel(item) }
                else if (!item.active) Action("在文件管理定位", enabled = !busy) { locate(item) }
            }
        }
        Note(
            "Windows 资源管理器默认隐藏这类文件；开启显示受保护的操作系统文件后仍可查看。" +
                "安卓文件管理始终列出，可直接删除释放空间。手机断线继续创建；取消、失败或接收端退出会清理未完成文件。"
        )
    }

    override fun onStart() {
        super.onStart()
        polling = task {
            while (true) {
                if (!busy) {
                    try { refreshNow() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status(e.message ?: "读取占位任务失败", true) }
                }
                delay(1500)
            }
        }
    }

    override fun onStop() {
        polling?.cancel(); polling = null; super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("target", targetPath)
        outState.putString("size", sizeText)
        outState.putInt("modeIndex", modeIndex)
        super.onSaveInstanceState(outState)
    }

    internal fun confirmCreate() {
        if (busy) return
        val path = targetPath.trim()
        if (path.isEmpty()) { status("请输入新文件完整路径", true); return }
        val bytes = runCatching { parseSize(sizeText) }.getOrNull()
        if (bytes == null) { status("请输入 0–500 GB，最多精确到 1 字节", true); return }
        val selectedMode = if (modeIndex == 1) "fast" else "stable"
        val modeName = if (selectedMode == "stable") "稳定" else "快速"
        AlertDialog.Builder(this).setTitle("创建并实际占用空间？")
            .setMessage(
                "$path\n\n大小：$sizeText GB（$bytes 字节）\n模式：$modeName\n" +
                    "将实际分配磁盘空间，至少保留 1 GB 可用空间。可在任务中取消；已完成文件可在文件管理删除。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                if (!busy) task {
                    busy = true
                    try {
                        val features = pinnedCall(origin, "capabilities").optJSONArray("features")
                        check(
                            features != null &&
                                (0 until features.length()).any { features.optString(it) == "placeholders_modes" }
                        ) { "请先停止并完整更新 Windows 3.2.2 接收端，旧版不支持稳定模式" }
                        FileDelay.call(
                            this@PlaceholderFilesActivity, origin, "placeholders",
                            JSONObject().put("action", "create").put("path", path)
                                .put("size", bytes).put("mode", selectedMode)
                        )
                        status("创建任务已提交，进度显示在下方")
                    } finally { busy = false }
                    refreshNow()
                }
            }.show()
    }

    internal fun cancel(item: Item) {
        if (busy) return
        task {
            busy = true
            try {
                val cancelled = pinnedCall(
                    origin, "placeholders",
                    JSONObject().put("action", "cancel").put("job_id", item.id)
                )
                status(
                    if (cancelled.optBoolean("cancel_requested", true)) "已请求取消，等待磁盘返回并清理完成"
                    else "任务已进入完成阶段，请等待结果"
                )
            } finally { busy = false }
            refreshNow()
        }
    }

    internal fun locate(item: Item) = task {
        // 导航前先确认仍是打开页面时的那台电脑。
        pinnedCall(origin, "capabilities")
        startActivity(
            Intent(this@PlaceholderFilesActivity, StorageActivity::class.java)
                .putExtra("path", parentOf(item.path))
        )
    }

    internal fun refreshJobs() {
        if (!busy) task { refreshNow() }
    }

    private suspend fun refreshNow() {
        if (busy) return
        busy = true
        try {
            val result = pinnedCall(origin, "placeholders")
            val list = result.getJSONArray("items")
            items = (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                val state = item.optString("status")
                val active = state in listOf(
                    "queued", "checking", "allocating", "writing", "flushing",
                    "verifying", "cancelling", "cleaning", "committing"
                )
                val total = item.optLong("size")
                val written = item.optLong("written")
                Item(
                    id = item.getString("id"),
                    title = when (state) {
                        "queued" -> "等待创建"
                        "checking" -> "正在检查目录与磁盘容量"
                        "allocating" -> "正在快速分配真实空间"
                        "writing" -> "正在占用空间"
                        "flushing" -> "正在刷新写入缓存"
                        "verifying" -> "正在核实磁盘分配"
                        "cancelling" -> "正在取消，等待磁盘返回并清理"
                        "cleaning" -> "正在关闭文件并清理"
                        "committing" -> "正在完成文件"
                        "completed" -> "创建完成"
                        "cancelled" -> "已取消并清理"
                        else -> "创建失败"
                    },
                    path = item.getString("path"),
                    modeName = if (!item.has("mode")) null
                    else if (item.optString("mode") == "stable") "稳定模式" else "快速模式",
                    sizeLine = "已完成 ${gb(written)} GB / ${gb(total)} GB" +
                        if (state == "completed") " · 实际分配 ${gb(item.optLong("allocated"))} GB" else "",
                    progress = if (active && state != "writing") null
                    else if (total == 0L) (if (state == "completed") 1f else 0f)
                    else (written.toDouble() / total).toFloat().coerceIn(0f, 1f),
                    error = if (item.has("error")) item.getString("error") else null,
                    waitingDisk = active && item.optLong("idle_seconds") >= 5,
                    active = active,
                    canCancel = active &&
                        item.optBoolean("can_cancel", state !in listOf("cancelling", "cleaning", "committing")),
                    failed = state == "failed"
                )
            }
        } finally { busy = false }
    }

    companion object {
        internal fun parseSize(value: String): Long {
            val text = value.trim()
            require(Regex("(?:[0-9]{1,3}(?:\\.[0-9]{0,9})?|\\.[0-9]{1,9})").matches(text))
            val gb = BigDecimal(text)
            require(gb >= BigDecimal.ZERO && gb <= BigDecimal("500"))
            return gb.multiply(BigDecimal("1000000000")).longValueExact()
        }

        private fun gb(bytes: Long) =
            BigDecimal.valueOf(bytes).divide(BigDecimal("1000000000")).stripTrailingZeros().toPlainString()
    }
}

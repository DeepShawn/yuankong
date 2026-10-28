package com.aicode.lancmd

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.ConnectionManager.LogKind
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 进程管理 GUI：列表 + 搜索 + 排序 + 单个结束 + 批量结束。
 * 接收端回传自身 PID，界面禁止结束它，避免“点一下就自断连接”。
 */
class ProcessDialog(
    private val ctx: Context,
    private val scope: LifecycleCoroutineScope,
    private val onLog: (String, LogKind) -> Unit
) {
    data class Proc(
        val name: String,
        val pid: Int,
        val memBytes: Long,
        val memText: String,
        val session: String
    )

    private val all = ArrayList<Proc>()
    private val shown = ArrayList<Proc>()
    private val selected = LinkedHashSet<Int>()
    private var selfPid = -1
    private var query = ""
    private var sortByMemory = true
    private var loading = false
    private var killing = false
    private var loadSequence = 0
    private var loadJob: Job? = null
    private var killJob: Job? = null

    private lateinit var dialog: AlertDialog
    private lateinit var adapter: Adapter
    private lateinit var tvCount: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var btnSort: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvPrivilege: TextView

    fun show() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_process, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvProcess)
        tvCount = view.findViewById(R.id.tvCount)
        pbLoading = view.findViewById(R.id.pbLoading)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnSort = view.findViewById(R.id.btnSortMem)
        etSearch = view.findViewById(R.id.etSearch)
        tvPrivilege = view.findViewById(R.id.tvPrivilege)
        tvPrivilege.setOnClickListener { showPrivilegeHelp() }

        adapter = Adapter()
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })

        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnRefresh.setOnClickListener { load() }
        btnSort.setOnClickListener {
            sortByMemory = !sortByMemory
            btnSort.text = ctx.getString(if (sortByMemory) R.string.sort_memory else R.string.sort_name)
            applyFilter()
        }

        dialog = WorkspaceDialog.Builder(ctx)
            .setTitle(R.string.process_title)
            .setView(view)
            .setPositiveButton(R.string.end_process, null)
            .setNeutralButton("全选", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Themes.color(ctx, R.color.danger))
                setOnClickListener { killSelected() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { toggleSelectAll() }
            updateCount()
        }
        dialog.setOnDismissListener { loadJob?.cancel(); killJob?.cancel() }
        dialog.show()
        load()
    }

    // ------------------------------------------------------------ 数据

    private fun load() {
        if (killing) return
        val sequence = ++loadSequence
        loadJob?.cancel()
        loading = true
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        updateCount()
        loadJob = scope.launch {
            try {
                val r = ConnectionManager.send("ps", JSONObject(), timeoutMs = 25_000)
                if (sequence != loadSequence || !dialog.isShowing) return@launch
                if (!r.optBoolean("ok")) {
                    fail(r.optString("error", "获取进程失败"))
                    return@launch
                }
                selfPid = r.optInt("self_pid", -1)
                tvPrivilege.text = when (r.opt("admin") as? Boolean) {
                    true -> "电脑权限：管理员 · 点击查看权限说明"
                    false -> "电脑权限：标准用户 · 点击查看管理员启动说明"
                    null -> "电脑权限：未知 · 请更新接收端后刷新"
                }
                val items = r.optJSONArray("items")
                all.clear()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        all.add(
                            Proc(
                                name = o.optString("name", "?"),
                                pid = o.optInt("pid", -1),
                                memBytes = o.optLong("mem_bytes", 0),
                                memText = o.optString("mem", ""),
                                session = o.optString("session", "")
                            )
                        )
                    }
                }
                applyFilter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sequence == loadSequence && dialog.isShowing) fail(e.message ?: "获取进程失败")
            } finally {
                if (sequence == loadSequence) {
                    loading = false
                    pbLoading.visibility = View.GONE
                    if (dialog.isShowing) updateCount()
                }
            }
        }
    }

    private fun fail(msg: String) {
        all.clear(); shown.clear(); selected.clear()
        adapter.notifyDataSetChanged()
        pbLoading.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = msg
        tvPrivilege.text = "电脑权限：未能读取 · 点击查看说明"
        updateCount()
    }

    private fun applyFilter() {
        shown.clear()
        val list = all.filter {
            query.isEmpty() || it.name.lowercase().contains(query) || it.pid.toString() == query
        }
        val sorted = if (sortByMemory) {
            list.sortedByDescending { it.memBytes }
        } else {
            list.sortedBy { it.name.lowercase() }
        }
        shown.addAll(sorted)
        selected.retainAll(shown.filter { selectable(it) }.map { it.pid }.toSet())
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty()) tvEmpty.text = ctx.getString(R.string.no_process)
        updateCount()
    }

    private fun updateCount() {
        val sel = selected.count { pid -> shown.any { it.pid == pid } }
        tvCount.text = buildString {
            append("${shown.size} 个进程")
            if (all.size != shown.size) append(" / 共 ${all.size}")
            if (sel > 0) append("  ·  已选 $sel")
        }
        val targets = shown.filter { selectable(it) }
        val busy = loading || killing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy && sel > 0
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            isEnabled = !busy && targets.isNotEmpty()
            text = if (targets.isNotEmpty() && targets.all { it.pid in selected }) "取消全选" else "全选"
        }
        btnRefresh.isEnabled = !busy
        btnSort.isEnabled = !killing
        etSearch.isEnabled = !killing
    }

    private fun selectable(p: Proc) = p.pid > 1 && p.pid != selfPid

    private fun showPrivilegeHelp() {
        WorkspaceDialog.Builder(ctx)
            .setTitle("电脑接收端权限")
            .setMessage("在电脑打开新版 Start.exe，点击「管理员启动 / 重启」，并在 Windows UAC 中允许。\n\n如果已运行普通权限接收端，需要重启后权限才会改变，手机会短暂断开并自动重连。完成后刷新进程列表查看实际权限。\n\n管理员权限不等于系统最高权限；受保护的系统进程仍可能拒绝操作。")
            .setPositiveButton("知道了", null).show()
    }

    private fun toggleSelectAll() {
        if (loading || killing) return
        val targets = shown.filter { selectable(it) }
        if (targets.isEmpty()) return
        val allSelected = targets.all { selected.contains(it.pid) }
        if (allSelected) selected.removeAll(targets.map { it.pid }.toSet())
        else targets.forEach { selected.add(it.pid) }
        updateCount()
        adapter.notifyDataSetChanged()
    }

    private fun killSelected() {
        if (loading || killing) return
        val targets = shown.filter { it.pid in selected && selectable(it) }
        if (targets.isEmpty()) return
        val names = targets.take(4).joinToString("、") { "${it.name}(${it.pid})" }
        val more = if (targets.size > 4) " 等 ${targets.size} 个进程" else ""

        WorkspaceDialog.Builder(ctx)
            .setTitle("结束 ${targets.size} 个进程")
            .setMessage("$names$more\n\n强制结束可能丢失未保存数据。")
            .setPositiveButton("结束") { _, _ ->
                doKill(targets)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doKill(targets: List<Proc>) {
        if (loading || killing || targets.isEmpty()) return
        killing = true
        selected.removeAll(targets.map { it.pid }.toSet())
        updateCount()
        adapter.notifyDataSetChanged()
        pbLoading.visibility = View.VISIBLE
        killJob = scope.launch {
            try {
                var ok = 0
                val failed = ArrayList<String>()
                for (p in targets) {
                    try {
                        val r = ConnectionManager.send(
                            "kill",
                            JSONObject().put("pid", p.pid).put("force", true),
                            timeoutMs = 15_000
                        )
                        if (r.optBoolean("ok")) {
                            ok++
                            onLog("已结束 ${p.name} (PID ${p.pid})", LogKind.OK)
                        } else {
                            failed.add("${p.name}: ${r.optString("error", "失败")}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.add("${p.name}: ${e.message}")
                    }
                }
                for (f in failed) onLog("结束失败 $f", LogKind.ERROR)
                if (failed.isEmpty()) onLog("已结束 $ok 个进程", LogKind.OK)
            } finally {
                killing = false
                pbLoading.visibility = View.GONE
                if (dialog.isShowing) load()
            }
        }
    }

    private fun killOne(p: Proc) {
        if (loading || killing || !selectable(p)) return
        WorkspaceDialog.Builder(ctx)
            .setTitle("结束进程")
            .setMessage("${p.name}\nPID ${p.pid}  ·  ${p.memText}\n\n强制结束可能丢失未保存数据。")
            .setPositiveButton("结束") { _, _ -> doKill(listOf(p)) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 列表

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val mark: View = v.findViewById(R.id.vMark)
            val name: TextView = v.findViewById(R.id.tvName)
            val meta: TextView = v.findViewById(R.id.tvMeta)
            val kill: ImageView = v.findViewById(R.id.ivKill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_process, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = shown[position]
            val isSelf = p.pid == selfPid
            val isProtected = !selectable(p)
            val isSelected = selected.contains(p.pid)

            holder.name.text = p.name
            holder.meta.text = buildString {
                append("PID ").append(p.pid)
                if (p.memText.isNotEmpty()) append("  ·  ").append(p.memText)
                if (p.session.isNotEmpty()) append("  ·  ").append(p.session)
                if (isSelf) append("  ·  接收端自身")
                else if (isProtected) append("  ·  系统保留进程")
            }

            holder.name.setTextColor(
                Themes.color(ctx, if (isSelf) R.color.warn else R.color.text)
            )
            holder.name.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            holder.mark.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            holder.kill.visibility = if (isProtected) View.INVISIBLE else View.VISIBLE
            holder.itemView.setBackgroundColor(
                if (isSelected) Themes.color(ctx, R.color.surface_variant)
                else android.graphics.Color.TRANSPARENT
            )

            holder.itemView.setOnClickListener {
                if (loading || killing) return@setOnClickListener
                if (isProtected) {
                    onLog(if (isSelf) "不能结束接收端自身，否则会断开连接" else "不能结束系统保留进程", LogKind.ERROR)
                    return@setOnClickListener
                }
                if (!selected.remove(p.pid)) selected.add(p.pid)
                notifyItemChanged(position)
                updateCount()
            }
            holder.kill.setOnClickListener {
                if (!isProtected) killOne(p)
            }
        }
    }
}

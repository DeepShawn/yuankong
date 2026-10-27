package com.aicode.lancmd

import android.view.View
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.joinPath
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A directory must be successfully listed before it can be selected. */
class RemoteDirectoryPicker(private val activity: AppCompatActivity,private val expectedClient:AgentClient?=null) {
    fun show(initial: String, selected: (String) -> Unit) {
        val client = expectedClient ?: runCatching { ConnectionManager.sessionClient() }.getOrElse { return }
        if(client.closed || runCatching { ConnectionManager.sessionClient() }.getOrNull()!==client)return
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val location = TextView(activity).apply { setTextIsSelectable(true); textSize = 13f }
        body.addView(location)
        val drives = LinearLayout(activity)
        body.addView(HorizontalScrollView(activity).apply { addView(drives) })
        val up = MaterialButton(activity).apply { text = "上一级" }
        body.addView(up)
        val list = ListView(activity)
        body.addView(list, LinearLayout.LayoutParams(-1, dp(240)))
        val note = TextView(activity).apply { textSize = 12f; text = "点选文件夹进入，再选择当前文件夹" }
        body.addView(note)
        val dialog = AlertDialog.Builder(activity).setTitle("选择电脑目标文件夹").setView(body)
            .setPositiveButton("选择此文件夹", null).setNegativeButton("取消", null).create()
        var job: Job? = null
        var current = ""
        var parent = ""
        var sequence = 0
        suspend fun call(cmd: String, params: JSONObject = JSONObject()): JSONObject {
            check(ConnectionManager.sessionClient() === client) { "连接已切换，请重新选择目标电脑" }
            val result = client.call(cmd, params)
            check(ConnectionManager.sessionClient() === client) { "连接已切换，请重新选择目标电脑" }
            check(result.optBoolean("ok")) { result.optString("error", "读取目录失败") }
            return result
        }
        fun load(path: String, discover: Boolean = false) {
            job?.cancel()
            val request = ++sequence
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            list.isEnabled = false; up.isEnabled = false
            note.text = "正在读取文件夹…"
            job = activity.lifecycleScope.launch {
                try {
                    if (discover) {
                        val storage = call("storage").getJSONArray("drives")
                        for (i in 0 until storage.length()) {
                            val root = storage.getJSONObject(i).getString("path")
                            drives.addView(MaterialButton(activity).apply { text = root; setOnClickListener { load(root) } })
                        }
                    }
                    val result = call("ls", JSONObject().put("path", path))
                    if (request != sequence || !dialog.isShowing) return@launch
                    current = result.getString("path")
                    parent = result.optString("parent", current)
                    location.text = current
                    val items = result.getJSONArray("items")
                    val folders = (0 until items.length()).map { items.getJSONObject(it) }
                        .filter { it.optBoolean("dir") }.map { it.getString("name") }.sortedBy { it.lowercase() }
                    list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, folders.map { "📁  $it" })
                    list.setOnItemClickListener { _, _, index, _ -> load(joinPath(current, folders[index])) }
                    list.isEnabled = true; up.isEnabled = parent.isNotBlank() && parent != current
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    note.text = if (folders.isEmpty()) "此目录没有子文件夹，可直接选择当前文件夹" else "${folders.size} 个文件夹 · 选择当前文件夹作为接收位置"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (request == sequence) {
                        note.text = "${e.message ?: "无法读取目录"}。请选择磁盘重试。"
                        up.isEnabled = current.isNotBlank() && parent != current
                    }
                }
            }
        }
        up.setOnClickListener { load(parent) }
        dialog.setOnDismissListener { sequence++; job?.cancel() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (current.isNotBlank() && ConnectionManager.isConnected() &&
                    runCatching { ConnectionManager.sessionClient() === client }.getOrDefault(false)) {
                    dialog.dismiss(); selected(current)
                } else note.text = "连接已切换，请关闭后重新选择目标电脑"
            }
            load(initial, discover = true)
        }
        dialog.show()
    }
}

package com.aicode.lancmd

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class AudioDialog(private val activity: AppCompatActivity) {
    fun show() {
        val client = runCatching { ConnectionManager.sessionClient() }.getOrElse { return }
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val info = TextView(activity).apply { text = "正在读取电脑音量…"; textSize = 16f }
        body.addView(info)
        val slider = SeekBar(activity).apply { max = 100; contentDescription = "电脑主音量"; id = R.id.audioSlider }
        body.addView(slider)
        val row = LinearLayout(activity)
        body.addView(row)
        val controls = ArrayList<android.view.View>().apply { add(slider) }
        var volume = 0
        var muted = false
        var loaded = false
        var job: Job? = null
        lateinit var mute: MaterialButton
        val dialog = AlertDialog.Builder(activity).setTitle("电脑音量").setView(body).setNegativeButton("关闭", null).create()
        fun update(params: JSONObject = JSONObject().put("action", "get")) {
            if (job?.isActive == true) return
            controls.forEach { it.isEnabled = false }
            job = activity.lifecycleScope.launch {
                try {
                    check(ConnectionManager.sessionClient() === client) { "连接已切换，请重新打开音量面板" }
                    val result = client.call("audio", params)
                    check(ConnectionManager.sessionClient() === client) { "连接已切换，请重新打开音量面板" }
                    check(result.optBoolean("ok")) {
                        val error = result.optString("error", "读取音量失败")
                        if (error.contains("未知命令")) "$error，请更新 Windows 接收端至 1.7.0" else error
                    }
                    volume = result.getInt("volume"); muted = result.getBoolean("muted"); loaded = true
                    slider.progress = volume
                    info.text = "$volume%${if (muted) " · 已静音" else ""}"
                    mute.text = if (muted) "取消静音" else "静音"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { loaded = false; info.text = e.message ?: "读取音量失败" }
                finally { controls.forEach { it.isEnabled = loaded || it.tag == "refresh" } }
            }
        }
        listOf("−10" to -10, "+10" to 10).forEach { (title, amount) ->
            val button = MaterialButton(activity).apply {
                text = title; minWidth = 0
                setOnClickListener { update(JSONObject().put("action", "set").put("volume", (volume + amount).coerceIn(0, 100))) }
            }
            controls.add(button); row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        mute = MaterialButton(activity).apply {
            text = "静音"; minWidth = 0; id = R.id.audioMute
            setOnClickListener { update(JSONObject().put("action", "set").put("muted", !muted)) }
        }
        controls.add(mute); row.addView(mute, LinearLayout.LayoutParams(0, -2, 1f))
        val presets = LinearLayout(activity)
        listOf(0, 25, 50, 75, 100).forEach { percent ->
            val button = MaterialButton(activity).apply {
                text = "$percent%"; textSize = 11f; minWidth = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { update(JSONObject().put("action", "set").put("volume", percent)) }
            }
            controls.add(button); presets.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(presets)
        body.addView(MaterialButton(activity).apply {
            text = "刷新"; tag = "refresh"; controls.add(this); setOnClickListener { update() }
        })
        body.addView(TextView(activity).apply { text = "调节 Windows 默认输出设备的主音量。静音状态单独控制。"; textSize = 12f })
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) { if (user) info.text = "$progress%" }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { update(JSONObject().put("action", "set").put("volume", bar.progress)) }
        })
        dialog.setOnDismissListener { job?.cancel() }
        dialog.setOnShowListener { update() }
        dialog.show()
    }
}

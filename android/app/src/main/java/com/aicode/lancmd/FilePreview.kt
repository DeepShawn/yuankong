package com.aicode.lancmd

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.AgentClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object FilePreview {
    fun show(activity: AppCompatActivity, client: AgentClient?, path: String) {
        val text = TextView(activity).apply {
            setPadding(24, 24, 24, 24); setTextColor(Themes.color(activity, R.color.text))
            textSize = 13f; setTextIsSelectable(true); text = "正在读取预览…"
        }
        val scroll = ScrollView(activity).apply { addView(text) }
        val dialog = WorkspaceDialog.Builder(activity).setTitle(path.substringAfterLast('\\').substringAfterLast('/'))
            .setView(scroll).setPositiveButton("关闭", null).create()
        val job = activity.lifecycleScope.launch {
            try {
                val result = FileDelay.call(activity,client, "files", JSONObject().put("action", "preview").put("path", path))
                when (result.getString("kind")) {
                    "text" -> {
                        text.typeface = android.graphics.Typeface.MONOSPACE
                        text.text = result.optString("text") + if (result.optBoolean("truncated")) "\n\n[仅显示前 128 KB]" else ""
                    }
                    "archive" -> {
                        val items = result.getJSONArray("items")
                        text.text = "ZIP · ${result.optInt("count")} 项\n\n" + (0 until items.length()).joinToString("\n") {
                            val item = items.getJSONObject(it)
                            (if (item.optBoolean("dir")) "📁 " else "") + item.optString("name") + "  ${item.optLong("size")} B"
                        } + if (result.optBoolean("truncated")) "\n[仅显示前 500 项]" else ""
                    }
                    "image" -> {
                        val encoded = result.getString("data")
                        check(encoded.length <= 3 * 1024 * 1024) { "图片预览过大" }
                        val bitmap = withContext(Dispatchers.Default) {
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            check(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
                            options.inSampleSize = 1
                            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                            options.inJustDecodeBounds = false
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("无法解码图片")
                        }
                        scroll.removeAllViews()
                        scroll.addView(ImageView(activity).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bitmap) })
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { text.text = e.message ?: "无法预览" }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }
}

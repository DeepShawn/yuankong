package com.aicode.lancmd

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.aicode.lancmd.net.ConnectionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

abstract class FeatureActivity : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected lateinit var statusText: TextView

    protected fun setup(title: String, description: String, scrollable: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setTitleTextColor(color(R.color.text))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(color(R.color.text))
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        if (scrollable) {
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        statusText = label(description, 13f, R.color.dim).apply {
            setPadding(0, dp(4), 0, dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(statusText)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
    }

    protected fun label(text: String, size: Float = 14f, tint: Int = R.color.text) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(tint))
    }

    protected fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        cornerRadius = dp(14)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.surface))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.text), color(R.color.dim))))
        strokeWidth = dp(1)
        strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.outline))
        setOnClickListener { action() }
    }

    protected fun primary(button: MaterialButton): MaterialButton = button.apply {
        backgroundTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.accent), color(R.color.surface)))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.bg), color(R.color.dim))))
        strokeWidth = 0
    }

    protected fun panel(): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline)
            setCardBackgroundColor(color(R.color.surface))
            addView(content)
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return content
    }

    protected suspend fun request(command: String, params: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
        val result = ConnectionManager.send(command, params, timeout)
        if (!result.optBoolean("ok")) {
            val error = result.optString("error", "操作失败")
            throw IOException(if (error.contains("未知命令")) "$error，请更新电脑端至 1.5.0" else error)
        }
        return result
    }

    protected fun task(block: suspend () -> Unit): Job = lifecycleScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(e.message ?: "操作失败", true) }
    }

    protected fun status(text: String, error: Boolean = false) {
        if (isDestroyed) return
        statusText.text = text
        statusText.setTextColor(color(if (error) R.color.danger else R.color.dim))
    }

    protected fun color(id: Int): Int = ContextCompat.getColor(this, id)
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

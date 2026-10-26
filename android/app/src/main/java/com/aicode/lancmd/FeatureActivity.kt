package com.aicode.lancmd

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
    }

    protected fun setup(title: String, description: String, scrollable: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setTitleTextAppearance(this@FeatureActivity, R.style.TextAppearance_LanCmd_ToolbarTitle)
            setTitleTextColor(color(R.color.text))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(color(R.color.text))
            setNavigationOnClickListener { navigateBack() }
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.status_bg)
            setLineSpacing(dp(2).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(12) })
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

    protected open fun navigateBack() { finish() }

    /** 统一输入框外观：圆角描边底、聚焦时高亮、主题文字与提示色。 */
    protected fun styleInput(view: EditText): EditText = view.apply {
        setBackgroundResource(R.drawable.input_bg)
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.dim))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        textSize = 14f
        if (minHeight < dp(48)) minHeight = dp(48)
    }

    /** 统一复选框外观：主题文字色与选中时的强调色勾选。 */
    protected fun styleCheck(view: CheckBox): CheckBox = view.apply {
        setTextColor(color(R.color.text))
        textSize = 13f
        setPadding(dp(6), dp(6), 0, dp(6))
        buttonTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.accent), color(R.color.dim)))
    }

    /** 统一下拉框外观：圆角描边底与内边距，弹出项保持系统默认。 */
    protected fun styleSpinner(view: Spinner): Spinner = view.apply {
        setBackgroundResource(R.drawable.input_bg)
        minimumHeight = dp(48)
        if (paddingStart < dp(12)) setPadding(dp(12), paddingTop, dp(12), paddingBottom)
    }

    /** 并排等分的按钮自动留出间距，避免相邻按钮贴在一起。 */
    private class SpacedButton(context: android.content.Context) : MaterialButton(context) {
        override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
            if (params is LinearLayout.LayoutParams && params.width == 0 && params.weight > 0f &&
                params.leftMargin == 0 && params.rightMargin == 0) {
                val gap = (3 * resources.displayMetrics.density).toInt()
                params.leftMargin = gap; params.rightMargin = gap
            }
            super.setLayoutParams(params)
        }
    }

    protected fun button(text: String, action: () -> Unit): MaterialButton = SpacedButton(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        cornerRadius = dp(14)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        rippleColor = android.content.res.ColorStateList.valueOf(color(R.color.accent_glow))
        backgroundTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.surface_variant), color(R.color.surface)))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.text), color(R.color.dim))))
        strokeWidth = dp(1)
        strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.outline_soft))
        setOnClickListener { action() }
    }

    protected fun primary(button: MaterialButton): MaterialButton = button.apply {
        backgroundTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.accent), color(R.color.surface)))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color(R.color.on_accent), color(R.color.dim))))
        rippleColor = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        strokeWidth = 0
    }

    protected fun panel(): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline_soft)
            setCardBackgroundColor(color(R.color.surface))
            addView(content)
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
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

    protected fun color(id: Int): Int = Themes.color(this, id)
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

package com.aicode.lancmd

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 外观主题选择：主题色与背景色调，应用后重建当前页面。 */
class ThemeDialog(private val activity: AppCompatActivity) {
    private var accent = Themes.accent(activity)
    private var tone = Themes.tone(activity)
    private val accentViews = ArrayList<Pair<Themes.Option, View>>()
    private val toneViews = ArrayList<Pair<Themes.Option, View>>()

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = Themes.color(activity, id)

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(8))
        }
        body.addView(section("主题色"))
        Themes.accents.chunked(2).forEach { group ->
            val row = LinearLayout(activity)
            group.forEach { option ->
                val chip = accentChip(option)
                accentViews.add(option to chip)
                row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        body.addView(section("背景色调"))
        Themes.tones.chunked(2).forEach { group ->
            val row = LinearLayout(activity)
            group.forEach { option ->
                val chip = toneChip(option)
                toneViews.add(option to chip)
                row.addView(chip, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            body.addView(row)
        }
        body.addView(TextView(activity).apply {
            text = "设置保存在本机，仅改变外观，不影响连接与功能。"
            textSize = 11.5f
            setTextColor(color(R.color.dim))
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        refresh()
        WorkspaceDialog.Builder(activity)
            .setTitle("外观主题")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                if (accent.key != Themes.accent(activity).key || tone.key != Themes.tone(activity).key) {
                    Prefs.saveTheme(activity, accent.key, tone.key)
                    activity.recreate()
                }
            }
            .show()
    }

    private fun section(title: String) = TextView(activity).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.06f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(R.color.dim))
        setPadding(dp(4), dp(12), 0, dp(8))
    }

    private fun accentChip(option: Themes.Option): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true; isFocusable = true
            contentDescription = "主题色 ${option.label}"
            setOnClickListener { accent = option; refresh() }
        }
        val swatch = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(option.swatch, option.swatch2)).apply {
                shape = GradientDrawable.OVAL
            }
        }
        swatch.addView(TextView(activity).apply {
            text = "✓"; textSize = 18f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xE6101010.toInt())
            tag = "check"
        }, FrameLayout.LayoutParams(-1, -1))
        box.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)))
        box.addView(TextView(activity).apply {
            text = option.label; textSize = 12.5f; gravity = Gravity.CENTER
            setTextColor(color(R.color.text))
            setPadding(dp(10), 0, 0, 0)
        })
        return box
    }

    private fun toneChip(option: Themes.Option) = TextView(activity).apply {
        text = option.label
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xFFEAF1F8.toInt())
        isClickable = true; isFocusable = true
        contentDescription = "背景色调 ${option.label}"
        setOnClickListener { tone = option; refresh() }
    }

    private fun refresh() {
        accentViews.forEach { (option, view) ->
            val selected = option.key == accent.key
            val swatch = (view as LinearLayout).getChildAt(0) as FrameLayout
            (swatch.background as GradientDrawable).setStroke(if (selected) dp(3) else 0, 0xFFFFFFFF.toInt())
            swatch.findViewWithTag<View>("check").visibility = if (selected) View.VISIBLE else View.INVISIBLE
            view.isSelected = selected
        }
        toneViews.forEach { (option, view) ->
            val selected = option.key == tone.key
            view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(option.swatch2, option.swatch)).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(if (selected) dp(2) else dp(1), if (selected) accent.swatch else 0x33FFFFFF)
            }
            view.isSelected = selected
            (view as TextView).text = if (selected) "✓ ${option.label}" else option.label
        }
    }
}

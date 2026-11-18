package com.aicode.lancmd

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils

/** 外观主题选择：主题色与背景色调，应用后重建当前页面。 */
class ThemeDialog(private val activity: AppCompatActivity) {
    private var accent = Themes.accent(activity)
    private var tone = Themes.tone(activity)
    private var style = InterfaceStyle.fromKey(Prefs.uiStyle(activity))
    private val styleViews = ArrayList<Pair<InterfaceStyle, TextView>>()
    private val accentViews = ArrayList<Pair<Themes.Option, View>>()
    private val toneViews = ArrayList<Pair<Themes.Option, View>>()
    private lateinit var preview: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var previewAction: TextView

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = Themes.color(activity, id)

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        body.addView(section("外观预览"))
        body.addView(createPreview())
        body.addView(section("界面风格"))
        for (appearance in InterfaceStyle.entries) {
            val option = TextView(activity).apply {
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(color(R.color.text))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minHeight = dp(52)
                contentDescription = "界面风格 ${appearance.label}"
                isClickable = true
                isFocusable = true
                setOnClickListener { style = appearance; refresh() }
            }
            styleViews.add(appearance to option)
            body.addView(option, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        body.addView(section("深色 / 亮色背景"))
        Themes.tones.chunked(2).forEach { group ->
            val row=LinearLayout(activity)
            group.forEach { option ->
                val chip=toneChip(option);toneViews.add(option to chip)
                row.addView(chip,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})
            }
            body.addView(row)
        }
        body.addView(section("主题色 · ${Themes.accents.size} 色"))
        Themes.accents.chunked(3).forEach { group ->
            val row = LinearLayout(activity)
            group.forEach { option ->
                val chip = accentChip(option)
                accentViews.add(option to chip)
                row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        refresh()
        WorkspaceDialog.Builder(activity)
            .setTitle("外观主题")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                if (accent.key != Themes.accent(activity).key || tone.key != Themes.tone(activity).key || style.key != Prefs.uiStyle(activity)) {
                    Prefs.saveTheme(activity, accent.key, tone.key)
                    Prefs.saveUiStyle(activity, style.key)
                    activity.recreate()
                }
            }
            .show()
    }

    private fun createPreview(): LinearLayout {
        previewLabel = TextView(activity).apply {
            text = "当前电脑"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        previewAction = TextView(activity).apply {
            text = "切换电脑"
            textSize = 12f
            setPadding(dp(9), dp(7), dp(9), dp(7))
            gravity = Gravity.CENTER
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(previewLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(previewAction, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            preview = this
        }
    }

    private fun section(title: String) = TextView(activity).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.06f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(R.color.dim))
        setPadding(dp(4), dp(12), 0, dp(8))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun accentChip(option: Themes.Option): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8));minimumHeight=dp(48)
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
        box.addView(swatch, LinearLayout.LayoutParams(dp(24), dp(24)))
        box.addView(TextView(activity).apply {
            text = option.label; textSize = 12.5f; gravity = Gravity.CENTER
            setTextColor(color(R.color.text))
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    private fun toneChip(option: Themes.Option) = TextView(activity).apply {
        text = option.label
        textSize = 13f
        minHeight = dp(48)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if(option.light)0xFF172238.toInt() else 0xFFEAF1F8.toInt())
        isClickable = true; isFocusable = true
        contentDescription = "背景色调 ${option.label}"
        setOnClickListener { tone = option; refresh() }
    }

    private fun refresh() {
        val sample = ContextThemeWrapper(activity, if (tone.light) R.style.Theme_LanCmd_Light else R.style.Theme_LanCmd).apply {
            theme.applyStyle(tone.style, true)
            theme.applyStyle(if (tone.light) accent.lightStyle else accent.style, true)
        }
        val solid = style == InterfaceStyle.MIUIX
        val surface = Themes.color(sample, R.color.surface)
        val primary = Themes.color(sample, R.color.accent)
        preview.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(if (solid) surface else ColorUtils.blendARGB(surface, primary, .12f), surface)
        ).apply {
            cornerRadius = dp(if (solid) 16 else 21).toFloat()
            setStroke(dp(1), Themes.color(sample, R.color.outline))
        }
        previewLabel.setTextColor(Themes.color(sample, R.color.text))
        previewAction.setTextColor(Themes.color(sample, R.color.on_accent))
        previewAction.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(primary, if (solid || tone.light) primary else Themes.color(sample, R.color.accent_2))
        ).apply { cornerRadius = dp(if (solid) 12 else 16).toFloat() }
        styleViews.forEach { (appearance, view) ->
            view.isSelected = appearance == style
            val name = if (view.isSelected) "✓ ${appearance.label}" else appearance.label
            view.text = SpannableString("$name\n${appearance.summary}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
                setSpan(RelativeSizeSpan(.86f), name.length + 1, length, 0)
                setSpan(ForegroundColorSpan(color(R.color.dim)), name.length + 1, length, 0)
            }
            view.background=GradientDrawable().apply {
                setColor(color(if(view.isSelected)R.color.accent_dim else R.color.surface_variant));cornerRadius=dp(16).toFloat()
                setStroke(dp(1),color(if(view.isSelected)R.color.accent else R.color.outline_soft))
            }
        }
        accentViews.forEach { (option, view) ->
            val selected = option.key == accent.key
            val swatch = (view as LinearLayout).getChildAt(0) as FrameLayout
            (swatch.background as GradientDrawable).colors=if(tone.light)intArrayOf(option.lightSwatch,option.lightSwatch) else intArrayOf(option.swatch,option.swatch2)
            (swatch.background as GradientDrawable).setStroke(if (selected) dp(3) else 0, 0xFFFFFFFF.toInt())
            swatch.findViewWithTag<View>("check").visibility = if (selected) View.VISIBLE else View.INVISIBLE
            swatch.findViewWithTag<TextView>("check").setTextColor(if(tone.light)0xFFFFFFFF.toInt() else 0xE6101010.toInt())
            view.isSelected = selected
        }
        toneViews.forEach { (option, view) ->
            val selected = option.key == tone.key
            view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(option.swatch2, option.swatch)).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(if (selected) dp(2) else dp(1), if (selected) {if(option.light)accent.lightSwatch else accent.swatch} else if(option.light)0x33526078 else 0x33FFFFFF)
            }
            view.isSelected = selected
            (view as TextView).text = if (selected) "✓ ${option.label}" else option.label
        }
    }
}

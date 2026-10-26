package com.aicode.lancmd

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

/** A width-adaptive keyboard. Modifiers apply to one key and never stay held remotely. */
class VirtualKeyboard(context: Context, private val send: (List<String>) -> Unit) : LinearLayout(context) {
    private val modifiers = linkedSetOf<String>()
    private val keys = ArrayList<MaterialButton>()
    private val modifierButtons = linkedMapOf<String, MaterialButton>()
    private val tabs = ArrayList<MaterialButton>()
    private val pages = ArrayList<View>()
    private var selectedPage = 0
    private lateinit var selectionText: TextView
    private lateinit var clear: MaterialButton

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun states(disabled: Int, selected: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(color(disabled), color(selected), color(normal)))

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(color(R.color.bg)); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), color(R.color.outline))
        }
        val sections = LinearLayout(context)
        listOf("字母", "符号", "功能").forEachIndexed { index, title ->
            val tab = styledButton(title, 13f).apply {
                contentDescription = "虚拟键盘 $title 分区"
                setOnClickListener { selectPage(index) }
            }
            tabs.add(tab)
            sections.addView(tab, keyParams(1f, 38))
        }
        addView(sections)
        row(this, listOf("Ctrl", "Alt", "Shift", "Win"), height = 44, special = true)

        val selection = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        selectionText = TextView(context).apply {
            textSize = 11f
            setPadding(dp(4), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        selection.addView(selectionText, LayoutParams(0, -2, 1f))
        clear = styledButton("清除", 11f).apply {
            contentDescription = "清除已选修饰键"
            setOnClickListener { resetModifiers() }
        }
        selection.addView(clear, keyParams(0f, 32).apply { width = dp(48) })
        addView(selection, LayoutParams(-1, dp(36)))

        val letters = page()
        row(letters, "1234567890".map { it.toString() })
        row(letters, "QWERTYUIOP".map { it.toString() })
        row(letters, "ASDFGHJKL".map { it.toString() }, inset = 0.5f)
        row(letters, "ZXCVBNM".map { it.toString() }, inset = 1.5f)

        val symbols = page()
        row(symbols, "!@#$%^&*()".map { it.toString() })
        row(symbols, listOf("`", "-", "=", "[", "]", "\\", ";", "'"))
        row(symbols, listOf("~", "_", "+", "{", "}", "|", ":", "\""))
        row(symbols, listOf(",", ".", "/", "<", ">", "?"))

        val functions = page()
        (1..12).chunked(4).forEach { group -> row(functions, group.map { "F$it" }) }
        row(functions, listOf("Home", "End", "PageUp", "PageDown"), special = true)

        row(this, listOf("Esc", "Tab", "空格", "退格", "Enter"), special = true,
            weights = listOf(1f, 1f, 2.2f, 1.3f, 1.4f))
        row(this, listOf("←", "↑", "↓", "→", "Delete"), special = true)
        selectPage(0)
        updateModifiers()
    }

    private fun styledButton(label: String, size: Float) = MaterialButton(context).apply {
        text = label
        textSize = size
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        insetTop = 0; insetBottom = 0
        setPadding(dp(1), 0, dp(1), 0)
        setSingleLine()
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        cornerRadius = dp(8)
        strokeWidth = dp(1)
        backgroundTintList = states(R.color.surface, R.color.accent_dim, R.color.surface_variant)
        setTextColor(states(R.color.dim, R.color.accent, R.color.text))
        strokeColor = states(R.color.outline, R.color.accent, R.color.outline)
        rippleColor = ColorStateList.valueOf(color(R.color.accent_dim))
        stateListAnimator = null
    }

    private fun keyParams(weight: Float, height: Int = 44) = LayoutParams(0, dp(height), weight).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun page() = LinearLayout(context).apply {
        orientation = VERTICAL
        pages.add(this)
        this@VirtualKeyboard.addView(this, LayoutParams(-1, -2))
    }

    private fun row(parent: LinearLayout, labels: List<String>, inset: Float = 0f, height: Int = 44,
                    special: Boolean = false, weights: List<Float> = labels.map { 1f }) {
        val line = LinearLayout(context)
        fun spacer() { if (inset > 0) line.addView(View(context), LayoutParams(0, 1, inset)) }
        spacer()
        labels.forEachIndexed { index, label ->
            val code = keyName(label)
            val modifier = code in MODIFIERS
            val button = styledButton(label, if (label.length == 1) 17f else if (label.length > 4) 11f else 12f).apply {
                contentDescription = "虚拟键盘 " + when (label) {
                    "←" -> "向左"; "↑" -> "向上"; "↓" -> "向下"; "→" -> "向右"
                    "Enter" -> "回车"; "Delete" -> "删除"; else -> label
                }
                if (special) backgroundTintList = states(R.color.surface, R.color.accent_dim, R.color.surface)
                if (label == "Enter") {
                    backgroundTintList = states(R.color.surface, R.color.accent, R.color.accent_dim)
                    setTextColor(states(R.color.dim, R.color.on_accent, R.color.accent))
                }
                setOnClickListener {
                    if (!this@VirtualKeyboard.isEnabled) return@setOnClickListener
                    if (modifier) {
                        if (!modifiers.add(code)) modifiers.remove(code)
                        updateModifiers()
                    } else {
                        val shifted = SHIFTED[label]
                        val chord = (modifiers.toList() + (if (shifted != null) listOf("SHIFT", shifted) else listOf(code))).distinct()
                        resetModifiers()
                        send(chord)
                    }
                }
            }
            if (modifier) modifierButtons[code] = button
            keys.add(button)
            line.addView(button, keyParams(weights[index], height))
        }
        spacer()
        parent.addView(line, LayoutParams(-1, -2))
    }

    private fun selectPage(index: Int) {
        selectedPage = index
        tabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
            ViewCompat.setStateDescription(tab, if (i == index) "当前分区" else "切换分区")
        }
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        if (::selectionText.isInitialized) updateModifiers()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!::selectionText.isInitialized) return
        keys.forEach { it.isEnabled = enabled }
        if (!enabled) resetModifiers() else updateModifiers()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && ::selectionText.isInitialized) resetModifiers()
    }

    fun resetModifiers() {
        modifiers.clear()
        updateModifiers()
    }

    private fun updateModifiers() {
        modifierButtons.forEach { (code, button) ->
            val selected = code in modifiers
            button.isSelected = selected
            button.isActivated = selected
            ViewCompat.setStateDescription(button, if (selected) "已选，仅用于下一个按键" else "未选")
        }
        selectionText.text = when {
            !isEnabled -> "开始控制后可发送按键"
            modifiers.isEmpty() -> if (selectedPage == 1) "符号按美式键位发送" else "修饰键仅作用于下一个按键"
            else -> modifiers.joinToString(" + ") { modifierButtons.getValue(it).text.toString() } + " · 下一键"
        }
        selectionText.setTextColor(color(if (modifiers.isEmpty()) R.color.dim else R.color.accent))
        clear.isEnabled = isEnabled && modifiers.isNotEmpty()
    }

    private fun keyName(label: String) = when (label) {
        "空格" -> "SPACE"; "退格" -> "BACKSPACE"; "←" -> "LEFT"; "↑" -> "UP"; "↓" -> "DOWN"; "→" -> "RIGHT"
        "-" -> "OEM_MINUS"; "=" -> "OEM_PLUS"; "[" -> "OEM_4"; "]" -> "OEM_6"
        ";" -> "OEM_1"; "'" -> "OEM_7"; "," -> "OEM_COMMA"; "." -> "OEM_PERIOD"
        "/" -> "OEM_2"; "\\" -> "OEM_5"; "`" -> "OEM_3"
        else -> label.uppercase(Locale.ROOT)
    }

    companion object {
        private val MODIFIERS = setOf("CTRL", "ALT", "SHIFT", "WIN")
        // Standard US Windows keyboard symbols; use the same physical OEM key protocol.
        private val SHIFTED = "!@#$%^&*()".mapIndexed { index, char -> char.toString() to ((index + 1) % 10).toString() }.toMap() + mapOf(
            "~" to "OEM_3", "_" to "OEM_MINUS", "+" to "OEM_PLUS", "{" to "OEM_4", "}" to "OEM_6",
            "|" to "OEM_5", ":" to "OEM_1", "\"" to "OEM_7", "<" to "OEM_COMMA", ">" to "OEM_PERIOD", "?" to "OEM_2")
    }
}

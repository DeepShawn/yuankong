package com.aicode.lancmd

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/** Modifiers apply to the next key only, so no key can remain held remotely. */
class VirtualKeyboard(context: Context, private val send: (List<String>) -> Unit) : LinearLayout(context) {
    private val modifiers = linkedSetOf<String>()
    private val buttons = ArrayList<MaterialButton>()
    private val modifierButtons = ArrayList<MaterialButton>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        val rows = listOf(
            listOf("Esc", "Tab", "Ctrl", "Alt", "Shift", "Win"),
            (1..12).map { "F$it" },
            "1234567890".map { it.toString() },
            "QWERTYUIOP".map { it.toString() },
            "ASDFGHJKL".map { it.toString() },
            "ZXCVBNM".map { it.toString() },
            listOf("Space", "Enter", "退格", "Delete"),
            listOf("Home", "End", "PageUp", "PageDown"),
            listOf("←", "↑", "↓", "→", "-", "=", "[", "]", ";", "'", ",", ".", "/", "\\", "`")
        )
        rows.forEach { names ->
            val row = LinearLayout(context)
            names.forEach { label ->
                val key = keyName(label)
                val button = MaterialButton(context).apply {
                    text = label; isAllCaps = false; textSize = 11f
                    minWidth = 0; minimumWidth = 0; minHeight = dp(44)
                    setPadding(dp(5), 0, dp(5), 0)
                    contentDescription = "虚拟键盘 $label"
                    setOnClickListener {
                        if (key in listOf("CTRL", "ALT", "SHIFT", "WIN")) {
                            if (!modifiers.add(key)) modifiers.remove(key)
                            isActivated = key in modifiers
                            alpha = if (isActivated) 0.55f else 1f
                        } else {
                            val keys = modifiers.toList() + key
                            resetModifiers()
                            send(keys)
                        }
                    }
                }
                if (key in listOf("CTRL", "ALT", "SHIFT", "WIN")) modifierButtons.add(button)
                buttons.add(button)
                row.addView(button, LayoutParams(dp(if (label.length > 3) 76 else 48), dp(48)))
            }
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        buttons.forEach { it.isEnabled = enabled }
        if (!enabled) resetModifiers()
    }

    fun resetModifiers() {
        modifiers.clear()
        modifierButtons.forEach { it.isActivated = false; it.alpha = 1f }
    }

    private fun keyName(label: String) = when (label) {
        "退格" -> "BACKSPACE"; "←" -> "LEFT"; "↑" -> "UP"; "↓" -> "DOWN"; "→" -> "RIGHT"
        "-" -> "OEM_MINUS"; "=" -> "OEM_PLUS"; "[" -> "OEM_4"; "]" -> "OEM_6"
        ";" -> "OEM_1"; "'" -> "OEM_7"; "," -> "OEM_COMMA"; "." -> "OEM_PERIOD"
        "/" -> "OEM_2"; "\\" -> "OEM_5"; "`" -> "OEM_3"
        else -> label.uppercase(java.util.Locale.ROOT)
    }
}

package com.airvoice.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * 竖屏按键面板（终端友好）：QWERTY + 常驻数字行 + 符号页 + ESC/TAB/CTRL/ALT/方向键。
 * CTRL/ALT/SHIFT 为单次锁定：点按高亮，下一个键带修饰发出后自动解锁。
 */
class KeyPanel(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onText(text: String)
        fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean)
        fun onDelete()
        fun onEnter()
    }

    private var shift = false
    private var ctrl = false
    private var alt = false
    private var symbols = false
    private lateinit var container: LinearLayout

    private val letters = listOf(
        "qwertyuiop".toList(),
        "asdfghjkl".toList(),
        "zxcvbnm".toList(),
    )

    private val symbolRows = listOf(
        listOf("`", "-", "=", "[", "]", "{", "}", "|", "\\", "~"),
        listOf(";", ":", "'", "\"", "/", "?", "!", "@", "#", "$"),
        listOf("%", "^", "&", "*", "(", ")", "_", "+"),
    )

    fun build(container: LinearLayout) {
        this.container = container
        render()
    }

    private fun render() {
        container.removeAllViews()
        // 控制行 1：ESC / TAB / CTRL / ALT / PGUP / PGDN（🎤 回语音在顶部工具行）
        addRow(
            listOf(
                Spec("ESC", 1.5f) { sendKey(KeyEvent.KEYCODE_ESCAPE) },
                Spec("TAB", 1.5f) { sendKey(KeyEvent.KEYCODE_TAB) },
                Spec("CTRL", 1.5f, modifier = { ctrl }) { ctrl = !ctrl },
                Spec("ALT", 1.5f, modifier = { alt }) { alt = !alt },
                Spec("PGUP", 1.5f) { sendKey(KeyEvent.KEYCODE_PAGE_UP) },
                Spec("PGDN", 1.5f) { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) },
            ),
            heightDp = 40,
            textSp = 13,
        )
        // 控制行 2：方向 / HOME / END
        addRow(
            listOf(
                Spec("◀") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                Spec("▲") { sendKey(KeyEvent.KEYCODE_DPAD_UP) },
                Spec("▼") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                Spec("▶") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                Spec("HOME", 1.5f) { sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
                Spec("END", 1.5f) { sendKey(KeyEvent.KEYCODE_MOVE_END) },
            ),
            heightDp = 40,
            textSp = 13,
        )
        // 常驻数字行
        addRow("1234567890".map { ch -> Spec(ch.toString()) { type(ch) } })
        // 字母/符号页
        if (symbols) {
            symbolRows.forEach { row ->
                addRow(row.map { s -> Spec(s) { type(s) } })
            }
        } else {
            addRow(letters[0].map { ch -> Spec(letter(ch)) { type(ch) } })
            addRow(letters[1].map { ch -> Spec(letter(ch)) { type(ch) } })
            addRow(
                listOf(Spec("⇧", 1.5f, modifier = { shift }) { shift = !shift; render() }) +
                    letters[2].map { ch -> Spec(letter(ch)) { type(ch) } } +
                    Spec("⌫", 1.5f) { listener.onDelete() },
            )
        }
        // 底行
        addRow(
            listOf(
                Spec(if (symbols) "abc" else "!@#", 1.5f) { symbols = !symbols; render() },
                Spec(",") { type(",") },
                Spec("空格", 4f) { type(" ") },
                Spec(".") { type(".") },
                Spec("⏎", 1.5f, accent = true) { listener.onEnter() },
            ),
        )
    }

    private fun letter(ch: Char): String =
        (if (shift) ch.uppercaseChar() else ch).toString()

    /** 普通字符：有修饰键时转 KeyEvent（Ctrl+C 等），否则直接上屏 */
    private fun type(ch: Char) = type(ch.toString())

    private fun type(text: String) {
        if (ctrl || alt) {
            val code = keyCodeFor(text.singleOrNull()?.lowercaseChar() ?: ' ')
            if (code != null) {
                listener.onKey(code, ctrl, alt)
            } else {
                listener.onText(text)
            }
            ctrl = false
            alt = false
            render()
        } else {
            listener.onText(if (shift) text.uppercase() else text)
            if (shift) {
                shift = false
                render()
            }
        }
    }

    private fun sendKey(keyCode: Int) {
        listener.onKey(keyCode, ctrl, alt)
        if (ctrl || alt) {
            ctrl = false
            alt = false
            render()
        }
    }

    private fun keyCodeFor(ch: Char): Int? = when (ch) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        ' ' -> KeyEvent.KEYCODE_SPACE
        else -> null
    }

    private data class Spec(
        val label: String,
        val weight: Float = 1f,
        val accent: Boolean = false,
        val modifier: (() -> Boolean)? = null,
        val onTap: () -> Unit,
    )

    @SuppressLint("SetTextI18n")
    private fun addRow(specs: List<Spec>, heightDp: Int = 46, textSp: Int = 16) {
        val dm = context.resources.displayMetrics
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        specs.forEach { spec ->
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (heightDp * dm.density).toInt(),
                    spec.weight,
                ).apply {
                    val m = (2 * dm.density).toInt()
                    setMargins(m, m, m, m)
                }
                text = spec.label
                textSize = textSp.toFloat()
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                val active = spec.modifier?.invoke() == true
                setBackgroundResource(
                    when {
                        active || spec.accent -> R.drawable.bg_key_round10_tint
                        else -> R.drawable.bg_key_round10
                    },
                )
                setTextColor(
                    Color.parseColor(if (active || spec.accent) "#3D5AFE" else "#3B4256"),
                )
                isAllCaps = false // 否则 Button 默认全大写，字母行会变成 QWERTY
                setOnClickListener {
                    spec.onTap()
                    if (spec.modifier != null) render() // 修饰键高亮刷新
                }
            }
            row.addView(btn)
        }
        container.addView(row)
    }
}

package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.Bootstrap
import x.vladgba.keyboard.core.KEY_KEY
import x.vladgba.keyboard.core.ROW_HEIGHT
import x.vladgba.keyboard.core.SETTING_NUMBER_ROW
import x.vladgba.keyboard.flex.FlexNode

/**
 * Optional row of digits added on top of typing layouts when they're loaded, without touching
 * the layout files (setting `numberRow`: 0 off, 1 digits, 2 digits with ! @ # … on swipe-up).
 * Skipped for number/emoji/symbol pages and for layouts that already have a row of digits.
 * A layout can set its own `"numberRow"` to override the setting (e.g. `"0"` to never get one).
 */
object NumberRow {
    const val OFF = 0
    const val DIGITS = 1
    const val SYMBOLS = 2

    private val DIGIT_KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val SHIFTED = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")

    /** Adds the row to the parsed layout [node] of layout [name] when [mode] asks for one. */
    fun apply(node: FlexNode, name: String, mode: Int) {
        val m = (node.params[SETTING_NUMBER_ROW] as? String)?.trim()?.toIntOrNull() ?: mode
        if (m != DIGITS && m != SYMBOLS) return
        if (!Bootstrap.isTextLayout(name) || hasDigitRow(node)) return
        val row = FlexNode()
        row[ROW_HEIGHT] = "0.8"
        for (i in DIGIT_KEYS.indices) {
            val key = FlexNode()
            key[KEY_KEY] = DIGIT_KEYS[i]
            if (m == SYMBOLS) {
                key.childs.add("")          // up-left
                key.childs.add(SHIFTED[i])  // up
            }
            row.childs.add(key)
        }
        node.childs.add(0, row)
    }

    /** A row where at least 8 keys are single digits. */
    private fun hasDigitRow(node: FlexNode): Boolean = node.childs.filterIsInstance<FlexNode>().any { row ->
        row.childs.filterIsInstance<FlexNode>().count { k ->
            k.str(KEY_KEY).trim().let { it.length == 1 && it[0].isDigit() }
        } >= 8
    }
}

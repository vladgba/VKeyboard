package x.vladgba.keyboard.core

import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/** Uses the actual layout parser; diagnostics do not depend on Android logging or rendering. */
object LayoutValidator {
    fun validate(text: String, knownLayouts: Set<String>? = null): List<String> {
        val problems = ArrayList<String>()
        val parser = FlexParser(text) { }
        val tokens = parser.tokenize()
        var balance = 0
        for (token in tokens) {
            if (token.type == FlexParser.TokenType.BEGIN) balance++
            if (token.type == FlexParser.TokenType.END && --balance < 0) problems.add("Unexpected closing delimiter at ${token.row}:${token.col}")
        }
        if (balance != 0) problems.add("Unbalanced layout delimiters")
        val layout = parser.parse()
        problems.addAll(parser.warnings.map { it.toString() })
        if (layout.childCount() == 0) problems.add("Layout has no rows")
        if (layout.childCount() > 32) problems.add("Layout has more than 32 rows")
        fun positive(node: FlexNode, name: String, where: String) {
            val raw = node.params[name] as? String ?: return
            val value = raw.toFloatOrNull()
            if (value == null || !value.isFinite() || value <= 0f) problems.add("$where: $name must be a positive finite number")
        }
        positive(layout, ROW_HEIGHT, "Layout")
        for ((r, value) in layout.childs.withIndex()) {
            val row = value as? FlexNode
            if (row == null) { problems.add("Row ${r + 1} is not a node"); continue }
            positive(row, ROW_HEIGHT, "Row ${r + 1}")
            if (row.childCount() == 0) problems.add("Row ${r + 1} has no keys")
            if (row.childCount() > 128) problems.add("Row ${r + 1} has more than 128 keys")
            for ((k, item) in row.childs.withIndex()) {
                val key = item as? FlexNode
                val where = "Row ${r + 1}, key ${k + 1}"
                if (key == null) { problems.add("$where is not a node"); continue }
                positive(key, KEY_WIDTH, where)
                if (key.childCount() > 8) problems.add("$where has more than eight popup positions")
                val actions = listOf(KEY_KEY, KEY_CODE, KEY_TEXT, KEY_LAYOUT, KEY_MOD_META, KEY_COMBO, KEY_MACRO,
                    KEY_HOLD, KEY_ACTION, KEY_VOICE, KEY_COMPOSE, KEY_CLIPBOARD)
                if (actions.none { key.str(it).isNotEmpty() } && !key.bool(KEY_DO_NOT_SHOW) && key.bool(KEY_VISIBLE, true)) {
                    problems.add("$where has no label or action")
                }
                if (key.has(KEY_CODE) && FlexNode.parseInt(key.str(KEY_CODE)) == null) problems.add("$where has an invalid key code")
                val mode = key.str(KEY_MODE)
                if (mode.isNotBlank() && mode !in setOf(KEY_MODE_META, KEY_MODE_JOY, KEY_MODE_POPUP, KEY_MODE_RANDOM)) problems.add("$where has an unknown mode: $mode")
                val target = key.str(KEY_LAYOUT)
                if (knownLayouts != null && target.isNotBlank() && !target.startsWith("@") && target !in knownLayouts) {
                    problems.add("$where refers to missing layout: $target")
                }
            }
        }
        return problems.distinct()
    }
}

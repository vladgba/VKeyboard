package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

object Numpad {
    fun digit(label: String, inverted: Boolean): String = if (inverted && label.length == 1 && label[0] in '1'..'9') {
        val n = label.toInt()
        (if (n <= 3) n + 6 else if (n >= 7) n - 6 else n).toString()
    } else label

    fun rows(inverted: Boolean): List<FlexNode> = listOf("789", "456", "123", "0.⌫").map { labels ->
        FlexNode().apply {
            for (label in labels) childs.add(FlexNode().apply {
                this[KEY_KEY] = digit(label.toString(), inverted)
                if (label == '⌫') { this[KEY_CODE] = "-67"; this[KEY_HOLD_REPEAT] = "1" }
            })
        }
    }

    fun invert(node: FlexNode) {
        for (row in node.childs.filterIsInstance<FlexNode>()) for (key in row.childs.filterIsInstance<FlexNode>()) {
            if (!key.has(KEY_CODE)) key[KEY_KEY] = digit(key.str(KEY_KEY), true)
        }
    }
}

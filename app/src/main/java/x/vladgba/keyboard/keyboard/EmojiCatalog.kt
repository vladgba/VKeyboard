package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

/** Output strings from the existing emoji layouts; navigation and actions are never inserted. */
object EmojiCatalog {
    val files = listOf(EMOJI_FILENAME) + EMOJI_PAGES
    const val MAX_ENTRIES = 2048

    fun entries(layout: FlexNode): List<String> {
        val output = LinkedHashSet<String>()
        fun add(value: String) {
            if (output.size >= MAX_ENTRIES || value.isBlank() || value.length > 64 ||
                value.any { it.isWhitespace() || it.isISOControl() } || value.startsWith("@") ||
                value.startsWith("layout:") || value.startsWith("macro:") || value.startsWith("tr_voice:") ||
                value.startsWith("-") && value.toIntOrNull() != null) return
            output.add(value)
        }
        for (row in layout.childs.filterIsInstance<FlexNode>()) {
            for (key in row.childs.filterIsInstance<FlexNode>()) {
                if (key.has(KEY_LAYOUT) || key.has(KEY_CODE) || !key.bool(KEY_VISIBLE, true) || key.bool(KEY_DO_NOT_SHOW)) continue
                val text = key.str(KEY_TEXT)
                if (text.isBlank()) continue
                add(text)
                for (variant in key.childs.filterIsInstance<String>()) add(variant)
            }
        }
        return output.toList()
    }
}

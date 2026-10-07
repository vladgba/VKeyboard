package x.vladgba.keyboard.keyboard

import android.content.Context
import android.view.inputmethod.InputConnection
import x.vladgba.keyboard.core.DICT_EXT
import x.vladgba.keyboard.core.DICT_FILENAME
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.flex.FlexParser

/**
 * Abbreviation expansion (ported from the first VKeyboard's dict.json).
 * Entries live in `dict.dic` as `"abbr": "replacement"` pairs.
 * The replacement happens when a separator (space, Enter, punctuation) is typed after the abbreviation.
 */
class TextExpander(private val ctx: Context) {
    private var entries: Map<String, String> = emptyMap()
    private var lastModified = -1L

    fun reload() {
        val file = PFile(ctx, DICT_FILENAME, DICT_EXT)
        if (file.lastModified() == lastModified) return
        lastModified = file.lastModified()
        entries = FlexParser.parse(file.read()).params
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()
    }

    /** Replaces the word before the cursor if it is an abbreviation. Returns true when it did. */
    fun expand(ic: InputConnection): Boolean {
        if (entries.isEmpty()) return false
        val before = ic.getTextBeforeCursor(MAX_WORD, 0)?.toString() ?: return false
        if (before.isEmpty()) return false
        val start = before.indexOfLast { it.isWhitespace() } + 1
        val word = before.substring(start)
        if (word.isEmpty()) return false
        val replacement = entries[word] ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()
        return true
    }

    companion object {
        private const val MAX_WORD = 64
        fun isSeparator(s: CharSequence) = s.length == 1 && (s[0].isWhitespace() || s[0] in ".,!?;:")
    }
}

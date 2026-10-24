package x.vladgba.keyboard.keyboard

import java.text.Normalizer

/**
 * Dead keys: `@DEAD:<name>` arms one, and the next character typed is changed by it
 * (´ + e → é, superscript + 2 → ², arrows + 6 → →). Space or the same dead key again types the
 * accent itself. Accents are Unicode combining marks: the letter and the mark are merged into one
 * character where Unicode has one, otherwise the letter keeps the mark attached.
 */
object DeadKeys {
    const val PREFIX = "@DEAD:"

    private class Accent(val mark: Char, val spacing: String)

    private val ACCENTS = linkedMapOf(
        "acute" to Accent('́', "´"),
        "grave" to Accent('̀', "`"),
        "circumflex" to Accent('̂', "^"),
        "diaeresis" to Accent('̈', "¨"),
        "tilde" to Accent('̃', "~"),
        "macron" to Accent('̄', "¯"),
        "breve" to Accent('̆', "˘"),
        "dotabove" to Accent('̇', "˙"),
        "dotbelow" to Accent('̣', "."),
        "ring" to Accent('̊', "˚"),
        "doubleacute" to Accent('̋', "˝"),
        "caron" to Accent('̌', "ˇ"),
        "cedilla" to Accent('̧', "¸"),
        "ogonek" to Accent('̨', "˛"),
        "horn" to Accent('̛', "̛"),
        "hook" to Accent('̉', "̉"),
        "doublegrave" to Accent('̏', "̏"),
    )

    private fun table(from: String, to: String): Map<Int, String> {
        val a = codePoints(from)
        val b = codePoints(to)
        require(a.size == b.size)
        return a.indices.associate { a[it] to String(Character.toChars(b[it])) }
    }

    private fun codePoints(text: String): List<Int> {
        val points = ArrayList<Int>()
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            points.add(point)
            offset += Character.charCount(point)
        }
        return points
    }

    private val TABLES: Map<String, Map<Int, String>> = linkedMapOf(
        "stroke" to table("dDlLoOhHbBgGiItTzZ", "đĐłŁøØħĦƀɃǥǤɨƗŧŦƶƵ"),
        "superscript" to table("0123456789+-=()nia", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃ"),
        "subscript" to table("0123456789+-=()aeoxhklmnpst", "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜ"),
        "ordinal" to table("aoAO", "ªºªº"),
        "smallcaps" to table("abcdefghijklmnopqrstuvwyz", "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ"),
        // Number-pad directions: 8 up, 2 down, 4 left, 6 right, 7 9 1 3 diagonals.
        "arrows" to table("12346789-0", "↙↓↘←→↖↑↗↔↕"),
        // Number-pad positions of a box: 7 8 9 top, 4 5 6 middle, 1 2 3 bottom; - and | lines.
        "box" to table("123456789-|0", "└┴┘├┼┤┌┬┐─││"),
    )

    private val SYMBOLS = mapOf(
        "stroke" to "ø", "superscript" to "x²", "subscript" to "x₂", "ordinal" to "ª",
        "smallcaps" to "ᴀ", "arrows" to "→", "box" to "┼",
    )

    /** All dead key names, accents first. */
    val NAMES: List<String> = ACCENTS.keys.toList() + TABLES.keys.toList()

    fun isDeadKey(value: String) = value.trim().startsWith(PREFIX) && nameOf(value) in NAMES

    fun nameOf(value: String) = value.trim().removePrefix(PREFIX).trim().lowercase()

    /** How the dead key is drawn on keys: the accent on a dotted circle, or a sample result. */
    fun symbol(name: String): String = ACCENTS[name]?.let { "◌" + it.mark } ?: SYMBOLS[name] ?: "◌"

    /** What typing the dead key on its own produces (space or pressing it twice). */
    fun spacing(name: String): String = ACCENTS[name]?.spacing ?: ""

    /** [s] (one typed character) changed by dead key [name]. */
    fun apply(name: String, s: String): String {
        if (s == " ") return spacing(name).ifEmpty { " " }
        ACCENTS[name]?.let { a ->
            if (s.isEmpty() || !Character.isLetter(s.codePointAt(0))) return a.spacing + s
            return Normalizer.normalize(s + a.mark, Normalizer.Form.NFC)
        }
        val t = TABLES[name] ?: return s
        if (s.codePointCount(0, s.length) != 1) return s
        return t[s.codePointAt(0)] ?: t[s.lowercase().codePointAt(0)] ?: s
    }
}

package x.vladgba.keyboard.keyboard

/**
 * X11 / WinCompose style sequential character composition.
 *
 * Tapping a Compose key (@COMPOSE or "compose": "1") activates compose mode.
 * The next two characters are combined into a symbol or accented letter (e.g. ' + e → é, c + o → ©).
 */
class ComposeHelper {
    var active = false
        private set

    var firstChar: String? = null
        private set

    fun start() {
        active = true
        firstChar = null
    }

    fun cancel() {
        active = false
        firstChar = null
    }

    fun toggle(): Boolean {
        if (active) cancel() else start()
        return active
    }

    /**
     * Feeds one character sequence into the compose engine.
     * Returns:
     * - `null` if compose needs the second character.
     * - The composed string (or fallback concatenation) when the sequence finishes.
     */
    fun feed(s: String): String? {
        if (!active) return s
        val first = firstChar
        if (first == null) {
            firstChar = s
            return null
        }

        active = false
        firstChar = null
        return compose(first, s) ?: (first + s)
    }

    companion object {
        private val TABLE = HashMap<String, String>().apply {
            fun add(a: String, b: String, res: String) {
                put("$a+$b", res)
                put("$b+$a", res)
            }

            // Accents - acute
            add("'", "a", "á"); add("'", "e", "é"); add("'", "i", "í")
            add("'", "o", "ó"); add("'", "u", "ú"); add("'", "y", "ý")
            add("'", "c", "ć"); add("'", "s", "ś"); add("'", "z", "ź")
            add("'", "n", "ń")

            // Accents - grave
            add("`", "a", "à"); add("`", "e", "è"); add("`", "i", "ì")
            add("`", "o", "ò"); add("`", "u", "ù")

            // Accents - circumflex
            add("^", "a", "â"); add("^", "e", "ê"); add("^", "i", "î")
            add("^", "o", "ô"); add("^", "u", "û")
            add("^", "1", "¹"); add("^", "2", "²"); add("^", "3", "³")

            // Accents - umlaut / diaeresis
            add("\"", "a", "ä"); add("\"", "e", "ë"); add("\"", "i", "ï")
            add("\"", "o", "ö"); add("\"", "u", "ü"); add("\"", "y", "ÿ")

            // Accents - tilde
            add("~", "a", "ã"); add("~", "o", "õ"); add("~", "n", "ñ")

            // Accents - cedilla & comma
            add(",", "c", "ç"); add(",", "s", "ş"); add(",", "t", "ţ")

            // Stroke / slash
            add("/", "o", "ø"); add("-", "d", "đ"); add("-", "t", "ŧ")

            // Ligatures & German
            add("a", "e", "æ"); add("o", "e", "œ"); add("s", "s", "ß")

            // Symbols & Typography
            add("c", "o", "©"); add("r", "o", "®"); add("t", "m", "™")
            add("-", "-", "—"); add("-", ".", "–"); add(".", ".", "…")
            add("?", "?", "¿"); add("!", "!", "¡"); add("<", "<", "«"); add(">", ">", "»")
            add("<", "=", "≤"); add(">", "=", "≥"); add("!", "=", "≠"); add("+", "-", "±")
            add("1", "2", "½"); add("1", "4", "¼"); add("3", "4", "¾")
            add("=", "e", "€"); add("=", "y", "¥"); add("=", "l", "£")
            add("o", "o", "°"); add("u", "u", "µ"); add("p", "p", "§"); add("p", "!", "¶")
        }

        fun compose(a: String, b: String): String? {
            val direct = TABLE["$a+$b"]
            if (direct != null) return direct

            // Case variants (e.g. ' + E → É)
            val lowerA = a.lowercase()
            val lowerB = b.lowercase()
            val lowerResult = TABLE["$lowerA+$lowerB"] ?: return null

            val isUpper = (a.any { it.isUpperCase() } || b.any { it.isUpperCase() })
            return if (isUpper) lowerResult.uppercase() else lowerResult
        }
    }
}

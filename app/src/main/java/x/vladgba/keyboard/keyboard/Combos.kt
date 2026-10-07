package x.vladgba.keyboard.keyboard

import android.view.KeyEvent
import android.view.KeyEvent.*
import x.vladgba.keyboard.flex.FlexNode

/**
 * Key combinations written as text, e.g. `"ctrl+shift+t"`, `"meta+d"`, `"alt+f4"` or `"ctrl+-61"`
 * (a negative number is an Android key code). Used by layouts, macros, hardware remaps and the UI.
 */
object Combos {
    class Modifier(val name: String, val label: String, val mask: Int, val keyCode: Int)

    class Combo(val mods: List<Modifier>, val keyCode: Int) {
        val mask get() = mods.fold(0) { a, m -> a or m.mask }

        /** Canonical text form, parseable by [parse]. */
        fun spec() = (mods.map { it.name } + keyName(keyCode)).joinToString("+")

        /** Readable form for the UI: `Ctrl + Shift + T`. */
        fun describe() = (mods.map { it.label } + keyLabel(keyCode)).joinToString(" + ")
    }

    val CTRL = Modifier("ctrl", "Ctrl", META_CTRL_ON or META_CTRL_LEFT_ON, KEYCODE_CTRL_LEFT)
    val ALT = Modifier("alt", "Alt", META_ALT_ON or META_ALT_LEFT_ON, KEYCODE_ALT_LEFT)
    val SHIFT = Modifier("shift", "Shift", META_SHIFT_ON or META_SHIFT_LEFT_ON, KEYCODE_SHIFT_LEFT)
    val META = Modifier("meta", "Meta", META_META_ON or META_META_LEFT_ON, KEYCODE_META_LEFT)
    val FN = Modifier("fn", "Fn", META_FUNCTION_ON, KEYCODE_FUNCTION)
    val ALT_GR = Modifier("altgr", "AltGr", META_ALT_ON or META_ALT_RIGHT_ON, KEYCODE_ALT_RIGHT)

    /** Modifiers in canonical order. */
    val MODIFIERS = listOf(CTRL, ALT, SHIFT, META, FN)

    private val MOD_ALIASES = mapOf(
        "ctrl" to CTRL, "control" to CTRL, "alt" to ALT, "altgr" to ALT_GR, "shift" to SHIFT,
        "meta" to META, "win" to META, "super" to META, "cmd" to META, "fn" to FN,
    )

    private val KEY_ALIASES = mapOf(
        "esc" to KEYCODE_ESCAPE, "backspace" to KEYCODE_DEL, "bksp" to KEYCODE_DEL,
        "delete" to KEYCODE_FORWARD_DEL, "ins" to KEYCODE_INSERT,
        "up" to KEYCODE_DPAD_UP, "down" to KEYCODE_DPAD_DOWN, "left" to KEYCODE_DPAD_LEFT, "right" to KEYCODE_DPAD_RIGHT,
        "home" to KEYCODE_MOVE_HOME, "end" to KEYCODE_MOVE_END, "pgup" to KEYCODE_PAGE_UP, "pgdn" to KEYCODE_PAGE_DOWN,
        "return" to KEYCODE_ENTER, "prtsc" to KEYCODE_SYSRQ,
    )

    fun isModifierName(s: String) = s.trim().lowercase() in MOD_ALIASES

    /** Resolves one key name (`tab`, `f4`, `a`, `-61`) to an Android key code, or null. */
    fun keyCode(name: String): Int? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        FlexNode.parseInt(n)?.let { return if (it < 0) -it else null }
        KEY_ALIASES[n]?.let { return it }
        return KeyEvent.keyCodeFromString("KEYCODE_" + n.uppercase()).takeIf { it != KEYCODE_UNKNOWN }
    }

    /** `KEYCODE_TAB` -> `tab`; codes without a name become `-<code>` so they still parse. */
    fun keyName(code: Int): String {
        val s = KeyEvent.keyCodeToString(code)
        return if (s.startsWith("KEYCODE_")) s.removePrefix("KEYCODE_").lowercase() else "-$code"
    }

    /** `KEYCODE_PAGE_UP` -> `Page Up`. */
    fun keyLabel(code: Int): String {
        val s = KeyEvent.keyCodeToString(code)
        if (!s.startsWith("KEYCODE_")) return "#$code"
        return s.removePrefix("KEYCODE_").split('_').joinToString(" ") { w ->
            if (w.length <= 2) w else w[0] + w.substring(1).lowercase()
        }
    }

    /**
     * Parses `"ctrl+shift+t"`. With [requireModifier] a plain key name (`"tab"`) is rejected, so
     * values that may also be text (hold / swipe / popup chars) are only treated as combos when
     * they clearly are one.
     */
    fun parse(spec: String, requireModifier: Boolean = true): Combo? {
        val parts = spec.trim().lowercase().split('+').map { it.trim() }
        if (parts.size < (if (requireModifier) 2 else 1) || parts.any { it.isEmpty() }) return null
        val mods = parts.dropLast(1).map { MOD_ALIASES[it] ?: return null }.distinct()
        val code = keyCode(parts.last()) ?: return null
        return Combo(mods, code)
    }

    /** Names of the modifiers set in a KeyEvent meta state (for the key tester). */
    fun metaNames(meta: Int): List<String> {
        val out = ArrayList<String>()
        fun side(on: Int, left: Int, right: Int, name: String) {
            if (meta and on == 0) return
            out += when {
                meta and left != 0 && meta and right != 0 -> name
                meta and left != 0 -> "Left $name"
                meta and right != 0 -> "Right $name"
                else -> name
            }
        }
        side(META_CTRL_ON, META_CTRL_LEFT_ON, META_CTRL_RIGHT_ON, "Ctrl")
        side(META_ALT_ON, META_ALT_LEFT_ON, META_ALT_RIGHT_ON, "Alt")
        side(META_SHIFT_ON, META_SHIFT_LEFT_ON, META_SHIFT_RIGHT_ON, "Shift")
        side(META_META_ON, META_META_LEFT_ON, META_META_RIGHT_ON, "Meta")
        if (meta and META_FUNCTION_ON != 0) out += "Fn"
        if (meta and META_SYM_ON != 0) out += "Sym"
        if (meta and META_CAPS_LOCK_ON != 0) out += "Caps Lock"
        if (meta and META_NUM_LOCK_ON != 0) out += "Num Lock"
        if (meta and META_SCROLL_LOCK_ON != 0) out += "Scroll Lock"
        return out
    }
}

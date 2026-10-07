package x.vladgba.keyboard.ui

import android.graphics.Color
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

/** Human names for theme colors, grouped by where they appear, plus a palette generator. */
internal object ThemeColors {
    class Info(val key: String, val title: Int, val desc: Int)
    class Group(val title: Int, val items: List<Info>)

    val GROUPS = listOf(
        Group(R.string.tc_group_keyboard, listOf(
            Info(COLOR_KEYBOARD_BACKGROUND, R.string.tc_kb_bg, R.string.tc_kb_bg_d),
            Info(COLOR_KEY_BORDER, R.string.tc_border, R.string.tc_border_d),
        )),
        Group(R.string.tc_group_keys, listOf(
            Info(COLOR_KEY_BACKGROUND, R.string.tc_key_bg, R.string.tc_key_bg_d),
            Info(COLOR_KEY_PRESSED_BACKGROUND, R.string.tc_key_pressed, R.string.tc_key_pressed_d),
            Info(COLOR_TEXT_PRIMARY, R.string.tc_text, R.string.tc_text_d),
            Info(COLOR_KEY_SECONDARY, R.string.tc_hint, R.string.tc_hint_d),
        )),
        Group(R.string.tc_group_mods, listOf(
            Info(COLOR_KEY_BACKGROUND_META, R.string.tc_meta, R.string.tc_meta_d),
            Info(COLOR_KEY_PRESSED_MOD_BACKGROUND, R.string.tc_meta_locked, R.string.tc_meta_locked_d),
        )),
        Group(R.string.tc_group_popup, listOf(
            Info(COLOR_KEY_SHADOW, R.string.tc_dim, R.string.tc_dim_d),
            Info(COLOR_KEY_POPUP_BACKGROUND, R.string.tc_popup_bg, R.string.tc_popup_bg_d),
            Info(COLOR_TEXT_PREVIEW, R.string.tc_popup_text, R.string.tc_popup_text_d),
            Info(COLOR_KEY_POPUP_SELECTED, R.string.tc_popup_sel, R.string.tc_popup_sel_d),
        )),
    )

    val ALL: List<Info> = GROUPS.flatMap { it.items }

    fun info(key: String) = ALL.firstOrNull { it.key == key }

    /** Color of [key] in a theme node, falling back to settings/defaults like the keyboard does. */
    fun color(node: FlexNode, key: String): Int {
        val own = node.params[key] as? String
        val v = if (!own.isNullOrBlank()) own else Settings.str(key)
        return Theme.parseColor(v, Color.MAGENTA)
    }

    fun hex(c: Int) = Theme.toHex(c).uppercase()

    // ======================= palette generator =======================

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00ffffff) or (a shl 24)

    fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** A complete, readable theme from one accent color. */
    fun generate(accent: Int, dark: Boolean): Map<String, Int> {
        val a = accent or 0xff000000.toInt()
        return if (!dark) mapOf(
            COLOR_KEYBOARD_BACKGROUND to mix(0xffeceff1.toInt(), a, 0.10f),
            COLOR_KEY_BORDER to 0x33000000,
            COLOR_KEY_BACKGROUND to 0xffffffff.toInt(),
            COLOR_KEY_PRESSED_BACKGROUND to mix(0xffdadce0.toInt(), a, 0.20f),
            COLOR_TEXT_PRIMARY to 0xff202124.toInt(),
            COLOR_KEY_SECONDARY to 0x66000000,
            COLOR_KEY_BACKGROUND_META to mix(a, 0xffffffff.toInt(), 0.35f),
            COLOR_KEY_PRESSED_MOD_BACKGROUND to a,
            COLOR_KEY_SHADOW to 0x55000000,
            COLOR_KEY_POPUP_BACKGROUND to 0xffffffff.toInt(),
            COLOR_TEXT_PREVIEW to 0xff202124.toInt(),
            COLOR_KEY_POPUP_SELECTED to withAlpha(a, 0x55),
        ) else mapOf(
            COLOR_KEYBOARD_BACKGROUND to mix(0xff1b1c1f.toInt(), a, 0.08f),
            COLOR_KEY_BORDER to 0x66000000,
            COLOR_KEY_BACKGROUND to mix(0xff3c4043.toInt(), a, 0.10f),
            COLOR_KEY_PRESSED_BACKGROUND to mix(0xff5f6368.toInt(), a, 0.25f),
            COLOR_TEXT_PRIMARY to 0xffe8eaed.toInt(),
            COLOR_KEY_SECONDARY to 0x80ffffff.toInt(),
            COLOR_KEY_BACKGROUND_META to mix(a, 0xff000000.toInt(), 0.35f),
            COLOR_KEY_PRESSED_MOD_BACKGROUND to a,
            COLOR_KEY_SHADOW to 0x88000000.toInt(),
            COLOR_KEY_POPUP_BACKGROUND to mix(0xff3c4043.toInt(), a, 0.10f),
            COLOR_TEXT_PREVIEW to 0xffe8eaed.toInt(),
            COLOR_KEY_POPUP_SELECTED to withAlpha(a, 0x88),
        )
    }

    /** Text contrast check used to warn about unreadable labels (WCAG ratio, alpha ignored). */
    fun contrast(fg: Int, bg: Int): Double {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        fun lum(c: Int) = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
        val l1 = lum(fg)
        val l2 = lum(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** Blends a translucent color over a background (what the eye actually sees). */
    fun over(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun ch(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return Color.rgb(ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)))
    }
}

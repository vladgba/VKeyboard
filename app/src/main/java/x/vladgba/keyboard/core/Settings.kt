package x.vladgba.keyboard.core

import android.content.Context
import android.graphics.Color
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/** Built-in defaults; the bottom of the inheritance chain. */
private val DEFAULTS = FlexParser.parse(
    """
{
    "$SETTING_DEBUG": "0",
    "$SETTING_ERROR_TOASTS": "1",
    "$SETTING_META_ONE_SHOT": "1",
    "$SETTING_TEXT_EXPANSION": "1",
    "$SETTING_AUTO_NUM_LAYOUT": "1",
    "$SETTING_LANDSCAPE_HEIGHT": "1",
    "$SETTING_PORTRAIT_HEIGHT": "1",
    "$SETTING_AUTO_CAPS": "1",
    "$SETTING_DOUBLE_SPACE": "1",
    "$KEY_TEXT_SIZE_PRIMARY": "2.5",
    "$KEY_TEXT_SIZE_SECONDARY": "5",
    "$THEME_DAY": "baseLight",
    "$THEME_NIGHT": "baseDark",

    "$COLOR_TEXT_PRIMARY": "ff000000",
    "$COLOR_KEY_SECONDARY": "48000000",
    "$COLOR_KEYBOARD_BACKGROUND": "ffeeeeee",
    "$COLOR_KEY_BORDER": "33000000",
    "$COLOR_KEY_BACKGROUND": "ffffffff",
    "$COLOR_KEY_PRESSED_BACKGROUND": "ffd6d6d6",
    "$COLOR_KEY_PREVIEW_SELECTED": "22000000",
    "$COLOR_KEY_POPUP_BACKGROUND": "ffffffff",
    "$COLOR_TEXT_PREVIEW": "ff222222",
    "$COLOR_KEY_SHADOW": "55000000",
    "$COLOR_KEY_BACKGROUND_META": "ff0099ff",
    "$COLOR_KEY_PRESSED_MOD_BACKGROUND": "ff0066cc",

    "$KEY_BORDER_RADIUS": 10,
    "$KEY_PADDING": 10,
    "$SENSE_HARD_PRESS": 400,
    "$SENSE_ADDITIONAL_CHARS": 70,
    "$SENSE_HORIZONTAL_TICK": 70,
    "$SENSE_VERTICAL_TICK": 60,
    "$SENSE_HOLD_PRESS": 300,
    "$SENSE_HOLD_PRESS_REPEAT": 50,
    "$KEY_VISIBLE": 1,
    "$SETTING_INPUT_DEVICE": "/dev/input/event4"
}
"""
)

/**
 * User settings (`settings.txt`). Values not set by the user fall back to [DEFAULTS].
 */
object Settings : FlexNode(DEFAULTS) {
    private var lastModified = -1L

    val defaults: FlexNode get() = DEFAULTS

    /** Reloads `settings.txt` when it changed on disk. */
    fun loadVars(ctx: Context) {
        val file = PFile(ctx, SETTINGS_FILENAME)
        val modified = file.lastModified()
        if (modified == lastModified) return
        lastModified = modified
        replaceWith(FlexParser.parse(file.read()))
    }

    fun save(ctx: Context) {
        PFile(ctx, SETTINGS_FILENAME).write(toString())
        lastModified = -1L
    }
}

/**
 * Current color theme. Sits between layouts and [Settings], so a layout/row/key can still override
 * any color, and a theme overrides the color defaults.
 */
object Theme : FlexNode(Settings) {
    private var loadedName = ""
    private var lastModified = -1L

    fun load(ctx: Context, night: Boolean) {
        val name = Settings.str(if (night) THEME_NIGHT else THEME_DAY)
        val file = PFile(ctx, name, THEME_EXT)
        if (!file.exists()) PFile.install(ctx, name, THEME_EXT)
        if (name == loadedName && file.lastModified() == lastModified) return
        loadedName = name
        lastModified = file.lastModified()

        replaceWith(upgrade(FlexParser.parse(file.read())))
    }

    /** Fills in colors that older theme files don't have, exactly as the keyboard will show them. */
    fun upgrade(node: FlexNode): FlexNode {
        // Bundled themes used the old name for the active-modifier color.
        if (!node.params.containsKey(COLOR_KEY_BACKGROUND_META) && node.params.containsKey(COLOR_KEY_BACKGROUND_META_LEGACY))
            node[COLOR_KEY_BACKGROUND_META] = node.str(COLOR_KEY_BACKGROUND_META_LEGACY)
        // Themes written before these colors existed: derive them from the key background.
        val keyBg = node.str(COLOR_KEY_BACKGROUND)
        if (keyBg.isNotEmpty()) {
            if (!node.params.containsKey(COLOR_KEY_POPUP_BACKGROUND)) node[COLOR_KEY_POPUP_BACKGROUND] = keyBg
            if (!node.params.containsKey(COLOR_KEY_PRESSED_BACKGROUND))
                node[COLOR_KEY_PRESSED_BACKGROUND] = toHex(shade(parseColor(keyBg, Color.WHITE)))
        }
        return node
    }

    /** Forces the next [load] to re-read the theme file. */
    fun invalidate() {
        loadedName = ""
    }

    fun parseColor(s: String, fallback: Int): Int = try {
        s.trim().removePrefix("#").removePrefix("0x").toLong(16).toInt()
    } catch (_: Exception) {
        fallback
    }

    fun toHex(color: Int) = color.toUInt().toString(16).padStart(8, '0')

    /** Darkens light colors and lightens dark colors by ~15%. */
    fun shade(c: Int): Int {
        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        val f = if (lum > 128) 0.85f else 1.35f
        fun ch(v: Int) = (v * f).toInt().coerceIn(0, 255).let { if (lum <= 128 && it == 0) 40 else it }
        return Color.argb(Color.alpha(c), ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }
}

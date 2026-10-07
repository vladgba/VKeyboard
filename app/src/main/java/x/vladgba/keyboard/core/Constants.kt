package x.vladgba.keyboard.core

internal val LATIN_KEYS = 97..122
internal const val LATIN_OFFSET = 68
internal const val MULTIBYTE_UTF = 56320

internal const val SETTINGS_FILENAME = "settings"
internal const val NUM_FILENAME = "123"
internal const val NUM2_FILENAME = "$123e"
internal const val EMOJI_FILENAME = "emoji"
/** Emoji category pages besides [EMOJI_FILENAME]; installed and listed together with it. */
internal val EMOJI_PAGES = listOf("\$emojiPeople", "\$emojiNature", "\$emojiFood", "\$emojiTravel",
    "\$emojiActivity", "\$emojiObjects", "\$emojiSymbols", "\$emojiFlags")
internal fun isEmojiPage(name: String) = name in EMOJI_PAGES
internal const val DICT_FILENAME = "dict"
internal const val MACROS_FILENAME = "macros"
/** Own extension so the macros file never shows up as a layout. */
internal const val MACROS_EXT = "macro"
internal const val STATS_FILENAME = "touchstats"
internal const val STATS_EXT = "stats"
/** Record touch positions for the touch heatmap (aggregates only, never in password fields). */
internal const val SETTING_TOUCH_STATS = "touchStats"
/** Capitalize automatically where the text field asks for it (start of sentences, names...). */
internal const val SETTING_AUTO_CAPS = "autoCaps"
/** Two quick spaces after a word become ". ". */
internal const val SETTING_DOUBLE_SPACE = "doubleSpacePeriod"
/** Keyboard height multiplier in portrait orientation. */
internal const val SETTING_PORTRAIT_HEIGHT = "portraitHeight"
/** Action value prefix that switches layout, e.g. `"hold": "layout:@EMOJI"`. */
internal const val LAYOUT_ACTION_PREFIX = "layout:"
/** Action value (hold, hard press, swipe, popup) that starts voice typing; drawn as 🎤. */
internal const val VOICE_ACTION = "@VOICE"
/** Key param: a tap starts/stops voice typing. */
internal const val KEY_VOICE = "voice"
/** Layout param: speech language for this layout (BCP 47, e.g. "uk-UA"). */
internal const val KEY_VOICE_LANG = "speechLang"
/** Voice typing language: "auto" (from the layout) or a BCP 47 tag. */
internal const val SETTING_VOICE_LANG = "voiceLang"
internal const val VOICE_LANG_AUTO = "auto"
/** Prefer on-device (offline) speech recognition where available. */
internal const val SETTING_VOICE_OFFLINE = "voiceOffline"
internal const val DEFAULT_LAYOUT_ASSET = "en"

internal const val LAYOUT_EXT = "txt"
internal const val BLANK_LAYOUT = "blank"
internal const val THEME_EXT = "ini"
internal const val DICT_EXT = "dic"
internal const val BLANK_THEME = "blank"
internal const val SETTING_DEBUG = "debug"
internal const val SETTING_INPUT_DEVICE = "inputDevice"

// Hardware Key
internal const val SETTING_REDEFINE_HW_ACTION = "redefineHardwareActions"
internal const val KEY_DO_IF_HIDDEN = "doIfHidden"

// Settings
internal const val SETTING_DEF_LAYOUT = "defaultLayout"
/** 1 = tap modifier once for the next key, tap again to lock, third tap to release. 0 = plain toggle. */
internal const val SETTING_META_ONE_SHOT = "metaOneShot"
/** Expand abbreviations from the dictionary when a space/punctuation is typed. */
internal const val SETTING_TEXT_EXPANSION = "textExpansion"
/** Switch to the number layout automatically in number/phone/date fields. */
internal const val SETTING_AUTO_NUM_LAYOUT = "autoNumLayout"
/** Keyboard height multiplier in landscape orientation. */
internal const val SETTING_LANDSCAPE_HEIGHT = "landscapeHeight"
/** Show error toasts (otherwise errors are only logged). */
internal const val SETTING_ERROR_TOASTS = "errorToasts"

// Key
internal const val KEY_KEY = "key"
internal const val KEY_CODE = "code"
internal const val KEY_HOLD = "hold"
internal const val KEY_HOLD_REPEAT = "holdRepeat"
internal const val KEY_HARD_REPEAT = "hardRepeat"
internal const val KEY_HARD_PRESS = "hard"
internal const val KEY_TOP_ACTION = "top"
internal const val KEY_BOTTOM_ACTION = "bottom"
internal const val KEY_LEFT_ACTION = "left"
internal const val KEY_RIGHT_ACTION = "right"
internal const val KEY_TEXT = "text"
internal const val KEY_TEXT_CURSOR_OFFSET = "textCurOffset"
internal const val KEY_LAYOUT = "layout"

// Modifier
internal const val KEY_MOD_META = "meta"
/** Key combination sent by a key, e.g. `"ctrl+shift+t"`, `"meta+d"`, `"alt+tab"`, `"ctrl+-61"`. */
internal const val KEY_COMBO = "combo"
/** Name of a macro from `macros.txt` run by a key tap. */
internal const val KEY_MACRO = "macro"
/** Marks a key as the current one in a group (e.g. the open emoji category tab). */
internal const val KEY_SELECTED = "selected"

//Mode
internal const val KEY_MODE = "mode"
internal const val KEY_MODE_POPUP = "popup"
internal const val KEY_MODE_JOY = "joy"
internal const val KEY_MODE_META = "meta"
internal const val KEY_MODE_RANDOM = "random"

// Action
internal const val KEY_ACTION = "action"
internal const val KEY_RANDOM = "random"
internal const val KEY_RECORD = "record"
internal const val KEY_CLIPBOARD = "clipboard"
internal const val KEY_SWITCH_LAYOUT = "switchKeyb"
internal const val KEY_ACTION_ON_CTRL = "ctrl"
internal const val KEY_ACTION_ON_ALT = "alt"
internal const val KEY_ACTION_ON_SHIFT = "shift"
internal const val ACTION_APP = "app"
internal const val ACTION_SU = "sudo"
internal const val ACTION_UPPER_ALL = "upperAll"
internal const val ACTION_LOWER_ALL = "lowerAll"

// Sound
internal const val KEY_SOUND_TICK = "soundTick"
internal const val KEY_SOUND_PRESS = "soundPress"
internal const val KEY_SOUND_RELEASE = "soundRelease"
internal const val KEY_SOUND_ADDIT = "soundAdditional"

internal const val KEY_VIBRATE_TICK = "vibTick"
internal const val KEY_VIBRATE_PRESS = "vibPress"
internal const val KEY_VIBRATE_RELEASE = "vibRelease"
internal const val KEY_VIBRATE_ADDITIONAL = "vibAdditional"

// Theme
internal const val THEME_SWITCH = "themeSwitch"
internal const val THEME_DAY = "dayTheme"
internal const val THEME_NIGHT = "nightTheme"

// Style
internal const val KEY_VISIBLE = "visible"
internal const val KEY_DO_NOT_SHOW = "doNotShow"
internal const val ROW_HEIGHT = "height"
internal const val KEY_WIDTH = "width"
internal const val KEY_TEXT_SIZE_PRIMARY = "fontSizePrimary"
internal const val KEY_TEXT_SIZE_SECONDARY = "fontSizeSecondary"
internal const val KEY_PADDING = "padding"
internal const val KEY_HIDE_BORDERS = "borderHide"
internal const val KEY_BORDER_RADIUS = "radius"
internal const val KEY_SHADOW = "shadow"

// Color
internal const val COLOR_TEXT_PRIMARY = "primaryTextColor"
internal const val COLOR_KEY_SECONDARY = "secondaryTextColor"
internal const val COLOR_TEXT_PREVIEW = "previewTextColor"
internal const val COLOR_KEY_BORDER = "keyBorderColor"
internal const val COLOR_KEYBOARD_BACKGROUND = "keyboardBackgroundColor"
internal const val COLOR_KEY_BACKGROUND = "keyBackgroundColor"
internal const val COLOR_KEY_BACKGROUND_META = "metaBackgroundColor"
internal const val COLOR_KEY_SHADOW = "shadowColor"
internal const val COLOR_KEY_PREVIEW_SELECTED = "previewSelectedColor"

internal const val COLOR_KEY_PRESSED_BACKGROUND = "keyPressedBackgroundColor"
/** Old name of [COLOR_KEY_BACKGROUND_META] used by bundled themes; still accepted. */
internal const val COLOR_KEY_BACKGROUND_META_LEGACY = "modBackgroundColor"
internal const val COLOR_KEY_PRESSED_MOD_BACKGROUND = "metaPressedBackgroundColor"
internal const val COLOR_KEY_POPUP_BACKGROUND = "keyPreviewBackgroundColor"
internal const val COLOR_KEY_POPUP_SELECTED = "previewSelectedColor"

internal val COLORS = listOf(
    COLOR_TEXT_PRIMARY, COLOR_KEY_SECONDARY, COLOR_TEXT_PREVIEW,
    COLOR_KEY_BORDER, COLOR_KEYBOARD_BACKGROUND, COLOR_KEY_BACKGROUND,
    COLOR_KEY_BACKGROUND_META, COLOR_KEY_SHADOW, COLOR_KEY_PREVIEW_SELECTED,
    COLOR_KEY_PRESSED_BACKGROUND, COLOR_KEY_PRESSED_MOD_BACKGROUND,
    COLOR_KEY_POPUP_BACKGROUND, COLOR_KEY_POPUP_SELECTED
).distinct()

// Sensitivity
internal const val SENSE_HARD_PRESS = "hardSense"
internal const val SENSE_HOLD_PRESS = "longPressTime"
internal const val SENSE_HOLD_PRESS_REPEAT = "longPressRepeatTime"
internal const val SENSE_ADDITIONAL_CHARS = "extSense"
internal const val SENSE_HORIZONTAL_TICK = "horTick"
internal const val SENSE_VERTICAL_TICK = "verTick"

// Layout Switch
internal const val LAYOUT_PREV = "@PREV"
internal const val LAYOUT_NEXT = "@NEXT"
internal const val LAYOUT_NUM = "@NUM"
internal const val LAYOUT_TEXT = "@TEXT"
internal const val LAYOUT_EMOJI = "@EMOJI"

internal enum class KeybType {
    NORMAL, NUMBER, PHONE, URI, EMAIL, DATETIME, DATE, TIME
}
internal enum class KeybAction {
    NONE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS
}
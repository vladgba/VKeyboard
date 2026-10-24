package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*

object VisualControls {
    val enabled get() = Settings.bool(SETTING_VISUAL_CONTROLS, false)
    val keyboardAlpha get() = if (enabled) Settings.num(SETTING_KEYBOARD_OPACITY, 100).coerceIn(35, 100) * 255 / 100 else 255
    fun keyAlpha(alpha: Int): Int = if (enabled) alpha * Settings.num(SETTING_KEY_OPACITY, 100).coerceIn(0, 100) / 100 else alpha
    fun borderWidth(density: Float) = Settings.float(SETTING_BORDER_WIDTH, 1f).coerceIn(0f, 6f) * density
}

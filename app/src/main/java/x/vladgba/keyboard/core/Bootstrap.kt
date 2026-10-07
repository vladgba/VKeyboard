package x.vladgba.keyboard.core

import android.content.Context

/**
 * First-run setup shared by the app and the IME, so the keyboard works even if the user enables it
 * before ever opening the app.
 */
object Bootstrap {
    fun isFirstRun(ctx: Context) = !PFile(ctx, SETTINGS_FILENAME).exists()

    fun ensureInstalled(ctx: Context) {
        val settingsFile = PFile(ctx, SETTINGS_FILENAME)
        if (!settingsFile.exists()) settingsFile.write(Settings.defaults.toString())

        PFile.install(ctx, NUM_FILENAME)
        PFile.install(ctx, NUM2_FILENAME)
        PFile.install(ctx, EMOJI_FILENAME)
        for (page in EMOJI_PAGES) PFile.install(ctx, page)
        PFile.install(ctx, "baseLight", THEME_EXT)
        PFile.install(ctx, "baseDark", THEME_EXT)
        PFile.install(ctx, DICT_FILENAME, DICT_EXT)

        Settings.loadVars(ctx)
        if (textLayouts(ctx).isEmpty()) PFile.install(ctx, DEFAULT_LAYOUT_ASSET)
        if (Settings.str(SETTING_DEF_LAYOUT).isBlank()) {
            Settings[SETTING_DEF_LAYOUT] = textLayouts(ctx).firstOrNull() ?: DEFAULT_LAYOUT_ASSET
            Settings.save(ctx)
            Settings.loadVars(ctx)
        }
    }

    /** User layouts that take part in @NEXT/@PREV cycling. */
    fun textLayouts(ctx: Context): List<String> = PFile.list(ctx, LAYOUT_EXT).filter { isTextLayout(it) }

    fun isTextLayout(name: String) =
        name !in setOf(SETTINGS_FILENAME, NUM_FILENAME, EMOJI_FILENAME) && !name.startsWith("$")
}

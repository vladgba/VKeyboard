package x.vladgba.keyboard.ui

import android.content.Context
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*

/** Friendly names and descriptions for the layouts and themes bundled with the app. */
internal object BuiltIns {
    class Item(val file: String, val title: Int, val desc: Int)

    val LAYOUTS = listOf(
        Item("en", R.string.bi_en, R.string.bi_en_d),
        Item("enExt", R.string.bi_en_ext, R.string.bi_en_ext_d),
        Item("uk", R.string.bi_uk, R.string.bi_uk_d),
        Item("ruExt", R.string.bi_ru_ext, R.string.bi_ru_ext_d),
        Item("bg", R.string.bi_bg, R.string.bi_bg_d),
        Item("ru", R.string.bi_ru, R.string.bi_ru_d),
        Item("be", R.string.bi_be, R.string.bi_be_d),
        Item("de", R.string.bi_de, R.string.bi_de_d),
        Item("fr", R.string.bi_fr, R.string.bi_fr_d),
        Item("es", R.string.bi_es, R.string.bi_es_d),
        Item("pl", R.string.bi_pl, R.string.bi_pl_d),
        Item("ro", R.string.bi_ro, R.string.bi_ro_d),
        Item("dvorak", R.string.bi_dvorak, R.string.bi_dvorak_d),
        Item("colemak", R.string.bi_colemak, R.string.bi_colemak_d),
        Item("code", R.string.bi_code, R.string.bi_code_d),
        Item(NUM_FILENAME, R.string.bi_num, R.string.bi_num_d),
        Item(NUM2_FILENAME, R.string.bi_sym, R.string.bi_sym_d),
        Item(EMOJI_FILENAME, R.string.bi_emoji, R.string.bi_emoji_d),
        Item("\$nav", R.string.bi_nav, R.string.bi_nav_d),
        Item("\$numpad", R.string.bi_numpad, R.string.bi_numpad_d),
    )

    val THEMES = listOf(
        Item("baseLight", R.string.bi_light, R.string.bi_light_d),
        Item("baseDark", R.string.bi_dark, R.string.bi_dark_d),
        Item("amoled", R.string.bi_t_amoled, R.string.bi_t_amoled_d),
        Item("highContrast", R.string.bi_t_hc, R.string.bi_t_hc_d),
        Item("arctic", R.string.bi_t_arctic, R.string.bi_t_arctic_d),
        Item("midnightPurple", R.string.bi_t_purple, R.string.bi_t_purple_d),
        Item("retroDark", R.string.bi_t_retro, R.string.bi_t_retro_d),
        Item("solarDark", R.string.bi_t_solar_dark, R.string.bi_t_solar_dark_d),
        Item("forest", R.string.bi_t_forest, R.string.bi_t_forest_d),
        Item("solarLight", R.string.bi_t_solar_light, R.string.bi_t_solar_light_d),
        Item("paper", R.string.bi_t_paper, R.string.bi_t_paper_d),
        Item("ocean", R.string.bi_t_ocean, R.string.bi_t_ocean_d),
        Item("sakura", R.string.bi_t_sakura, R.string.bi_t_sakura_d),
    )

    fun layout(name: String) = LAYOUTS.firstOrNull { it.file == name }
    fun theme(name: String) = THEMES.firstOrNull { it.file == name }

    /** "English" for a known file name, otherwise the file name itself. */
    fun layoutTitle(ctx: Context, name: String) = layout(name)?.let { ctx.getString(it.title) } ?: name

    /** Bundled files of [ext] in a stable order: described ones first, then any others. */
    fun assetNames(ctx: Context, ext: String, blank: String): List<String> {
        val files = ctx.assets.list("").orEmpty().filter { it.endsWith(".$ext") }.map { it.removeSuffix(".$ext") }
            .filter { it != blank && !isEmojiPage(it) }
        val known = (if (ext == THEME_EXT) THEMES else LAYOUTS).map { it.file }
        return known.filter { it in files } + files.filter { it !in known }.sorted()
    }
}

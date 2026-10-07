package x.vladgba.keyboard.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language choice, independent of the phone language. No libraries:
 *  - Android 13+: the platform per-app language ([LocaleManager]); it also appears in the system's
 *    "App languages" settings (see res/xml/locales_config.xml) and updates every component itself.
 *  - Older versions: the choice is kept in preferences and applied to each activity's base context
 *    ([wrap]) and to the keyboard service's resources ([applyTo]); open screens recreate themselves.
 * An empty tag means "follow the system".
 */
object AppLocale {
    /** Languages the app is translated into, as BCP 47 tags (keep in sync with locales_config.xml). */
    val SUPPORTED = listOf("en", "be", "bg", "de", "es", "fr", "pl", "ro", "ru", "uk")

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** Bumped on every change, so open screens and the keyboard know they're stale. */
    @Volatile var version = 0
        private set

    private val modern get() = Build.VERSION.SDK_INT >= 33

    fun current(ctx: Context): String {
        if (modern) {
            val list = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (list == null || list.isEmpty) "" else list[0].toLanguageTag()
        }
        return prefs(ctx).getString(KEY_TAG, "") ?: ""
    }

    fun set(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(KEY_TAG, tag).apply()
        version++
        Labels.clear()
        if (modern) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            applyTo(ctx.applicationContext)
        }
    }

    /** Android 13+: carry a choice made on an older Android version over to the platform setting. */
    fun migrate(ctx: Context) {
        if (!modern) return
        val saved = prefs(ctx).getString(KEY_TAG, "") ?: ""
        val lm = ctx.getSystemService(LocaleManager::class.java) ?: return
        if (saved.isNotEmpty() && lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(saved)
    }

    /** For `attachBaseContext` of activities (older Android only). */
    fun wrap(base: Context): Context {
        if (modern) return base
        val tag = prefs(base).getString(KEY_TAG, "") ?: ""
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        setLocale(cfg, Locale.forLanguageTag(tag))
        return base.createConfigurationContext(cfg)
    }

    /**
     * Updates [ctx]'s resources in place (older Android only). Used for the keyboard service, which
     * lives for a long time and can't be recreated like an activity. Returns true if anything changed.
     */
    @Suppress("DEPRECATION")
    fun applyTo(ctx: Context): Boolean {
        if (modern) return false
        val tag = prefs(ctx).getString(KEY_TAG, "") ?: ""
        val res = ctx.resources
        val want = if (tag.isEmpty()) systemLocale() else Locale.forLanguageTag(tag)
        val cfg = Configuration(res.configuration)
        if (primary(cfg) == want) return false
        setLocale(cfg, want)
        res.updateConfiguration(cfg, res.displayMetrics)
        return true
    }

    /**
     * True if the translations for [tag] are on the device. With a Google Play (.aab) install,
     * only the phone's languages are downloaded; a sideloaded APK has them all.
     */
    fun isAvailable(ctx: Context, tag: String): Boolean {
        if (tag.isEmpty() || tag == "en") return true
        fun probe(l: Locale): String {
            val cfg = Configuration(ctx.resources.configuration)
            setLocale(cfg, l)
            return ctx.createConfigurationContext(cfg).getString(x.vladgba.keyboard.R.string.ui_language)
        }
        return try { probe(Locale.forLanguageTag(tag)) != probe(Locale.ENGLISH) } catch (_: Exception) { true }
    }

    /** Native name, e.g. "Українська"; empty tag gives null (caller shows "System default"). */
    fun displayName(tag: String): String? {
        if (tag.isEmpty()) return null
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
    }

    /** Activities call this from onResume: recreate when the language changed while they were open. */
    fun recreateIfStale(activity: Activity, seenVersion: Int): Boolean {
        if (seenVersion == version) return false
        activity.recreate()
        return true
    }

    private fun systemLocale(): Locale = android.content.res.Resources.getSystem().configuration.let { primary(it) }

    @Suppress("DEPRECATION")
    private fun primary(cfg: Configuration): Locale = if (Build.VERSION.SDK_INT >= 24) cfg.locales[0] else cfg.locale

    @Suppress("DEPRECATION")
    private fun setLocale(cfg: Configuration, l: Locale) {
        if (Build.VERSION.SDK_INT >= 24) cfg.setLocales(LocaleList(l)) else cfg.locale = l
        cfg.setLayoutDirection(l)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

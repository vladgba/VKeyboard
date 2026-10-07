package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import x.vladgba.keyboard.core.AppLocale

/**
 * Base for every screen: applies the in-app language on older Android versions and rebuilds the
 * screen when the language was changed elsewhere while it was in the back stack.
 */
abstract class LocalizedActivity : Activity() {
    private var localeVersion = AppLocale.version

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        localeVersion = AppLocale.version
        super.onCreate(savedInstanceState)
        // Before Android 13 the system loads manifest labels in the phone language; reload ours.
        if (Build.VERSION.SDK_INT < 33) try {
            @Suppress("DEPRECATION")
            val res = packageManager.getActivityInfo(componentName, 0).labelRes
            if (res != 0) title = getString(res)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        AppLocale.recreateIfStale(this, localeVersion)
    }
}

/** "App language" chooser shared by the home and settings screens. */
internal object LanguagePicker {
    fun currentName(a: Activity): String {
        val tag = AppLocale.current(a)
        return AppLocale.displayName(tag) ?: a.getString(x.vladgba.keyboard.R.string.ui_language_system)
    }

    fun show(a: Activity, onChanged: () -> Unit = {}) {
        val cur0 = AppLocale.current(a)
        val tags = listOf("") + AppLocale.SUPPORTED
            .filter { AppLocale.isAvailable(a, it) || cur0.startsWith(it) }
            .sortedBy { AppLocale.displayName(it)!!.lowercase() }
        val sys = android.content.res.Resources.getSystem().configuration.let {
            @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 24) it.locales[0] else it.locale
        }
        val sysName = sys.getDisplayLanguage(sys).replaceFirstChar { c -> c.titlecase(sys) }
        val names = tags.map {
            AppLocale.displayName(it) ?: a.getString(x.vladgba.keyboard.R.string.ui_language_system_with, sysName)
        }
        val cur = AppLocale.current(a).let { c -> tags.indexOfFirst { it.equals(c, true) || (it.isNotEmpty() && c.startsWith("$it-")) } }
        Ask.choice(a, a.getString(x.vladgba.keyboard.R.string.ui_language), names, cur.coerceAtLeast(0)) { i ->
            if (tags[i].equals(AppLocale.current(a), true)) return@choice
            AppLocale.set(a, tags[i])
            onChanged()
            // Android 13+ recreates the screens by itself.
            if (Build.VERSION.SDK_INT < 33) a.recreate()
        }
    }
}

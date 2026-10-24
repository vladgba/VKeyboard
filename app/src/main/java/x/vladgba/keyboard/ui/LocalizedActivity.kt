package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 35) fitSystemBars()
    }

    /**
     * Android 15+ draws every screen of an app targeting SDK 35 edge-to-edge: the content starts
     * under the status bar and the action bar, ends under the navigation bar, and is no longer
     * resized by the on-screen keyboard. Pads the content back inside the free area.
     */
    private fun fitSystemBars() {
        if (Build.VERSION.SDK_INT < 35) return
        val content = findViewById<View>(android.R.id.content) ?: return
        // The app always uses a light theme: dark status bar icons stay readable over it.
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        // The action bar's own layout already adds its height to the top inset it hands the content.
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        content.requestApplyInsets()
    }

    /**
     * The action bar's back arrow behaves like the system Back button, so screens with unsaved
     * changes ask before closing (without this the arrow closed them silently, or did nothing).
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        @Suppress("DEPRECATION") onBackPressed()
        return true
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

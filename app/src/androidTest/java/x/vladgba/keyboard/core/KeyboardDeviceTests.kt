package x.vladgba.keyboard.core

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.ContextThemeWrapper
import x.vladgba.keyboard.KeybWrapper
import x.vladgba.keyboard.keyboard.Keyboard

/** Framework instrumentation without a test-library dependency. */
class KeyboardDeviceTests : Instrumentation() {
    private var check = "orientation"
    override fun onCreate(arguments: Bundle?) {
        check = arguments?.getString("check") ?: "orientation"
        start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        runOnMainSync {
            try { runChecks(ContextThemeWrapper(targetContext, android.R.style.Theme_Material_Light), check) }
            catch (e: Throwable) { failure = e }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("result", failure?.stackTraceToString() ?: "PASS $check")
        })
    }
}

private fun runChecks(ctx: Context, check: String) {
    val original = PFile(ctx, "settings").read()
    try {
        Settings.loadVars(ctx)
        when (check) {
            "orientation" -> {
                Settings[SETTING_ORIENTATION_LAYOUTS] = "1"
                Settings[SETTING_LAYOUT_PORTRAIT] = "en"
                Settings[SETTING_LAYOUT_LANDSCAPE] = "uk"
                Settings.save(ctx)
                val kb = Keyboard(ctx, KeybWrapper())
                kb.onConfigurationChanged(Configuration(ctx.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT })
                check(kb.currentLayoutName == "en")
                kb.onConfigurationChanged(Configuration(ctx.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
                check(kb.currentLayoutName == "uk")
                kb.switchLayout("en")
                check(Settings.str(SETTING_LAYOUT_LANDSCAPE) == "en")
                kb.numLayout()
                check(Settings.str(SETTING_LAYOUT_LANDSCAPE) == "en")
                kb.onConfigurationChanged(Configuration(ctx.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT })
                check(kb.currentLayoutName == "123")
                kb.textLayout()
                check(kb.currentLayoutName == "en")
                Settings[SETTING_ORIENTATION_LAYOUTS] = "0"
                Settings[SETTING_LAYOUT_LANDSCAPE] = "uk"
                kb.onConfigurationChanged(Configuration(ctx.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
                check(kb.currentLayoutName == "en")
                kb.onDestroy()
                println("PASS orientation: rotation, remembering, temporary number page and disabled behavior")
            }
            else -> error("Unknown device check")
        }
    } finally {
        PFile(ctx, "settings").write(original)
        Settings.invalidate()
    }
}

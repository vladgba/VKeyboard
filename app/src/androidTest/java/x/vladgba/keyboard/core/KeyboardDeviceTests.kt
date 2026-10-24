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
            "numpad" -> {
                Settings[SETTING_SIDE_NUMPAD] = "1"
                Settings[SETTING_INVERT_NUMPAD] = "0"
                Settings[SETTING_SPLIT] = "0"
                Settings.save(ctx)
                val kb = Keyboard(ctx, KeybWrapper())
                kb.switchLayout("en")
                kb.currentLayout.measure(1080, true)
                check(kb.currentLayout.sideRows.size == 4)
                val seven = kb.currentLayout.sideRows[0].keys[0]
                check(seven.str(KEY_KEY) == "7" && seven.x >= 810)
                check(kb.currentLayout.getKey(seven.x + seven.width / 2, seven.y + seven.height / 2) === seven)
                check(kb.currentLayout.rows.all { row -> row.keys.all { it.x + it.width <= 810 } })
                Settings[SETTING_SIDE_NUMPAD] = "2"
                Settings[SETTING_INVERT_NUMPAD] = "1"
                kb.reloadLayout()
                kb.currentLayout.measure(1080, true)
                check(kb.currentLayout.sideRows[0].keys[0].str(KEY_KEY) == "1")
                check(kb.currentLayout.sideRows[0].keys[0].x == 0)
                check(kb.currentLayout.rows.all { row -> row.keys.all { it.width == 0 || it.x >= 270 } })
                Settings[SETTING_SIDE_NUMPAD] = "3"
                kb.reloadLayout()
                kb.currentLayout.measure(1080, true)
                check(kb.currentLayout.sideRows.all { row -> row.keys.all { it.width == 0 } })
                kb.currentLayout.measure(1920, false)
                check(kb.currentLayout.sideRows.all { row -> row.keys.all { it.width > 0 } })
                Settings[SETTING_SIDE_NUMPAD] = "0"
                kb.reloadLayout()
                check(kb.currentLayout.sideRows.isEmpty())
                PFile.install(ctx, "\$numpad")
                kb.switchLayout("\$numpad")
                check(kb.currentLayout.rows[0].keys[0].str(KEY_KEY) == "1")
                kb.onDestroy()
                println("PASS numpad: right/left/landscape, hit testing, inversion and off")
            }
            else -> error("Unknown device check")
        }
    } finally {
        PFile(ctx, "settings").write(original)
        Settings.invalidate()
    }
}

package x.vladgba.keyboard.core

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
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
    val originalWords = PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).read()
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
            "extra-row" -> {
                Settings[SETTING_EXTRA_ROW] = "1"
                Settings[SETTING_EXTRA_ROW_KEYS] = "Tab|Ctrl|F12|hello"
                Settings.save(ctx)
                val kb = Keyboard(ctx, KeybWrapper())
                kb.switchLayout("en")
                val keys = kb.currentLayout.rows[0].keys
                check(keys.size == 4 && keys[0].num(KEY_CODE) == -61)
                check(keys[1].num(KEY_MOD_META) == 4096 && keys[2].num(KEY_CODE) == -142)
                check(keys[3].str(KEY_TEXT) == "hello")
                val count = kb.currentLayout.rows.size
                Settings[SETTING_EXTRA_ROW] = "0"
                kb.reloadLayout()
                check(kb.currentLayout.rows.size == count - 1)
                Settings[SETTING_EXTRA_ROW] = "1"
                kb.switchLayout("123")
                check(kb.currentLayout.rows.none { row -> row.keys.any { it.str(KEY_TEXT) == "hello" } })
                kb.onDestroy()
                println("PASS extra-row: codes, modifiers, custom text, off and numeric exclusions")
            }
            "rtl" -> {
                val file = PFile(ctx, "roadmap-test-rtl")
                check(!file.exists())
                try {
                    file.write("(\"rtl\":\"1\", ((\"key\":\"א\"), (\"key\":\"(\")))")
                    Settings[SETTING_RTL_MARKS] = "1"
                    Settings[SETTING_MIRROR_BRACKETS] = "1"
                    Settings.save(ctx)
                    val connection = BaseInputConnection(EditText(ctx), true)
                    val kb = Keyboard(ctx, KeybWrapper(), connection)
                    kb.switchLayout("roadmap-test-rtl")
                    check(kb.currentLayout.rtl)
                    check(kb.currentLayout.rows[0].keys[0].str(KEY_KEY) == "@RTL:LRM")
                    kb.onText("(")
                    check(connection.editable.toString() == ")")
                    kb.runSpecial("@RTL:RLM")
                    check(connection.editable.toString() == ")\u200f")
                    Settings[SETTING_RTL_MARKS] = "0"
                    Settings[SETTING_MIRROR_BRACKETS] = "0"
                    kb.runSpecial("@RTL:LRM")
                    kb.onText("(")
                    check(connection.editable.toString() == ")\u200f(")
                    kb.reloadLayout()
                    check(kb.currentLayout.rows.size == 1)
                    kb.onDestroy()
                } finally { file.delete() }
                println("PASS rtl: detection, mirrored output, Unicode marks, controls and off")
            }
            "marks" -> {
                val ar = PFile(ctx, "roadmap-test-ar")
                val he = PFile(ctx, "roadmap-test-he")
                check(!ar.exists() && !he.exists())
                try {
                    ar.write("(((\"key\":\"ب\")))")
                    he.write("(((\"key\":\"ש\")))")
                    Settings[SETTING_COMBINING_MARKS] = "1"
                    Settings[SETTING_RTL_MARKS] = "0"
                    Settings[SETTING_LEARN_WORDS] = "1"
                    Settings.save(ctx)
                    val connection = BaseInputConnection(EditText(ctx), true)
                    val kb = Keyboard(ctx, KeybWrapper(), connection)
                    kb.switchLayout("roadmap-test-ar")
                    check(kb.currentLayout.rows.size == 3)
                    kb.onText("ب")
                    kb.runSpecial("@MARK:064E")
                    check(connection.editable.toString() == "بَ")
                    kb.onText(" ")
                    UserWords.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    check(UserWords.contains(ctx, "بَ"))
                    kb.switchLayout("roadmap-test-he")
                    connection.editable?.clear()
                    kb.onText("ש")
                    kb.runSpecial("@MARK:05C1")
                    check(connection.editable.toString() == "שׁ")
                    Settings[SETTING_COMBINING_MARKS] = "0"
                    kb.runSpecial("@MARK:05C2")
                    check(connection.editable.toString() == "שׁ")
                    kb.reloadLayout()
                    check(kb.currentLayout.rows.size == 1)
                    kb.onDestroy()
                } finally { ar.delete(); he.delete() }
                println("PASS marks: Arabic/Hebrew output, diacritic learning and disabled behavior")
            }
            "modmap" -> {
                val file = PFile(ctx, "roadmap-test-modmap")
                check(!file.exists())
                try {
                    file.write("(\"modmap\":(\"alt\":(\"é\":\"α\"), \"ctrl+shift\":(\"é\":\"!\")), ((\"key\":\"é\", \"modmap\":(\"ctrl\":\"β\"))))")
                    Settings[SETTING_MODIFIER_MAPPINGS] = "1"
                    Settings.save(ctx)
                    val connection = BaseInputConnection(EditText(ctx), true)
                    val kb = Keyboard(ctx, KeybWrapper(), connection)
                    kb.switchLayout("roadmap-test-modmap")
                    kb.currentLayout.measure(1080, true)
                    val key = kb.currentLayout.rows.last().keys[0]
                    fun tap(mask: Int) {
                        kb.metaState = mask
                        key.press(key.x + key.width / 2, key.y + key.height / 2)
                        key.release(key.x + key.width / 2, key.y + key.height / 2, 0)
                    }
                    tap(android.view.KeyEvent.META_ALT_ON)
                    tap(android.view.KeyEvent.META_CTRL_ON)
                    tap(android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)
                    check(connection.editable.toString() == "αβ!")
                    Settings[SETTING_MODIFIER_MAPPINGS] = "0"
                    tap(0)
                    check(connection.editable.toString() == "αβ!é")
                    kb.onDestroy()
                } finally { file.delete() }
                println("PASS modmap: layout/key precedence, modifier combinations, actual key taps and off")
            }
            "languages" -> {
                Settings[SETTING_EXPANDED_LAYOUTS] = "1"
                Settings[SETTING_COMBINING_MARKS] = "0"
                Settings[SETTING_RTL_MARKS] = "0"
                Settings[SETTING_EXTRA_ROW] = "0"
                Settings[SETTING_SIDE_NUMPAD] = "0"
                Settings.save(ctx)
                val connection = BaseInputConnection(EditText(ctx), true)
                val kb = Keyboard(ctx, KeybWrapper(), connection)
                for (name in ExpandedLayouts.names) {
                    val text = PFile.asset(ctx, name) ?: error("Missing asset $name")
                    val parser = x.vladgba.keyboard.flex.FlexParser(text)
                    val parsed = parser.parse()
                    check(parser.warnings.isEmpty()) { "$name: ${parser.warnings}" }
                    check(parsed.childCount() == 4)
                    val file = PFile(ctx, "roadmap-test-$name")
                    check(!file.exists())
                    try {
                        file.write(text)
                        kb.switchLayout("roadmap-test-$name")
                        val layout = kb.currentLayout
                        layout.measure(1080, true)
                        check(layout.loaded && layout.keys.any { it.num(KEY_CODE) == -62 })
                        check(layout.keys.any { it.num(KEY_CODE) == -67 } && layout.keys.any { it.num(KEY_CODE) == -66 })
                        for (key in layout.keys) {
                            check(key.width > 0 && key.x >= 0 && key.x + key.width <= 1080)
                            check(layout.getKey(key.x + key.width / 2, key.y + key.height / 2) === key)
                        }
                        check(layout.rtl == (name in listOf("ar", "he")))
                        if (name == "tr") {
                            check(kb.getShifted('i'.code, true) == 'İ'.code && kb.getShifted('ı'.code, true) == 'I'.code)
                            kb.metaState = android.view.KeyEvent.META_SHIFT_ON
                            kb.onKey('i'.code)
                            check(connection.editable.toString().endsWith("İ"))
                            kb.metaState = 0
                        }
                    } finally { file.delete() }
                }
                Settings[SETTING_EXPANDED_LAYOUTS] = "0"
                kb.switchLayout("tr")
                check(kb.currentLayoutName !in ExpandedLayouts.names)
                check(ExpandedLayouts.names.none { ExpandedLayouts.enabled(it) })
                kb.onDestroy()
                println("PASS languages: 12 assets, parser validity, geometry/hit tests, RTL, Turkish casing and off")
            }
            else -> error("Unknown device check")
        }
    } finally {
        UserWords.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).write(originalWords)
        UserWords.reload()
        PFile(ctx, "settings").write(original)
        Settings.invalidate()
    }
}

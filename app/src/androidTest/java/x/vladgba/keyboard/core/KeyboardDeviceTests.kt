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
        if (check != "async") runOnMainSync {
            try { runChecks(ContextThemeWrapper(targetContext, android.R.style.Theme_Material_Light), check) }
            catch (e: Throwable) { failure = e }
        }
        if (failure == null && check in listOf("all", "async")) {
            try { runLifecycleChecks() } catch (e: Throwable) { failure = e }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("result", failure?.stackTraceToString() ?: "PASS $check")
        })
    }
}

private fun runChecks(ctx: Context, check: String) {
    if (check == "all") {
        for (name in listOf("orientation", "numpad", "extra-row", "rtl", "marks", "modmap", "languages", "hardware", "voice-provider", "visuals", "emoji", "learning")) runChecks(ctx, name)
        return
    }
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
            "hardware" -> {
                val policy = x.vladgba.keyboard.keyboard.PhysicalKeyboardPolicy
                check(policy.qualifies(false, android.view.InputDevice.SOURCE_KEYBOARD, android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC))
                check(!policy.qualifies(true, android.view.InputDevice.SOURCE_KEYBOARD, android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC))
                check(!policy.qualifies(false, android.view.InputDevice.SOURCE_DPAD, android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC))
                check(!policy.qualifies(false, android.view.InputDevice.SOURCE_TOUCHSCREEN, android.view.InputDevice.KEYBOARD_TYPE_NONE))
                val connected = policy.connected()
                println("PASS hardware: physical alphabetic filtering and Android device enumeration (connected=$connected)")
            }
            "voice-provider" -> {
                val providers = x.vladgba.keyboard.keyboard.SpeechProviders
                val component = android.content.ComponentName("example.provider", "example.provider.Recognizer")
                val choices = listOf(x.vladgba.keyboard.keyboard.SpeechProviders.Provider(component, "Example"))
                check(providers.resolve(true, component.flattenToString(), choices) == component)
                check(providers.resolve(false, component.flattenToString(), choices) == null)
                check(providers.resolve(true, "invalid", choices) == null)
                check(providers.resolve(true, "", choices) == null)
                check(providers.resolve(true, component.flattenToString(), emptyList()) == null)
                val actual = providers.available(ctx)
                check(actual.map { it.component }.distinct().size == actual.size)
                println("PASS voice-provider: selection, disabled/missing fallback, Android discovery (${actual.size} services)")
            }
            "visuals" -> {
                val controls = x.vladgba.keyboard.keyboard.VisualControls
                Settings[SETTING_VISUAL_CONTROLS] = "1"
                Settings[SETTING_KEYBOARD_OPACITY] = "50"
                Settings[SETTING_KEY_OPACITY] = "25"
                Settings[SETTING_BORDER_WIDTH] = "3"
                check(controls.keyboardAlpha == 127 && controls.keyAlpha(200) == 50)
                check(controls.borderWidth(2f) == 6f)
                Settings.save(ctx)
                val kb = Keyboard(ctx, KeybWrapper())
                kb.currentLayout.measure(1080, true)
                val bitmap = android.graphics.Bitmap.createBitmap(1080, kb.currentLayout.height, android.graphics.Bitmap.Config.ARGB_8888)
                kb.draw(android.graphics.Canvas(bitmap), bitmap.width, bitmap.height, false)
                check(bitmap.getPixel(20, 20) != 0)
                bitmap.recycle()
                val frame = x.vladgba.keyboard.keyboard.ImeFrame(ctx, kb)
                frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                val composed = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
                frame.draw(android.graphics.Canvas(composed))
                check(android.graphics.Color.alpha(composed.getPixel(2, composed.height - 2)) in 120..130)
                composed.recycle()
                Settings[SETTING_VISUAL_CONTROLS] = "0"
                check(controls.keyboardAlpha == 255 && controls.keyAlpha(200) == 200)
                kb.onDestroy()
                println("PASS visuals: slider values, drawing and disabled theme restoration")
            }
            "emoji" -> {
                Settings[SETTING_TABBED_EMOJI] = "1"
                Settings.save(ctx)
                val connection = BaseInputConnection(EditText(ctx), true)
                val kb = Keyboard(ctx, KeybWrapper(), connection)
                val frame = x.vladgba.keyboard.keyboard.ImeFrame(ctx, kb)
                kb.switchLayout("en")
                fun measure(width: Int) {
                    frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                    frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                }
                kb.emojiLayout()
                measure(1080)
                var drawer = frame.findViewWithTag<x.vladgba.keyboard.keyboard.EmojiDrawer>("emoji-drawer")
                check(drawer != null && drawer.category == EMOJI_FILENAME)
                check(kb.currentLayoutName == "en" && frame.keyboardView.visibility == android.view.View.INVISIBLE)
                for (page in x.vladgba.keyboard.keyboard.EmojiCatalog.files) {
                    drawer.findViewWithTag<android.view.View>("emoji-tab:$page").performClick()
                    check(drawer.category == page)
                    val grid = drawer.findViewWithTag<android.widget.GridView>("emoji-grid")
                    check(grid.adapter.count > 0) { "Empty emoji category $page" }
                    val emoji = grid.adapter.getItem(0) as String
                    val before = connection.editable.toString()
                    grid.performItemClick(grid.adapter.getView(0, null, grid), 0, 0)
                    check(connection.editable.toString() == before + emoji)
                    check(kb.currentLayoutName == "en")
                }
                drawer.findViewWithTag<android.view.View>("emoji-tab:\$emojiPeople").performClick()
                val grid = drawer.findViewWithTag<android.widget.GridView>("emoji-grid")
                val sequenceIndex = (0 until grid.adapter.count).first { (grid.adapter.getItem(it) as String).contains('\u200d') }
                val sequence = grid.adapter.getItem(sequenceIndex) as String
                grid.performItemClick(grid.adapter.getView(sequenceIndex, null, grid), sequenceIndex, sequenceIndex.toLong())
                check(connection.editable.toString().endsWith(sequence))
                drawer.findViewWithTag<android.view.View>("emoji-close").performClick()
                check(frame.findViewWithTag<android.view.View>("emoji-drawer") == null)
                check(frame.keyboardView.visibility == android.view.View.VISIBLE)
                kb.emojiLayout()
                drawer = frame.findViewWithTag("emoji-drawer")
                check(drawer.category == "\$emojiPeople")
                frame.showClipboard()
                check(frame.findViewWithTag<android.view.View>("emoji-drawer") == null)
                kb.switchLayout("\$emojiFlags")
                check(frame.findViewWithTag<x.vladgba.keyboard.keyboard.EmojiDrawer>("emoji-drawer").category == "\$emojiFlags")
                measure(1920)
                val bitmap = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
                frame.draw(android.graphics.Canvas(bitmap))
                bitmap.recycle()
                val pageFile = PFile(ctx, EMOJI_FILENAME)
                val originalPage = pageFile.readBytes()
                try {
                    pageFile.write("()")
                    frame.showEmoji(EMOJI_FILENAME)
                    val emptyGrid = frame.findViewWithTag<android.widget.GridView>("emoji-grid")
                    check(emptyGrid.adapter.count == 0)
                    check(frame.findViewWithTag<android.view.View>("emoji-close") != null)
                    pageFile.writeBytes(ByteArray(512 * 1024 + 1) { 32 })
                    frame.showEmoji(EMOJI_FILENAME)
                    check(frame.findViewWithTag<android.widget.GridView>("emoji-grid").adapter.count == 0)
                } finally { pageFile.writeBytes(originalPage) }
                Settings[SETTING_TABBED_EMOJI] = "0"
                kb.emojiLayout()
                check(kb.currentLayoutName == EMOJI_FILENAME && frame.findViewWithTag<android.view.View>("emoji-drawer") == null)
                kb.onDestroy()
                println("PASS emoji: category tabs, complete sequences, insertion, layout preservation, close, clipboard, width and disabled fallback")
            }
            "learning" -> {
                Settings[SETTING_LEARN_WORDS] = "1"
                Settings[SETTING_SUGGESTIONS] = "1"
                Settings.save(ctx)
                UserWords.clear(ctx)
                val connection = BaseInputConnection(EditText(ctx), true)
                val kb = Keyboard(ctx, KeybWrapper(), connection)
                val text = android.text.InputType.TYPE_CLASS_TEXT
                val privateFields = listOf(
                    text or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    text or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    text or android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                for (type in privateFields + text) {
                    connection.editable?.clear()
                    kb.onStartInput(android.view.inputmethod.EditorInfo().apply {
                        inputType = type
                        if (type == text) imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    })
                    kb.onText("privatetestword"); kb.onText(" ")
                    UserWords.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    check(!kb.suggestionsShown && UserWords.size(ctx) == 0)
                }
                Settings[SETTING_LEARN_WORDS] = "0"
                kb.onStartInput(android.view.inputmethod.EditorInfo().apply { inputType = text })
                connection.editable?.clear()
                kb.onText("disabledtestword"); kb.onText(" ")
                UserWords.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                check(UserWords.size(ctx) == 0)
                kb.onStartInput(android.view.inputmethod.EditorInfo().apply { inputType = text or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS })
                check(!kb.suggestionsShown)
                Settings[SETTING_LEARN_WORDS] = "1"
                kb.onStartInput(android.view.inputmethod.EditorInfo().apply { inputType = text })
                connection.editable?.clear()
                kb.onText("learnedtestword"); kb.onText(" ")
                UserWords.submit { UserWords.flush(ctx) }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                UserWords.reload()
                check(UserWords.contains(ctx, "learnedtestword"))
                UserWords.remove(ctx, "learnedtestword")
                check(!UserWords.contains(ctx, "learnedtestword") && UserWords.complete(ctx, "learn", 3).isEmpty())
                check(UserWords.merge(ctx, listOf("Hello" to 3, "hello" to 1, "a\u0000" to 5, "a\ud800" to 5)) == 1)
                UserWords.learn(ctx, "hello")
                val snapshot = UserWords.all(ctx).single()
                check(snapshot.form == "hello" && snapshot.count == 4)
                snapshot.form = "changed"; snapshot.count = 999
                check(UserWords.all(ctx).single().count == 4 && UserWords.all(ctx).single().form == "hello")
                val tasks = (1..50).map { UserWords.submit { UserWords.learn(ctx, "queuedword") } }
                for (task in tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS)
                UserWords.flush(ctx); UserWords.reload()
                check(UserWords.all(ctx).first { it.form == "queuedword" }.count == 50)
                val combo = x.vladgba.keyboard.keyboard.Combos.parse("control+shift+a")!!
                check(combo.mods.size == 2 && combo.keyCode == android.view.KeyEvent.KEYCODE_A)
                check(x.vladgba.keyboard.keyboard.Combos.parse(combo.spec())?.mask == combo.mask)
                check(x.vladgba.keyboard.keyboard.Combos.parse("ctrl++a") == null)
                check(x.vladgba.keyboard.keyboard.Combos.parse("ctrl+unknownkey") == null)
                check(x.vladgba.keyboard.keyboard.Combos.parse("a") == null)
                check(x.vladgba.keyboard.keyboard.Combos.parse("a", false)?.keyCode == android.view.KeyEvent.KEYCODE_A)
                kb.onDestroy()
                println("PASS learning: privacy fields, switches, persistence, deletion, snapshots, queue ordering and Android key combos")
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

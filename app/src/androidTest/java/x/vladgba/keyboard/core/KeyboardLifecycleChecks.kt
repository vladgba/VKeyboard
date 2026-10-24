package x.vladgba.keyboard.core

import android.app.Instrumentation
import android.content.Context
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import x.vladgba.keyboard.KeybWrapper
import x.vladgba.keyboard.keyboard.ImeFrame
import x.vladgba.keyboard.keyboard.Keyboard

/** Deterministic queue gates exercise stale results without depending on timing or large files. */
internal fun Instrumentation.runLifecycleChecks() {
    val ctx: Context = ContextThemeWrapper(targetContext, android.R.style.Theme_Material_Light)
    val originalSettings = PFile(ctx, SETTINGS_FILENAME).read()
    val originalWords = PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).read()
    val gates = ArrayList<CountDownLatch>()
    var kb: Keyboard? = null
    lateinit var keyboard: Keyboard
    lateinit var connection: BaseInputConnection
    lateinit var frame: ImeFrame
    fun ui(work: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { work() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    fun settle() {
        waitForIdleSync()
        UserWords.submit { }.get(10, TimeUnit.SECONDS)
        waitForIdleSync()
    }
    fun gate(): Pair<CountDownLatch, Future<*>> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1).also { gates.add(it) }
        val future = UserWords.submit { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(started.await(10, TimeUnit.SECONDS))
        return release to future
    }
    fun field(private: Boolean = false) {
        connection.editable?.clear()
        keyboard.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (private) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
            initialSelStart = 0; initialSelEnd = 0
        })
    }
    fun type(text: String) {
        keyboard.onText(text)
        keyboard.onSelectionChanged(text.length, text.length)
    }
    try {
        UserWords.submit { }.get(10, TimeUnit.SECONDS)
        ui {
            Settings.loadVars(ctx)
            Settings[SETTING_SUGGESTIONS] = "1"
            Settings[SETTING_LEARN_WORDS] = "0"
            Settings[SETTING_TABBED_EMOJI] = "1"
            Settings.save(ctx)
            UserWords.clear(ctx)
            UserWords.merge(ctx, listOf("hello" to 120, "help" to 100, "world" to 110))
            connection = BaseInputConnection(EditText(ctx), true)
            keyboard = Keyboard(ctx, KeybWrapper(), connection).also { kb = it }
            frame = ImeFrame(ctx, keyboard)
            keyboard.switchLayout("en")
            field(); type("hel")
        }
        settle()
        ui { check(keyboard.suggestions.firstOrNull()?.text == "hello") }

        val privateGate = gate()
        ui { field(); type("wor") }
        waitForIdleSync()
        ui { field(private = true); type("secret") }
        privateGate.first.countDown(); privateGate.second.get(10, TimeUnit.SECONDS)
        settle()
        ui { check(!keyboard.suggestionsShown && keyboard.suggestions.isEmpty() && connection.editable.toString() == "secret") }

        val prefixGate = gate()
        ui { field(); type("hel") }
        waitForIdleSync()
        ui { connection.editable?.clear(); type("wor") }
        waitForIdleSync()
        prefixGate.first.countDown(); prefixGate.second.get(10, TimeUnit.SECONDS)
        settle()
        ui {
            check(keyboard.suggestions.firstOrNull()?.text == "world")
            connection.editable?.clear(); connection.commitText("different", 1)
            keyboard.acceptSuggestion(0)
            check(connection.editable.toString() == "different")
        }

        val drawerGate = gate()
        ui { field(); type("hel") }
        waitForIdleSync()
        ui { keyboard.emojiLayout() }
        drawerGate.first.countDown(); drawerGate.second.get(10, TimeUnit.SECONDS)
        settle()
        ui {
            check(keyboard.suggestions.isEmpty())
            check(frame.findViewWithTag<android.view.View>("emoji-drawer") != null)
            frame.hideDrawer()
        }
        settle()
        ui { check(keyboard.suggestions.firstOrNull()?.text == "hello") }

        val destroyGate = gate()
        ui { connection.editable?.clear(); type("wor") }
        waitForIdleSync()
        ui { keyboard.onDestroy() }
        destroyGate.first.countDown(); destroyGate.second.get(10, TimeUnit.SECONDS)
        settle()
        ui { check(keyboard.suggestions.isEmpty()) }
        println("PASS asynchronous suggestions: private-field cancellation, latest prefix, acceptance guard, drawer and destruction")
    } finally {
        gates.forEach { it.countDown() }
        ui { kb?.onDestroy() }
        UserWords.submit { }.get(15, TimeUnit.SECONDS)
        PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).write(originalWords)
        UserWords.reload()
        PFile(ctx, SETTINGS_FILENAME).write(originalSettings)
        Settings.invalidate()
    }
}

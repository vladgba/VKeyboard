package x.vladgba.keyboard.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.ui.VoicePermissionActivity
import java.util.Locale

data class TranslationSpec(val sourceLang: String, val targetLang: String)

/**
 * Voice typing with the system speech recognizer (android.speech, no libraries).
 *
 * - Partial results are shown as composing (underlined) text and in the overlay, the final result
 *   is committed with a space before it when needed and a capital where the field asks for one.
 * - The language follows the current layout (`"voiceLang"` in a layout overrides it).
 * - Without a recognition service it switches to a voice keyboard (input method with a "voice"
 *   subtype) if one is installed.
 * Everything runs on the main thread, as SpeechRecognizer requires.
 */
class VoiceInput(private val kb: Keyboard) {
    enum class State { IDLE, STARTING, LISTENING, PROCESSING }

    var state = State.IDLE
        private set
    /** Text recognized so far (shown in the overlay). */
    var partial = ""
        private set
    /** Microphone level 0..1 for the overlay animation. */
    var level = 0f
        private set

    private var recognizer: SpeechRecognizer? = null
    private val ctx get() = kb.ctx

    var translationSpec: TranslationSpec? = null
        private set
    val isTranslating get() = translationSpec != null

    private var transRequestId = 0L

    val active get() = state != State.IDLE

    fun toggle(spec: TranslationSpec? = null) = if (active) stop() else start(spec)
    fun toggle(specStr: String?) = toggle(specStr?.let { parseTrVoiceSpec(it) })

    fun start(spec: TranslationSpec? = null) {
        if (kb.isEditor || active) return
        if (Build.VERSION.SDK_INT >= 23 && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An input method can't show the permission dialog itself.
            ctx.startActivity(Intent(ctx, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val rec = createRecognizer()
        if (rec == null) {
            if (!switchToVoiceKeyboard()) toast(R.string.vo_err_unavailable)
            return
        }
        recognizer = rec
        rec.setRecognitionListener(listener)
        translationSpec = spec
        partial = ""
        level = 0f
        state = State.STARTING
        spaceBefore = null
        // Started from a long-press: that finger's "up" goes to the overlay, so release keys now.
        kb.resetTouches()
        try {
            rec.startListening(recognizerIntent())
        } catch (e: Exception) {
            kb.prStack(e)
            finish()
        }
        kb.invalidate()
    }

    /** Finish listening; the final result still arrives. */
    fun stop() {
        when (state) {
            State.STARTING, State.LISTENING -> {
                state = State.PROCESSING
                recognizer?.stopListening()
                kb.invalidate()
            }
            State.PROCESSING -> cancel() // second tap while waiting: give up
            State.IDLE -> {}
        }
    }

    /** Abort without inserting anything (keyboard hidden, field changed). */
    fun cancel() {
        if (!active) return
        transRequestId++
        recognizer?.cancel()
        clearComposing()
        finish()
    }

    fun release() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish() {
        state = State.IDLE
        partial = ""
        level = 0f
        translationSpec = null
        recognizer?.destroy()
        recognizer = null
        kb.invalidate()
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (Settings.bool(SETTING_VOICE_PROVIDER_ENABLED, false)) {
            val selected = SpeechProviders.resolve(true, Settings.str(SETTING_VOICE_PROVIDER), SpeechProviders.available(ctx))
            if (selected != null) {
                try { return SpeechRecognizer.createSpeechRecognizer(ctx, selected) }
                catch (e: Exception) { android.util.Log.w("VKeyboard", "Selected speech provider unavailable", e) }
            }
        }
        val offline = Settings.bool(SETTING_VOICE_OFFLINE)
        if (offline && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            return try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (_: Exception) { null }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        return try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        val lang = language()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        if (Settings.bool(SETTING_VOICE_OFFLINE)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** BCP 47 tag: translation source > setting > layout's "voiceLang" > known layout > system language. */
    fun language(): String {
        translationSpec?.let { spec ->
            if (spec.sourceLang.isNotBlank() && spec.sourceLang != VOICE_LANG_AUTO && spec.sourceLang != "auto") {
                LAYOUT_LANGS[spec.sourceLang]?.let { return it }
                if (spec.sourceLang.contains("-")) return spec.sourceLang
                return "${spec.sourceLang}-${spec.sourceLang.uppercase(Locale.ROOT)}"
            }
        }
        Settings.str(SETTING_VOICE_LANG).trim().takeIf { it.isNotEmpty() && it != VOICE_LANG_AUTO }?.let { return it }
        kb.currentLayout.str(KEY_VOICE_LANG).trim().takeIf { it.isNotEmpty() }?.let { return it }
        LAYOUT_LANGS[kb.currentLayoutName]?.let { return it }
        // For the number/emoji layouts use the last typing layout's language.
        LAYOUT_LANGS[kb.lastTypingLayout]?.let { return it }
        return Locale.getDefault().toLanguageTag()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = State.LISTENING
            kb.invalidate()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // Typical range is about -2..10 dB.
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            kb.invalidate()
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (state == State.LISTENING) state = State.PROCESSING
            kb.invalidate()
        }

        override fun onError(error: Int) {
            transRequestId++
            clearComposing()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.vo_err_nothing
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.vo_err_network
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.vo_err_permission
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.vo_err_busy
                SpeechRecognizer.ERROR_CLIENT -> 0 // our own cancel/stop
                else -> if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) R.string.vo_err_language
                else R.string.vo_err_generic
            }
            finish()
            if (msg != 0) toast(msg)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                commit("")
                finish()
                return
            }
            val spec = translationSpec
            if (spec != null) {
                state = State.PROCESSING
                kb.invalidate()
                val currentReqId = ++transRequestId
                val src = if (spec.sourceLang.isNotBlank() && spec.sourceLang != "auto") spec.sourceLang
                          else language().substringBefore("-").lowercase(Locale.ROOT)
                val tgt = spec.targetLang.substringBefore("-").lowercase(Locale.ROOT)

                Translator.translate(ctx, text, src, tgt, onSuccess = { translated ->
                    if (transRequestId != currentReqId || state != State.PROCESSING) return@translate
                    commit(translated)
                    finish()
                }, onError = { err ->
                    if (transRequestId != currentReqId || state != State.PROCESSING) return@translate
                    kb.prStack(err)
                    commit(text)
                    finish()
                    toast(R.string.vo_err_translate)
                })
            } else {
                commit(text)
                finish()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isEmpty()) return
            partial = text
            kb.inputConnection?.setComposingText(prepare(text), 1)
            kb.invalidate()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun clearComposing() {
        kb.inputConnection?.let {
            it.setComposingText("", 1)
            it.finishComposingText()
        }
    }

    /** Space before the text unless at the start or after a space; capital if the field wants one. */
    private fun prepare(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text
        val ic = kb.inputConnection ?: return text
        if (kb.autoShift) text = text.substring(0, 1).uppercase(Locale.getDefault()) + text.substring(1)
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val composing = partial.isNotEmpty() && state != State.IDLE
        // While composing, the character before the cursor is our own composing text: decide once.
        if (!composing || spaceBefore == null) spaceBefore = before.isNotEmpty() && !before[0].isWhitespace()
        return if (spaceBefore == true) " $text" else text
    }

    private var spaceBefore: Boolean? = null

    private fun commit(raw: String) {
        val ic = kb.inputConnection ?: return
        if (raw.isBlank()) {
            clearComposing()
            return
        }
        ic.beginBatchEdit()
        ic.setComposingText(prepare(raw), 1)
        ic.finishComposingText()
        ic.endBatchEdit()
        spaceBefore = null
        kb.onSelectionChanged()
    }

    /** Fallback: hand over to a voice input method (e.g. a system voice keyboard). */
    private fun switchToVoiceKeyboard(): Boolean {
        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == ctx.packageName) continue
            for (i in 0 until imi.subtypeCount) {
                val subtype = imi.getSubtypeAt(i)
                if (subtype.mode == "voice") {
                    val ime = kb.ime ?: return false
                    if (Build.VERSION.SDK_INT >= 28) ime.switchInputMethod(imi.id, subtype)
                    else {
                        val token = ime.window?.window?.attributes?.token ?: return false
                        @Suppress("DEPRECATION") imm.setInputMethodAndSubtype(token, imi.id, subtype)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun toast(res: Int) = Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()

    companion object {
        /**
         * Parses voice translation specifier like `@tr_voice/ru-en`, `tr_voice:ru-en`,
         * `@tr_voice/en`, `@tr_voice`, etc.
         */
        fun parseTrVoiceSpec(value: String): TranslationSpec? {
            val clean = value.trim()
            val raw = when {
                clean.startsWith("@tr_voice/") -> clean.removePrefix("@tr_voice/")
                clean.startsWith("@tr_voice:") -> clean.removePrefix("@tr_voice:")
                clean.startsWith("@tr_voice") -> clean.removePrefix("@tr_voice").removePrefix("/").removePrefix(":")
                clean.startsWith("tr_voice:") -> clean.removePrefix("tr_voice:")
                clean.startsWith("tr_voice/") -> clean.removePrefix("tr_voice/")
                clean.startsWith("tr_voice") -> clean.removePrefix("tr_voice").removePrefix("/").removePrefix(":")
                else -> return null
            }.trim()

            if (raw.isEmpty()) return TranslationSpec("auto", "en")

            val parts = when {
                raw.contains("->") -> raw.split("->", limit = 2)
                raw.contains(">") -> raw.split(">", limit = 2)
                raw.contains("/") -> raw.split("/", limit = 2)
                raw.contains(":") -> raw.split(":", limit = 2)
                raw.contains("-") -> {
                    val p = raw.split("-")
                    if (p.size == 4) listOf("${p[0]}-${p[1]}", "${p[2]}-${p[3]}")
                    else if (p.size >= 2) listOf(p[0], p[1])
                    else listOf(raw)
                }
                raw.contains("_") -> raw.split("_", limit = 2)
                else -> listOf(raw)
            }.map { it.trim().lowercase(Locale.ROOT) }

            return if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                TranslationSpec(parts[0], parts[1])
            } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                TranslationSpec("auto", parts[0])
            } else {
                TranslationSpec("auto", "en")
            }
        }

        /** Speech language for the bundled layouts. */
        val LAYOUT_LANGS = mapOf(
            "en" to "en-US", "enExt" to "en-US", "dvorak" to "en-US", "colemak" to "en-US", "code" to "en-US",
            "uk" to "uk-UA", "ru" to "ru-RU", "ruExt" to "ru-RU", "be" to "be-BY", "bg" to "bg-BG",
            "de" to "de-DE", "fr" to "fr-FR", "es" to "es-ES", "pl" to "pl-PL", "ro" to "ro-RO",
            "ar" to "ar-SA", "he" to "he-IL", "el" to "el-GR", "tr" to "tr-TR", "it" to "it-IT", "pt" to "pt-PT",
            "cs" to "cs-CZ", "hu" to "hu-HU", "sv" to "sv-SE", "fi" to "fi-FI", "nb" to "nb-NO", "da" to "da-DK",
        )
    }
}

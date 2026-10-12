package x.vladgba.keyboard.keyboard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Translates text caught by voice input or other keyboard actions.
 *
 * Supports:
 * 1. On-device translation via Android 12+ (API 31+) TranslationManager when available.
 * 2. Online translation fallback via Google Translate free endpoint (and MyMemory if rate-limited).
 *
 * Zero external libraries: uses only Android framework and Kotlin stdlib.
 */
object Translator {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun translate(
        ctx: Context,
        text: String,
        sourceLang: String,
        targetLang: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (text.isBlank()) {
            mainHandler.post { onSuccess(text) }
            return
        }

        val src = sourceLang.trim().lowercase(Locale.ROOT)
        val tgt = targetLang.trim().lowercase(Locale.ROOT)
        if (src.isNotEmpty() && src != "auto" && src == tgt) {
            mainHandler.post { onSuccess(text) }
            return
        }

        // 1. Try on-device translation on Android 12+ (API 31+) if supported
        if (Build.VERSION.SDK_INT >= 31) {
            tryOnDevice(ctx, text, src, tgt, onSuccess = { result ->
                mainHandler.post { onSuccess(result) }
            }, onFallback = {
                translateOnline(text, src, tgt, onSuccess, onError)
            })
            return
        }

        // 2. Online translation
        translateOnline(text, src, tgt, onSuccess, onError)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun tryOnDevice(
        ctx: Context,
        text: String,
        src: String,
        tgt: String,
        onSuccess: (String) -> Unit,
        onFallback: () -> Unit
    ) {
        try {
            val tm = ctx.getSystemService(android.view.translation.TranslationManager::class.java)
            if (tm == null) {
                onFallback()
                return
            }

            val srcLocale = if (src == "auto" || src.isEmpty()) android.icu.util.ULocale.getDefault() else android.icu.util.ULocale.forLanguageTag(src)
            val tgtLocale = android.icu.util.ULocale.forLanguageTag(tgt)

            val srcSpec = android.view.translation.TranslationSpec(srcLocale, android.view.translation.TranslationSpec.DATA_FORMAT_TEXT)
            val tgtSpec = android.view.translation.TranslationSpec(tgtLocale, android.view.translation.TranslationSpec.DATA_FORMAT_TEXT)

            val caps = tm.getOnDeviceTranslationCapabilities(
                android.view.translation.TranslationSpec.DATA_FORMAT_TEXT,
                android.view.translation.TranslationSpec.DATA_FORMAT_TEXT
            )
            val supported = caps.any {
                it.sourceSpec.locale.language.equals(srcLocale.language, ignoreCase = true) &&
                it.targetSpec.locale.language.equals(tgtLocale.language, ignoreCase = true) &&
                it.state != android.view.translation.TranslationCapability.STATE_NOT_AVAILABLE
            }

            if (!supported) {
                onFallback()
                return
            }

            val translationContext = android.view.translation.TranslationContext.Builder(srcSpec, tgtSpec)
                .setTranslationFlags(android.view.translation.TranslationContext.FLAG_LOW_LATENCY)
                .build()

            tm.createOnDeviceTranslator(translationContext, ctx.mainExecutor) { translator ->
                if (translator == null) {
                    onFallback()
                    return@createOnDeviceTranslator
                }
                try {
                    val request = android.view.translation.TranslationRequest.Builder()
                        .setTranslationRequestValues(listOf(android.view.translation.TranslationRequestValue.forText(text)))
                        .build()

                    translator.translate(request, null, ctx.mainExecutor) { response ->
                        try {
                            val values = response.translationResponseValues
                            val translated = if (values.size() > 0) values.valueAt(0)?.text?.toString() else null
                            translator.destroy()
                            if (!translated.isNullOrBlank()) {
                                onSuccess(translated)
                            } else {
                                onFallback()
                            }
                        } catch (_: Throwable) {
                            try { translator.destroy() } catch (_: Throwable) {}
                            onFallback()
                        }
                    }
                } catch (_: Throwable) {
                    try { translator.destroy() } catch (_: Throwable) {}
                    onFallback()
                }
            }
        } catch (_: Throwable) {
            onFallback()
        }
    }

    private fun translateOnline(
        text: String,
        src: String,
        tgt: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Thread {
            try {
                // Try Google Translate
                val googleResult = queryGoogleTranslate(text, src, tgt)
                if (!googleResult.isNullOrBlank()) {
                    mainHandler.post { onSuccess(googleResult) }
                    return@Thread
                }
            } catch (_: Throwable) {}

            try {
                // Fallback to MyMemory
                val myMemoryResult = queryMyMemory(text, src, tgt)
                if (!myMemoryResult.isNullOrBlank()) {
                    mainHandler.post { onSuccess(myMemoryResult) }
                    return@Thread
                }
            } catch (_: Throwable) {}

            mainHandler.post { onError(Exception("Translation failed for $src -> $tgt")) }
        }.start()
    }

    private fun queryGoogleTranslate(text: String, src: String, tgt: String): String? {
        val sl = if (src.isBlank() || src == "auto") "auto" else src.substringBefore("-")
        val tl = tgt.substringBefore("-")
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=$sl&tl=$tl&q=$encoded"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        }

        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONArray(body)
            val sentences = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val chunk = sentences.optJSONArray(i) ?: continue
                val str = chunk.optString(0)
                if (!str.isNullOrEmpty()) sb.append(str)
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    private fun queryMyMemory(text: String, src: String, tgt: String): String? {
        val sl = if (src.isBlank() || src == "auto") "auto" else src.substringBefore("-")
        val tl = tgt.substringBefore("-")
        val langpair = if (sl == "auto") tl else "$sl|$tl"
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langpair"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val responseData = root.optJSONObject("responseData") ?: return null
            val translated = responseData.optString("translatedText")
            return translated.takeIf { !it.isNullOrBlank() }
        } finally {
            conn.disconnect()
        }
    }
}

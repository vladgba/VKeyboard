package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

object ExtraRow {
    const val DEFAULT_KEYS = "Esc|Tab|Ctrl|Alt|←|↓|↑|→"
    private val codes = mapOf("esc" to 111, "tab" to 61, "←" to 21, "→" to 22, "↑" to 19, "↓" to 20,
        "home" to 122, "end" to 123, "delete" to 112, "pgup" to 92, "pgdn" to 93)
    fun build(spec: String): FlexNode = FlexNode().apply {
        this[ROW_HEIGHT] = "0.65"
        for (token in spec.split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(16)) {
            val value = token.take(128)
            childs.add(FlexNode().apply {
                this[KEY_KEY] = value.take(12)
                val lower = value.lowercase()
                val modifier = when (lower) { "ctrl" -> 4096; "alt" -> 2; "shift" -> 1; "meta" -> 65536; else -> 0 }
                val function = lower.removePrefix("f").toIntOrNull()?.takeIf { lower.startsWith("f") && it in 1..12 }
                val code = codes[lower] ?: function?.let { 130 + it }
                when {
                    modifier != 0 -> {
                        this[KEY_MODE] = KEY_MODE_META
                        this[KEY_MOD_META] = modifier.toString()
                        this[KEY_CODE] = when (lower) { "ctrl" -> "113"; "alt" -> "57"; "shift" -> "59"; else -> "117" }
                    }
                    code != null -> this[KEY_CODE] = (-code).toString()
                    else -> this[KEY_TEXT] = value
                }
            })
        }
    }
}

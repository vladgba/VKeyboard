package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

object RtlTools {
    const val PREFIX = "@RTL:"
    val marks = linkedMapOf("LRM" to "\u200e", "RLM" to "\u200f", "ALM" to "\u061c",
        "LRI" to "\u2066", "RLI" to "\u2067", "FSI" to "\u2068", "PDI" to "\u2069")
    private val brackets = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{',
        '<' to '>', '>' to '<', '«' to '»', '»' to '«', '‹' to '›', '›' to '‹')
    fun mirror(text: String): String = if (text.length == 1) brackets[text[0]]?.toString() ?: text else text
    fun row(): FlexNode = FlexNode().apply {
        this[ROW_HEIGHT] = "0.6"
        for (name in marks.keys) childs.add(FlexNode().apply { this[KEY_KEY] = PREFIX + name })
    }
}

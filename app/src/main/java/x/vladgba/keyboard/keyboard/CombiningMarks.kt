package x.vladgba.keyboard.keyboard

import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode

object CombiningMarks {
    const val PREFIX = "@MARK:"
    private val arabic = listOf(0x064e, 0x064f, 0x0650, 0x0652, 0x0651, 0x064b, 0x064c, 0x064d, 0x0653, 0x0654, 0x0655, 0x0670)
    private val hebrew = listOf(0x05b0, 0x05b1, 0x05b2, 0x05b3, 0x05b4, 0x05b5, 0x05b6, 0x05b7, 0x05b8, 0x05b9, 0x05ba, 0x05bb, 0x05bc, 0x05c1, 0x05c2, 0x05c7)
    fun mark(action: String): String? = action.removePrefix(PREFIX).toIntOrNull(16)?.takeIf { it in arabic || it in hebrew }
        ?.let { it.toChar().toString() }
    fun label(action: String) = mark(action)?.let { "◌$it" } ?: action
    fun rows(node: FlexNode): List<FlexNode> {
        val letters = node.childs.filterIsInstance<FlexNode>().flatMap { row -> row.childs.filterIsInstance<FlexNode>() }
            .joinToString("") { it.str(KEY_KEY) }
        val codes = when {
            letters.any { it in '\u05d0'..'\u05ea' } -> hebrew
            letters.any { it in '\u0620'..'\u064a' } -> arabic
            else -> return emptyList()
        }
        return codes.chunked(8).map { group -> FlexNode().apply {
            this[ROW_HEIGHT] = "0.55"
            for (cp in group) childs.add(FlexNode().apply { this[KEY_KEY] = PREFIX + cp.toString(16).uppercase().padStart(4, '0') })
        } }
    }
}

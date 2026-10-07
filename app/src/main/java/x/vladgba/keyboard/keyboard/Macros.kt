package x.vladgba.keyboard.keyboard

import android.content.Context
import x.vladgba.keyboard.core.MACROS_EXT
import x.vladgba.keyboard.core.MACROS_FILENAME
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/**
 * Named macros stored in `macros.macro` (app storage):
 * ```
 * (
 *     "copyAll": ("key:ctrl+a", "key:ctrl+c"),
 *     "sign":    ("text:Best regards,", "key:enter", "text:Vlad"),
 *     "slow":    ("key:ctrl+s", "wait:300", "key:alt+f4"),
 * )
 * ```
 * A key runs one with `macro: "copyAll"`; long-press, heavy press, swipes and popup chars accept
 * `"macro:copyAll"`.
 */
object Macros {
    const val PREFIX = "macro:"
    const val MAX_WAIT_MS = 10_000

    enum class Type(val prefix: String) { KEY("key:"), TEXT("text:"), WAIT("wait:") }

    class Step(val type: Type, val value: String) {
        override fun toString() = type.prefix + value
    }

    private var root = FlexNode()
    private var lastModified = -1L

    fun parseStep(s: String): Step {
        Type.values().firstOrNull { s.startsWith(it.prefix) }?.let { return Step(it, s.removePrefix(it.prefix)) }
        // Without a prefix: a key combination if it looks like one, otherwise text.
        return Step(if (Combos.parse(s, requireModifier = false) != null) Type.KEY else Type.TEXT, s)
    }

    private fun load(ctx: Context) {
        val file = PFile(ctx, MACROS_FILENAME, MACROS_EXT)
        if (file.lastModified() == lastModified) return
        lastModified = file.lastModified()
        root = FlexParser.parse(file.read())
    }

    private fun write(ctx: Context) {
        PFile(ctx, MACROS_FILENAME, MACROS_EXT).write(root.toString())
        lastModified = -1L
    }

    fun names(ctx: Context): List<String> {
        load(ctx)
        return root.params.filterValues { it is FlexNode }.keys.sortedBy { it.lowercase() }
    }

    fun exists(ctx: Context, name: String): Boolean {
        load(ctx)
        return root.params[name] is FlexNode
    }

    fun steps(ctx: Context, name: String): List<Step> {
        load(ctx)
        val node = root.params[name] as? FlexNode ?: return emptyList()
        return node.childs.filterIsInstance<String>().map(::parseStep)
    }

    fun save(ctx: Context, name: String, steps: List<Step>) {
        load(ctx)
        root[name] = FlexNode().apply { childs.addAll(steps.map { it.toString() }) }
        write(ctx)
    }

    fun delete(ctx: Context, name: String) {
        load(ctx)
        root.params.remove(name)
        write(ctx)
    }

    fun rename(ctx: Context, from: String, to: String) {
        load(ctx)
        val node = root.params.remove(from) ?: return
        root[to] = node
        write(ctx)
    }

    /** One-line summary for lists: `Ctrl + A → Ctrl + C`. */
    fun summary(steps: List<Step>): String = steps.joinToString("  →  ") { describe(it) }

    fun describe(step: Step): String = when (step.type) {
        Type.KEY -> Combos.parse(step.value, requireModifier = false)?.describe() ?: "? ${step.value}"
        Type.TEXT -> "“${step.value}”"
        Type.WAIT -> "${step.value} ms"
    }
}

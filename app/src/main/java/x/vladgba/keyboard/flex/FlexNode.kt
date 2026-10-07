package x.vladgba.keyboard.flex

/**
 * A node of a Flexaml tree: named [params] plus positional [childs].
 *
 * Named params are inherited: when a value is missing, it is looked up in [parent].
 * This is what makes `key -> row -> layout -> theme -> settings -> defaults` work.
 * Positional children are NOT inherited (a key without popup chars must not show its row's chars).
 */
open class FlexNode(var parent: FlexNode? = null) {
    var params: MutableMap<String, Any> = mutableMapOf()
    var childs: MutableList<Any> = mutableListOf()

    /** Creates a node that shares [f]'s data (edits are visible in [f]) but has its own [parent]. */
    constructor(f: FlexNode, parent: FlexNode?) : this(parent) {
        params = f.params
        childs = f.childs
    }

    // ---------- lookup ----------

    fun has(k: String): Boolean = params.containsKey(k) || parent?.has(k) == true

    fun has(i: Int): Boolean = i in childs.indices

    open operator fun get(s: String): FlexNode = when (val v = params[s]) {
        is FlexNode -> v
        else -> parent?.takeIf { it.has(s) }?.get(s) ?: FlexNode()
    }

    operator fun get(i: Int): FlexNode = (childs.getOrNull(i) as? FlexNode) ?: FlexNode()

    fun str(s: String, d: String = ""): String {
        if (params.containsKey(s)) return params[s] as? String ?: d
        return parent?.takeIf { it.has(s) }?.str(s, d) ?: d
    }

    fun str(i: Int, d: String = ""): String = childs.getOrNull(i) as? String ?: d

    fun num(s: String, d: Int = 0): Int {
        (params[s] as? String)?.trim()?.let { v ->
            if (v.isNotEmpty()) parseInt(v)?.let { return it }
        }
        return parent?.takeIf { it.has(s) }?.num(s, d) ?: d
    }

    fun float(s: String, d: Float = 0f): Float {
        (params[s] as? String)?.trim()?.let { v ->
            if (v.isNotEmpty()) v.toFloatOrNull()?.let { return it }
        }
        return parent?.takeIf { it.has(s) }?.float(s, d) ?: d
    }

    fun bool(s: String, d: Boolean = false) =
        if (has(s)) str(s).trim().lowercase() in TRUE_VALUES else d

    fun childCount() = childs.size

    fun paramCount() = params.size

    // ---------- mutation ----------

    operator fun set(i: Int, value: Any) {
        while (childs.size <= i) childs.add("")
        childs[i] = value
    }

    operator fun set(key: String, value: Any) {
        params[key] = value
    }

    /** Replaces own data with [data]'s data (inherited values stay untouched). */
    fun replaceWith(data: FlexNode) {
        params = data.params
        childs = data.childs
    }

    // ---------- serialization ----------

    override fun toString() = toStr(0, false)

    fun toStr(level: Int, isChild: Boolean, minify: Boolean = false): String {
        val sb = StringBuilder()
        if (minify) sb.append('(')
        else sb.append(if (isChild) indent(level) else "").append("(\n")

        if (params.isNotEmpty()) {
            params2str(sb, level + 1, minify)
            if (minify) sb.append(if (childs.isNotEmpty()) "," else "")
            else sb.append(if (childs.isNotEmpty()) ",\n\n" else "\n")
        }
        if (childs.isNotEmpty()) {
            childs2str(sb, level + 1, minify)
            if (!minify) sb.append('\n')
        }

        if (minify) sb.append(')') else sb.append(indent(level)).append(')')
        return sb.toString()
    }

    private fun params2str(sb: StringBuilder, level: Int, minify: Boolean) {
        var first = true
        for ((key, value) in params) {
            if (!first) sb.append(if (minify) "," else ",\n")
            first = false
            if (!minify) sb.append(indent(level))
            escape(key, sb)
            sb.append(if (minify) ":" else ": ")
            if (value is FlexNode) sb.append(value.toStr(level, false, minify))
            else escape(value.toString(), sb)
        }
    }

    private fun childs2str(sb: StringBuilder, level: Int, minify: Boolean) {
        for (i in childs.indices) {
            if (i != 0) sb.append(if (minify) "," else ",\n")
            val c = childs[i]
            if (c is FlexNode) sb.append(c.toStr(level, true, minify))
            else {
                if (!minify) sb.append(indent(level))
                escape(c.toString(), sb)
            }
        }
    }

    companion object {
        private val TRUE_VALUES = setOf("1", "true", "yes", "on")

        private fun indent(n: Int) = "    ".repeat(n)

        /** Parses decimal, `0x` hex and negative numbers. */
        fun parseInt(v: String): Int? {
            val neg = v.startsWith("-")
            val body = if (neg) v.substring(1) else v
            val n = if (body.startsWith("0x", true)) body.substring(2).toLongOrNull(16)?.toInt()
            else body.toIntOrNull()
            return n?.let { if (neg) -it else it }
        }

        fun escape(data: String, sb: StringBuilder = StringBuilder()): StringBuilder {
            sb.append('"')
            for (c in data) {
                val e = FlexParser.ESCAPE[c]
                when {
                    e != null -> sb.append(e)
                    c < ' ' || c.code > 0x7f -> sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            return sb.append('"')
        }
    }
}

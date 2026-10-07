package x.vladgba.keyboard.ui

/**
 * Fast, editor-side lexer for Flexaml text. It mirrors [x.vladgba.keyboard.flex.FlexParser]'s
 * character rules but keeps offsets, so the raw editor can colour tokens, match brackets,
 * find the node under the cursor and point at structural problems. Never throws.
 */
internal object CodeLexer {
    enum class Kind { COMMENT, STRING, KEY, NUMBER, WORD, OPEN, CLOSE, ASSIGN, SEP }

    class Tok(val kind: Kind, val start: Int, val end: Int) {
        /** Bracket nesting depth for OPEN/CLOSE (0 = outermost). */
        var depth = 0
        /** For positional scalar values: index among the parent node's positional children. */
        var slot = -1
    }

    /** One `( … )` block. [close] is -1 when the bracket is never closed. */
    class Node(val open: Int, val parent: Node?, val depth: Int, val nodeIndex: Int, val name: String?) {
        var close = -1
        var label: String? = null
        var positional = 0
        var nodes = 0
        fun contains(offset: Int) = offset > open && (close < 0 || offset <= close)
    }

    enum class ProblemType { UNCLOSED, UNEXPECTED_CLOSE, UNTERMINATED }
    class Problem(val offset: Int, val type: ProblemType)

    class Result(val toks: List<Tok>, val nodes: List<Node>, val pairs: Map<Int, Int>, val problems: List<Problem>) {
        /** First token index whose end is after [offset]. */
        fun firstTokAfter(offset: Int): Int {
            var lo = 0
            var hi = toks.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (toks[mid].end <= offset) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun tokAt(offset: Int): Tok? = toks.getOrNull(firstTokAfter(offset))?.takeIf { it.start <= offset }

        /** Innermost node containing [offset]. */
        fun nodeAt(offset: Int): Node? {
            var best: Node? = null
            for (n in nodes) if (n.contains(offset) && (best == null || n.depth > best.depth)) best = n
            return best
        }
    }

    fun lex(s: CharSequence): Result {
        val toks = ArrayList<Tok>()
        val nodes = ArrayList<Node>()
        val pairs = HashMap<Int, Int>()
        val problems = ArrayList<Problem>()
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '#' -> {
                    val st = i
                    while (i < n && s[i] != '\n' && s[i] != '\r') i++
                    toks += Tok(Kind.COMMENT, st, i)
                }
                c == '"' -> {
                    val st = i
                    i++
                    var closed = false
                    while (i < n) {
                        val d = s[i]
                        if (d == '\\') { i += 2; continue }
                        i++
                        if (d == '"') { closed = true; break }
                    }
                    if (i > n) i = n
                    if (!closed) problems += Problem(st, ProblemType.UNTERMINATED)
                    toks += Tok(Kind.STRING, st, i)
                }
                c.isDigit() || c == '-' -> {
                    val st = i
                    i++
                    if (i < n && (s[i] == 'x' || s[i] == 'X')) i++
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) i++
                    toks += Tok(Kind.NUMBER, st, i)
                }
                c.isLetter() || c == '_' || c == '!' -> {
                    val st = i
                    i++
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    toks += Tok(Kind.WORD, st, i)
                }
                c == '(' || c == '[' || c == '{' -> { toks += Tok(Kind.OPEN, i, i + 1); i++ }
                c == ')' || c == ']' || c == '}' -> { toks += Tok(Kind.CLOSE, i, i + 1); i++ }
                c == ':' || c == '=' -> { toks += Tok(Kind.ASSIGN, i, i + 1); i++ }
                c == ',' || c == ';' -> { toks += Tok(Kind.SEP, i, i + 1); i++ }
                else -> i++
            }
        }

        // Second pass: keys, structure, slots.
        val stack = ArrayList<Node>()
        var afterAssign = false
        var pendingName: String? = null
        for ((ti, t) in toks.withIndex()) {
            if (t.kind == Kind.COMMENT || t.kind == Kind.SEP) continue
            val next = nextSignificant(toks, ti)
            val isKey = next?.kind == Kind.ASSIGN && (t.kind == Kind.STRING || t.kind == Kind.WORD || t.kind == Kind.NUMBER)
            when (t.kind) {
                Kind.OPEN -> {
                    val parent = stack.lastOrNull()
                    val node = Node(t.start, parent, stack.size, parent?.nodes ?: 0, if (afterAssign) pendingName else null)
                    if (parent != null && !afterAssign) { parent.nodes++; parent.positional++ }
                    t.depth = stack.size
                    stack += node
                    nodes += node
                    afterAssign = false
                }
                Kind.CLOSE -> {
                    val node = stack.removeLastOrNull()
                    if (node == null) problems += Problem(t.start, ProblemType.UNEXPECTED_CLOSE)
                    else {
                        node.close = t.start
                        pairs[node.open] = t.start
                        pairs[t.start] = node.open
                    }
                    t.depth = stack.size
                    afterAssign = false
                }
                Kind.ASSIGN -> afterAssign = true
                else -> {
                    if (isKey) {
                        toks[ti] = Tok(Kind.KEY, t.start, t.end)
                        pendingName = unquote(s, t)
                    } else if (afterAssign) {
                        if (pendingName == "key") stack.lastOrNull()?.label = unquote(s, t)
                        afterAssign = false
                    } else {
                        val parent = stack.lastOrNull()
                        if (parent != null) t.slot = parent.positional++
                    }
                }
            }
        }
        for (open in stack) problems += Problem(open.open, ProblemType.UNCLOSED)
        problems.sortBy { it.offset }
        return Result(toks, nodes, pairs, problems)
    }

    private fun nextSignificant(toks: List<Tok>, from: Int): Tok? {
        for (j in from + 1 until toks.size) {
            val k = toks[j].kind
            if (k != Kind.COMMENT && k != Kind.SEP) return toks[j]
        }
        return null
    }

    fun unquote(s: CharSequence, t: Tok): String {
        val raw = s.subSequence(t.start, t.end).toString()
        return if (t.kind == Kind.STRING || (raw.length >= 2 && raw.startsWith('"'))) raw.removePrefix("\"").removeSuffix("\"") else raw
    }

    /** `ffaabbcc` / `aabbcc` inside quotes → ARGB, else null. */
    fun colorOf(s: CharSequence, t: Tok): Int? {
        if (t.kind != Kind.STRING) return null
        val v = unquote(s, t).removePrefix("#")
        if (v.length != 6 && v.length != 8) return null
        if (!v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val x = v.toLong(16).toInt()
        return if (v.length == 6) x or (0xff shl 24) else x
    }

    /** 1-based line/col → offset (as reported by FlexParser warnings). */
    fun offsetOf(s: CharSequence, line: Int, col: Int): Int {
        var l = 1
        var i = 0
        while (i < s.length && l < line) { if (s[i] == '\n') l++; i++ }
        return (i + (col - 1).coerceAtLeast(0)).coerceAtMost(s.length)
    }
}

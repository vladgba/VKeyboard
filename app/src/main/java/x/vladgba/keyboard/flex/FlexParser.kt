package x.vladgba.keyboard.flex

import android.util.Log

/**
 * Parser for the "Flexaml" format used by layouts, themes and settings.
 *
 * It is a relaxed JSON superset:
 *  - `(`, `[` and `{` are all "open node", `)`, `]`, `}` are all "close node"
 *  - `:` and `=` both assign, `,` and `;` are optional separators
 *  - words (identifiers) and numbers can be written without quotes
 *  - `#` starts a line comment
 *  - nodes hold named params and positional children at the same time
 *
 * The parser never throws: broken input produces a best-effort tree and [warnings].
 */
open class FlexParser(private val input: String) {

    enum class TokenType { WORD, COLON, STR, NUM, BEGIN, END, HEX }

    class Token(val type: TokenType, val text: String, val row: Int, val col: Int) {
        override fun toString() = "$type [$row $col] $text"
    }

    class ParseWarning(val msg: String, val line: Int, val col: Int) {
        override fun toString() = "[$line:$col] $msg"
    }

    private var pos = 0
    private val length = input.length
    private var row = 1
    private var col = 1

    private var tokenPos = 0
    private val tokens = mutableListOf<Token>()
    private val buffer = StringBuilder()

    val warnings = mutableListOf<ParseWarning>()

    open fun parse(): FlexNode {
        val result = FlexNode()
        try {
            if (tokens.isEmpty()) tokenize()
            if (tokens.isEmpty()) return result
            enumerate(result)
        } catch (e: Exception) {
            warnings += ParseWarning(e.message ?: e.toString(), row, col)
            Log.w(TAG, "Parse failed", e)
        }
        return if (tokens.isNotEmpty() && tokens[0].type == TokenType.BEGIN && result.childCount() > 0) result[0] else result
    }

    private fun enumerate(v: FlexNode) {
        while (tokenPos < tokens.size) {
            val t = tokens[tokenPos]
            when {
                t.type == TokenType.END -> return
                isValue(tokenPos) -> {
                    if (tokenPos + 1 < tokens.size && tokens[tokenPos + 1].type == TokenType.COLON) assign(v)
                    else v.childs.add(tokens[tokenPos++].text)
                }
                t.type == TokenType.BEGIN -> {
                    val node = FlexNode()
                    v.childs.add(node)
                    tokenPos++ // "("
                    enumerate(node)
                    tokenPos++ // ")"
                }
                else -> {
                    warn("Unexpected token ${t.type}" + if (t.text.isEmpty()) "" else " `${t.text}`", t)
                    tokenPos++
                }
            }
        }
    }

    private fun assign(v: FlexNode) {
        if (tokenPos + 2 >= tokens.size) {
            warn("Assignment without value", tokens[tokenPos])
            tokenPos = tokens.size
            return
        }
        val key = tokens[tokenPos].text
        when {
            isValue(tokenPos + 2) -> {
                v.params[key] = tokens[tokenPos + 2].text
                tokenPos += 3 // key : value
            }
            tokens[tokenPos + 2].type == TokenType.BEGIN -> {
                val node = FlexNode()
                v.params[key] = node
                tokenPos += 3 // key : (
                enumerate(node)
                tokenPos++ // )
            }
            else -> {
                warn("Unexpected value for `$key`", tokens[tokenPos + 2])
                tokenPos += 2
            }
        }
    }

    private fun isValue(i: Int) = tokens[i].type.let {
        it == TokenType.STR || it == TokenType.WORD || it == TokenType.NUM || it == TokenType.HEX
    }

    fun tokenize(): List<Token> {
        while (pos < length) {
            val c = peek(0)
            when {
                c.isDigit() || c == '-' -> tokenizeNumber()
                c.isLetter() || c == '_' || c == '!' -> tokenizeWord()
                c == '"' -> tokenizeText()
                c == '#' -> tokenizeComment()
                c in OPERATORS -> tokenizeOperator()
                else -> next() // whitespace and unknown symbols
            }
        }
        return tokens
    }

    private fun tokenizeComment() {
        var c = next()
        while (c != '\r' && c != '\n' && c != '\u0000') c = next()
    }

    private fun tokenizeWord() {
        buffer.setLength(0)
        buffer.append(peek(0))
        var c = next()
        while (c.isLetterOrDigit() || c == '_') {
            buffer.append(c)
            c = next()
        }
        addToken(TokenType.WORD, buffer.toString())
    }

    private fun tokenizeOperator() {
        val c = peek(0)
        next()
        when (c) {
            in BEGIN_TOKENS -> addToken(TokenType.BEGIN)
            in END_TOKENS -> addToken(TokenType.END)
            in ASSIGN_TOKENS -> addToken(TokenType.COLON)
            else -> {} // separators are optional and ignored
        }
    }

    private fun tokenizeText() {
        val startRow = row
        val startCol = col
        next() // skip opening "
        buffer.setLength(0)
        var c = peek(0)
        while (true) {
            if (pos >= length) {
                warnings += ParseWarning("Unterminated string", startRow, startCol)
                break
            }
            if (c == '\\') {
                c = next()
                val unescaped = UNESCAPE[c]
                if (unescaped != null) {
                    buffer.append(unescaped)
                    c = next()
                } else if (c == 'u') {
                    val rollback = pos
                    while (c == 'u') c = next()
                    val hex = StringBuilder()
                    while (isHex(c) && hex.length < 6) {
                        hex.append(c)
                        c = next()
                    }
                    val cp = hex.toString().toIntOrNull(16)
                    if (cp != null && Character.isValidCodePoint(cp)) buffer.appendCodePoint(cp)
                    else {
                        buffer.append("\\u")
                        pos = rollback
                        c = peek(0)
                    }
                } else {
                    buffer.append('\\').append(c)
                    c = next()
                }
                continue
            }
            if (c == '"') break
            buffer.append(c)
            c = next()
        }
        next() // skip closing "
        addToken(TokenType.STR, buffer.toString())
    }

    private fun tokenizeNumber() {
        buffer.setLength(0)
        var c = peek(0)
        if (c == '-') {
            buffer.append(c)
            c = next()
        }
        if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            next()
            next()
            return tokenizeHexNumber()
        }
        while (c.isDigit() || (c == '.' && buffer.indexOf(".") == -1)) {
            buffer.append(c)
            c = next()
        }
        addToken(TokenType.NUM, buffer.toString())
    }

    private fun tokenizeHexNumber() {
        buffer.setLength(0)
        var c = peek(0)
        while (isHex(c) || c == '_') {
            if (c != '_') buffer.append(c) // `_` may be used as a digit separator
            c = next()
        }
        if (buffer.isNotEmpty()) addToken(TokenType.HEX, buffer.toString())
    }

    private fun isHex(c: Char) = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    private fun next(): Char {
        pos++
        val c = peek(0)
        if (c == '\n') {
            row++
            col = 1
        } else col++
        return c
    }

    private fun peek(relPos: Int): Char = (pos + relPos).let { if (it >= length) '\u0000' else input[it] }

    private fun addToken(type: TokenType, text: String = "") {
        tokens += Token(type, text, row, col)
    }

    private fun warn(msg: String, token: Token) {
        ParseWarning(msg, token.row, token.col).also {
            Log.w(TAG, it.toString())
            warnings += it
        }
    }

    companion object {
        private const val TAG = "FlexParser"
        private val BEGIN_TOKENS = charArrayOf('(', '[', '{')
        private val END_TOKENS = charArrayOf(')', ']', '}')
        private val ASSIGN_TOKENS = charArrayOf(':', '=')
        private val SEPARATOR_TOKENS = charArrayOf(',', ';')
        private val OPERATORS = (BEGIN_TOKENS + END_TOKENS + ASSIGN_TOKENS + SEPARATOR_TOKENS).toSet()

        private val UNESCAPE = mapOf(
            '\\' to "\\", '"' to "\"", 'n' to "\n", 'r' to "\r",
            't' to "\t", 'b' to "\b", '0' to "\u0000", 'f' to "\u000C"
        )

        internal val ESCAPE = mapOf(
            '\\' to "\\\\", '"' to "\\\"", '\n' to "\\n", '\r' to "\\r",
            '\t' to "\\t", '\b' to "\\b", '\u0000' to "\\0", '\u000C' to "\\f"
        )

        fun parse(text: String) = FlexParser(text).parse()
    }
}

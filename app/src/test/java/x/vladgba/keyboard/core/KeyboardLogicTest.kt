package x.vladgba.keyboard.core

import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser
import x.vladgba.keyboard.keyboard.*
import kotlin.random.Random

private fun parseLayout(text: String): FlexNode {
    val parser = FlexParser(text) { }
    val value = parser.parse()
    check(parser.warnings.isEmpty()) { parser.warnings.toString() }
    return value
}

fun main() {
    checkComposeAndDeadKeys()
    checkLayoutRoundTrips()
    checkGeneratedRows()
    checkModifierMappings()
    checkUnicodeWords()
    checkEmojiExtraction()
    println("PASS keyboard logic: compose/dead keys, 250 layout round-trips, generated rows, modifier maps, Unicode and emoji boundaries")
}

private fun checkComposeAndDeadKeys() {
    val engine = ComposeHelper()
    check(engine.feed("x") == "x" && !engine.active)
    engine.start()
    check(engine.feed("'") == null && engine.firstChar == "'")
    check(engine.feed("E") == "É" && !engine.active && engine.firstChar == null)
    engine.start()
    check(engine.feed("unknown") == null && engine.feed("pair") == "unknownpair")
    engine.start(); engine.feed("'"); engine.cancel()
    check(engine.feed("e") == "e" && engine.dead == null)
    engine.arm("tilde")
    check(engine.feed("n") == "ñ" && !engine.active && engine.dead == null)
    engine.arm("acute"); engine.cancel()
    check(engine.feed("e") == "e")
    check(engine.toggle() && !engine.toggle())
    for ((first, second, expected) in listOf(Triple("c", "o", "©"), Triple("1", "2", "½"), Triple("s", "s", "ß"))) {
        check(ComposeHelper.compose(first, second) == expected)
        check(ComposeHelper.compose(second, first) == expected)
    }
    check(DeadKeys.apply("acute", "e") == "é")
    check(DeadKeys.apply("acute", "x") == "x\u0301")
    check(DeadKeys.apply("acute", " ") == "´")
    check(DeadKeys.apply("acute", "2") == "´2")
    check(DeadKeys.apply("superscript", "2") == "²")
    check(DeadKeys.apply("arrows", "8") == "↑")
    check(DeadKeys.apply("stroke", "ab") == "ab")
    check(!DeadKeys.isDeadKey("@DEAD:unknown") && DeadKeys.isDeadKey("@DEAD:acute"))
}

private fun checkLayoutRoundTrips() {
    val random = Random(84319)
    val values = listOf("", "quote\"", "slash\\", "#comment", "(brackets)", "line\nnext", "tab\tend", "0x20", "-67", "é", "שׁ", "بَ", "👨‍👩‍👧", "🇺🇦")
    fun tree(depth: Int): FlexNode = FlexNode().apply {
        repeat(random.nextInt(1, 5)) { this["param$it"] = values.random(random) }
        if (depth > 0) this["nested"] = tree(depth - 1)
        repeat(random.nextInt(5)) { childs.add(if (depth > 0 && random.nextBoolean()) tree(depth - 1) else values.random(random)) }
    }
    fun same(a: FlexNode, b: FlexNode) {
        check(a.params.keys == b.params.keys && a.childs.size == b.childs.size)
        for ((key, value) in a.params) {
            val other = b.params[key]
            if (value is FlexNode) { check(other is FlexNode); same(value, other) } else check(value == other)
        }
        for ((i, value) in a.childs.withIndex()) {
            val other = b.childs[i]
            if (value is FlexNode) { check(other is FlexNode); same(value, other) } else check(value == other)
        }
    }
    repeat(250) {
        val source = tree(3)
        same(source, parseLayout(source.toString()))
        same(source, parseLayout(source.toStr(0, false, true)))
    }
    val parent = FlexNode().apply { this["color"] = "blue"; childs.add("parent popup") }
    val child = FlexNode(parent)
    check(child.str("color") == "blue" && child.childCount() == 0 && child.str(0).isEmpty())
    child["color"] = "red"
    check(parent.str("color") == "blue")
}

private fun checkGeneratedRows() {
    fun basic() = parseLayout("(((\"key\":\"a\")))")
    val digits = basic()
    NumberRow.apply(digits, "en", NumberRow.SYMBOLS)
    check(digits.childCount() == 2 && digits[0].childCount() == 10)
    check((0..9).map { digits[0][it].str(KEY_KEY) }.joinToString("") == "1234567890")
    check(digits[0][0].str(1) == "!" && digits[0][9].str(1) == ")")
    NumberRow.apply(digits, "en", NumberRow.SYMBOLS)
    check(digits.childCount() == 2)
    for (name in listOf("123", "emoji", "\$nav", "settings")) {
        val layout = basic(); NumberRow.apply(layout, name, NumberRow.DIGITS); check(layout.childCount() == 1)
    }
    val overridden = basic().apply { this[SETTING_NUMBER_ROW] = "0" }
    NumberRow.apply(overridden, "en", NumberRow.DIGITS)
    check(overridden.childCount() == 1)
    for (digit in '0'..'9') {
        val value = digit.toString()
        check(Numpad.digit(Numpad.digit(value, true), true) == value)
        check(Numpad.digit(value, false) == value)
    }
    check(Numpad.digit("7", true) == "1" && Numpad.digit("2", true) == "8")
    check(Numpad.digit("١", true) == "١" && Numpad.digit("12", true) == "12")
    val extra = ExtraRow.build("| Tab |Ctrl|Alt|Shift|Meta|F1|F12|literal||")
    check(extra.childCount() == 8 && extra[0].num(KEY_CODE) == -61)
    check(extra[1].num(KEY_CODE) == 113 && extra[2].num(KEY_CODE) == 57 && extra[3].num(KEY_CODE) == 59 && extra[4].num(KEY_CODE) == 117)
    check(extra[5].num(KEY_CODE) == -131 && extra[6].num(KEY_CODE) == -142 && extra[7].str(KEY_TEXT) == "literal")
    check(ExtraRow.build("x|".repeat(40)).childCount() == 16)
    check(ExtraRow.build(" || ").childCount() == 0)
}

private fun checkModifierMappings() {
    val layout = parseLayout("(\"modmap\":(\"alt\":(\"a\":\"α\"),\"ctrl+shift\":(\"a\":\"!\"),\"altgr\":(\"a\":\"€\")),((\"key\":\"a\",\"modmap\":(\"ctrl\":\"β\",\"unknown\":\"bad\"))))")
    val key = layout[0][0]
    check(ModifierMap.resolve(key, layout, 0x02) == "α")
    check(ModifierMap.resolve(key, layout, 0x1000) == "β")
    check(ModifierMap.resolve(key, layout, 0x1001) == "!")
    check(ModifierMap.resolve(key, layout, 0x22) == "€")
    check(ModifierMap.resolve(key, layout, 0) == null)
    check(ModifierMap.resolve(key, layout, 0x1002) == null)
    key["modmap"]["alt"] = "own"
    check(ModifierMap.resolve(key, layout, 0x02) == "own")
    key["modmap"]["ctrl"] = ""
    check(ModifierMap.resolve(key, layout, 0x1000) == "")
}

private fun checkUnicodeWords() {
    for (word in listOf("hello", "café", "l'été", "тест", "بَ", "שׁ")) check(UserWords.isLearnable(word)) { word }
    for (word in listOf("", "x", "1234", "a1", "two words", "a\t", "a\u0000", "a".repeat(49), "a\ud800", "\udc00a")) {
        check(!UserWords.isLearnable(word)) { "Invalid learned word accepted: ${word.toCharArray().map { it.code }}" }
    }
    check(isLetterOrMark('َ') && isLetterOrMark('ְ') && isLetterOrMark('é'))
    check(!isLetterOrMark(' ') && !isLetterOrMark('_') && !isLetterOrMark('1'))
    for (symbol in "()[]{}<>«»‹›") check(RtlTools.mirror(RtlTools.mirror(symbol.toString())) == symbol.toString())
    check(RtlTools.mirror("(") == ")" && RtlTools.mirror("a(b)") == "a(b)")
    check(RtlTools.marks["RLM"] == "\u200f" && RtlTools.marks["LRI"] == "\u2066")
    for (letter in listOf("ب", "ש")) {
        val rows = CombiningMarks.rows(parseLayout("(((\"key\":\"$letter\")))"))
        check(rows.isNotEmpty())
        for (row in rows) for (item in row.childs.filterIsInstance<FlexNode>()) {
            val mark = CombiningMarks.mark(item.str(KEY_KEY)) ?: error("Invalid generated mark")
            check(Character.getType(mark[0]) == Character.NON_SPACING_MARK.toInt())
        }
    }
    check(CombiningMarks.rows(parseLayout("(((\"key\":\"a\")))")).isEmpty())
    check(CombiningMarks.mark("@MARK:1F600") == null)
}

private fun checkEmojiExtraction() {
    val page = parseLayout("(((\"key\":\"tab\",\"layout\":\"emoji\",\"text\":\"ignore\")),((\"key\":\"😀\",\"text\":\"😀\",\"😀\",\"👨‍👩‍👧\",\"🇺🇦\",\"@VOICE\",\"-67\",\"macro:bad\",\" \"),(\"key\":\"hidden\",\"text\":\"hidden\",\"visible\":\"0\"),(\"key\":\"code\",\"text\":\"ignore\",\"code\":\"-67\")))")
    check(EmojiCatalog.entries(page) == listOf("😀", "👨‍👩‍👧", "🇺🇦"))
    val actions = parseLayout("(((\"text\":\"😀\",\"macro\":\"run\"),(\"text\":\"😀\",\"voice\":\"1\"),(\"text\":\"😀\",\"compose\":\"1\"),(\"text\":\"😀\",\"mode\":\"meta\"),(\"text\":\"😀\",\"ctrl+c\",\"Alt+F4\",\"🫶\")))")
    check(EmojiCatalog.entries(actions) == listOf("😀", "🫶"))
    val many = FlexNode().apply {
        childs.add(FlexNode().apply { repeat(EmojiCatalog.MAX_ENTRIES + 20) { n ->
            childs.add(FlexNode().apply { this[KEY_TEXT] = "😀$n" })
        } })
    }
    check(EmojiCatalog.entries(many).size == EmojiCatalog.MAX_ENTRIES)
}

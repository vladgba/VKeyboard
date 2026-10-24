package x.vladgba.keyboard.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Synthetic encoder for format-level tests; no third-party dictionaries or libraries. */
private class CompactFixture(private val dictionaries: Int = 1) {
    val data = MutableList(5 + dictionaries * 24) { 0 }
    class Edge(val label: Int, val target: Int, val final: Boolean, val number: Int)
    class Dictionary(val root: Int, val ranks: List<Int>, val aliases: List<Pair<Int, Int>> = emptyList())

    fun write(offset: Int, size: Int, value: Int) {
        repeat(size) { i -> data[offset + i] = (value ushr ((size - i - 1) * 8)) and 255 }
    }
    private fun append(values: List<Int>): Int {
        val start = data.size
        data.addAll(values)
        return start
    }
    private fun aligned(): Int {
        if (data.size % 2 != 0) data.add(0)
        return data.size
    }
    fun leaf() = branches(emptyList())
    fun prefix(text: String, target: Int, final: Boolean): Int = prefixBytes(text.toByteArray(), target, final)
    fun prefixBytes(text: ByteArray, target: Int, final: Boolean): Int {
        val start = aligned()
        append(listOf((text.size shl 1) or 1, 0, 0, 0) + text.map { it.toInt() and 255 })
        write(start + 1, 3, (target - start) or if (final) 1 else 0)
        return start
    }
    private fun pack(values: List<Int>, format: Int): List<Int> {
        if (format == 1) return values.chunked(2).map { (it[0] and 15) or ((it.getOrElse(1) { 0 } and 15) shl 4) }
        return values.flatMap { value -> (format / 2 - 1 downTo 0).map { (value ushr (it * 8)) and 255 } }
    }
    fun branches(edges: List<Edge>, pointerFormat: Int = 6, numberFormat: Int = 6): Int {
        val sorted = edges.sortedBy { it.label }
        val heap = arrayOfNulls<Edge>(edges.size)
        var cursor = 0
        fun fill(i: Int) {
            if (i >= heap.size) return
            fill(i * 2 + 1)
            heap[i] = sorted[cursor++]
            fill(i * 2 + 2)
        }
        fill(0)
        val start = aligned()
        val ordered = heap.map { it!! }
        append(listOf((pointerFormat shl 1) or (numberFormat shl 4), edges.size) + ordered.map { it.label } +
            pack(ordered.map { (it.target - start) or if (it.final) 1 else 0 }, pointerFormat) +
            pack(ordered.map { it.number }, numberFormat))
        return start
    }
    fun finish(vararg specs: Dictionary, aliasFormat: Int = 2): ByteArray {
        check(specs.size == dictionaries)
        data[0] = 68; data[1] = 105; data[2] = 99; data[3] = 1; data[4] = dictionaries
        for ((i, spec) in specs.withIndex()) {
            val frequency = append(pack(spec.ranks, 1))
            val name = append("synthetic-$i".map { it.code } + 0)
            val keys = if (spec.aliases.isEmpty()) 0 else append(pack(spec.aliases.map { it.first }, aliasFormat))
            val values = if (spec.aliases.isEmpty()) 0 else append(pack(spec.aliases.map { it.second }, aliasFormat))
            val header = 5 + i * 24
            write(header, 4, name)
            write(header + 4, 4, spec.root)
            write(header + 8, 4, frequency)
            write(header + 12, 1, aliasFormat or (aliasFormat shl 3))
            write(header + 13, 3, spec.aliases.size)
            write(header + 16, 4, keys)
            write(header + 20, 4, values)
        }
        return data.map { it.toByte() }.toByteArray()
    }

    fun trie(words: List<String>): Int {
        val encoded = words.map { it.toByteArray() }
        val end = leaf()
        fun build(group: List<ByteArray>, at: Int): Int {
            if (group.isEmpty()) return end
            var number = 0
            val edges = group.groupBy { it[at].toInt() and 255 }.toSortedMap().map { (label, entries) ->
                val rest = entries.filter { it.size > at + 1 }
                val child = build(rest, at + 1)
                Edge(label, child, entries.any { it.size == at + 1 }, number).also { number += entries.size }
            }
            return branches(edges)
        }
        return build(encoded, 0)
    }
}

internal fun checkCompactDictionaries() {
    fun expect(bytes: ByteArray, vararg words: Pair<String, Int>) {
        check(CompactDictionary.matches(bytes))
        val parsed = UserWords.parse(bytes)
        check(parsed == words.toList()) { "Compiled dictionary mismatch: $parsed" }
    }
    fun reject(bytes: ByteArray) {
        val error = runCatching { UserWords.parse(bytes) }.exceptionOrNull()
        check(error is IllegalArgumentException) { "Invalid compiled dictionary accepted: $error" }
    }
    expect(CompactFixture(0).finish())
    val single = CompactFixture().run {
        val root = prefix("hello", leaf(), true)
        finish(CompactFixture.Dictionary(root, listOf(12)))
    }
    expect(single, "hello" to 13)
    // Prefix terminals and shared suffix nodes must each have distinct frequency indexes.
    val prefixes = CompactFixture().run {
        val end = leaf()
        val branch = branches(listOf(CompactFixture.Edge(110, end, true, 0), CompactFixture.Edge(116, end, true, 1)))
        val root = prefix("a", branch, true)
        finish(CompactFixture.Dictionary(root, listOf(0, 9, 15)))
    }
    expect(prefixes, "a" to 1, "an" to 10, "at" to 16)
    for (format in listOf(1, 2, 4, 6)) {
        val shared = CompactFixture().run {
            val end = leaf()
            val suffix = prefix("t", end, true)
            val branch = branches(listOf(CompactFixture.Edge(97, suffix, false, 0), CompactFixture.Edge(111, suffix, false, 1)), format, format)
            val root = prefix("c", branch, false)
            finish(CompactFixture.Dictionary(root, listOf(3, 14)))
        }
        expect(shared, "cat" to 4, "cot" to 15)
        val positive = CompactFixture().run {
            val after = 30 + 3 + 2 * ((format + 1) / 2)
            val target = (after + 1) and -2
            val root = branches(listOf(CompactFixture.Edge(97, target, true, 0)), format, format)
            check(leaf() == target)
            finish(CompactFixture.Dictionary(root, listOf(4)))
        }
        expect(positive, "a" to 5)
    }
    val odd = CompactFixture().run {
        val end = leaf()
        val root = branches(listOf(CompactFixture.Edge(97, end, true, 0), CompactFixture.Edge(98, end, true, 1),
            CompactFixture.Edge(99, end, true, 2)), 1, 1)
        finish(CompactFixture.Dictionary(root, listOf(0, 7, 15)))
    }
    expect(odd, "a" to 1, "b" to 8, "c" to 16)
    // A forward reference exercises positive signed offsets, unlike child-first construction.
    val forward = CompactFixture().run {
        val root = prefix("x", 36, true)
        val end = leaf()
        check(root == 30 && end == 36)
        finish(CompactFixture.Dictionary(root, listOf(5)))
    }
    expect(forward, "x" to 6)
    val unicode = listOf("café", "тест", "😀")
    for (aliasFormat in listOf(1, 2, 4, 6)) {
        val bytes = CompactFixture().run {
            val root = trie(unicode)
            finish(CompactFixture.Dictionary(root, listOf(2, 8, 11), listOf(0 to 1)), aliasFormat = aliasFormat)
        }
        expect(bytes, "тест" to 9, "😀" to 12)
    }
    val multiple = CompactFixture(2).run {
        val first = prefix("one", leaf(), true)
        val second = prefix("два", leaf(), true)
        finish(CompactFixture.Dictionary(first, listOf(2)), CompactFixture.Dictionary(second, listOf(8)))
    }
    expect(multiple, "one" to 3, "два" to 9)
    val invalidWord = CompactFixture().run {
        val root = trie(listOf("hello", "two words"))
        finish(CompactFixture.Dictionary(root, listOf(2, 7)))
    }
    expect(invalidWord, "hello" to 3)
    val random = Random(9323)
    val generated = sortedSetOf<String>()
    repeat(4000) { generated.add(buildString { repeat(7) { append(('a'.code + random.nextInt(26)).toChar()) } }) }
    val large = CompactFixture().run { finish(CompactFixture.Dictionary(trie(generated.toList()), generated.indices.map { it % 16 })) }
    check(UserWords.parse(large) == generated.mapIndexed { i, word -> word to (i % 16 + 1) })
    for (n in 4 until single.size) reject(single.copyOf(n))
    for (word in listOf("Dictionary", "Dicção", "Dic")) {
        check(UserWords.parse("$word\t12\nDictation\t8".toByteArray()) == listOf(word to 12, "Dictation" to 8))
    }
    reject(single.copyOf().also { it[3] = 0 })
    reject(single.copyOf().also { it[3] = 2 })
    reject(single.copyOf().also { it[4] = 100 })
    reject(single.copyOf().also { for (i in 9..12) it[i] = 0 })
    reject(single.copyOf().also { it[it.lastIndex] = 65 })
    reject(CompactFixture().run { finish(CompactFixture.Dictionary(prefixBytes(byteArrayOf(0xc3.toByte()), leaf(), true), listOf(1))) })
    reject(CompactFixture().run {
        val root = trie(listOf("a", "b"))
        finish(CompactFixture.Dictionary(root, listOf(1, 2), listOf(0 to 10)))
    })
    reject(CompactFixture().run {
        val root = prefix("a", leaf(), true)
        finish(CompactFixture.Dictionary(root, listOf(1), listOf(0 to 0, 0 to 0)))
    })
    reject(CompactFixture().run {
        val end = leaf()
        val root = branches(listOf(CompactFixture.Edge(97, end, true, 1)))
        finish(CompactFixture.Dictionary(root, listOf(1, 2)))
    })
    reject(CompactFixture().run {
        val root = prefix("a", leaf(), true)
        write(root + 1, 3, 1)
        finish(CompactFixture.Dictionary(root, listOf(1)))
    })
    reject(CompactFixture().run {
        val first = prefix("a", leaf(), false)
        val second = prefix("b", first, false)
        write(first + 1, 3, second - first)
        finish(CompactFixture.Dictionary(second, emptyList()))
    })
    reject(CompactFixture().run {
        val end = leaf()
        val root = prefix("a", end, false)
        write(end, 1, 3)
        write(end + 1, 1, 0)
        finish(CompactFixture.Dictionary(root, listOf(1)))
    })
    val archive = ByteArrayOutputStream()
    ZipOutputStream(archive).use {
        it.putNextEntry(ZipEntry("main.cdict")); it.write(single); it.closeEntry()
        it.putNextEntry(ZipEntry("README.txt")); it.write("ignore".toByteArray()); it.closeEntry()
    }
    check(UserWords.parse(archive.toByteArray()) == listOf("hello" to 13))
    val renamedArchive = ByteArrayOutputStream()
    ZipOutputStream(renamedArchive).use {
        it.putNextEntry(ZipEntry("main.dict")); it.write(single); it.closeEntry()
    }
    check(UserWords.parse(renamedArchive.toByteArray()) == listOf("hello" to 13))
    println("Compiled dictionary checks passed (graphs, packed ranks, signed offsets, Unicode, aliases, containers and malformed input)")
}

/** Generate only synthetic fixtures for manual device checks. */
fun main(args: Array<String>) {
    if (args.isEmpty()) return checkCompactDictionaries()
    val folder = File(args.single()).apply { mkdirs() }
    val fixture = CompactFixture().run {
        val words = listOf("aliasfixture", "compactfixture", "тестдума")
        finish(CompactFixture.Dictionary(trie(words), listOf(0, 12, 9), listOf(0 to 1)))
    }
    File(folder, "vkeyboard-check.cdict").writeBytes(fixture)
    File(folder, "vkeyboard-broken.cdict").writeBytes(fixture.copyOf(fixture.size / 2))
    ZipOutputStream(File(folder, "vkeyboard-compact.zip").outputStream()).use {
        it.putNextEntry(ZipEntry("main.cdict")); it.write(fixture); it.closeEntry()
    }
}

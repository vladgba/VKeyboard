package x.vladgba.keyboard.core

import kotlin.random.Random
import kotlin.system.measureNanoTime

private fun distance(a: String, b: String): Int {
    val rows = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) rows[i][0] = i
    for (j in 0..b.length) rows[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        rows[i][j] = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1,
            rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
            rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
        }
    }
    return rows[a.length][b.length]
}

fun main() {
    val random = Random(8723)
    val alphabet = "abcdeéбвг’"
    val words = sortedMapOf<String, UserWords.Word>()
    repeat(1600) {
        val text = buildString { repeat(random.nextInt(2, 12)) { append(alphabet.random(random)) } }
        words[text] = UserWords.Word(text, random.nextInt(1, 200))
    }
    val index = WordIndex().apply { build(words.entries.map { it.key to it.value }) }
    val order = compareByDescending<UserWords.Word> { it.count }.thenBy { it.form }
    fun forms(list: List<UserWords.Word>) = list.map { it.form to it.count }
    fun verify(query: String) {
        for (limit in listOf(0, 1, 3, 8)) {
            val completions = if (query.isEmpty()) emptyList() else words.filterKeys { it.startsWith(query) }.values.sortedWith(order).take(limit)
            check(forms(index.complete(query, limit)) == forms(completions)) { "Completion mismatch for $query" }
            val corrections = if (query.length < 3) emptyList() else words.filterKeys {
                it != query && distance(it, query) == 1
            }.values.sortedWith(order).take(limit)
            check(forms(index.corrections(query, limit)) == forms(corrections)) { "Correction mismatch for $query" }
        }
    }
    verify("")
    verify("zzzz")
    repeat(80) {
        val word = words.keys.elementAt(random.nextInt(words.size))
        verify(word)
        verify(word.take(random.nextInt(1, word.length + 1)))
        verify(word.dropLast(1))
        verify("x" + word.drop(1))
        verify(word.take(1) + "a" + word.drop(1))
        verify(word[1].toString() + word[0] + word.drop(2))
    }
    // Learning, rank changes and deletion must update every ancestor's cached best entry.
    repeat(150) {
        val key = words.keys.elementAt(random.nextInt(words.size))
        words.getValue(key).count += 1000
        index.put(key, words.getValue(key))
    }
    repeat(150) {
        val key = words.keys.elementAt(random.nextInt(words.size))
        index.remove(key)
        words.remove(key)
    }
    words["éabc"] = UserWords.Word("éabc", 8000)
    index.put("éabc", words.getValue("éabc"))
    for (query in listOf("a", "é", "éabc", "éacb", "éab", "éabbc")) verify(query)
    check(index.complete("a", -1).isEmpty())
    check(index.corrections("a".repeat(49), 3).isEmpty())
    println("Word index equivalence checks passed (completion, edits, Unicode, ranking, learning, deletion)")
    benchmark()
}

private fun benchmark() {
    val random = Random(9345)
    val corpus = sortedMapOf<String, UserWords.Word>()
    repeat(200_000) {
        val text = buildString { repeat(random.nextInt(6, 13)) { append(('a'.code + random.nextInt(26)).toChar()) } }
        corpus[text] = UserWords.Word(text, random.nextInt(1, 10_000))
    }
    val index = WordIndex()
    val buildMs = measureNanoTime { index.build(corpus.entries.map { it.key to it.value }) } / 1_000_000.0
    val samples = corpus.keys.filterIndexed { i, _ -> i % 5000 == 0 }
    val prefixes = samples.map { it.take(1) }
    val typos = samples.map { it.dropLast(1) }
    val order = compareByDescending<UserWords.Word> { it.count }.thenBy { it.form }
    fun oldComplete(q: String) = corpus.subMap(q, q + Char.MAX_VALUE).values.sortedWith(order).take(3)
    // Former one-edit scan, avoiding the full DP oracle's allocations for a fair baseline.
    fun oneEdit(a: String, b: String): Boolean {
        if (a.length == b.length) {
            var first = -1
            var diffs = 0
            for (i in a.indices) if (a[i] != b[i]) {
                if (diffs == 0) first = i
                if (++diffs > 2) return false
            }
            return diffs == 1 || (diffs == 2 && first + 1 < a.length &&
                a[first] == b[first + 1] && a[first + 1] == b[first] && a.substring(first + 2) == b.substring(first + 2))
        }
        if (kotlin.math.abs(a.length - b.length) != 1) return false
        val (short, long) = if (a.length < b.length) a to b else b to a
        var i = 0
        while (i < short.length && short[i] == long[i]) i++
        return short.substring(i) == long.substring(i + 1)
    }
    fun oldCorrections(q: String) = corpus.entries.asSequence().filter { oneEdit(it.key, q) }
        .map { it.value }.sortedWith(order).take(3).toList()
    repeat(3) { for (q in typos) index.corrections(q, 3) }
    fun timing(label: String, queries: List<String>, work: (String) -> List<UserWords.Word>) {
        val timings = queries.map { q -> measureNanoTime { work(q) } / 1_000_000.0 }.sorted()
        println("$label: p50=%.3f ms, p95=%.3f ms, max=%.3f ms".format(
            timings[timings.size / 2], timings[(timings.size * .95).toInt()], timings.last()))
    }
    for (q in prefixes) check(index.complete(q, 3).map { it.form } == oldComplete(q).map { it.form })
    for (q in typos) check(index.corrections(q, 3).map { it.form } == oldCorrections(q).map { it.form })
    println("Synthetic corpus: ${corpus.size} words; index build %.1f ms".format(buildMs))
    timing("Former completion", prefixes, ::oldComplete)
    timing("Indexed completion", prefixes) { index.complete(it, 3) }
    timing("Former correction", typos, ::oldCorrections)
    timing("Indexed correction", typos) { index.corrections(it, 3) }
}

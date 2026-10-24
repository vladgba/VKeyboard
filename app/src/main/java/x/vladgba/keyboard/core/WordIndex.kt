package x.vladgba.keyboard.core

import java.util.PriorityQueue

/** Mutable ternary trie. Parallel arrays avoid one map/object per character. */
internal class WordIndex {
    private class Entry(val key: String, val word: UserWords.Word)
    private var chars = CharArray(256)
    private var low = IntArray(256)
    private var equal = IntArray(256)
    private var high = IntArray(256)
    private var values = arrayOfNulls<Entry>(256)
    private var best = arrayOfNulls<Entry>(256)
    private var size = 0
    private var root = 0

    private fun compare(a: Entry, b: Entry): Int {
        val frequency = b.word.count.compareTo(a.word.count)
        return if (frequency != 0) frequency else a.key.compareTo(b.key)
    }

    private fun node(c: Char): Int {
        if (++size == chars.size) {
            val capacity = chars.size * 2
            chars = chars.copyOf(capacity)
            low = low.copyOf(capacity)
            equal = equal.copyOf(capacity)
            high = high.copyOf(capacity)
            values = values.copyOf(capacity)
            best = best.copyOf(capacity)
        }
        chars[size] = c
        return size
    }

    /** Balanced insertion order keeps sorted dictionary files from producing long sibling chains. */
    fun build(sorted: List<Pair<String, UserWords.Word>>) {
        fun addRange(start: Int, end: Int) {
            if (start >= end) return
            val middle = (start + end) ushr 1
            val (key, word) = sorted[middle]
            put(key, word)
            addRange(start, middle)
            addRange(middle + 1, end)
        }
        addRange(0, sorted.size)
    }

    fun put(key: String, word: UserWords.Word) {
        if (key.isEmpty()) return
        if (root == 0) root = node(key[0])
        val path = ArrayList<Int>(key.length + 8)
        var current = root
        var offset = 0
        while (true) {
            path.add(current)
            val c = key[offset]
            when {
                c < chars[current] -> {
                    if (low[current] == 0) {
                        val child = node(c)
                        low[current] = child
                    }
                    current = low[current]
                }
                c > chars[current] -> {
                    if (high[current] == 0) {
                        val child = node(c)
                        high[current] = child
                    }
                    current = high[current]
                }
                offset == key.lastIndex -> {
                    values[current] = Entry(key, word)
                    break
                }
                else -> {
                    offset++
                    if (equal[current] == 0) {
                        val child = node(key[offset])
                        equal[current] = child
                    }
                    current = equal[current]
                }
            }
        }
        for (i in path.lastIndex downTo 0) updateBest(path[i])
    }

    private fun updateBest(n: Int) {
        var candidate = values[n]
        fun include(other: Entry?) {
            if (other != null && (candidate == null || compare(other, candidate!!) < 0)) candidate = other
        }
        include(best[low[n]])
        include(best[equal[n]])
        include(best[high[n]])
        best[n] = candidate
    }

    fun remove(key: String) {
        val path = ArrayList<Int>()
        var n = root
        var i = 0
        while (n != 0 && i < key.length) {
            path.add(n)
            when {
                key[i] < chars[n] -> n = low[n]
                key[i] > chars[n] -> n = high[n]
                i == key.lastIndex -> {
                    values[n] = null
                    break
                }
                else -> { i++; n = equal[n] }
            }
        }
        for (p in path.asReversed()) updateBest(p)
    }

    fun complete(prefix: String, limit: Int): List<UserWords.Word> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        var n = root
        var i = 0
        while (n != 0) {
            when {
                prefix[i] < chars[n] -> n = low[n]
                prefix[i] > chars[n] -> n = high[n]
                i == prefix.lastIndex -> break
                else -> { i++; n = equal[n] }
            }
        }
        if (n == 0) return emptyList()
        // A queue item represents either a subtree or one terminal value.
        data class Candidate(val node: Int, val terminal: Boolean, val rank: Entry)
        val queue = PriorityQueue<Candidate>(11) { a, b -> compare(a.rank, b.rank) }
        fun offer(node: Int, terminal: Boolean = false) {
            val rank = if (terminal) values[node] else best[node]
            if (rank != null) queue.add(Candidate(node, terminal, rank))
        }
        offer(n, true)
        offer(equal[n])
        val found = ArrayList<UserWords.Word>(limit)
        while (queue.isNotEmpty() && found.size < limit) {
            val candidate = queue.remove()
            if (candidate.terminal) found.add(candidate.rank.word)
            else {
                val p = candidate.node
                offer(p, true)
                offer(low[p])
                offer(equal[p])
                offer(high[p])
            }
        }
        return found
    }

    /** One-edit distance with adjacent transpositions, pruning nonmatching prefix branches. */
    fun corrections(typed: String, limit: Int): List<UserWords.Word> {
        if (typed.length < 3 || typed.length > 48 || limit <= 0) return emptyList()
        val found = ArrayList<Entry>(limit)
        val rows = Array(typed.length + 3) { IntArray(typed.length + 1) }
        for (j in 0..typed.length) rows[0][j] = j
        fun visit(n: Int, depth: Int, previous: Char) {
            if (n == 0 || best[n] == null) return
            if (found.size == limit && compare(best[n]!!, found.last()) >= 0) return
            visit(low[n], depth, previous)
            val c = chars[n]
            val row = rows[depth + 1]
            val last = rows[depth]
            row[0] = depth + 1
            var minimum = row[0]
            for (j in 1..typed.length) {
                var distance = minOf(row[j - 1] + 1, last[j] + 1, last[j - 1] + if (c == typed[j - 1]) 0 else 1)
                if (depth > 0 && j > 1 && c == typed[j - 2] && previous == typed[j - 1]) {
                    distance = minOf(distance, rows[depth - 1][j - 2] + 1)
                }
                row[j] = distance
                minimum = minOf(minimum, distance)
            }
            val entry = values[n]
            if (entry != null && row[typed.length] == 1) {
                var at = 0
                while (at < found.size && compare(found[at], entry) <= 0) at++
                if (at < limit) {
                    found.add(at, entry)
                    if (found.size > limit) found.removeAt(limit)
                }
            }
            if (minimum <= 1 && depth < typed.length) visit(equal[n], depth + 1, c)
            visit(high[n], depth, previous)
        }
        visit(root, 0, '\u0000')
        return found.map { it.word }
    }
}

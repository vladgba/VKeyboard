package x.vladgba.keyboard.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Words learned from typing (no built-in word lists), for suggestions and corrections.
 * Kept in `learned.words` as `word<TAB>count` lines, most used first; part of backups.
 *
 * Import reads, detected by content:
 *  - a plain word list: one word per line, optionally followed by a tab/space and a count;
 *  - a Gboard personal dictionary (`# Gboard Dictionary version:1`, `shortcut<TAB>word<TAB>language`),
 *    also inside its exported zip;
 *  - an AOSP-style `.combined` word list (` word=…,f=…` lines).
 * Export writes the plain list or a Gboard zip.
 */
object UserWords {
    class Word(var form: String, var count: Int)

    private const val MAX_LEN = 48
    private const val MAX_COUNT = 1_000_000

    /** Lower-cased word → how it is written and how often. */
    private var words: TreeMap<String, Word>? = null
    private var dirty = false

    private fun mapOf(ctx: Context): TreeMap<String, Word> = words ?: load(ctx).also { words = it }

    /** Forget what's loaded (storage changed: unlock after reboot, restore from backup, import). */
    fun reload() {
        words = null
        dirty = false
    }

    fun size(ctx: Context) = mapOf(ctx).size

    /** All words, most used first. */
    fun all(ctx: Context): List<Word> = mapOf(ctx).values.sortedWith(compareByDescending<Word> { it.count }.thenBy { it.form.lowercase() })

    /** Words starting with [prefix] (any case), most used first. */
    fun complete(ctx: Context, prefix: String, limit: Int): List<Word> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        return mapOf(ctx).subMap(p, true, p + Char.MAX_VALUE, true).values
            .sortedByDescending { it.count }
            .take(limit)
    }

    fun contains(ctx: Context, word: String) = mapOf(ctx).containsKey(word.lowercase())

    /**
     * Learned words one edit away from [typed] (a letter wrong, missing, extra, or two neighbours
     * swapped), most used first: "helo" → hello.
     */
    fun corrections(ctx: Context, typed: String, limit: Int): List<Word> {
        val t = typed.lowercase()
        if (t.length < 3) return emptyList()
        return mapOf(ctx).entries.asSequence()
            .filter { (k, _) -> k != t && kotlin.math.abs(k.length - t.length) <= 1 && oneEditApart(k, t) }
            .map { it.value }
            .sortedByDescending { it.count }
            .take(limit)
            .toList()
    }

    private fun oneEditApart(a: String, b: String): Boolean {
        if (a.length == b.length) {
            var first = -1
            var diffs = 0
            for (i in a.indices) if (a[i] != b[i]) {
                if (diffs == 0) first = i
                diffs++
                if (diffs > 2) return false
            }
            if (diffs <= 1) return true
            // Two neighbouring letters swapped.
            return first + 1 < a.length && a[first] == b[first + 1] && a[first + 1] == b[first] &&
                a.substring(first + 2) == b.substring(first + 2)
        }
        val (s, l) = if (a.length < b.length) a to b else b to a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }

    /** Counts a word the user typed. Casing: a word typed in lower case once stays lower case. */
    fun learn(ctx: Context, typed: String) {
        if (!isLearnable(typed)) return
        val key = typed.lowercase()
        val m = mapOf(ctx)
        val w = m[key]
        if (w == null) m[key] = Word(typed, 1)
        else {
            w.count = (w.count + 1).coerceAtMost(MAX_COUNT)
            if (typed == key) w.form = key
        }
        dirty = true
    }

    fun remove(ctx: Context, word: String) {
        if (mapOf(ctx).remove(word.lowercase()) != null) save(ctx)
    }

    fun clear(ctx: Context) {
        mapOf(ctx).clear()
        save(ctx)
    }

    /** Adds [list] (word to count); existing words keep the higher count. Returns how many were new. */
    fun merge(ctx: Context, list: List<Pair<String, Int>>): Int {
        val m = mapOf(ctx)
        var added = 0
        for ((word, count) in list) {
            val w = word.trim()
            if (!isValid(w)) continue
            val key = w.lowercase()
            val c = count.coerceIn(1, MAX_COUNT)
            val e = m[key]
            if (e == null) {
                m[key] = Word(w, c)
                added++
            } else if (c > e.count) e.count = c
        }
        save(ctx)
        return added
    }

    /** Writes pending learning to the file (called when the keyboard hides). */
    fun flush(ctx: Context) {
        if (dirty) save(ctx)
    }

    /** A typed word worth learning: 2+ letters, no digits, at most [MAX_LEN] characters. */
    fun isLearnable(w: String) =
        w.length in 2..MAX_LEN && w.any { it.isLetter() } && w.none { it.isDigit() || it.isWhitespace() }

    private fun isValid(w: String) = w.length in 1..MAX_LEN && w.none { it.isWhitespace() || it.isISOControl() }

    private fun load(ctx: Context): TreeMap<String, Word> {
        val m = TreeMap<String, Word>()
        for ((w, c) in parsePlain(PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).read())) {
            if (isValid(w)) m[w.lowercase()] = Word(w, c.coerceIn(1, MAX_COUNT))
        }
        return m
    }

    private fun save(ctx: Context) {
        dirty = false
        PFile(ctx, LEARNED_FILENAME, LEARNED_EXT).write(exportPlain(ctx))
    }

    // ======================= formats =======================

    /** `word<TAB>count` lines, most used first. */
    fun exportPlain(ctx: Context): String = buildString {
        for (w in all(ctx)) append(w.form).append('\t').append(w.count).append('\n')
    }

    /** Gboard personal dictionary zip (`dictionary.txt`, no shortcuts, all languages). */
    fun exportGboardZip(ctx: Context, os: OutputStream) {
        ZipOutputStream(os).use { zip ->
            zip.putNextEntry(ZipEntry("dictionary.txt"))
            val text = buildString {
                append(GBOARD_HEADER).append('\n')
                for (w in all(ctx)) append('\t').append(w.form).append('\t').append('\n')
            }
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Words (with counts) from a file in any of the supported formats. */
    fun parse(bytes: ByteArray): List<Pair<String, Int>> {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return parseZip(bytes)
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return emptyList()
        return when {
            head.startsWith(GBOARD_HEADER_PREFIX) -> parseGboard(text)
            head.startsWith("dictionary=") || text.contains("\n word=") || head.startsWith("word=") -> parseCombined(text)
            else -> parsePlain(text)
        }
    }

    private fun parseZip(bytes: ByteArray): List<Pair<String, Int>> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || !e.name.endsWith(".txt")) continue
                return parse(zip.readBytes())
            }
        }
        return emptyList()
    }

    /** One word per line, optionally followed by a tab or space and a count. `#` lines are comments. */
    fun parsePlain(text: String): List<Pair<String, Int>> = text.lineSequence().mapNotNull { line ->
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
        val parts = l.split('\t', ' ').filter { it.isNotEmpty() }
        val count = if (parts.size >= 2) parts.last().toIntOrNull() else null
        val word = if (count != null) parts.dropLast(1).joinToString(" ") else parts[0]
        word to (count ?: 1)
    }.toList()

    /** Gboard: `shortcut<TAB>word<TAB>language` after the header. */
    private fun parseGboard(text: String): List<Pair<String, Int>> = text.lineSequence().mapNotNull { line ->
        if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
        val cols = line.split('\t')
        val word = (if (cols.size >= 2 && cols[1].isNotBlank()) cols[1] else cols[0]).trim()
        if (word.isEmpty()) null else word to 1
    }.toList()

    /** AOSP `.combined`: ` word=hello,f=120,...` (shortcut/bigram lines are skipped). */
    private fun parseCombined(text: String): List<Pair<String, Int>> = text.lineSequence().mapNotNull { line ->
        val l = line.trim()
        if (!l.startsWith("word=")) return@mapNotNull null
        var word: String? = null
        var f = 1
        for (field in l.split(',')) {
            val i = field.indexOf('=')
            if (i < 0) continue
            when (field.substring(0, i)) {
                "word" -> word = field.substring(i + 1)
                "f" -> f = field.substring(i + 1).toIntOrNull() ?: 1
            }
        }
        word?.takeIf { it.isNotEmpty() }?.let { it to f.coerceAtLeast(1) }
    }.toList()

    private const val GBOARD_HEADER = "# Gboard Dictionary version:1"
    private const val GBOARD_HEADER_PREFIX = "# Gboard Dictionary"
}

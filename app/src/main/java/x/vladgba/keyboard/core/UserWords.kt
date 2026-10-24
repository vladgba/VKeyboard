package x.vladgba.keyboard.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.concurrent.Executors
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
 *  - an AOSP-style `.combined` word list (` word=…,f=…` lines), or static v2 binary `.dict`.
 *  - XML word/frequency backups or wordlist XML, also inside a zip archive.
 *  - compiled .cdict v1 files (all dictionaries, canonical words only).
 * Export writes the plain list or a Gboard zip.
 */
object UserWords {
    class Word(var form: String, var count: Int)

    private const val MAX_LEN = 48
    private const val MAX_COUNT = 1_000_000

    /** Lower-cased word → how it is written and how often. */
    private var words: TreeMap<String, Word>? = null
    private var dirty = false
    private var index = WordIndex()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "word-suggestions").apply { isDaemon = true }
    }

    /** Serializes keyboard reads, learning and persistence away from touch/input handling. */
    fun submit(work: () -> Unit) = worker.submit(work)

    private fun mapOf(ctx: Context): TreeMap<String, Word> = words ?: load(ctx).also { loaded ->
        index = WordIndex().apply { build(loaded.entries.map { it.key to it.value }) }
        words = loaded
    }

    /** Forget what's loaded (storage changed: unlock after reboot, restore from backup, import). */
    @Synchronized fun reload() {
        words = null
        index = WordIndex()
        dirty = false
    }

    @Synchronized fun size(ctx: Context) = mapOf(ctx).size

    /** All words, most used first. */
    @Synchronized fun all(ctx: Context): List<Word> = mapOf(ctx).values
        .map { Word(it.form, it.count) }.sortedWith(compareByDescending<Word> { it.count }.thenBy { it.form.lowercase() })

    /** Words starting with [prefix] (any case), most used first. */
    @Synchronized fun complete(ctx: Context, prefix: String, limit: Int): List<Word> {
        mapOf(ctx)
        return index.complete(prefix.lowercase(), limit).map { Word(it.form, it.count) }
    }

    @Synchronized fun contains(ctx: Context, word: String) = mapOf(ctx).containsKey(word.lowercase())

    /**
     * Learned words one edit away from [typed] (a letter wrong, missing, extra, or two neighbours
     * swapped), most used first: "helo" → hello.
     */
    @Synchronized fun corrections(ctx: Context, typed: String, limit: Int): List<Word> {
        mapOf(ctx)
        return index.corrections(typed.lowercase(), limit).map { Word(it.form, it.count) }
    }

    class Matches(val words: List<Word>, val known: Boolean)

    @Synchronized fun query(ctx: Context, prefix: String, limit: Int = 3): Matches {
        val known = contains(ctx, prefix)
        val found = complete(ctx, prefix, limit).toMutableList()
        if (!known && found.size < limit) {
            for (word in corrections(ctx, prefix, limit)) {
                if (found.none { it.form.equals(word.form, true) }) found.add(word)
                if (found.size == limit) break
            }
        }
        return Matches(found, known)
    }

    /** Counts a word the user typed. Casing: a word typed in lower case once stays lower case. */
    @Synchronized fun learn(ctx: Context, typed: String) {
        if (!isLearnable(typed)) return
        val key = typed.lowercase()
        val m = mapOf(ctx)
        val w = m[key]
        if (w == null) m[key] = Word(typed, 1)
        else {
            w.count = (w.count + 1).coerceAtMost(MAX_COUNT)
            if (typed == key) w.form = key
        }
        index.put(key, m.getValue(key))
        dirty = true
    }

    @Synchronized fun remove(ctx: Context, word: String) {
        val key = word.lowercase()
        if (mapOf(ctx).remove(key) != null) {
            index.remove(key)
            save(ctx)
        }
    }

    @Synchronized fun clear(ctx: Context) {
        mapOf(ctx).clear()
        index = WordIndex()
        save(ctx)
    }

    /** Adds [list] (word to count); existing words keep the higher count. Returns how many were new. */
    @Synchronized fun merge(ctx: Context, list: List<Pair<String, Int>>): Int {
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
        index = WordIndex().apply { build(m.entries.map { it.key to it.value }) }
        save(ctx)
        return added
    }

    /** Writes pending learning to the file (called when the keyboard hides). */
    @Synchronized fun flush(ctx: Context) {
        if (dirty) save(ctx)
    }

    /** A typed word worth learning: 2+ letters, no digits, at most [MAX_LEN] characters. */
    fun isLearnable(w: String) =
        w.length >= 2 && isValid(w) && w.any { it.isLetter() } && w.none { it.isDigit() }

    private fun isValid(w: String): Boolean {
        if (w.length !in 1..MAX_LEN) return false
        var i = 0
        while (i < w.length) {
            val char = w[i++]
            if (char.isWhitespace() || char.isISOControl()) return false
            if (Character.isHighSurrogate(char)) {
                if (i >= w.length || !Character.isLowSurrogate(w[i++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }

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
    fun parse(bytes: ByteArray): List<Pair<String, Int>> = parseFile(bytes, allowZip = true)

    const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

    /** Shared bound for document streams and the total expanded archive size. */
    fun readImport(input: InputStream, limit: Int = MAX_IMPORT_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(n <= limit - output.size()) { "Dictionary import exceeds 32 MiB" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun parseFile(bytes: ByteArray, allowZip: Boolean): List<Pair<String, Int>> {
        require(bytes.size <= MAX_IMPORT_BYTES) { "Dictionary import exceeds 32 MiB" }
        if (AospDictionary.matches(bytes)) return AospDictionary.parse(bytes)
        if (CompactDictionary.matches(bytes)) return CompactDictionary.parse(bytes)
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            require(allowZip) { "Nested dictionary archives are not supported" }
            return parseZip(bytes)
        }
        val text = decodeText(bytes)
        if (text.trimStart().startsWith("<")) return XmlWordBackup.parse(text)
            ?: throw IllegalArgumentException("Unsupported dictionary XML format")
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return emptyList()
        return when {
            head.startsWith(GBOARD_HEADER_PREFIX) -> parseGboard(text)
            head.startsWith("dictionary=") || text.contains("\n word=") || head.startsWith("word=") -> parseCombined(text)
            else -> parsePlain(text)
        }
    }

    private fun parseZip(bytes: ByteArray): List<Pair<String, Int>> {
        val found = ArrayList<Pair<String, Int>>()
        var fallback: ByteArray? = null
        var fallbackCount = 0
        var structured = false
        var expanded = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                require(++entries <= 4096) { "Dictionary archive contains too many entries" }
                if (e.isDirectory) continue
                val data = readImport(zip, MAX_IMPORT_BYTES - expanded)
                expanded += data.size
                val name = e.name.substringAfterLast('/').lowercase()
                val words = when {
                    name.endsWith(".xml") -> XmlWordBackup.parse(decodeText(data))
                    name == "dictionary.txt" || name.endsWith(".combined") ||
                        CompactDictionary.matches(data) ||
                        (name.endsWith(".dict") && AospDictionary.matches(data)) -> parseFile(data, allowZip = false)
                    name.endsWith(".txt") && !name.startsWith("readme") && !name.startsWith("license") -> {
                        fallback = data
                        fallbackCount++
                        null
                    }
                    else -> null
                }
                if (words != null) {
                    structured = true
                    require(words.size <= 500_000 - found.size) { "Dictionary archive contains too many words" }
                    found.addAll(words)
                }
            }
        }
        require(entries > 0 || (bytes.size >= 22 && bytes[2] == 5.toByte() && bytes[3] == 6.toByte())) {
            "Invalid dictionary archive"
        }
        if (structured) return found
        require(fallbackCount <= 1) { "Archive contains multiple unrecognized word lists; import one file at a time" }
        return fallback?.let { parseFile(it, allowZip = false) } ?: emptyList()
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

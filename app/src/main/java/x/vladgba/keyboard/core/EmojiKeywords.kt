package x.vladgba.keyboard.core

import android.content.Context

/**
 * Keywords for emoji suggestions (cat → 🐱), imported by the user; nothing is bundled.
 * Kept in `emoji.keywords` as `keyword<TAB>emoji emoji…` lines; part of backups.
 *
 * Import reads, detected by content:
 *  - Unicode CLDR annotations XML (`<annotation cp="🐱">cat | face | pet</annotation>`);
 *  - CLDR JSON annotations (`"🐱": {"default": ["cat", "face"], "tts": ["cat face"]}`);
 *  - a plain list: `keyword<TAB>emoji` (or `emoji<TAB>keyword | keyword`) per line.
 */
object EmojiKeywords {
    private var map: HashMap<String, MutableList<String>>? = null

    private fun mapOf(ctx: Context) = map ?: load(ctx).also { map = it }

    @Synchronized fun reload() {
        map = null
    }

    @Synchronized fun size(ctx: Context) = mapOf(ctx).size

    /** Emojis for [word] (exact keyword, any case), best first. */
    @Synchronized fun lookup(ctx: Context, word: String): List<String> = mapOf(ctx)[word.lowercase()]?.toList() ?: emptyList()

    @Synchronized fun clear(ctx: Context) {
        mapOf(ctx).clear()
        save(ctx)
    }

    /** Merges keyword → emoji pairs from [bytes]; returns how many pairs were read. */
    @Synchronized fun import(ctx: Context, bytes: ByteArray): Int {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val pairs = when {
            text.contains("<annotation") -> parseXml(text)
            text.trimStart().startsWith("{") -> parseJson(text)
            else -> parsePlain(text)
        }
        val m = mapOf(ctx)
        for ((kw, emoji) in pairs) {
            val k = kw.trim().lowercase()
            if (k.isEmpty() || emoji.isBlank() || k.contains(' ')) continue
            val list = m.getOrPut(k) { mutableListOf() }
            if (emoji !in list) list.add(emoji)
        }
        save(ctx)
        return pairs.size
    }

    private val XML = Regex("<annotation cp=\"([^\"]+)\"( type=\"tts\")?>([^<]*)</annotation>")

    private fun parseXml(text: String): List<Pair<String, String>> = XML.findAll(text).flatMap { m ->
        val emoji = unescape(m.groupValues[1])
        val tts = m.groupValues[2].isNotEmpty()
        val words = if (tts) m.groupValues[3].split(' ') else m.groupValues[3].split('|')
        words.map { it.trim() to emoji }
    }.toList()

    private val JSON = Regex("\"([^\"]+)\"\\s*:\\s*\\{\\s*\"default\"\\s*:\\s*\\[([^\\]]*)\\]")

    private fun parseJson(text: String): List<Pair<String, String>> = JSON.findAll(text).flatMap { m ->
        val emoji = m.groupValues[1]
        Regex("\"([^\"]*)\"").findAll(m.groupValues[2]).map { it.groupValues[1] to emoji }
    }.toList()

    private fun parsePlain(text: String): List<Pair<String, String>> = text.lineSequence().flatMap { line ->
        val cols = line.split('\t')
        if (line.isBlank() || line.startsWith("#") || cols.size < 2) return@flatMap emptySequence()
        val a = cols[0].trim()
        val b = cols[1].trim()
        // Whichever column has letters holds the keywords.
        if (a.any { it.isLetter() }) b.split(' ').filter { it.isNotBlank() }.map { a to it }.asSequence()
        else b.split('|', ' ').filter { it.isNotBlank() }.map { it to a }.asSequence()
    }.toList()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

    private fun load(ctx: Context): HashMap<String, MutableList<String>> {
        val m = HashMap<String, MutableList<String>>()
        for (line in PFile(ctx, EMOJI_KEYWORDS_FILENAME, EMOJI_KEYWORDS_EXT).read().lineSequence()) {
            val cols = line.split('\t')
            if (cols.size < 2 || cols[0].isBlank()) continue
            m[cols[0]] = cols[1].split(' ').filter { it.isNotBlank() }.toMutableList()
        }
        return m
    }

    private fun save(ctx: Context) {
        val text = buildString {
            for ((k, v) in mapOf(ctx).toSortedMap()) append(k).append('\t').append(v.joinToString(" ")).append('\n')
        }
        PFile(ctx, EMOJI_KEYWORDS_FILENAME, EMOJI_KEYWORDS_EXT).write(text)
    }
}

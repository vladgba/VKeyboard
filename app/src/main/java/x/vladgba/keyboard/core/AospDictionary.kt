package x.vladgba.keyboard.core

/** Reads user-selected static v2 dictionaries. No word lists are included with the app. */
object AospDictionary {
    private val magic = byteArrayOf(0x9b.toByte(), 0xc1.toByte(), 0x3a, 0xfe.toByte())

    fun matches(bytes: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    fun parse(bytes: ByteArray): List<Pair<String, Int>> = Reader(bytes).read()

    // Independent reader for the published AOSP static binary format:
    // https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/db4f373/java/src/com/android/inputmethod/latin/makedict/FormatSpec.java
    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        private var bodyStart = 12
        private val result = ArrayList<Pair<String, Int>>()
        private val visited = HashSet<Int>()

        fun read(): List<Pair<String, Int>> {
            require(matches(bytes)) { "Not an AOSP binary dictionary" }
            position = 4
            val version = uint(2)
            require(version == 2) { "Unsupported AOSP dictionary version $version; use a .combined or text export" }
            val options = uint(2)
            require(options and 0x12 == 0) { "Dynamic or timestamped AOSP dictionaries are not supported" }
            val headerSize = uint(4)
            require(headerSize in 12 until bytes.size) { "Invalid AOSP dictionary header" }
            bodyStart = headerSize
            readArray(headerSize, "")
            return result
        }

        private fun readArray(address: Int, prefix: String) {
            require(address in bodyStart until bytes.size && visited.add(address)) { "Invalid AOSP dictionary address" }
            // A nonempty segment is required at every level, bounding recursion by word length.
            require(prefix.length < 48) { "AOSP dictionary word is too long" }
            position = address
            val first = uint(1)
            val count = if (first and 0x80 == 0) first else ((first and 0x7f) shl 8) or uint(1)
            repeat(count) {
                val flags = uint(1)
                val word = buildString {
                    append(prefix)
                    if (flags and 0x20 != 0) {
                        val start = length
                        while (true) {
                            val cp = codePoint()
                            if (cp == -1) break
                            appendCodePoint(cp)
                            require(length <= 48) { "AOSP dictionary word is too long" }
                        }
                        require(length > start) { "Empty AOSP dictionary segment" }
                    } else {
                        val cp = codePoint()
                        require(cp != -1) { "Empty AOSP dictionary character" }
                        appendCodePoint(cp)
                    }
                }
                require(word.length <= 48) { "AOSP dictionary word is too long" }
                val terminal = flags and 0x10 != 0
                val frequency = if (terminal) uint(1) else 0
                val addressBytes = flags ushr 6
                val childField = position
                val child = if (addressBytes == 0) -1 else childField + uint(addressBytes)
                if (addressBytes != 0) require(child > childField) { "Invalid AOSP dictionary child offset" }

                // Skip metadata: only unigram words/frequencies belong in learned.words.
                if (flags and 0x08 != 0) {
                    val start = position
                    val size = uint(2)
                    require(size >= 2 && size <= bytes.size - start) { "Invalid AOSP dictionary shortcut list" }
                    position = start + size
                }
                if (flags and 0x04 != 0) {
                    do {
                        val attr = uint(1)
                        val size = (attr ushr 4) and 3
                        require(size != 0) { "Invalid AOSP dictionary bigram" }
                        uint(size)
                    } while (attr and 0x80 != 0)
                }
                if (terminal && flags and 0x03 == 0) {
                    result.add(word to frequency.coerceAtLeast(1))
                    require(result.size <= 500_000) { "AOSP dictionary contains too many words" }
                }
                val next = position
                if (child != -1) readArray(child, word)
                position = next
            }
        }

        /** Latin-1 bytes or three-byte Unicode code points; 0x1f ends a segment. */
        private fun codePoint(): Int {
            val first = uint(1)
            if (first == 0x1f) return -1
            val cp = if (first >= 0x20) first else (first shl 16) or uint(2)
            require(Character.isValidCodePoint(cp) && cp !in 0xd800..0xdfff && !Character.isISOControl(cp)) {
                "Invalid AOSP dictionary character"
            }
            return cp
        }

        private fun uint(size: Int): Int {
            require(size <= bytes.size - position) { "Truncated AOSP dictionary" }
            var value = 0L
            repeat(size) { value = (value shl 8) or (bytes[position++].toLong() and 0xff) }
            require(value <= Int.MAX_VALUE) { "Invalid AOSP dictionary number" }
            return value.toInt()
        }
    }
}

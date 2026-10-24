package x.vladgba.keyboard.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Independent reader of the published .cdict v1 interchange format. */
internal object CompactDictionary {
    fun matches(data: ByteArray): Boolean {
        if (data.size < 4 || data[0] != 'D'.code.toByte() || data[1] != 'i'.code.toByte() || data[2] != 'c'.code.toByte()) return false
        val version = data[3].toInt() and 255
        // Avoid mistaking plain words such as Dictionary, Dicção or Dic<TAB>count for a binary header.
        return version in 0..31 && !version.toChar().isWhitespace()
    }

    fun parse(data: ByteArray): List<Pair<String, Int>> = Reader(data).read()

    private class Descriptor(val name: Int, val root: Int, val frequencies: Int,
        val aliasFormat: Int, val aliasCount: Int, val aliasKeys: Int, val aliasValues: Int)

    private class Reader(private val data: ByteArray) {
        private val result = ArrayList<Pair<String, Int>>()
        private val path = ByteArray(192)
        private val active = HashSet<Int>()
        private val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        private var bodyStart = 0
        private var steps = 0
        private var terminals = 0
        private var ordinal = 0
        private var frequencyStart = 0
        private var frequencyEnd = 0
        private var aliases: Set<Int> = emptySet()

        fun read(): List<Pair<String, Int>> {
            require(matches(data)) { "Not a compiled dictionary" }
            val version = unsigned(3, 1)
            require(version == 1) { "Unsupported .cdict version $version; use a text export" }
            val count = unsigned(4, 1)
            bodyStart = 5 + count * 24
            bounds(0, bodyStart)
            val dictionaries = (0 until count).map { i ->
                val start = 5 + i * 24
                Descriptor(unsigned(start, 4), unsigned(start + 4, 4), unsigned(start + 8, 4),
                    unsigned(start + 12, 1), unsigned(start + 13, 3),
                    unsigned(start + 16, 4), unsigned(start + 20, 4))
            }
            val regions = dictionaries.flatMap {
                listOf(it.name, it.root and -2, it.frequencies, it.aliasKeys, it.aliasValues)
            }.filter { it >= bodyStart }.sorted()
            for (dictionary in dictionaries) {
                validAddress(dictionary.name)
                var nameEnd = dictionary.name
                while (nameEnd < data.size && nameEnd - dictionary.name < 4096 && data[nameEnd] != 0.toByte()) nameEnd++
                require(nameEnd < data.size && data[nameEnd] == 0.toByte()) { "Invalid compiled dictionary name" }
                decode(data, dictionary.name, nameEnd - dictionary.name)
                validAddress(dictionary.frequencies)
                frequencyStart = dictionary.frequencies
                frequencyEnd = regions.firstOrNull { it > frequencyStart } ?: data.size
                val aliasMap = readAliases(dictionary)
                aliases = aliasMap.keys
                ordinal = 0
                follow(0, dictionary.root, 0, 0)
                require(aliasMap.all { (key, target) -> key < ordinal && target < ordinal }) {
                    "Invalid compiled dictionary alias index"
                }
            }
            return result
        }

        private fun readAliases(dictionary: Descriptor): Map<Int, Int> {
            require(dictionary.aliasCount <= 500_000) { "Too many compiled dictionary aliases" }
            if (dictionary.aliasCount == 0) return emptyMap()
            val keyFormat = dictionary.aliasFormat and 7
            val valueFormat = (dictionary.aliasFormat ushr 3) and 7
            validAddress(dictionary.aliasKeys)
            validAddress(dictionary.aliasValues)
            bounds(dictionary.aliasKeys, arraySize(keyFormat, dictionary.aliasCount))
            bounds(dictionary.aliasValues, arraySize(valueFormat, dictionary.aliasCount))
            val found = HashMap<Int, Int>()
            repeat(dictionary.aliasCount) { i ->
                val key = element(dictionary.aliasKeys, keyFormat, i)
                val value = element(dictionary.aliasValues, valueFormat, i)
                require(found.put(key, value) == null) { "Duplicate compiled dictionary alias index" }
            }
            return found
        }

        /** A final transition owns a word index; its children start at the next index. */
        private fun follow(parent: Int, pointer: Int, index: Int, length: Int) {
            val before = ordinal
            require(++steps <= 50_000_000) { "Compiled dictionary graph is too large" }
            require(index in 0..500_000) { "Invalid compiled dictionary word index" }
            val final = pointer and 1 != 0
            if (final) {
                require(index == ordinal++ && ++terminals <= 500_000) { "Invalid compiled dictionary word numbering" }
                val at = frequencyStart + index / 2
                require(at < frequencyEnd) { "Truncated compiled dictionary frequencies" }
                val frequency = (unsigned(at, 1) ushr ((index and 1) * 4)) and 15
                val word = decode(path, 0, length)
                if (index !in aliases && word.length in 1..48 && word.none { it.isWhitespace() || it.isISOControl() }) {
                    // The file contains quantized ranks 0..15, not recoverable typing counts.
                    result.add(word to frequency + 1)
                }
            }
            val offset = pointer and -2
            require(offset != 0) { "Invalid compiled dictionary transition" }
            val address = parent + offset
            validAddress(address)
            require(active.add(address)) { "Cycle in compiled dictionary graph" }
            val header = unsigned(address, 1)
            val nextIndex = index + if (final) 1 else 0
            if (header and 1 != 0) {
                val size = header ushr 1
                require(size > 0 && size <= path.size - length) { "Compiled dictionary word is too long" }
                bounds(address, 4 + size)
                data.copyInto(path, length, address + 4, address + 4 + size)
                follow(address, signed(address + 1, 3), nextIndex, length + size)
            } else {
                require(length < path.size) { "Compiled dictionary word is too long" }
                val count = unsigned(address + 1, 1)
                val pointerFormat = (header ushr 1) and 7
                val numberFormat = (header ushr 4) and 7
                val pointers = address + 2 + count
                val numbers = pointers + arraySize(pointerFormat, count)
                bounds(address, 2 + count + arraySize(pointerFormat, count) + arraySize(numberFormat, count))
                var previous = -1
                // Labels are a complete binary search tree in heap order; visit them in byte order.
                fun branch(i: Int) {
                    if (i >= count) return
                    branch(i * 2 + 1)
                    val label = unsigned(address + 2 + i, 1)
                    require(label > previous) { "Invalid compiled dictionary branch labels" }
                    previous = label
                    path[length] = label.toByte()
                    follow(address, element(pointers, pointerFormat, i, signed = true),
                        nextIndex + element(numbers, numberFormat, i), length + 1)
                    branch(i * 2 + 2)
                }
                branch(0)
            }
            active.remove(address)
            require(ordinal > before || length == 0) { "Dead branch in compiled dictionary graph" }
        }

        private fun arraySize(format: Int, count: Int): Int {
            require(format == 1 || format == 2 || format == 4 || format == 6) { "Invalid compiled dictionary integer format" }
            return (format * count + 1) / 2
        }

        private fun element(start: Int, format: Int, index: Int, signed: Boolean = false): Int {
            val width = format * 4
            val value = if (format == 1) (unsigned(start + index / 2, 1) ushr ((index and 1) * 4)) and 15
                else unsigned(start + index * (format / 2), format / 2)
            return if (signed && value and (1 shl (width - 1)) != 0) value - (1 shl width) else value
        }

        private fun signed(start: Int, size: Int): Int {
            val value = unsigned(start, size)
            return if (value and (1 shl (size * 8 - 1)) != 0) value - (1 shl (size * 8)) else value
        }

        private fun unsigned(start: Int, size: Int): Int {
            bounds(start, size)
            var value = 0L
            repeat(size) { i -> value = (value shl 8) or (data[start + i].toLong() and 255) }
            require(value <= Int.MAX_VALUE) { "Invalid compiled dictionary offset" }
            return value.toInt()
        }

        private fun validAddress(address: Int) {
            require(address in bodyStart until data.size) { "Invalid compiled dictionary address" }
        }

        private fun bounds(start: Int, size: Int) {
            require(start >= 0 && size >= 0 && start <= data.size - size) { "Truncated compiled dictionary" }
        }

        private fun decode(bytes: ByteArray, start: Int, size: Int): String = try {
            utf8.decode(ByteBuffer.wrap(bytes, start, size)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 in compiled dictionary", e)
        }
    }
}

package x.vladgba.keyboard.core

/** Run with :app:verifyDictionaryImport; no JUnit or downloaded word lists required. */
fun main() {
    fun dict(vararg body: Int, version: Int = 2, options: Int = 0): ByteArray =
        (listOf(0x9b, 0xc1, 0x3a, 0xfe, 0, version, 0, options, 0, 0, 0, 12) + body.toList())
            .map { it.toByte() }.toByteArray()
    fun expect(bytes: ByteArray, vararg words: Pair<String, Int>) {
        check(AospDictionary.matches(bytes))
        check(AospDictionary.parse(bytes) == words.toList()) { "Unexpected words: ${AospDictionary.parse(bytes)}" }
    }
    fun reject(bytes: ByteArray) {
        check(runCatching { AospDictionary.parse(bytes) }.exceptionOrNull() is IllegalArgumentException) {
            "Malformed/unsupported dictionary was accepted"
        }
    }

    expect(dict(0))
    expect(dict(1, 0x30, 104, 101, 108, 108, 111, 0x1f, 120), "hello" to 120)
    // A terminal prefix with two children; child address relative to its field.
    expect(dict(1, 0x50, 97, 10, 1, 2, 0x10, 110, 20, 0x10, 116, 30),
        "a" to 10, "an" to 20, "at" to 30)
    // Siblings must resume after recursively walking a child.
    expect(dict(2, 0x40, 97, 4, 0x10, 98, 30, 1, 0x10, 110, 20), "an" to 20, "b" to 30)
    // Two/three-byte offsets and two-byte node counts.
    expect(dict(1, 0x80, 97, 0, 2, 1, 0x10, 98, 2), "ab" to 2)
    expect(dict(1, 0xc0, 97, 0, 0, 3, 1, 0x10, 98, 2), "ab" to 2)
    expect(dict(0x80, 1, 0x10, 97, 0), "a" to 1)
    // Latin-1, Cyrillic and a supplementary Unicode code point.
    expect(dict(3, 0x10, 0xe9, 20, 0x10, 0, 4, 0x31, 30, 0x10, 1, 0xf6, 0, 40),
        "é" to 20, "б" to 30, "😀" to 40)
    expect(dict(3, 0x11, 97, 10, 0x12, 98, 20, 0x10, 99, 30), "c" to 30)
    // Shortcut byte length includes its own two-byte size field; bigrams have variable offsets.
    expect(dict(2, 0x1c, 97, 10, 0, 5, 1, 98, 0x1f, 0x90, 1, 0x20, 0, 2,
        0x10, 99, 30), "a" to 10, "c" to 30)
    reject(dict(0, version = 3))
    reject(dict(0, version = 4))
    reject(dict(0, options = 2))
    reject(dict(0, options = 16))
    reject(dict(1, 0x10, 97))
    reject(dict(1, 0x30, 97))
    reject(dict(1, 0x30, 0x1f, 10))
    reject(dict(1, 0x10, 0, 0xd8, 0, 10))
    reject(dict(1, 0x10, 0x11, 0, 0, 10))
    reject(dict(1, 0x40, 97, 0))
    reject(dict(1, 0x40, 97, 255))
    reject(dict(1, 0x18, 97, 10, 0, 1))
    reject(dict(1, 0x14, 97, 10, 0))
    reject(dict(0).also { it[11] = 127 })
    val example = dict(1, 0x30, 104, 101, 108, 108, 111, 0x1f, 120)
    check(UserWords.parse(example) == listOf("hello" to 120))
    check(UserWords.parse("hello\t120\nworld\t20".toByteArray()) == listOf("hello" to 120, "world" to 20))
    check(UserWords.parse("dictionary=test\n word=hello,f=120\n".toByteArray()) == listOf("hello" to 120))
    check(UserWords.parse("# Gboard Dictionary version:1\n\thello\ten\n".toByteArray()) == listOf("hello" to 1))
    for (n in 0 until example.size) reject(example.copyOf(n))
    check(!AospDictionary.matches("hello\t120".toByteArray()))
    check(!AospDictionary.matches(byteArrayOf(0x50, 0x4b)))
    println("AOSP dictionary checks passed (valid tries, Unicode, metadata, malformed files and all truncations)")
}

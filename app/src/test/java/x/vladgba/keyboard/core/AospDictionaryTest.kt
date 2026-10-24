package x.vladgba.keyboard.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    checkXmlBackups()
    checkCompactDictionaries()
    println("AOSP dictionary checks passed (valid tries, Unicode, metadata, malformed files and all truncations)")
}

private fun checkXmlBackups() {
    fun xmlWords(text: String) = UserWords.parse(text.toByteArray())
    fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    fun reject(bytes: ByteArray) {
        check(runCatching { UserWords.parse(bytes) }.isFailure) { "Invalid backup accepted" }
    }
    val backup = """
        <?xml version="1.0" encoding="UTF-8"?>
        <BackupPrefs version="1">
          <pref><value locale="en"/>
            <pref><value word="hello"/><value freq="120"/></pref>
            <pref><value freq="20"/><value word="l&apos;été"/></pref>
          </pref>
          <pref><value locale="bg"/>
            <pref><value word="тест"/><value freq="90"/></pref>
          </pref>
          <pref><value unrelated="untouched"/></pref>
        </BackupPrefs>
    """.trimIndent()
    val expected = listOf("hello" to 120, "l'été" to 20, "тест" to 90)
    check(xmlWords(backup) == expected)
    check(xmlWords("\uFEFF$backup") == expected)
    check(UserWords.parse(backup.toByteArray(Charsets.UTF_16)) == expected)
    check(UserWords.parse(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + backup.toByteArray(Charsets.UTF_16LE)) == expected)
    check(xmlWords("<BackupPrefs version='1'/>").isEmpty())
    check(xmlWords("<wordlist><w f='255'>hello</w><w>café</w><w f='0'>zero</w></wordlist>") ==
        listOf("hello" to 255, "café" to 1, "zero" to 1))
    check(xmlWords("<wordlist><w f='2000000'>high</w><w f='-5'>low</w></wordlist>") ==
        listOf("high" to 1_000_000, "low" to 1))
    check(UserWords.parse(archive("readme.txt" to "not a word list".toByteArray(),
        "backups/UserWords.xml" to backup.toByteArray(),
        "settings.xml" to "<map><value ignored='true'/></map>".toByteArray())) == expected)
    check(UserWords.parse(archive("en.xml" to "<wordlist><w f='5'>alpha</w></wordlist>".toByteArray(),
        "bg.xml" to "<wordlist><w f='6'>бета</w></wordlist>".toByteArray())) == listOf("alpha" to 5, "бета" to 6))
    check(UserWords.parse(archive("dictionary.txt" to "# Gboard Dictionary version:1\n\thello\ten\n".toByteArray())) == listOf("hello" to 1))
    check(UserWords.parse(archive("words.txt" to "hello\t50\nworld\t10".toByteArray())) == listOf("hello" to 50, "world" to 10))
    check(UserWords.parse(archive()).isEmpty())
    reject("<settings><word value='noise'/></settings>".toByteArray())
    reject(backup.replace("freq=\"120\"", "freq=\"invalid\"").toByteArray())
    reject(backup.replace("word=\"hello\"", "word=\"two words\"").toByteArray())
    reject(backup.replace("<value freq=\"120\"/>", "<value freq=\"120\"/><value freq=\"121\"/>").toByteArray())
    reject("<wordlist><w f='1'><nested/></w></wordlist>".toByteArray())
    reject("<!DOCTYPE wordlist [<!ENTITY x SYSTEM 'file:///not-read'>]><wordlist><w>&x;</w></wordlist>".toByteArray())
    reject("<wordlist><w>&unknown;</w></wordlist>".toByteArray())
    reject(("<pref>".repeat(65) + "</pref>".repeat(65)).toByteArray())
    for (n in 1 until backup.length) reject(backup.take(n).toByteArray())
    reject(archive("dictionary.txt" to archive("words.txt" to "hello".toByteArray())))
    reject(archive("a.txt" to "hello".toByteArray(), "b.txt" to "world".toByteArray()))
    reject(byteArrayOf(0x50, 0x4b))
    check(UserWords.readImport(ByteArrayInputStream(byteArrayOf(1, 2)), 2).contentEquals(byteArrayOf(1, 2)))
    check(runCatching { UserWords.readImport(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2) }.isFailure)
    // Highly compressed input must respect the expanded-size limit too.
    reject(archive("unused.bin" to ByteArray(UserWords.MAX_IMPORT_BYTES + 1)))
    println("XML/ZIP backup checks passed (locales, frequencies, encodings, malformed input and size limits)")
}

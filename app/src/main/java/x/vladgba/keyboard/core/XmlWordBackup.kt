package x.vladgba.keyboard.core

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Interchange XML only: preference records containing word/freq values, or a wordlist of w tags. */
internal object XmlWordBackup {
    /** Null means well-formed XML which does not describe a supported dictionary. */
    fun parse(text: String): List<Pair<String, Int>>? {
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "Dictionary XML must not contain a document type or entity declarations"
        }
        val reader = BackupReader()
        try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
                .parse(InputSource(StringReader(text)), reader)
        } catch (e: org.xml.sax.SAXException) {
            throw IllegalArgumentException("Invalid dictionary XML", e)
        }
        return if (reader.recognized) reader.words else null
    }

    private class Record(val depth: Int, var word: String? = null, var frequency: String? = null)

    private class BackupReader : DefaultHandler() {
        val words = ArrayList<Pair<String, Int>>()
        var recognized = false
        private var depth = 0
        private val records = ArrayList<Record>()
        private var wordlist = false
        private var wordText: StringBuilder? = null
        private var wordFrequency: String? = null

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw IllegalArgumentException("External entities are not supported in dictionary XML")

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            require(++depth <= 64) { "Dictionary XML is nested too deeply" }
            val tag = localName.ifEmpty { qName }
            if (depth == 1 && tag == "wordlist") {
                wordlist = true
                recognized = true
            }
            if (depth == 1 && tag.endsWith("Prefs") && attributes.getValue("version")?.toIntOrNull() != null) {
                recognized = true
            }
            require(wordText == null) { "Nested elements inside a dictionary word" }
            if (wordlist) {
                require(depth == 1 || (depth == 2 && tag == "w")) { "Unsupported wordlist XML element" }
                if (depth == 2) {
                    wordText = StringBuilder()
                    wordFrequency = attributes.getValue("f") ?: "1"
                }
            } else if (tag == "pref") {
                records.add(Record(depth))
            } else if (tag == "value" && records.lastOrNull()?.depth == depth - 1) {
                val record = records.last()
                attributes.getValue("word")?.let {
                    require(record.word == null) { "Duplicate dictionary word value" }
                    record.word = it
                }
                attributes.getValue("freq")?.let {
                    require(record.frequency == null) { "Duplicate dictionary frequency value" }
                    record.frequency = it
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            wordText?.let {
                require(it.length + length <= 256) { "Dictionary XML word is too long" }
                it.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val tag = localName.ifEmpty { qName }
            if (wordlist && depth == 2 && tag == "w") {
                addWord(wordText.toString(), wordFrequency!!)
                wordText = null
            } else if (tag == "pref" && records.lastOrNull()?.depth == depth) {
                val record = records.removeAt(records.lastIndex)
                if (record.word != null && record.frequency != null) {
                    recognized = true
                    addWord(record.word!!, record.frequency!!)
                }
            }
            depth--
        }

        override fun error(e: SAXParseException): Unit = throw e
        override fun fatalError(e: SAXParseException): Unit = throw e

        private fun addWord(rawWord: String, rawFrequency: String) {
            val frequency = rawFrequency.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid dictionary XML frequency")
            val word = rawWord.trim()
            if (word.isEmpty()) return
            require(word.length <= 48 && word.none { it.isWhitespace() || it.isISOControl() }) {
                "Invalid dictionary XML word"
            }
            require(words.size < 500_000) { "Dictionary XML contains too many words" }
            words.add(word to frequency.coerceIn(1, 1_000_000))
        }
    }
}

package com.exciteddev.flironepro.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two language files (English default, Ukrainian) must stay in sync. */
class ResourcesTest {

    private fun load(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("missing ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent
        }
        return out
    }

    private val en get() = load("src/main/res/values/strings.xml")
    private val uk get() = load("src/main/res/values-uk/strings.xml")

    private val placeholder = Regex("%\\d\\$[sdf]|%%")
    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun bothLanguagesHaveTheSameKeys() {
        assertEquals(en.keys, uk.keys)
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        val e = en
        val u = uk
        for ((key, value) in e) {
            val a = placeholder.findAll(value).map { it.value }.sorted().toList()
            val b = placeholder.findAll(u.getValue(key)).map { it.value }.sorted().toList()
            assertEquals("placeholders differ for $key", a, b)
        }
    }

    @Test
    fun noStringIsEmpty() {
        for ((k, v) in en) assertTrue("empty en $k", v.isNotBlank())
        for ((k, v) in uk) assertTrue("empty uk $k", v.isNotBlank())
    }

    @Test
    fun englishFileHasNoCyrillicExceptLanguageName() {
        for ((k, v) in en) {
            if (k == "lang_uk") continue
            assertTrue("Cyrillic text in the English file: $k", !cyrillic.containsMatchIn(v))
        }
    }

    @Test
    fun ukrainianFileIsActuallyTranslated() {
        val e = en
        val u = uk
        // technical keys can stay the same; most user-facing strings must differ
        val same = e.keys.filter { e.getValue(it) == u.getValue(it) }
        assertTrue("too many untranslated strings: $same", same.size <= 12)
    }

    @Test
    fun frameRateIsWrittenAsFramesPerSecondInUkrainian() {
        assertTrue(uk.getValue("fps_fmt").contains("кадрів/с"))
        assertTrue(en.getValue("fps_fmt").contains("fps"))
    }
}

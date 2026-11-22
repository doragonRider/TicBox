package dev.ticbox.companion.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Every language has the same keys and the same format arguments, so no text is assembled. */
class StringResourcesTest {
    private val res = File("src/main/res")
    private val languages = listOf("values", "values-zh", "values-b+zh+Hant")

    private fun load(folder: String): Map<String, List<String>> {
        val document =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(File(res, "$folder/strings.xml"))
        val entries = mutableMapOf<String, List<String>>()
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val element = strings.item(index) as Element
            entries[element.getAttribute("name")] = listOf(element.textContent)
        }
        val plurals = document.getElementsByTagName("plurals")
        for (index in 0 until plurals.length) {
            val element = plurals.item(index) as Element
            val items = element.getElementsByTagName("item")
            entries["plurals/" + element.getAttribute("name")] =
                (0 until items.length).map { items.item(it).textContent }
        }
        return entries
    }

    private fun arguments(text: String) =
        Regex("%(\\d)\\$[sd]").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun allLanguagesDefineTheSameKeysAndArguments() {
        val base = load("values")
        assertTrue(base.isNotEmpty())
        languages.drop(1).forEach { folder ->
            val translated = load(folder)
            assertEquals(base.keys, translated.keys, "Keys differ in $folder")
            base.forEach { (key, texts) ->
                val expected = arguments(texts.last())
                translated.getValue(key).forEach { text ->
                    assertEquals(expected, arguments(text), "Arguments differ for $key in $folder")
                }
            }
        }
    }

    @Test
    fun noTextIsAFragmentWithEdgeSpacesOrLineBreaks() {
        languages.forEach { folder ->
            load(folder).forEach { (key, texts) ->
                texts.forEach { text ->
                    assertEquals(
                        text.trim(),
                        text,
                        "$key in $folder has leading or trailing whitespace",
                    )
                    assertTrue(
                        "\\n" !in text && "\n" !in text,
                        "$key in $folder contains a line break",
                    )
                }
            }
        }
    }
}

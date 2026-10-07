package kapoue.hestia.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Garde-fou de la règle « toute chaîne dans TOUS les fichiers de langue » (CLAUDE.md) : chaque
 * chaîne traduisible et chaque <plurals> de values/ (anglais, défaut) doit exister dans chaque
 * locale, avec exactement les mêmes espaces de format. Test JVM pur : lit les XML du dépôt.
 */
class StringResourcesConsistencyTest {

    /** Locale → quantités de pluriel obligatoires (règles CLDR de la langue). */
    private val LOCALES = mapOf(
        "values-fr" to setOf("one", "many", "other"),
        "values-ru" to setOf("one", "few", "many", "other"),
    )
    private val DEFAULT_QUANTITIES = setOf("one", "other")

    private class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(dir: String): Resources {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("Fichier introuvable : ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val e = stringNodes.item(i) as Element
            if (e.getAttribute("translatable") == "false") continue
            strings[e.getAttribute("name")] = e.textContent
        }
        val plurals = mutableMapOf<String, Map<String, String>>()
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            val e = pluralNodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length)
                .map { items.item(it) as Element }
                .associate { it.getAttribute("quantity") to it.textContent }
        }
        return Resources(strings, plurals)
    }

    private val placeholder = Regex("""%(\d+\$)?\d*(\.\d+)?[sdfx]""")

    /** Espaces de format d'une chaîne, `%%` (pourcent littéral) exclu. */
    private fun placeholders(s: String): List<String> =
        placeholder.findAll(s.replace("%%", "")).map { it.value }.sorted().toList()

    private val default by lazy { load("values") }

    @Test
    fun `chaque locale contient exactement les chaines traduisibles du defaut`() {
        for (dir in LOCALES.keys) {
            val locale = load(dir)
            assertEquals("$dir : chaînes manquantes", emptySet<String>(), default.strings.keys - locale.strings.keys)
            assertEquals("$dir : chaînes en trop (absentes de values/)", emptySet<String>(), locale.strings.keys - default.strings.keys)
            assertEquals("$dir : plurals manquants", emptySet<String>(), default.plurals.keys - locale.plurals.keys)
            assertEquals("$dir : plurals en trop", emptySet<String>(), locale.plurals.keys - default.plurals.keys)
        }
    }

    @Test
    fun `memes espaces de format que le defaut`() {
        for (dir in LOCALES.keys) {
            val locale = load(dir)
            for ((key, text) in locale.strings) {
                val expected = default.strings[key] ?: continue
                assertEquals("$dir/$key", placeholders(expected), placeholders(text))
            }
            for ((key, items) in locale.plurals) {
                val expected = default.plurals[key]?.get("other") ?: continue
                for ((quantity, text) in items) {
                    assertEquals("$dir/$key[$quantity]", placeholders(expected), placeholders(text))
                }
            }
        }
    }

    @Test
    fun `quantites de pluriel requises par chaque langue`() {
        for ((key, items) in default.plurals) {
            assertTrue("values/$key : il manque ${DEFAULT_QUANTITIES - items.keys}", items.keys.containsAll(DEFAULT_QUANTITIES))
        }
        for ((dir, required) in LOCALES) {
            for ((key, items) in load(dir).plurals) {
                assertTrue("$dir/$key : il manque ${required - items.keys}", items.keys.containsAll(required))
            }
        }
    }

    @Test
    fun `les dossiers values-xx existants sont exactement ceux verifies`() {
        val existing = File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            .orEmpty()
            .filter { File(it, "strings.xml").exists() }
            .map { it.name }
            .toSet()
        assertEquals(
            "Locale ajoutée ou retirée sans mise à jour de LOCALES : les contrôles de cohérence l'ignoreraient",
            existing,
            LOCALES.keys,
        )
    }

    @Test
    fun `aucune chaine traduisible vide`() {
        for (dir in listOf("values") + LOCALES.keys) {
            val res = load(dir)
            val empty = res.strings.filterValues { it.isBlank() }.keys +
                res.plurals.flatMap { (k, items) -> items.filterValues { it.isBlank() }.keys.map { "$k[$it]" } }
            assertEquals("$dir : chaînes vides", emptySet<String>(), empty.toSet())
        }
    }

    @Test
    fun `les nombres de canaux sont des plurals`() {
        for (key in listOf("settings_group_channels", "delete_group_message")) {
            assertTrue("values/$key doit être un <plurals>", key in default.plurals)
            assertTrue("values/$key ne doit plus être un <string>", key !in default.strings)
        }
    }
}

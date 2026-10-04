package kapoue.hestia.core.locale

import kapoue.hestia.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleMatchingTest {

    @Test
    fun `anglais force garde la region de la liste systeme`() {
        assertEquals("en-GB", preferredTagFor(AppLanguage.EN, listOf("en-GB", "fr-FR")))
    }

    @Test
    fun `anglais force sans anglais dans le systeme donne l'etiquette nue`() {
        assertEquals("en", preferredTagFor(AppLanguage.EN, listOf("fr-FR")))
    }

    @Test
    fun `francais force prend la premiere correspondance`() {
        assertEquals("fr-CA", preferredTagFor(AppLanguage.FR, listOf("de-DE", "fr-CA", "fr-FR")))
    }

    @Test
    fun `liste systeme vide donne l'etiquette nue`() {
        assertEquals("ru", preferredTagFor(AppLanguage.RU, emptyList()))
    }

    @Test
    fun `systeme n'a pas d'etiquette`() {
        assertNull(preferredTagFor(AppLanguage.SYSTEM, listOf("en-GB")))
    }

    @Test
    fun `comparaison de la langue insensible a la casse et etiquette renvoyee telle quelle`() {
        assertEquals("EN-gb", preferredTagFor(AppLanguage.EN, listOf("EN-gb")))
        assertEquals("fr_CA", preferredTagFor(AppLanguage.FR, listOf("fr_CA")))
    }
}

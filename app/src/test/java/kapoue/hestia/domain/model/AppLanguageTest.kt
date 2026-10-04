package kapoue.hestia.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `chaque langue retrouve sa propre etiquette`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun `systeme n'a pas d'etiquette`() {
        assertNull(AppLanguage.SYSTEM.tag)
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("   "))
    }

    @Test
    fun `etiquettes regionales et casse ignorees`() {
        assertEquals(AppLanguage.FR, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.FR, AppLanguage.fromTag("fr_CA"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("EN-us"))
    }

    @Test
    fun `langue non prise en charge ou valeur corrompue donne systeme`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zz-ZZ"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("%%garbage%%"))
    }
}

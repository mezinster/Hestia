package kapoue.hestia.core.locale

import kapoue.hestia.domain.model.AppLanguage

/**
 * Étiquette à appliquer quand l'utilisateur force [language] dans Réglages. On reprend la
 * première locale de la liste SYSTÈME de même langue (ex. « en-GB » pour un téléphone réglé en
 * anglais britannique) plutôt que la langue nue : la région pilote le format régional des dates
 * et des heures (24 h, jour/mois/année…), qui ne doit pas changer parce qu'on force la langue.
 * Sans correspondance, on retombe sur l'étiquette nue de la langue ([AppLanguage.tag]) ; nulle
 * pour [AppLanguage.SYSTEM] (pas de surcharge). La comparaison de la langue (avant `-` ou `_`)
 * ignore la casse ; l'étiquette système retenue est renvoyée telle quelle.
 */
fun preferredTagFor(language: AppLanguage, systemTags: List<String>): String? {
    val wanted = language.tag ?: return null
    return systemTags.firstOrNull {
        it.substringBefore('-').substringBefore('_').equals(wanted, ignoreCase = true)
    } ?: wanted
}

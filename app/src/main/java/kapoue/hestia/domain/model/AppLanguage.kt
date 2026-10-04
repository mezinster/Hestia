package kapoue.hestia.domain.model

/**
 * Langue de l'interface choisie dans Réglages. [SYSTEM] = suivre la langue du téléphone.
 * [tag] : étiquette BCP 47 réduite à la langue (sans région), nulle pour [SYSTEM].
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    EN("en"),
    FR("fr"),
    RU("ru");

    companion object {
        /**
         * Retrouve la langue d'une étiquette (« fr », « fr-FR », « fr_CA »…), insensible à la casse.
         * Toute valeur absente, vide ou non prise en charge donne [SYSTEM] — jamais d'exception :
         * l'étiquette peut venir des préférences (version antérieure) ou du réglage système.
         */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase()
            if (language.isNullOrEmpty()) return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}

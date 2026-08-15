package kapoue.hestia.domain.model

/**
 * État de la LED d'un appareil physique, déduit du réglage natif `night_mode` (jamais stocké par
 * Hestia — toujours relu en direct). Un seul réglage par appareil physique, partagé par tous ses
 * canaux sur un bloc multi-canaux (le composant LED n'est pas par canal).
 */
enum class LedNightModeState {
    /** 100 % le jour, réduite la nuit (22h-8h). */
    ON,

    /** Éteinte en permanence. */
    OFF,

    /** Composant absent sur ce modèle, ou appareil injoignable. */
    UNAVAILABLE,
}

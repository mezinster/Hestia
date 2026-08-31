package kapoue.hestia.domain.model

/**
 * Type d'appareil, choisi par l'utilisateur. Détermine l'icône affichée.
 *
 * [SMOKE_DETECTOR] a son propre type, distinct de [SENSOR] générique (2026-08-31) : premier
 * appareil sans relais (`supportsSwitch = false`), sur pile, sans test de connexion possible à
 * l'ajout (dort la majeure partie du temps — voir SMOKE-DETECTOR.md). [SENSOR] reste générique
 * pour d'éventuels autres capteurs futurs, pas de type fourre-tout créé pour rien.
 */
enum class DeviceType {
    PLUG,
    LAMP,
    SENSOR,
    SMOKE_DETECTOR,
}

/** Pilote de communication. Une seule valeur en V1 ; l'enum prépare d'autres marques. */
enum class DriverType {
    SHELLY_GEN2,
}

/** Choix de thème de l'application (préférence utilisateur). */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

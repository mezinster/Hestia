package kapoue.hestia.domain.model

/** Type d'appareil, choisi par l'utilisateur. Détermine l'icône affichée. */
enum class DeviceType {
    PLUG,
    LAMP,
    SENSOR,
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

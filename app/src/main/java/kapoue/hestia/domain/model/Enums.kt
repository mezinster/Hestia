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

/** Nature d'une entrée du journal d'activité (ActivationLog). */
enum class ActivationAction {
    TURNED_ON,
    TURNED_OFF,
    TIMER_STARTED,
    TIMER_CANCELLED,
    PRESENCE_DEPLOYED,
    PRESENCE_STOPPED,
}

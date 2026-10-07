package kapoue.hestia.data.rpc

/**
 * Nature d'un appareil Gen2+ qui n'expose **aucun** canal `switch:N`, déduite de ses composants
 * (`Shelly.GetComponents`) — seul le relais marche/arrêt est piloté par Hestia. Sert à expliquer
 * à l'utilisateur pourquoi l'appareil est refusé à l'ajout, plutôt que de l'ajouter quand même
 * comme canal 0 et de laisser toutes les commandes échouer ensuite.
 */
enum class UnsupportedKind {
    /** Volet roulant (`cover`), ex. un 2PM configuré en mode volet. */
    COVER,

    /** Variateur ou contrôleur de LED (`light`, `rgb`, `rgbw`, `cct`). */
    LIGHT,

    /** Compteur d'énergie sans relais (`em`, `em1`, `pm1`). */
    ENERGY_METER,

    /** Détecteur de fumée choisi par erreur comme prise : il a sa propre tuile. */
    SMOKE_DETECTOR,

    /** Capteur (température, humidité, inondation, luminosité…). */
    SENSOR,

    /** Entrées seules, rien à commuter (ex. Shelly i4). */
    INPUT_ONLY,

    /** Rien de reconnaissable. */
    UNKNOWN,
}

/** Types de composants classés par priorité : le premier présent l'emporte. */
private val KIND_BY_COMPONENT: List<Pair<UnsupportedKind, Set<String>>> = listOf(
    UnsupportedKind.COVER to setOf("cover"),
    UnsupportedKind.LIGHT to setOf("light", "rgb", "rgbw", "cct"),
    UnsupportedKind.ENERGY_METER to setOf("em", "em1", "pm1"),
    UnsupportedKind.SMOKE_DETECTOR to setOf("smoke"),
    UnsupportedKind.SENSOR to setOf("temperature", "humidity", "flood", "illuminance", "presence", "voltmeter"),
    UnsupportedKind.INPUT_ONLY to setOf("input"),
)

/** Classe un appareil d'après ses clés de composants (`cover:0`, `sys`…). */
internal fun classifyUnsupported(componentKeys: List<String>): UnsupportedKind {
    val types = componentKeys.map { it.substringBefore(':') }.toSet()
    return KIND_BY_COMPONENT.firstOrNull { (_, keys) -> keys.any { it in types } }?.first
        ?: UnsupportedKind.UNKNOWN
}

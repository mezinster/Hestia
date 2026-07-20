package kapoue.hestia.data.rpc

/**
 * Capacités déduites d'un appareil à l'ajout (SPEC-V1 § 3 « Détection des capacités »),
 * en interrogeant l'appareil plutôt qu'en maintenant un catalogue de modèles en dur.
 */
data class DeviceCapabilities(
    val generation: Int,
    val model: String?,
    val reportedName: String?,
    /** Identifiants des canaux switch exposés (ex. [0] pour un Plug M, [0,1,2,3] pour un Pro 4PM). */
    val switchChannels: List<Int>,
    val hasScripting: Boolean,
    val hasPowerMetering: Boolean,
) {
    val isMultiChannel: Boolean get() = switchChannels.size > 1
}

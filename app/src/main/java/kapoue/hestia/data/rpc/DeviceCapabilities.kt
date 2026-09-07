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
    /**
     * Nom déjà configuré sur l'appareil pour chaque canal (Switch.GetConfig.name), quand il
     * existe — sert à proposer ce nom à l'ajout plutôt qu'un générique (nom des prises, 2026-09-07).
     */
    val channelNames: Map<Int, String> = emptyMap(),
    val hasScripting: Boolean,
    val hasPowerMetering: Boolean,
) {
    val isMultiChannel: Boolean get() = switchChannels.size > 1
}

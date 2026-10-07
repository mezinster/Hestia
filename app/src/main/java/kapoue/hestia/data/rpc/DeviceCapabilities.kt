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
    /** Canaux variateur (`light:N`) exposés — voir variateurs, 2026-10-07. */
    val lightChannels: List<Int> = emptyList(),
    /** Nom configuré sur l'appareil pour chaque canal light (`light:N` config `name`). */
    val lightChannelNames: Map<Int, String> = emptyMap(),
    /** Canaux volet (`cover:N`) exposés — volets roulants, lot S1 (2026-10-07). */
    val coverChannels: List<Int> = emptyList(),
    /** Nom configuré sur l'appareil pour chaque canal volet (`cover:N` config `name`). */
    val coverChannelNames: Map<Int, String> = emptyMap(),
    val hasScripting: Boolean,
    val hasPowerMetering: Boolean,
    /** Clés brutes de `Shelly.GetComponents` (`switch:0`, `cover:0`, `sys`…), pour le diagnostic. */
    val componentKeys: List<String> = emptyList(),
) {
    val isMultiChannel: Boolean get() = switchChannels.size > 1

    /** Nature de l'appareil s'il n'a ni relais, ni variateur, ni volet pilotable par Hestia, null sinon. */
    val unsupportedKind: UnsupportedKind?
        get() = if (switchChannels.isEmpty() && lightChannels.isEmpty() && coverChannels.isEmpty()) classifyUnsupported(componentKeys) else null
}

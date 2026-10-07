package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.ComponentEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extraction des canaux d'un type de composant (`switch`, `light`) depuis `Shelly.GetComponents`
 * — sortie de [ShellyRpcClient.probe] pour être testable sans appareil (variateurs, 2026-10-07).
 */
internal fun parseChannels(components: List<ComponentEntry>, prefix: String): List<Int> {
    val pattern = Regex("""${Regex.escape(prefix)}:(\d+)""")
    return components.mapNotNull { pattern.matchEntire(it.key)?.groupValues?.get(1)?.toIntOrNull() }.sorted()
}

/** Nom déjà configuré sur l'appareil pour chaque canal du type [prefix], s'il n'est pas vide. */
internal fun parseChannelNames(components: List<ComponentEntry>, prefix: String): Map<Int, String> {
    val pattern = Regex("""${Regex.escape(prefix)}:(\d+)""")
    return components.mapNotNull { entry ->
        val id = pattern.matchEntire(entry.key)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val name = (entry.config?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        id to name
    }.toMap()
}

/** Vrai si l'appareil n'a que des canaux variateur : l'ajout passe alors par le dépôt Light. */
internal fun DeviceCapabilities.isLightOnly(): Boolean = switchChannels.isEmpty() && lightChannels.isNotEmpty()

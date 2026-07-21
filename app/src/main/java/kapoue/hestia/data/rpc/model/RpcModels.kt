package kapoue.hestia.data.rpc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Modèles JSON-RPC 2.0 pour l'API Shelly Gen2+ (SPEC-V1 § 3).
 * Seuls les champs réellement exploités sont déclarés ; le reste est ignoré
 * (ignoreUnknownKeys = true côté client).
 */

@Serializable
data class RpcRequest(
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class RpcEnvelope(
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: RpcErrorBody? = null,
)

@Serializable
data class RpcErrorBody(
    val code: Int,
    val message: String = "",
)

/** Réponse de Shelly.GetDeviceInfo. */
@Serializable
data class DeviceInfoResult(
    val id: String? = null,
    val model: String? = null,
    /** Nom convivial de l'appareil, s'il est configuré. */
    val name: String? = null,
    /** Génération : 2, 3, 4… Absent ⇒ probablement Gen1. */
    val gen: Int? = null,
    @SerialName("fw_id") val firmwareId: String? = null,
    @SerialName("app") val appName: String? = null,
    @SerialName("auth_en") val authEnabled: Boolean? = null,
)

/**
 * Réponse de Shelly.GetComponents. On lit uniquement les clés de composants
 * (« switch:0 », « script:1 », « pm1:0 »…) pour déduire les capacités, sans catalogue en dur.
 */
@Serializable
data class ComponentsResult(
    val components: List<ComponentEntry> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ComponentEntry(
    val key: String,
    val status: JsonObject? = null,
    val config: JsonObject? = null,
)

/** Réponse de Switch.Set : état précédent du relais. */
@Serializable
data class SwitchSetResult(
    @SerialName("was_on") val wasOn: Boolean? = null,
)

/** Réponse de Switch.SetConfig / Script.SetConfig. */
@Serializable
data class SetConfigResult(
    @SerialName("restart_required") val restartRequired: Boolean = false,
)

// --- Scripting (simulation de présence, lot 4) ---

/** Réponse de Script.Create. */
@Serializable
data class ScriptCreateResult(val id: Int)

/** Réponse de Script.PutCode : longueur totale du code accepté. */
@Serializable
data class ScriptPutCodeResult(val len: Int = 0)

/** Réponse de Script.Start / Script.Stop (et Delete, dont le résultat vide est ignoré). */
@Serializable
data class ScriptRunResult(
    @SerialName("was_running") val wasRunning: Boolean? = null,
)

/** Réponse de Script.List. */
@Serializable
data class ScriptListResult(
    val scripts: List<ScriptEntry> = emptyList(),
)

@Serializable
data class ScriptEntry(
    val id: Int,
    val name: String? = null,
    val enable: Boolean = false,
    val running: Boolean = false,
)

/** Horloge de l'appareil, extraite de Shelly.GetStatus → sys (contrôle de dérive). */
@Serializable
data class ShellyFullStatus(
    val sys: SysStatus? = null,
)

@Serializable
data class SysStatus(
    /** Heure de l'appareil en epoch Unix (secondes, UTC). */
    val unixtime: Long? = null,
    /** Heure locale de l'appareil, ex. « 05:09 ». */
    val time: String? = null,
    @SerialName("utc_offset") val utcOffset: Int? = null,
)

/**
 * Réponse de Switch.GetStatus (sous-ensemble). Champs du minuteur validés sur Plug M Gen3 :
 * l'appareil ne renvoie PAS de `timer_remaining`, mais `timer_started_at` (epoch Unix, s) et
 * `timer_duration` (s) — le temps restant se calcule à partir de ces deux valeurs.
 */
@Serializable
data class SwitchStatusResult(
    val id: Int,
    val output: Boolean = false,
    /** Puissance active instantanée (W) si l'appareil mesure la puissance. */
    val apower: Double? = null,
    /** Instant de démarrage du minuteur (epoch Unix, secondes). Absent si pas de minuteur. */
    @SerialName("timer_started_at") val timerStartedAt: Double? = null,
    /** Durée totale du minuteur (secondes). Absent si pas de minuteur. */
    @SerialName("timer_duration") val timerDuration: Double? = null,
)

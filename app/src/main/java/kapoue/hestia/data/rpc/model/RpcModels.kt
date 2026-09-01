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
    /** Version lisible du firmware (ex. « 1.8.99-plugmg3prod0 »), affichée à l'utilisateur. */
    val ver: String? = null,
    @SerialName("app") val appName: String? = null,
    @SerialName("auth_en") val authEnabled: Boolean? = null,
    /** Adresse MAC de l'appareil — identique au « Cloud ID » affiché dans son interface native
     * une fois le cloud activé (validé en direct le 2026-08-20). */
    val mac: String? = null,
)

/**
 * Réponse de Shelly.CheckForUpdate. Chaque section absente signifie qu'aucune mise à jour n'est
 * disponible sur ce canal. On ne propose jamais l'installation de [beta] depuis Hestia (trop
 * risqué pour un outil grand public) : elle n'est affichée qu'à titre informatif.
 */
@Serializable
data class CheckForUpdateResult(
    val stable: FirmwareUpdateInfo? = null,
    val beta: FirmwareUpdateInfo? = null,
)

@Serializable
data class FirmwareUpdateInfo(
    val version: String,
    @SerialName("build_id") val buildId: String? = null,
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

/** Réponse de Script.GetCode : le code source du script (pour relire la config embarquée). */
@Serializable
data class ScriptGetCodeResult(
    val data: String = "",
    val left: Int = 0,
)

/**
 * Réponse de Script.Eval : le champ s'appelle bien `result` dans le schéma Shelly lui-même (pas un
 * effet de l'enveloppe JSON-RPC) — toujours une chaîne, y compris pour une valeur numérique
 * (`"3"`), validé en direct le 2026-08-18.
 */
@Serializable
data class ScriptEvalResult(
    val result: String? = null,
)

@Serializable
data class ScriptEntry(
    val id: Int,
    val name: String? = null,
    val enable: Boolean = false,
    val running: Boolean = false,
)

// --- Webhooks natifs (relais ntfy des détecteurs de fumée, lot 4a — voir SMOKE-DETECTOR.md) ---

/** Réponse de Webhook.Create. */
@Serializable
data class WebhookCreateResult(val id: Int = 0)

/** Réponse de Webhook.List. */
@Serializable
data class WebhookListResult(
    val hooks: List<WebhookEntry> = emptyList(),
)

@Serializable
data class WebhookEntry(
    val id: Int,
    val cid: Int? = null,
    val enable: Boolean = false,
    val event: String = "",
    val name: String? = null,
    val urls: List<String> = emptyList(),
)

/** Réponse de Schedule.List : les programmes cron stockés dans l'appareil (plannings). */
@Serializable
data class ScheduleListResult(
    val jobs: List<ScheduleJob> = emptyList(),
)

@Serializable
data class ScheduleJob(
    val id: Int,
    val enable: Boolean = true,
    val timespec: String = "",
    val calls: List<ScheduleCall> = emptyList(),
)

/** Une action déclenchée par un programme. Les params dépendent de la méthode (gardés bruts). */
@Serializable
data class ScheduleCall(
    val method: String = "",
    val params: JsonObject? = null,
)

/** Réponse de Schedule.Create : identifiant du programme créé. */
@Serializable
data class ScheduleCreateResult(val id: Int, val rev: Int? = null)

/** Réponse de Schedule.Delete (on n'exploite que la présence d'un résultat). */
@Serializable
data class ScheduleDeleteResult(val rev: Int? = null)

// --- Cloud Shelly (opt-in, désactivé par défaut — voir CLAUDE.md) ---

/** Réponse de Cloud.GetConfig. [server] n'est renseigné qu'une fois déjà connecté au moins une fois. */
@Serializable
data class CloudConfigResult(
    val enable: Boolean = false,
    val server: String? = null,
)

/** Réponse de Cloud.GetStatus. */
@Serializable
data class CloudStatusResult(
    val connected: Boolean = false,
)

/**
 * État complet de l'appareil (Shelly.GetStatus). `sys` sert au contrôle de dérive d'horloge ;
 * `smoke`/`devicePower`/`temperature` (2026-08-31, détecteur de fumée — voir SMOKE-DETECTOR.md)
 * sont absents (null) sur tout appareil qui n'a pas ces composants, ignoreUnknownKeys s'occupant
 * du reste sans qu'on ait à lister chaque type d'appareil.
 */
@Serializable
data class ShellyFullStatus(
    val sys: SysStatus? = null,
    @SerialName("smoke:0") val smoke: SmokeStatusResult? = null,
    @SerialName("devicepower:0") val devicePower: DevicePowerStatusResult? = null,
    @SerialName("temperature:0") val temperature: TemperatureStatusResult? = null,
)

@Serializable
data class SysStatus(
    /** Heure de l'appareil en epoch Unix (secondes, UTC). */
    val unixtime: Long? = null,
    /** Heure locale de l'appareil, ex. « 05:09 ». */
    val time: String? = null,
    @SerialName("utc_offset") val utcOffset: Int? = null,
)

/** `Smoke.GetStatus` (voir SMOKE-DETECTOR.md). */
@Serializable
data class SmokeStatusResult(
    val alarm: Boolean = false,
    val mute: Boolean = false,
)

/**
 * `DevicePower.GetStatus`. [errors] non vide (ex. `["read"]`) signale une lecture de batterie
 * impossible — vécu en direct : peut venir d'une vraie panne matérielle **ou** d'une config
 * corrompue résolue par une simple réinitialisation d'usine côté appareil, jamais présenté comme
 * un défaut définitif. `battery.V` (tension) n'est volontairement pas modélisée : elle peut
 * valoir une chaîne non numérique (`"Q"`) en cas d'erreur, ce qui ferait échouer le décodage —
 * seul `percent` est exploité par Hestia, déjà calculé par le firmware.
 */
@Serializable
data class DevicePowerStatusResult(
    val battery: BatteryStatus? = null,
    val errors: List<String>? = null,
)

@Serializable
data class BatteryStatus(
    val percent: Int? = null,
)

/** `Temperature.GetStatus` — température de la pièce en degrés Celsius. */
@Serializable
data class TemperatureStatusResult(
    val tC: Double? = null,
)

/**
 * Réponse de `<composant>.GetConfig` pour le composant LED d'un appareil (`plugs_ui` sur une prise
 * solo, `powerstrip_ui` sur un bloc multi-canaux — un seul réglage par appareil physique, jamais
 * par canal). Seul `night_mode` nous intéresse (validé en direct le 2026-08-15 sur Plug M Gen3 et
 * Shelly Strip 4) : couleurs/mode d'affichage ignorés, jamais modifiés par Hestia.
 */
@Serializable
data class LedUiConfigResult(
    val leds: LedUiLeds? = null,
)

@Serializable
data class LedUiLeds(
    @SerialName("night_mode") val nightMode: LedNightMode? = null,
)

@Serializable
data class LedNightMode(
    val enable: Boolean = false,
    val brightness: Double = 100.0,
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
    /**
     * Origine de la dernière bascule : `"button"`/`"short_push"` (bouton physique, variable selon
     * le modèle), `"HTTP_in"` (RPC, ex. l'app), `"loopback"` (un script) — validé en direct le
     * 2026-08-17. Persiste jusqu'à la bascule suivante, utile pour savoir après coup qui a coupé.
     */
    val source: String? = null,
    /** Compteurs natifs cumulés (secondes/nombre de bascules) — voir [SwitchCounts]. */
    val counts: SwitchCounts? = null,
)

/**
 * Sous-ensemble de `counts` dans `Switch.GetStatus` : compteurs cumulés côté appareil, jamais
 * remis à zéro par Hestia (`on_time_rst_ts` existe côté firmware mais n'est pas utilisé ici).
 * [onTime] sert uniquement de brique interne pour calculer la durée du ON **en cours** (voir
 * `DashboardViewModel`, mémorise la valeur relevée à la dernière extinction connue, la
 * différence donne une durée exacte même après une app fermée/hors réseau entre-temps) — jamais
 * affiché tel quel (cumul depuis toujours, pas la donnée demandée par l'utilisateur).
 */
@Serializable
data class SwitchCounts(
    @SerialName("on_time") val onTime: Double? = null,
)

/**
 * Lecture combinée d'un détecteur de fumée — mêmes champs qu'on lise en local
 * (`Shelly.GetStatus` → `smoke`/`devicePower`/`temperature`) ou reconstruits depuis le cloud,
 * comme [SwitchStatusResult] pour un canal (voir SMOKE-DETECTOR.md). [updatedAtEpochSec] :
 * instant de cette lecture (epoch Unix, secondes) — l'heure du téléphone pour une lecture
 * locale, le champ `_updated` du cloud sinon ; jamais garanti d'être frais, l'appareil dort la
 * majeure partie du temps.
 */
data class SensorReadingResult(
    val alarm: Boolean,
    val mute: Boolean,
    val batteryPercent: Int?,
    val batteryError: Boolean,
    val temperatureC: Double?,
    val updatedAtEpochSec: Long?,
)

package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceStatusResult
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.domain.model.Planning

/** État visuel d'un canal sur le Tableau. */
sealed interface TileStatus {
    /** Avant la première lecture réussie. */
    data object Loading : TileStatus

    /**
     * Canal joignable. [timerEndsAtElapsed] (référentiel SystemClock.elapsedRealtime, en ms)
     * est non nul quand un minuteur est actif sur l'appareil : le compte à rebours est décrémenté
     * localement à la seconde et resynchronisé à chaque lecture.
     */
    data class Online(
        val output: Boolean,
        val timerEndsAtElapsed: Long?,
        /** Puissance active instantanée en watts, si l'appareil la mesure. Null sinon. */
        val powerWatts: Double?,
        /** Vrai si cette lecture vient du repli cloud (appareil injoignable en local) — jamais
         * silencieux, voir CLAUDE.md : la tuile affiche un petit picto nuage dans ce cas. */
        val viaCloud: Boolean = false,
        /** Compteur natif cumulé de secondes ON (`counts.on_time`), brique interne pour calculer
         * la durée du ON en cours (voir [kapoue.hestia.data.rpc.model.SwitchCounts]) — jamais
         * affiché tel quel. Null si l'appareil ne le fournit pas. */
        val onTimeSec: Double? = null,
    ) : TileStatus

    /** Appareil injoignable (timeout, réseau, erreur RPC) — inclut aussi le cas où la permission
     * réseau local manque : aucune interrogation n'est tentée, un message global le signale
     * (bandeau en tête du Tableau), plutôt qu'un état par tuile. */
    data object Offline : TileStatus
}

/**
 * Simulation de présence **réellement en cours** sur l'appareil : l'exécution du script est
 * vérifiée via `Script.List` (jamais supposée), la plage horaire vient du cache local.
 * Nul quand aucune simulation ne tourne.
 */
data class PresenceInfo(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

/** Une tuile = un canal, avec son état courant. */
data class TileUiState(
    val device: Device,
    val status: TileStatus,
    val presence: PresenceInfo? = null,
    /** Plannings présents sur l'appareil ; la tuile affiche celui **en cours** s'il y en a un. */
    val plannings: List<Planning> = emptyList(),
    /**
     * Seuil surveillant actuellement ce canal, affiché à côté du décompte s'il y en a un, ou de
     * la durée du ON sinon. Deux sources fusionnées par
     * [kapoue.hestia.ui.screens.dashboard.DashboardViewModel] : un minuteur natif avec durée
     * (souvenir local confirmé par l'appareil) ou un minuteur « sans limite de durée » relu en
     * direct sur le script `hestia_charge` (voir `DeviceRepository.getActiveChargeThreshold`).
     */
    val pendingThresholdW: Int? = null,
    /**
     * Instant d'allumage du canal, dans le référentiel SystemClock.elapsedRealtime (ms) — même
     * mécanique que [TileStatus.Online.timerEndsAtElapsed] mais pour un point de départ passé
     * plutôt qu'une fin future. Calculé par [kapoue.hestia.ui.screens.dashboard.DashboardViewModel]
     * à partir du compteur natif `counts.on_time` (voir sa doc) : durée exacte du ON en cours,
     * robuste à une app fermée/hors réseau entre-temps. Null si non allumé, ou si aucune
     * référence fiable n'a encore été observée (première fois que Hestia voit ce canal allumé).
     */
    val onSinceElapsed: Long? = null,
    /**
     * Nom de l'appareil physique (celui de Réglages, pas le modèle technique), affiché dans
     * l'en-tête au-dessus du canal — pour tous les appareils, mono ou multi-canaux.
     */
    val groupLabel: String = "",
    /** Vrai pour le premier canal du groupe dans l'ordre d'affichage : affiche l'en-tête au-dessus. */
    val isFirstInGroup: Boolean = false,
    /** Vrai si l'appareil physique a plusieurs canaux (ex. Strip 4) : change le picto de l'en-tête. */
    val isMultiChannel: Boolean = false,
)

data class DashboardUiState(
    val tiles: List<TileUiState> = emptyList(),
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
)

/** Convertit un résultat Switch.GetStatus en état de tuile. Partagé Tableau ↔ Détail. */
internal fun DeviceStatusResult.toTileStatus(): TileStatus = when (val r = result) {
    is RpcResult.Success -> TileStatus.Online(
        output = r.value.output,
        timerEndsAtElapsed = timerEndsAtElapsed(r.value.timerStartedAt, r.value.timerDuration),
        powerWatts = r.value.apower,
        viaCloud = viaCloud,
        onTimeSec = r.value.counts?.onTime,
    )
    // Un appareil injoignable (ou en erreur) n'empêche pas d'utiliser les autres.
    is RpcResult.RpcError -> TileStatus.Offline
    is RpcResult.Failure -> TileStatus.Offline
}

/**
 * Instant de fin du minuteur dans le référentiel SystemClock.elapsedRealtime (ms), ou null.
 * Calculé à partir de `timer_started_at` + `timer_duration` (l'appareil ne fournit pas de
 * temps restant direct) et de l'heure du téléphone.
 */
private fun timerEndsAtElapsed(startedAt: Double?, duration: Double?): Long? {
    if (startedAt == null || duration == null) return null
    val endEpoch = startedAt + duration
    val nowEpoch = System.currentTimeMillis() / 1000.0
    val remainingSec = (endEpoch - nowEpoch).toLong()
    if (remainingSec <= 0) return null
    return SystemClock.elapsedRealtime() + remainingSec * 1000
}

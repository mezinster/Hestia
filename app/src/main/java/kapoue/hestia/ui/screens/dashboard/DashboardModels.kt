package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult

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
    ) : TileStatus

    /** Appareil injoignable (timeout, réseau, erreur RPC). */
    data object Offline : TileStatus

    /** Permission réseau local non accordée : aucune interrogation tentée. */
    data object PermissionRequired : TileStatus
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

/** Une tuile = un canal, avec son numéro d'affichage et son état courant. */
data class TileUiState(
    val number: Int,
    val device: Device,
    val status: TileStatus,
    val presence: PresenceInfo? = null,
)

data class DashboardUiState(
    val tiles: List<TileUiState> = emptyList(),
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
)

/** Convertit un résultat Switch.GetStatus en état de tuile. Partagé Tableau ↔ Détail. */
internal fun RpcResult<SwitchStatusResult>.toTileStatus(): TileStatus = when (this) {
    is RpcResult.Success -> TileStatus.Online(
        output = value.output,
        timerEndsAtElapsed = timerEndsAtElapsed(value.timerStartedAt, value.timerDuration),
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

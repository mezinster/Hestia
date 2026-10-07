package kapoue.hestia.data.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.CoverScheduleRepository
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.domain.model.coverEventInstantsBetween
import java.time.Instant
import java.time.ZoneId
import java.util.Objects

/**
 * Passage périodique (~15 min) qui **lit** l'état des appareils et notifie :
 * - les bornes de programmation franchies depuis le dernier passage (début/fin de planning, de
 *   plage de présence), calculées par arithmétique de dates ;
 * - la fin des minuteurs armés depuis Hestia (« Active pour », coupure sur seuil), suivie via un
 *   mémo local ([PendingTimer]).
 *
 * Principe : aucune donnée de programmation n'est stockée par Hestia. Le worker relit les plannings
 * (`Schedule.List`) et les plages de présence (script) sur l'appareil à chaque passage ; les bornes
 * sont ensuite calculées par arithmétique de dates (déterministe). Quand l'appareil est injoignable
 * (téléphone hors du réseau), la lecture échoue silencieusement : aucune notif, rien n'est supposé.
 *
 * Dépendances Hilt récupérées via [EntryPointAccessors] : pas besoin de hilt-work ni de modifier
 * l'Application, un [CoroutineWorker] au constructeur standard suffit.
 */
class NotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun deviceRepository(): DeviceRepository
        fun coverScheduleRepository(): CoverScheduleRepository
        fun appPreferences(): AppPreferences
        fun logger(): DiagnosticLogger
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
        val prefs = deps.appPreferences()
        // Désactivé entre-temps (course avec l'annulation) : ne rien faire.
        if (!prefs.notificationsEnabled.value) return Result.success()
        val repository = deps.deviceRepository()

        val now = System.currentTimeMillis()
        val last = prefs.notificationsLastRun
        // Fenêtre d'observation (dernier passage → maintenant), mais bornée : le mode Doze peut
        // suspendre le worker longtemps, on ne veut pas déverser un paquet de notifs périmées au
        // réveil. Au-delà de MAX_LOOKBACK, les bornes plus anciennes sont simplement ignorées.
        val from = if (last <= 0L) now - INTERVAL_MS else maxOf(last, now - MAX_LOOKBACK_MS)
        val zone = ZoneId.systemDefault()

        val devices = repository.getDevicesOnce().filterNot { it.ipAddress.startsWith(DEMO_PREFIX) }
        var posted = 0
        for (device in devices) {
            // getPlannings fusionne plannings précis et simulations de présence depuis la fusion
            // du 2026-08-18 (gated en interne sur hasScripting pour la présence) — une seule
            // boucle suffit désormais, [Planning.isPresence] choisit juste le texte de notif.
            if (device.supportsSwitch || device.isLight) {
                for (p in repository.getPlannings(device).getOrNull().orEmpty()) {
                    val (startKind, startText) = if (p.isPresence) "pr-start" to R.string.notif_presence_started else "pl-start" to R.string.notif_planning_started
                    val (endKind, endText) = if (p.isPresence) "pr-end" to R.string.notif_presence_ended else "pl-end" to R.string.notif_planning_ended
                    ScheduleCodec.boundaryInstant(p.startMinutes, p.endMinutes, p.days, ScheduleCodec.Boundary.START, from, now, zone)?.let { t ->
                        val threshold = p.cutoffThresholdW
                        val startBody = if (!p.isPresence && threshold != null) {
                            ctx.getString(R.string.notif_planning_started_with_threshold, formatClockTime(p.startMinutes), formatClockTime(p.endMinutes), threshold)
                        } else {
                            ctx.getString(startText, formatClockTime(p.startMinutes), formatClockTime(p.endMinutes))
                        }
                        post(ctx, device.id, startKind, t, device.name, startBody)
                        posted++
                    }
                    ScheduleCodec.boundaryInstant(p.startMinutes, p.endMinutes, p.days, ScheduleCodec.Boundary.END, from, now, zone)?.let { t ->
                        post(ctx, device.id, endKind, t, device.name,
                            ctx.getString(endText, formatClockTime(p.startMinutes), formatClockTime(p.endMinutes)))
                        posted++
                    }
                }
            }
            // Événements programmés des volets (lus sur l'appareil ; échec = silencieux, comme ci-dessus).
            if (device.isCover) {
                val fromLocal = Instant.ofEpochMilli(from).atZone(zone).toLocalDateTime()
                val toLocal = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
                for (e in deps.coverScheduleRepository().getEvents(device).getOrNull().orEmpty()) {
                    val (kind, text) = when (val a = e.action) {
                        CoverEventAction.Open -> "cv-open" to ctx.getString(R.string.cover_notif_opened)
                        CoverEventAction.Close -> "cv-close" to ctx.getString(R.string.cover_notif_closed)
                        is CoverEventAction.GoTo -> "cv-goto" to ctx.getString(R.string.cover_notif_moved, a.position)
                    }
                    for (t in coverEventInstantsBetween(e, fromLocal, toLocal)) {
                        post(ctx, device.id, kind, t.atZone(zone).toInstant().toEpochMilli(), device.name, text)
                        posted++
                    }
                }
            }
        }
        posted += processPendingTimers(ctx, repository, prefs, devices, now)

        prefs.notificationsLastRun = now
        deps.logger().info(LOG_TAG, "Notifications : ${devices.size} appareil(s), $posted borne(s) notifiée(s)")
        return Result.success()
    }

    /**
     * Détecte la fin des minuteurs armés depuis Hestia (« Active pour », avec ou sans coupure sur
     * seuil) et notifie. Deux issues distinctes :
     * - **fin prévue atteinte** (ou coupure seuil allée jusqu'au bout) → « Active pour … terminé » ;
     * - **coupure sur seuil déclenchée avant la fin** → « Prise coupée (consommation…) ».
     *
     * Une extinction manuelle / par le bouton physique n'émet aucune notification : soit le mémo a
     * déjà été effacé (extinction via Hestia), soit le script de seuil tourne encore (on le vérifie).
     * @return le nombre de notifications émises.
     */
    private suspend fun processPendingTimers(
        ctx: Context,
        repository: DeviceRepository,
        prefs: AppPreferences,
        devices: List<Device>,
        now: Long,
    ): Int {
        var posted = 0
        for (timer in prefs.pendingTimers()) {
            val device = devices.firstOrNull { it.id == timer.deviceId }
            if (device == null) {
                // Appareil supprimé : nettoyer le mémo.
                prefs.removePendingTimer(timer.deviceId)
                continue
            }
            val reachedEnd = now >= timer.endMillis
            // Avant la fin prévue, ne lire l'état que pour repérer une coupure anticipée.
            val offEarly = !reachedEnd && repository.getStatus(device).result.getOrNull()?.output == false
            if (!reachedEnd && !offEarly) continue // toujours en cours

            // Terminé (fin atteinte ou prise déjà coupée). Trop ancien → on efface sans notifier.
            val stale = reachedEnd && now - timer.endMillis > MAX_LOOKBACK_MS
            when {
                stale -> Unit
                timer.thresholdW != null && repository.cutoffScriptFired(device) -> {
                    post(ctx, device.id, "cut", timer.endMillis, device.name,
                        ctx.getString(R.string.notif_cutoff_triggered))
                    posted++
                }
                // Coupé avant la fin mais pas par le seuil ⇒ manuel / bouton physique : pas de notif.
                offEarly -> Unit
                // Arrivé au terme prévu (minuteur simple, ou coupure seuil allée jusqu'au bout).
                else -> {
                    post(ctx, device.id, "timer-end", timer.endMillis, device.name,
                        ctx.getString(R.string.notif_timer_ended, timer.label))
                    posted++
                }
            }
            prefs.removePendingTimer(timer.deviceId)
        }
        return posted
    }

    private fun post(ctx: Context, deviceId: Long, kind: String, instant: Long, title: String, text: String) {
        // Identifiant déterministe (appareil + type + instant) : un même événement re-couvert par
        // deux passages remplace sa notif au lieu d'en créer une seconde.
        val id = Objects.hash(deviceId, kind, instant) and 0x7FFFFFFF
        ProgrammationNotifier.post(ctx, id, title, text)
    }

    private companion object {
        const val INTERVAL_MS = 15L * 60 * 1000
        const val MAX_LOOKBACK_MS = 30L * 60 * 1000
        const val DEMO_PREFIX = "203.0.113."
        const val LOG_TAG = "notif"
    }
}

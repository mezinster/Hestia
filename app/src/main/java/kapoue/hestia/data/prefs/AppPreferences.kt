package kapoue.hestia.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.data.notifications.PendingTimer
import kapoue.hestia.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Préférences de l'application (les seules stockées en propre côté Hestia, cf. CLAUDE.md).
 * Persistance simple via SharedPreferences ; exposée en Flow pour piloter le thème en direct.
 */
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("hestia_prefs", Context.MODE_PRIVATE)

    /**
     * Stockage chiffré (Android Keystore) réservé au sujet ntfy : à traiter comme un mot de
     * passe, jamais en clair (n'importe qui le connaissant peut lire — ou polluer — les notifs).
     */
    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "hestia_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    // --- Notifications de bornes de programmation (opt-in, désactivé par défaut) ---

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFS, false))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFS, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    /**
     * Horodatage (ms) du dernier passage du worker de notifications. Sert à calculer l'intervalle
     * [dernier passage, maintenant] dans lequel chercher les bornes franchies. 0 = jamais passé.
     */
    var notificationsLastRun: Long
        get() = prefs.getLong(KEY_NOTIFS_LAST_RUN, 0L)
        set(value) { prefs.edit().putLong(KEY_NOTIFS_LAST_RUN, value).apply() }

    // --- Notifications instantanées via ntfy (opt-in, remplace le worker ci-dessus si actif) ---

    private val _ntfyEnabled = MutableStateFlow(prefs.getBoolean(KEY_NTFY_ENABLED, false))
    val ntfyEnabled: StateFlow<Boolean> = _ntfyEnabled.asStateFlow()

    fun setNtfyEnabled(enabled: Boolean) {
        if (enabled != _ntfyEnabled.value) bumpNtfyGeneration()
        prefs.edit().putBoolean(KEY_NTFY_ENABLED, enabled).apply()
        _ntfyEnabled.value = enabled
    }

    /** Sujet ntfy, à traiter comme un mot de passe — stocké chiffré, jamais en clair. */
    private val _ntfyTopic = MutableStateFlow(securePrefs.getString(KEY_NTFY_TOPIC, null))
    val ntfyTopic: StateFlow<String?> = _ntfyTopic.asStateFlow()

    fun setNtfyTopic(topic: String?) {
        if (topic != _ntfyTopic.value) bumpNtfyGeneration()
        securePrefs.edit().putString(KEY_NTFY_TOPIC, topic).apply()
        _ntfyTopic.value = topic
    }

    /**
     * Rattrapage des appareils injoignables au moment d'un bascule de réglage ntfy : chaque
     * changement incrémente une génération ; un appareil « à jour » pour la génération courante
     * n'a pas besoin d'être resynchronisé. Le Tableau (qui interroge déjà tous les appareils en
     * boucle) compare et resynchronise ceux qui ont raté un changement — pas de tâche de fond.
     */
    private fun bumpNtfyGeneration() {
        prefs.edit().putInt(KEY_NTFY_GEN, ntfyGeneration + 1).apply()
    }

    val ntfyGeneration: Int get() = prefs.getInt(KEY_NTFY_GEN, 0)

    private fun ntfySyncedMap(): Map<String, Int> =
        runCatching { json.decodeFromString<Map<String, Int>>(prefs.getString(KEY_NTFY_SYNCED, null) ?: "{}") }
            .getOrDefault(emptyMap())

    fun isNtfySynced(deviceId: Long): Boolean = ntfySyncedMap()[deviceId.toString()] == ntfyGeneration

    fun markNtfySynced(deviceId: Long) {
        val updated = ntfySyncedMap() + (deviceId.toString() to ntfyGeneration)
        prefs.edit().putString(KEY_NTFY_SYNCED, json.encodeToString(updated)).apply()
    }

    /**
     * Même mécanisme que [isNtfySynced]/[markNtfySynced], pour le relais ntfy des détecteurs de
     * fumée (Lot 4a, voir SMOKE-DETECTOR.md) — réutilise volontairement [ntfyGeneration] : la
     * couverture du relais dépend exactement des mêmes réglages (activation/sujet ntfy), pas
     * besoin d'un second compteur. Un détecteur « à jour » pour la génération courante n'a pas
     * besoin d'être re-sondé à chaque réveil.
     */
    private fun smokeRelaySyncedMap(): Map<String, Int> =
        runCatching { json.decodeFromString<Map<String, Int>>(prefs.getString(KEY_SMOKE_RELAY_SYNCED, null) ?: "{}") }
            .getOrDefault(emptyMap())

    fun isSmokeRelaySynced(deviceId: Long): Boolean = smokeRelaySyncedMap()[deviceId.toString()] == ntfyGeneration

    fun markSmokeRelaySynced(deviceId: Long) {
        val updated = smokeRelaySyncedMap() + (deviceId.toString() to ntfyGeneration)
        prefs.edit().putString(KEY_SMOKE_RELAY_SYNCED, json.encodeToString(updated)).apply()
    }

    /**
     * Dernier résultat connu (best-effort) de [kapoue.hestia.data.repository.DeviceRepository.
     * resyncSmokeRelay] : vrai si au moins un appareil relaie actuellement les détecteurs de
     * fumée vers ntfy. Sert uniquement au bandeau de couverture zéro (Lot 4c) — jamais recalculé
     * à l'ouverture de l'app (pas d'appel réseau juste pour ça), seulement à chaque
     * resynchronisation réelle. Défaut à vrai (pas de bandeau) : l'absence d'information ne doit
     * jamais alarmer inutilement avant la première resynchronisation.
     */
    private val _smokeRelayCoverageOk = MutableStateFlow(prefs.getBoolean(KEY_SMOKE_RELAY_COVERAGE_OK, true))
    val smokeRelayCoverageOk: StateFlow<Boolean> = _smokeRelayCoverageOk.asStateFlow()

    fun setSmokeRelayCoverageOk(ok: Boolean) {
        prefs.edit().putBoolean(KEY_SMOKE_RELAY_COVERAGE_OK, ok).apply()
        _smokeRelayCoverageOk.value = ok
    }

    // --- Cloud Shelly, repli à distance (opt-in, désactivé par défaut — voir CLAUDE.md) ---

    /** Clé d'autorisation cloud, à traiter comme un mot de passe — stockée chiffrée, jamais en clair. */
    private val _cloudAuthKey = MutableStateFlow(securePrefs.getString(KEY_CLOUD_AUTH_KEY, null))
    val cloudAuthKey: StateFlow<String?> = _cloudAuthKey.asStateFlow()

    /** Adresse du serveur cloud assigné au compte (ex. « shelly-281-eu.shelly.cloud ») — pas un secret. */
    private val _cloudServer = MutableStateFlow(prefs.getString(KEY_CLOUD_SERVER, null))
    val cloudServer: StateFlow<String?> = _cloudServer.asStateFlow()

    fun setCloudCredentials(authKey: String?, server: String?) {
        securePrefs.edit().putString(KEY_CLOUD_AUTH_KEY, authKey).apply()
        prefs.edit().putString(KEY_CLOUD_SERVER, server).apply()
        _cloudAuthKey.value = authKey
        _cloudServer.value = server
    }

    fun clearCloudCredentials() = setCloudCredentials(null, null)

    // --- Minuteurs en attente (mémos pour notifier la fin d'un « Active pour » / coupure seuil) ---

    fun pendingTimers(): List<PendingTimer> =
        runCatching { json.decodeFromString<List<PendingTimer>>(prefs.getString(KEY_PENDING_TIMERS, null) ?: "[]") }
            .getOrDefault(emptyList())

    /** Enregistre (ou remplace) le minuteur en attente d'un appareil. */
    fun putPendingTimer(timer: PendingTimer) {
        val updated = pendingTimers().filterNot { it.deviceId == timer.deviceId } + timer
        prefs.edit().putString(KEY_PENDING_TIMERS, json.encodeToString(updated)).apply()
    }

    /** Retire le minuteur en attente d'un appareil (fin détectée, annulation ou extinction). */
    fun removePendingTimer(deviceId: Long) {
        val updated = pendingTimers().filterNot { it.deviceId == deviceId }
        prefs.edit().putString(KEY_PENDING_TIMERS, json.encodeToString(updated)).apply()
    }

    // --- Durée du ON en cours (mémo local, voir DashboardViewModel/SwitchCounts) ---

    /**
     * Dernière valeur connue de `counts.on_time` (secondes cumulées) relevée à l'extinction d'un
     * canal — sert de référence pour calculer la durée exacte du ON en cours, même après une app
     * fermée entre-temps (voir doc de [kapoue.hestia.data.rpc.model.SwitchCounts]).
     */
    fun onTimeBaseline(deviceId: Long): Double? = onTimeBaselineMap()[deviceId.toString()]

    fun setOnTimeBaseline(deviceId: Long, onTimeSec: Double) {
        val updated = onTimeBaselineMap() + (deviceId.toString() to onTimeSec)
        prefs.edit().putString(KEY_ON_TIME_BASELINE, json.encodeToString(updated)).apply()
    }

    private fun onTimeBaselineMap(): Map<String, Double> =
        runCatching { json.decodeFromString<Map<String, Double>>(prefs.getString(KEY_ON_TIME_BASELINE, null) ?: "{}") }
            .getOrDefault(emptyMap())

    /**
     * Instant estimé (epoch ms) du début de l'allumage **en cours** — calculé une seule fois par
     * allumage (au premier cycle où on le voit allumé), jamais recalculé aux cycles suivants.
     * Recalculer à chaque cycle à partir de `counts.on_time` faisait dériver l'affichage (la
     * précision du compteur natif n'est pas garantie à la seconde près) : le compte à rebours
     * paraissait revenir en arrière en rouvrant la modale — bug remonté par David le 2026-08-22.
     * Effacé dès qu'on revoit le canal éteint, pour qu'un allumage suivant recalcule le sien.
     */
    fun onSinceEpoch(deviceId: Long): Long? = onSinceEpochMap()[deviceId.toString()]

    fun setOnSinceEpoch(deviceId: Long, epochMillis: Long) {
        val updated = onSinceEpochMap() + (deviceId.toString() to epochMillis)
        prefs.edit().putString(KEY_ON_SINCE_EPOCH, json.encodeToString(updated)).apply()
    }

    fun clearOnSinceEpoch(deviceId: Long) {
        val updated = onSinceEpochMap() - deviceId.toString()
        prefs.edit().putString(KEY_ON_SINCE_EPOCH, json.encodeToString(updated)).apply()
    }

    private fun onSinceEpochMap(): Map<String, Long> =
        runCatching { json.decodeFromString<Map<String, Long>>(prefs.getString(KEY_ON_SINCE_EPOCH, null) ?: "{}") }
            .getOrDefault(emptyMap())

    // --- Présence désactivée pour aujourd'hui (mémo local, bouton ON/OFF pendant une présence) ---

    /**
     * Vrai si ce canal a été désactivé pour aujourd'hui via le bouton ON/OFF, app **ou** bouton
     * physique détecté (voir `DeviceRepository.stopPresenceForToday` et la détection par `source`
     * dans `DashboardViewModel`) — s'efface tout seul le lendemain (comparé à la date du jour,
     * jamais nettoyé explicitement).
     */
    fun isPresenceDisabledToday(deviceId: Long): Boolean =
        presenceDisabledTodayMap()[deviceId.toString()] == LocalDate.now().toString()

    fun markPresenceDisabledToday(deviceId: Long) {
        val updated = presenceDisabledTodayMap() + (deviceId.toString() to LocalDate.now().toString())
        prefs.edit().putString(KEY_PRESENCE_DISABLED_TODAY, json.encodeToString(updated)).apply()
    }

    /**
     * À appeler à chaque création/modification d'une présence sur ce canal — sans ça, une
     * présence supprimée puis recréée restait « désactivée aujourd'hui » indéfiniment, le mémo
     * n'étant lié qu'au canal et à la date, pas à une présence précise (bug vécu en direct par
     * David le 2026-08-22 : nouvelle présence créée, toujours marquée désactivée).
     */
    fun clearPresenceDisabledToday(deviceId: Long) {
        val updated = presenceDisabledTodayMap() - deviceId.toString()
        prefs.edit().putString(KEY_PRESENCE_DISABLED_TODAY, json.encodeToString(updated)).apply()
    }

    private fun presenceDisabledTodayMap(): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(prefs.getString(KEY_PRESENCE_DISABLED_TODAY, null) ?: "{}") }
            .getOrDefault(emptyMap())

    // --- Planning récurrent désactivé pour aujourd'hui (bouton ON/OFF, app ou bouton physique) ---

    /**
     * Vrai si un planning **récurrent** de ce canal a été désactivé pour aujourd'hui — même
     * principe que [isPresenceDisabledToday], s'efface tout seul le lendemain. Contrairement à la
     * présence, couvre aussi le bouton physique : un planning ne dépend d'aucun script Hestia
     * (juste des programmes cron natifs), un appui bouton pendant sa fenêtre est donc détectable
     * sans rien changer côté appareil — voir `DashboardViewModel` (`source` de `Switch.GetStatus`).
     * Jamais posé pour un planning Unique (pas de « lendemain » à distinguer).
     */
    fun isPlanningDisabledToday(deviceId: Long): Boolean =
        planningDisabledTodayMap()[deviceId.toString()] == LocalDate.now().toString()

    fun markPlanningDisabledToday(deviceId: Long) {
        val updated = planningDisabledTodayMap() + (deviceId.toString() to LocalDate.now().toString())
        prefs.edit().putString(KEY_PLANNING_DISABLED_TODAY, json.encodeToString(updated)).apply()
    }

    /** Même raison que [clearPresenceDisabledToday], côté planning récurrent : à appeler à
     * chaque création/modification d'un planning précis sur ce canal. */
    fun clearPlanningDisabledToday(deviceId: Long) {
        val updated = planningDisabledTodayMap() - deviceId.toString()
        prefs.edit().putString(KEY_PLANNING_DISABLED_TODAY, json.encodeToString(updated)).apply()
    }

    private fun planningDisabledTodayMap(): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(prefs.getString(KEY_PLANNING_DISABLED_TODAY, null) ?: "{}") }
            .getOrDefault(emptyMap())

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_NOTIFS = "notifications_enabled"
        const val KEY_NOTIFS_LAST_RUN = "notifications_last_run"
        const val KEY_NTFY_ENABLED = "ntfy_enabled"
        const val KEY_NTFY_TOPIC = "ntfy_topic"
        const val KEY_NTFY_GEN = "ntfy_generation"
        const val KEY_NTFY_SYNCED = "ntfy_synced_devices"
        const val KEY_SMOKE_RELAY_SYNCED = "smoke_relay_synced_devices"
        const val KEY_SMOKE_RELAY_COVERAGE_OK = "smoke_relay_coverage_ok"
        const val KEY_PENDING_TIMERS = "pending_timers"
        const val KEY_ON_TIME_BASELINE = "on_time_baseline"
        const val KEY_ON_SINCE_EPOCH = "on_since_epoch"
        const val KEY_PRESENCE_DISABLED_TODAY = "presence_disabled_today"
        const val KEY_PLANNING_DISABLED_TODAY = "planning_disabled_today"
        const val KEY_CLOUD_AUTH_KEY = "cloud_auth_key"
        const val KEY_CLOUD_SERVER = "cloud_server"
        val json = Json { ignoreUnknownKeys = true }
    }
}

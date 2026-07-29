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
        prefs.edit().putBoolean(KEY_NTFY_ENABLED, enabled).apply()
        _ntfyEnabled.value = enabled
    }

    /** Sujet ntfy, à traiter comme un mot de passe — stocké chiffré, jamais en clair. */
    private val _ntfyTopic = MutableStateFlow(securePrefs.getString(KEY_NTFY_TOPIC, null))
    val ntfyTopic: StateFlow<String?> = _ntfyTopic.asStateFlow()

    fun setNtfyTopic(topic: String?) {
        securePrefs.edit().putString(KEY_NTFY_TOPIC, topic).apply()
        _ntfyTopic.value = topic
    }

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

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_NOTIFS = "notifications_enabled"
        const val KEY_NOTIFS_LAST_RUN = "notifications_last_run"
        const val KEY_NTFY_ENABLED = "ntfy_enabled"
        const val KEY_NTFY_TOPIC = "ntfy_topic"
        const val KEY_PENDING_TIMERS = "pending_timers"
        val json = Json { ignoreUnknownKeys = true }
    }
}

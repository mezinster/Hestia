package kapoue.hestia.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.backup.BackupManager
import kapoue.hestia.data.backup.ImportResult
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.notifications.NotificationScheduler
import kapoue.hestia.data.notifications.NtfyClient
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.domain.model.ThemeMode
import kapoue.hestia.ui.common.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: DeviceRepository,
    private val backupManager: BackupManager,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    private val ntfyClient: NtfyClient,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> = appPreferences.themeMode

    fun setThemeMode(mode: ThemeMode) = appPreferences.setThemeMode(mode)

    val notificationsEnabled: StateFlow<Boolean> = appPreferences.notificationsEnabled

    /**
     * Active/désactive les notifications de bornes de programmation : mémorise la préférence et
     * programme (ou annule) le worker périodique. L'autorisation `POST_NOTIFICATIONS` est demandée
     * en amont côté écran ; ici on suppose qu'elle est accordée quand [enabled] vaut vrai.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        appPreferences.setNotificationsEnabled(enabled)
        if (enabled) NotificationScheduler.schedule(appContext) else NotificationScheduler.cancel(appContext)
        logger.info(DiagnosticLogger.UI, "Notifications de programmation ${if (enabled) "activées" else "désactivées"}")
    }

    /**
     * Réconcilie l'état de l'interrupteur avec l'autorisation système : si les notifications sont
     * marquées actives alors que l'autorisation n'est plus accordée (révoquée à la fermeture, ou
     * coupée depuis les réglages Android), on repasse à OFF pour éviter un interrupteur menteur.
     */
    fun reconcileNotifications(canPost: Boolean) {
        if (notificationsEnabled.value && !canPost) setNotificationsEnabled(false)
    }

    // --- Notifications instantanées via ntfy ---

    val ntfyEnabled: StateFlow<Boolean> = appPreferences.ntfyEnabled
    val ntfyTopic: StateFlow<String?> = appPreferences.ntfyTopic

    /**
     * Active/désactive ntfy. À l'activation, prend le relais du worker périodique (coupé s'il
     * était actif). Dans les deux sens, **recrée** les plannings et redéploie le script de
     * présence de chaque appareil joignable pour qu'ils reflètent le nouvel état — un appareil
     * injoignable à cet instant garde son ancien comportement jusqu'à sa prochaine modification
     * (pas de tâche de fond pour rattraper ça tout seul, cf. principe du projet).
     */
    fun setNtfyEnabled(enabled: Boolean) {
        appPreferences.setNtfyEnabled(enabled)
        if (enabled && notificationsEnabled.value) setNotificationsEnabled(false)
        logger.info(DiagnosticLogger.UI, "ntfy ${if (enabled) "activé" else "désactivé"}")
        resyncNtfy()
    }

    /** Sujet ntfy ; vide = équivalent à non configuré. Resynchronise si ntfy est déjà actif. */
    fun setNtfyTopic(topic: String) {
        appPreferences.setNtfyTopic(topic.trim().ifBlank { null })
        if (ntfyEnabled.value) resyncNtfy()
    }

    private fun resyncNtfy() {
        viewModelScope.launch { repository.resyncNtfyForAllDevices() }
    }

    /** Envoie une notif de test avec le texte choisi par l'utilisateur (pas configurable). */
    fun testNtfy() {
        val topic = ntfyTopic.value ?: return
        viewModelScope.launch {
            ntfyClient.send(topic, appContext.getString(R.string.app_name), appContext.getString(R.string.ntfy_test_message))
        }
    }

    /** Connectivité par appareil : null = en cours/inconnu, true = joignable, false = injoignable. */
    private val _connectivity = MutableStateFlow<Map<Long, Boolean?>>(emptyMap())
    val connectivity: StateFlow<Map<Long, Boolean?>> = _connectivity.asStateFlow()

    fun deleteDevice(device: Device) {
        viewModelScope.launch { repository.deleteDevice(device) }
    }

    /** Message transitoire (résultat export/import) à afficher puis consommer. */
    private val _backupMessage = MutableStateFlow<UserMessage?>(null)
    val backupMessage: StateFlow<UserMessage?> = _backupMessage.asStateFlow()

    fun consumeBackupMessage() {
        _backupMessage.value = null
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val ok = backupManager.export(uri)
            _backupMessage.value = UserMessage(
                if (ok) R.string.backup_export_success else R.string.backup_export_error,
            )
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = when (backupManager.import(uri)) {
                ImportResult.Success -> UserMessage(R.string.backup_import_success)
                ImportResult.InvalidFormat -> UserMessage(R.string.backup_import_invalid)
                ImportResult.ReadError -> UserMessage(R.string.backup_import_error)
            }
        }
    }

    /** Vérifie une fois la connectivité de chaque appareil (appelé à l'affichage/reprise). */
    fun checkConnectivity(permissionUsable: Boolean) {
        if (!permissionUsable) {
            _connectivity.value = emptyMap()
            return
        }
        viewModelScope.launch {
            val current = repository.getDevicesOnce()
            _connectivity.value = current.associate { it.id to null }
            for (device in current) {
                val online = repository.getStatus(device) is RpcResult.Success
                _connectivity.value = _connectivity.value + (device.id to online)
            }
        }
    }

    fun moveUp(device: Device) = move(device, -1)

    fun moveDown(device: Device) = move(device, +1)

    private fun move(device: Device, delta: Int) {
        val ordered = devices.value
        val index = ordered.indexOfFirst { it.id == device.id }
        val target = index + delta
        if (index < 0 || target < 0 || target >= ordered.size) return
        viewModelScope.launch { repository.swapPositions(ordered[index], ordered[target]) }
    }
}

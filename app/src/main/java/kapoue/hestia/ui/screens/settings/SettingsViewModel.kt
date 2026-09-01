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
import kapoue.hestia.data.cloud.ShellyCloudClient
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.notifications.NotificationScheduler
import kapoue.hestia.data.notifications.NtfyClient
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.ThemeMode
import kapoue.hestia.ui.common.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    private val cloudClient: ShellyCloudClient,
) : ViewModel() {

    // Même ordre que le Tableau (canaux d'un même appareil physique toujours groupés) — un
    // « monter »/« descendre » ci-dessous déplace donc bien un groupe entier, jamais un seul canal.
    val devices: StateFlow<List<Device>> = repository.observeDevices()
        .map { repository.groupedForDisplay(it) }
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
        repository.resyncNtfyForAllDevices()
    }

    /** Envoie une notif de test avec le texte choisi par l'utilisateur (pas configurable). */
    fun testNtfy() {
        val topic = ntfyTopic.value ?: return
        viewModelScope.launch {
            ntfyClient.send(topic, appContext.getString(R.string.app_name), appContext.getString(R.string.ntfy_test_message))
        }
    }

    // --- Cloud Shelly, repli à distance (opt-in, désactivé par défaut) ---

    val cloudAuthKey: StateFlow<String?> = appPreferences.cloudAuthKey
    val cloudServer: StateFlow<String?> = appPreferences.cloudServer

    private val _cloudTesting = MutableStateFlow(false)
    val cloudTesting: StateFlow<Boolean> = _cloudTesting.asStateFlow()

    private val _cloudTestMessage = MutableStateFlow<UserMessage?>(null)
    val cloudTestMessage: StateFlow<UserMessage?> = _cloudTestMessage.asStateFlow()

    fun consumeCloudTestMessage() {
        _cloudTestMessage.value = null
    }

    fun setCloudCredentials(authKey: String, server: String) {
        appPreferences.setCloudCredentials(authKey.trim().ifBlank { null }, server.trim().ifBlank { null })
    }

    fun clearCloudCredentials() {
        appPreferences.clearCloudCredentials()
        logger.info(DiagnosticLogger.UI, "Cloud Shelly (repli à distance) : identifiants effacés")
    }

    /**
     * Valide clé + serveur sans dépendre d'un appareil réel (voir [ShellyCloudClient.testAuth]) —
     * enregistre d'abord la saisie courante pour tester la bonne valeur, comme pour ntfy.
     */
    fun testCloudKey(authKey: String, server: String) {
        val trimmedKey = authKey.trim()
        val trimmedServer = server.trim().removePrefix("https://").removePrefix("http://")
        if (trimmedKey.isBlank() || trimmedServer.isBlank()) return
        setCloudCredentials(trimmedKey, trimmedServer)
        viewModelScope.launch {
            _cloudTesting.value = true
            val ok = cloudClient.testAuth(trimmedServer, trimmedKey)
            logger.info(DiagnosticLogger.UI, "Cloud Shelly (repli à distance) : test clé → ${if (ok) "réussi" else "échoué"}")
            _cloudTesting.value = false
            _cloudTestMessage.value = UserMessage(
                if (ok) R.string.settings_cloud_test_success else R.string.settings_cloud_test_error,
            )
        }
    }

    /** Connectivité par appareil : null = en cours/inconnu, true = joignable, false = injoignable. */
    private val _connectivity = MutableStateFlow<Map<Long, Boolean?>>(emptyMap())
    val connectivity: StateFlow<Map<Long, Boolean?>> = _connectivity.asStateFlow()

    /** IP effectivement utilisée par appareil (1ᵉʳ ou 2ᵉ emplacement selon la bascule automatique). */
    private val _activeIp = MutableStateFlow<Map<Long, String>>(emptyMap())
    val activeIp: StateFlow<Map<Long, String>> = _activeIp.asStateFlow()

    fun deleteDevice(device: Device) {
        viewModelScope.launch { repository.deleteDevice(device) }
    }

    /** Supprime tous les canaux d'un même appareil physique (bloc multi-canaux) d'un coup. */
    fun deleteDeviceGroup(members: List<Device>) {
        viewModelScope.launch { repository.deleteDeviceGroup(members) }
    }

    /**
     * Renomme un seul canal (ex. « Frigo ») — sans toucher au nom partagé de l'appareil. Le
     * nouveau nom est aussi celui qui apparaît dans les notifications ntfy (planning, présence,
     * bouton) : sans le redéploiement qui suit, elles auraient gardé l'ancien nom indéfiniment —
     * exactement le même bug que celui déjà corrigé pour le renommage depuis l'écran Modifier
     * (2026-08-19), sur ce chemin-ci distinct qui ne l'appelait pas encore.
     */
    fun renameChannel(device: Device, newName: String) {
        viewModelScope.launch {
            val updated = device.copy(name = newName)
            repository.updateDevice(updated)
            repository.resyncDeviceName(updated)
        }
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
                // Détecteur de fumée : Switch.GetStatus n'a aucun sens pour ce type (voir
                // SMOKE-DETECTOR.md), retournait toujours faux — chemin dédié, même principe que
                // partout ailleurs pour ce type d'appareil.
                val online = if (device.type == DeviceType.SMOKE_DETECTOR) {
                    repository.getSensorStatus(device).result is RpcResult.Success
                } else {
                    repository.getStatus(device).result is RpcResult.Success
                }
                _connectivity.value = _connectivity.value + (device.id to online)
                _activeIp.value = _activeIp.value + (device.id to repository.activeIp(device))
            }
        }
    }

    fun moveUp(device: Device) = move(device, -1)

    fun moveDown(device: Device) = move(device, +1)

    /**
     * Déplace le **groupe entier** (tous les canaux du même appareil physique, même IP) auquel
     * appartient [device], jamais un seul canal isolé — sinon un bloc multi-canaux (ex. Strip 4)
     * se retrouverait de nouveau mélangé avec un autre appareil au premier réordonnancement.
     */
    private fun move(device: Device, delta: Int) {
        val groups = devices.value.groupBy { it.ipAddress }.entries.toList()
        val groupIndex = groups.indexOfFirst { (ip, _) -> ip == device.ipAddress }
        val targetIndex = groupIndex + delta
        if (groupIndex < 0 || targetIndex < 0 || targetIndex >= groups.size) return
        val reordered = groups.toMutableList()
        val moved = reordered.removeAt(groupIndex)
        reordered.add(targetIndex, moved)
        val flat = reordered.flatMap { it.value }
        viewModelScope.launch { repository.reorderDevices(flat) }
    }
}

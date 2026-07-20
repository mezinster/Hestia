package kapoue.hestia.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.backup.BackupManager
import kapoue.hestia.data.backup.ImportResult
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
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
    private val repository: DeviceRepository,
    private val backupManager: BackupManager,
    private val logger: DiagnosticLogger,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

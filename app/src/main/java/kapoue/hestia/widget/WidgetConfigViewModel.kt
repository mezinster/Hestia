package kapoue.hestia.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    repository: DeviceRepository,
) : ViewModel() {
    val devices: StateFlow<List<Device>> = repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

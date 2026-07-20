package kapoue.hestia.ui.screens.diagnostic

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticLogDao: DiagnosticLogDao,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()

    /**
     * Construit le rapport partageable : en-tête de contexte + entrées horodatées (ISO 8601,
     * de la plus récente à la plus ancienne). [themeLabel] et [permissionLabel] proviennent de
     * l'UI (thème et libellé de permission traduits).
     */
    fun load(themeLabel: String, permissionLabel: String) {
        viewModelScope.launch {
            _report.value = buildReport(themeLabel, permissionLabel)
        }
    }

    private suspend fun buildReport(themeLabel: String, permissionLabel: String): String {
        val entries = diagnosticLogDao.getAll()
        val deviceCount = deviceRepository.getDevicesOnce().size
        val sb = StringBuilder()
        sb.appendLine("Hestia — journal de diagnostic")
        sb.appendLine("Application : ${appVersion()}")
        sb.appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Langue : ${Locale.getDefault()}")
        sb.appendLine("Thème : $themeLabel")
        sb.appendLine("Permission réseau local : $permissionLabel")
        sb.appendLine("Appareils configurés : $deviceCount")
        sb.appendLine("----")
        for (entry in entries) {
            sb.append(ISO.format(Instant.ofEpochMilli(entry.timestamp)))
            sb.append("  ").append(entry.level.padEnd(5))
            sb.append(" ").append(entry.tag.padEnd(8))
            sb.append(" ").appendLine(entry.message)
        }
        return sb.toString()
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("?")

    private companion object {
        val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}

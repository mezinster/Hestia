package kapoue.hestia.ui.screens.diagnostic

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.domain.model.DeviceType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _report = MutableStateFlow("")
    val report: StateFlow<String> = _report.asStateFlow()

    /**
     * Construit le rapport partageable : en-tête de contexte + liste des appareils (avec
     * version de firmware lue **en direct**, en parallèle) + entrées horodatées (ISO 8601, de la
     * plus récente à la plus ancienne). [themeLabel] et [permissionLabel] proviennent de l'UI
     * (thème et libellé de permission traduits).
     *
     * Contrairement au reste de l'écran, la lecture du firmware sort du pur local (appel RPC vers
     * chaque appareil, jamais vers Shelly) : un appareil injoignable au moment précis de
     * l'ouverture n'empêche pas le reste du rapport, il affiche juste « injoignable ».
     */
    fun load(themeLabel: String, permissionLabel: String) {
        viewModelScope.launch {
            _report.value = buildReport(themeLabel, permissionLabel)
        }
    }

    private suspend fun buildReport(themeLabel: String, permissionLabel: String): String {
        val entries = diagnosticLogDao.getAll()
        val devices = deviceRepository.getDevicesOnce()
        val notificationsLabel = context.getString(
            if (appPreferences.notificationsEnabled.value) R.string.diagnostic_report_enabled else R.string.diagnostic_report_disabled,
        )

        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.diagnostic_report_header))
        sb.appendLine(context.getString(R.string.diagnostic_report_app, appVersion()))
        sb.appendLine(context.getString(R.string.diagnostic_report_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        sb.appendLine(context.getString(R.string.diagnostic_report_device, Build.MANUFACTURER, Build.MODEL))
        sb.appendLine(context.getString(R.string.diagnostic_report_language, Locale.getDefault().toString()))
        sb.appendLine(context.getString(R.string.diagnostic_report_theme, themeLabel))
        sb.appendLine(context.getString(R.string.diagnostic_report_permission, permissionLabel))
        sb.appendLine(context.getString(R.string.diagnostic_report_device_count, devices.size))
        sb.appendLine(context.getString(R.string.diagnostic_report_notifications, notificationsLabel))

        if (devices.isNotEmpty()) {
            sb.appendLine(context.getString(R.string.diagnostic_report_devices_header))
            for (line in deviceLines(devices)) sb.appendLine(line)
        }

        sb.appendLine("----")
        for (entry in entries) {
            sb.append(ISO.format(Instant.ofEpochMilli(entry.timestamp)))
            sb.append("  ").append(entry.level.padEnd(5))
            sb.append(" ").append(entry.tag.padEnd(8))
            sb.append(" ").appendLine(entry.message)
        }
        return sb.toString()
    }

    /** Une ligne par appareil, firmware relu **en parallèle** (pas séquentiellement). */
    private suspend fun deviceLines(devices: List<Device>): List<String> = coroutineScope {
        devices.map { device ->
            async {
                val firmware = deviceRepository.getInstalledFirmwareVersion(device)
                    ?: context.getString(R.string.diagnostic_report_firmware_unreachable)
                context.getString(
                    R.string.diagnostic_report_device_line,
                    device.name,
                    device.ipAddress,
                    context.getString(deviceTypeLabelRes(device.type)),
                    firmware,
                )
            }
        }.awaitAll()
    }

    private fun deviceTypeLabelRes(type: DeviceType): Int = when (type) {
        DeviceType.PLUG -> R.string.device_type_plug
        DeviceType.LAMP -> R.string.device_type_dimmer
        DeviceType.SENSOR -> R.string.device_type_sensor
        DeviceType.SMOKE_DETECTOR -> R.string.device_type_smoke_detector
        DeviceType.SHUTTER -> R.string.device_type_shutter
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

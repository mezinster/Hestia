package kapoue.hestia.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PresenceConfig
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Résultat d'un import (aucune donnée modifiée sauf en cas de [Success]). */
sealed interface ImportResult {
    data object Success : ImportResult
    data object InvalidFormat : ImportResult
    data object ReadError : ImportResult
}

/**
 * Export/import de la configuration via un fichier JSON (SPEC-V1 § 6).
 * Import = **remplacement intégral** ; la validation du format précède toute modification.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceDao: DeviceDao,
    private val presenceConfigDao: PresenceConfigDao,
    private val json: Json,
) {
    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val devices = deviceDao.getAllOnce()
            val deviceBackups = devices.map { it.toBackup() }
            val configBackups = devices.mapNotNull { device ->
                presenceConfigDao.getForDevice(device.id)?.toBackup(device)
            }
            val backup = BackupFile(
                format = FORMAT,
                formatVersion = FORMAT_VERSION,
                appVersion = appVersion(),
                exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                devices = deviceBackups,
                presenceConfigs = configBackups,
            )
            val text = json.encodeToString(BackupFile.serializer(), backup)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return@withContext ImportResult.ReadError

        val backup = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
            ?: return@withContext ImportResult.InvalidFormat
        if (backup.format != FORMAT || backup.formatVersion != FORMAT_VERSION) {
            return@withContext ImportResult.InvalidFormat
        }

        runCatching {
            // Remplacement intégral : CASCADE purge présence + journal d'activité.
            deviceDao.deleteAll()
            val idByChannel = HashMap<Pair<String, Int>, Long>()
            for (deviceBackup in backup.devices) {
                val newId = deviceDao.insert(deviceBackup.toEntity())
                idByChannel[deviceBackup.ipAddress to deviceBackup.switchId] = newId
            }
            for (configBackup in backup.presenceConfigs) {
                val deviceId = idByChannel[configBackup.deviceIp to configBackup.deviceSwitchId] ?: continue
                presenceConfigDao.upsert(configBackup.toEntity(deviceId))
            }
        }.fold(
            onSuccess = { ImportResult.Success },
            onFailure = { ImportResult.ReadError },
        )
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "?"
    }.getOrDefault("?")

    private companion object {
        const val FORMAT = "hestia-backup"
        const val FORMAT_VERSION = 1
    }
}

private fun Device.toBackup() = DeviceBackup(
    name = name,
    ipAddress = ipAddress,
    switchId = switchId,
    type = type.name,
    driver = driver.name,
    model = model,
    supportsSwitch = supportsSwitch,
    hasScripting = hasScripting,
    hasPowerMetering = hasPowerMetering,
    position = position,
    presetDurationSeconds = presetDurationSeconds,
    presetThresholdW = presetThresholdW,
    presetName = presetName,
    preset2DurationSeconds = preset2DurationSeconds,
    preset2ThresholdW = preset2ThresholdW,
    preset2Name = preset2Name,
)

private fun DeviceBackup.toEntity() = Device(
    name = name,
    ipAddress = ipAddress,
    switchId = switchId,
    type = runCatching { DeviceType.valueOf(type) }.getOrDefault(DeviceType.PLUG),
    driver = runCatching { DriverType.valueOf(driver) }.getOrDefault(DriverType.SHELLY_GEN2),
    model = model,
    supportsSwitch = supportsSwitch,
    hasScripting = hasScripting,
    hasPowerMetering = hasPowerMetering,
    position = position,
    presetDurationSeconds = presetDurationSeconds,
    presetThresholdW = presetThresholdW,
    presetName = presetName,
    preset2DurationSeconds = preset2DurationSeconds,
    preset2ThresholdW = preset2ThresholdW,
    preset2Name = preset2Name,
)

private fun PresenceConfig.toBackup(device: Device) = PresenceConfigBackup(
    deviceIp = device.ipAddress,
    deviceSwitchId = device.switchId,
    startHour = startHour,
    startMinute = startMinute,
    endHour = endHour,
    endMinute = endMinute,
    randomMarginMinutes = randomMarginMinutes,
    shellyScriptId = shellyScriptId,
    enabled = enabled,
)

private fun PresenceConfigBackup.toEntity(deviceId: Long) = PresenceConfig(
    deviceId = deviceId,
    startHour = startHour,
    startMinute = startMinute,
    endHour = endHour,
    endMinute = endMinute,
    randomMarginMinutes = randomMarginMinutes,
    shellyScriptId = shellyScriptId,
    enabled = enabled,
)

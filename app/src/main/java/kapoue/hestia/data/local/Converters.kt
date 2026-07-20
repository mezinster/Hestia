package kapoue.hestia.data.local

import androidx.room.TypeConverter
import kapoue.hestia.domain.model.ActivationAction
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType

/** Conversion des enums métier en chaînes stables pour Room (robuste aux réordonnancements). */
class Converters {
    @TypeConverter fun deviceTypeToString(value: DeviceType): String = value.name
    @TypeConverter fun stringToDeviceType(value: String): DeviceType = DeviceType.valueOf(value)

    @TypeConverter fun driverTypeToString(value: DriverType): String = value.name
    @TypeConverter fun stringToDriverType(value: String): DriverType = DriverType.valueOf(value)

    @TypeConverter fun activationActionToString(value: ActivationAction): String = value.name
    @TypeConverter fun stringToActivationAction(value: String): ActivationAction =
        ActivationAction.valueOf(value)
}

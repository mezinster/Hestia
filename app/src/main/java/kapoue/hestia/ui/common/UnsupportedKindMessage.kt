package kapoue.hestia.ui.common

import kapoue.hestia.R
import kapoue.hestia.data.rpc.UnsupportedKind

/** Message affiché quand l'appareil sondé n'a aucun relais pilotable (voir [UnsupportedKind]). */
fun UnsupportedKind.toUserMessage(): UserMessage = UserMessage(
    when (this) {
        UnsupportedKind.COVER -> R.string.error_unsupported_cover
        UnsupportedKind.LIGHT -> R.string.error_unsupported_light
        UnsupportedKind.ENERGY_METER -> R.string.error_unsupported_energy_meter
        UnsupportedKind.SMOKE_DETECTOR -> R.string.error_unsupported_smoke_detector
        UnsupportedKind.SENSOR -> R.string.error_unsupported_sensor
        UnsupportedKind.INPUT_ONLY -> R.string.error_unsupported_input_only
        UnsupportedKind.UNKNOWN -> R.string.error_unsupported_unknown
    },
)

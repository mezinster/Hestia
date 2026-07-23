package kapoue.hestia.core.util

/**
 * Formate une puissance en watts avec une décimale : « 0.0 W », « 479.3 W ».
 * Séparateur décimal **toujours un point** (Locale.US), en français comme en anglais : choix
 * produit, pour un affichage technique homogène quelle que soit la langue de l'appareil.
 */
fun formatPower(watts: Double): String = String.format(java.util.Locale.US, "%.1f W", watts)

/** Formate une plage horaire pour l'affichage : « 9h00 - 11h00 ». */
fun formatTimeRange(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): String =
    "%dh%02d - %dh%02d".format(startHour, startMinute, endHour, endMinute)

/** Formate une durée en secondes pour un compte à rebours : « M:SS » ou « H:MM:SS ». */
fun formatCountdown(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

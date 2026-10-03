package kapoue.hestia.core.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Formate une puissance en watts avec une décimale : « 0.0 W », « 479.3 W ».
 * Séparateur décimal **toujours un point** (Locale.US), en français comme en anglais : choix
 * produit, pour un affichage technique homogène quelle que soit la langue de l'appareil.
 */
fun formatPower(watts: Double): String = String.format(java.util.Locale.US, "%.1f W", watts)

/**
 * Formate une heure selon la locale de l'appareil (« 09:00 » en français, « 9:00 AM » en
 * anglais) plutôt qu'un format français codé en dur — nécessaire pour la traduction anglaise.
 */
fun formatClockTime(hour: Int, minute: Int): String =
    LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Variante à partir des minutes depuis minuit (0..1439), format déjà utilisé par les bornes. */
fun formatClockTime(totalMinutes: Int): String = formatClockTime(totalMinutes / 60, totalMinutes % 60)

/** Formate une date selon la locale de l'appareil (« 30/07/26 » en français, « 7/30/26 » en anglais). */
fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))

/** Formate une plage horaire pour l'affichage : « 9:00 AM – 11:00 AM » (selon la locale). */
fun formatTimeRange(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): String =
    "${formatClockTime(startHour, startMinute)} – ${formatClockTime(endHour, endMinute)}"

/** Formate une durée en secondes pour un compte à rebours : « M:SS » ou « H:MM:SS ». */
fun formatCountdown(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

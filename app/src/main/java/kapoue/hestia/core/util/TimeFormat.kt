package kapoue.hestia.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

/** Horodatage court pour le journal d'activité (ex. « 19/07 14:22 »). */
fun formatLogTimestamp(epochMillis: Long): String =
    logFormatter.format(Instant.ofEpochMilli(epochMillis))

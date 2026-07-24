package kapoue.hestia.data.rpc

/**
 * Encodage/décodage des programmes cron Shelly pour les plannings.
 *
 * Format `timespec` observé sur Plug M Gen3 : « sec min heure jour-mois mois jour-semaine ».
 * Le jour de la semaine suit le cron standard : 0 = dimanche … 6 = samedi, `*` = tous les jours.
 * La prise stocke le champ tel qu'envoyé (validé au curl) : on écrit une forme canonique et on
 * sait la relire, tout en tolérant les variantes (`1-5`, `0,6`) qui pourraient venir de
 * l'interface web native.
 */
object ScheduleCodec {

    val ALL_DAYS: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 6)

    /** timespec d'un déclenchement quotidien à [hour]:[minute] sur les [days] donnés. */
    fun timespec(hour: Int, minute: Int, days: Set<Int>): String =
        "0 $minute $hour * * ${encodeDays(days)}"

    /** Forme canonique du champ jours : `*` pour tous les jours, sinon liste triée « 1,2,5 ». */
    fun encodeDays(days: Set<Int>): String =
        if (days.size >= 7 || days.isEmpty()) "*" else days.sorted().joinToString(",")

    /** Reconstruit (heure, minute, jours) depuis un timespec, ou null si le format n'est pas géré. */
    fun parse(timespec: String): Parsed? {
        val f = timespec.trim().split(Regex("\\s+"))
        if (f.size < 6) return null
        val minute = f[1].toIntOrNull() ?: return null
        val hour = f[2].toIntOrNull() ?: return null
        val days = decodeDays(f[5]) ?: return null
        return Parsed(hour, minute, days)
    }

    /** Décode le champ jours en tolérant `*`, les listes « 0,6 » et les intervalles « 1-5 ». */
    private fun decodeDays(field: String): Set<Int>? {
        if (field == "*") return ALL_DAYS
        val out = mutableSetOf<Int>()
        for (token in field.split(",")) {
            if ("-" in token) {
                val bounds = token.split("-")
                val start = bounds.getOrNull(0)?.toIntOrNull() ?: return null
                val end = bounds.getOrNull(1)?.toIntOrNull() ?: return null
                for (d in start..end) out += d.mod(7) // 7 → 0 : certains firmwares notent dimanche 7
            } else {
                out += (token.toIntOrNull() ?: return null).mod(7)
            }
        }
        return out.ifEmpty { null }
    }

    data class Parsed(val hour: Int, val minute: Int, val days: Set<Int>)
}

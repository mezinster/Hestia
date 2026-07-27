package kapoue.hestia.data.rpc

import java.time.Instant
import java.time.ZoneId

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

    /** Jours décalés au lendemain (pour l'extinction d'un créneau qui passe minuit). */
    fun nextDay(days: Set<Int>): Set<Int> = days.map { (it + 1) % 7 }.toSet()

    private const val WEEK = 7 * 1440 // minutes dans une semaine

    /**
     * Développe un créneau en intervalles [début, fin) sur une semaine (minutes 0..10080), un par
     * jour actif. Un créneau qui passe minuit (fin < début) s'étend sur le jour suivant et est
     * découpé à la frontière de la semaine. Permet un test de chevauchement uniforme jour/nuit.
     */
    fun weeklyIntervals(startMin: Int, endMin: Int, days: Set<Int>): List<Pair<Int, Int>> {
        val overnight = endMin < startMin
        val out = mutableListOf<Pair<Int, Int>>()
        for (d in days) {
            val base = d * 1440
            val s = base + startMin
            val e = if (overnight) base + 1440 + endMin else base + endMin
            if (e <= WEEK) out += s to e else { out += s to WEEK; out += 0 to (e - WEEK) }
        }
        return out
    }

    /** Vrai si deux ensembles d'intervalles hebdomadaires se recouvrent. */
    fun intervalsOverlap(a: List<Pair<Int, Int>>, b: List<Pair<Int, Int>>): Boolean =
        a.any { (aStart, aEnd) -> b.any { (bStart, bEnd) -> aStart < bEnd && bStart < aEnd } }

    enum class Boundary { START, END }

    /**
     * Instant (ms) auquel la borne [which] d'une fenêtre récurrente (début → début+durée) tombe
     * dans l'intervalle `(from, to]`, ou `null` si aucune occurrence. Gère les créneaux de nuit :
     * la fin est calculée comme début + durée, donc elle bascule naturellement au lendemain sans
     * qu'il faille décaler [activeDays] séparément (comme le fait [nextDay] pour Schedule natif).
     *
     * [activeDays] : jours d'activation du **début** (0 = dimanche … 6 = samedi). Renvoie la
     * première occurrence trouvée. Partagé entre le worker de notifications (bornes franchies
     * depuis son dernier passage) et le journal d'activité (déduction de la cause d'une bascule).
     */
    fun boundaryInstant(
        startMinute: Int,
        endMinute: Int,
        activeDays: Set<Int>,
        which: Boundary,
        from: Long,
        to: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val durationMin = ((endMinute - startMinute + 1440 - 1) % 1440) + 1
        var day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val lastDay = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val cronDay = day.dayOfWeek.value % 7 // lundi=1 … dimanche=7 → 0
            if (cronDay in activeDays) {
                val startAbs = day.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
                val t = if (which == Boundary.START) startAbs else startAbs + durationMin * 60_000L
                if (t > from && t <= to) return t
            }
            day = day.plusDays(1)
        }
        return null
    }
}

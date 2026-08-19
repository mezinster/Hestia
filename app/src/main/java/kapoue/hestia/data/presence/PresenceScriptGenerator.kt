package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport
import kapoue.hestia.domain.model.PresenceWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Génère (et relit) le script JavaScript de simulation de présence poussé sur l'appareil Shelly.
 * Structure de base **validée sur Plug M Gen3** (Timer.set, Date, Math.random, Shelly.call).
 *
 * Le script porte **plusieurs plages** et embarque sa configuration dans une ligne-marqueur
 * (`// hestia_windows:[[début,fin,marge],…]`), ce qui permet de la **relire** depuis n'importe
 * quel téléphone (Hestia ne stocke pas la config en propre — on lit l'état réel de l'appareil).
 *
 * Chaque minute, le script replanifie les allumages/extinctions du jour (avec marge aléatoire par
 * plage) et pilote le relais s'il tombe dans l'une des plages. Une plage dont la fin précède le
 * début passe minuit (ex. 22h → 6h).
 */
object PresenceScriptGenerator {

    /**
     * Nom réservé du script Hestia, **par canal** — un appareil multi-canaux (ex. Strip 4) partage
     * un seul moteur de scripts entre tous ses relais : un nom fixe ferait retrouver/écraser le
     * script d'un autre canal du même appareil au lieu du sien (bug vécu en direct, corrigé).
     * Ne jamais toucher un script portant un autre nom.
     */
    fun scriptName(switchId: Int): String = "hestia_presence_$switchId"

    private const val MARKER = "// hestia_windows:"

    /**
     * [ntfyTopic] non nul = notifie via ntfy à chaque bascule ([ntfyTitle] = nom de la prise,
     * [ntfyStartBody]/[ntfyEndBody] = textes début/fin). Toujours après `Switch.Set`.
     *
     * Chaque plage porte désormais ses propres [PresenceWindow.days] (2026-08-18, fusion Planning/
     * Présence) — `Date.getDay()` en JS suit la même convention (0 = dimanche … 6 = samedi), aucune
     * conversion nécessaire. Un créneau de nuit (qui passe minuit) est actif soit le soir d'un jour
     * autorisé, soit le matin qui suit un jour autorisé (même logique que [kapoue.hestia.domain.
     * model.Planning.isActiveNow], traduite en JS) — l'heure de bascule du matin réutilise le tirage
     * aléatoire du jour courant (même approximation que le reste du script, qui ne garde qu'un seul
     * jour de tirages à la fois).
     */
    fun generate(
        windows: List<PresenceWindow>,
        switchId: Int,
        ntfyTopic: String? = null,
        ntfyTitle: String = "",
        ntfyStartBody: String = "",
        ntfyEndBody: String = "",
    ): String {
        // [début(min), fin(min), marge(min), [jours]] par plage — même donnée pour le marqueur et le runtime.
        val arr = windows.joinToString(",", "[", "]") {
            "[${it.startMinutes},${it.endMinutes},${it.marginMinutes},[${it.days.sorted().joinToString(",")}]]"
        }
        val ntfyStart = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyStartBody)
        val ntfyEnd = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyEndBody)
        return """
            // Généré par Hestia — simulation de présence
            $MARKER$arr
            let CFG = { switchId: $switchId };
            let WINDOWS = $arr; // par plage : [début(min), fin(min), marge(min), [jours]]
            let planned = { day: null, on: [], off: [] };

            function rnd(m) { return Math.floor(Math.random() * (2 * m + 1)) - m; }

            function planDay(day) {
              planned.day = day;
              planned.on = [];
              planned.off = [];
              for (let i = 0; i < WINDOWS.length; i++) {
                planned.on.push(WINDOWS[i][0] + rnd(WINDOWS[i][2]));
                planned.off.push(WINDOWS[i][1] + rnd(WINDOWS[i][2]));
              }
            }

            Timer.set(60000, true, function () {
              let sys = Shelly.getComponentStatus("sys");
              if (!sys || !sys.unixtime) return;
              let d = new Date(sys.unixtime * 1000);
              let now = d.getHours() * 60 + d.getMinutes();
              let today = d.getDay();
              let yesterday = (today + 6) % 7;
              let day = Math.floor(sys.unixtime / 86400);
              if (planned.day !== day) planDay(day);
              let st = Shelly.getComponentStatus("switch", CFG.switchId);
              if (!st) return;
              let inWin = false;
              for (let i = 0; i < WINDOWS.length; i++) {
                let onAt = planned.on[i];
                let offAt = planned.off[i];
                let over = WINDOWS[i][0] > WINDOWS[i][1];
                let days = WINDOWS[i][3];
                let appliesToday = days.indexOf(today) >= 0;
                let appliesYesterday = days.indexOf(yesterday) >= 0;
                let w = over
                  ? ((appliesToday && now >= onAt) || (appliesYesterday && now < offAt))
                  : (appliesToday && now >= onAt && now < offAt);
                if (w) { inWin = true; break; }
              }
              if (inWin && !st.output) { Shelly.call("Switch.Set", { id: CFG.switchId, on: true }); $ntfyStart }
              if (!inWin && st.output) { Shelly.call("Switch.Set", { id: CFG.switchId, on: false }); $ntfyEnd }
            });
        """.trimIndent()
    }

    /**
     * Relit les plages depuis le code d'un script (ligne-marqueur). Retourne null si le marqueur
     * est absent ou illisible (ex. script d'une version antérieure) — l'appelant décide alors quoi
     * afficher (« plages inconnues »).
     */
    fun parse(code: String): List<PresenceWindow>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        val payload = line.trim().removePrefix(MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<List<JsonElement>>>(payload) }.getOrNull() ?: return null
        return rows.mapNotNull { r ->
            if (r.size < 3) return@mapNotNull null
            val start = r[0].jsonPrimitive.intOrNull ?: return@mapNotNull null
            val end = r[1].jsonPrimitive.intOrNull ?: return@mapNotNull null
            val margin = r[2].jsonPrimitive.intOrNull ?: return@mapNotNull null
            // Marqueur d'avant le 2026-08-18 (fusion Planning/Présence) : pas de 4ᵉ élément, la
            // plage s'appliquait tous les jours — on le retrouve à l'identique plutôt que de la
            // faire disparaître silencieusement d'un appareil déjà configuré.
            val days = (r.getOrNull(3) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.toSet()
                ?: (0..6).toSet()
            PresenceWindow(
                startHour = start / 60, startMinute = start % 60,
                endHour = end / 60, endMinute = end % 60,
                marginMinutes = margin,
                days = days,
            )
        }
    }
}

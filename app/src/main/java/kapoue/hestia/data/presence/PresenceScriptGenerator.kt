package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport
import kapoue.hestia.domain.model.PresenceWindow
import kotlinx.serialization.json.Json

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
     */
    fun generate(
        windows: List<PresenceWindow>,
        switchId: Int,
        ntfyTopic: String? = null,
        ntfyTitle: String = "",
        ntfyStartBody: String = "",
        ntfyEndBody: String = "",
    ): String {
        // [début(min), fin(min), marge(min)] par plage — même donnée pour le marqueur et le runtime.
        val arr = windows.joinToString(",", "[", "]") {
            "[${it.startMinutes},${it.endMinutes},${it.marginMinutes}]"
        }
        val ntfyStart = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyStartBody)
        val ntfyEnd = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyEndBody)
        return """
            // Généré par Hestia — simulation de présence
            $MARKER$arr
            let CFG = { switchId: $switchId };
            let WINDOWS = $arr; // par plage : [début(min), fin(min), marge(min)]
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
              let day = Math.floor(sys.unixtime / 86400);
              if (planned.day !== day) planDay(day);
              let st = Shelly.getComponentStatus("switch", CFG.switchId);
              if (!st) return;
              let inWin = false;
              for (let i = 0; i < WINDOWS.length; i++) {
                let onAt = planned.on[i];
                let offAt = planned.off[i];
                let over = WINDOWS[i][0] > WINDOWS[i][1];
                let w = over ? (now >= onAt || now < offAt) : (now >= onAt && now < offAt);
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
        val rows = runCatching { Json.decodeFromString<List<List<Int>>>(payload) }.getOrNull() ?: return null
        return rows.mapNotNull { r ->
            if (r.size < 3) return@mapNotNull null
            PresenceWindow(
                startHour = r[0] / 60, startMinute = r[0] % 60,
                endHour = r[1] / 60, endMinute = r[1] % 60,
                marginMinutes = r[2],
            )
        }
    }
}

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
 * **Un seul script par appareil physique** (2026-08-18, script « superviseur », même refonte que
 * le minuteur bouton et la coupure sur seuil) : le moteur de scripts Shelly n'autorise que 3
 * scripts activés simultanément par appareil — un script par canal aurait saturé cette limite dès
 * qu'un bloc multi-canaux avait la présence configurée sur plusieurs canaux en même temps que le
 * bouton et/ou le seuil. Un seul script surveille désormais tous les canaux configurés à la fois,
 * chacun avec ses propres plages et son propre état.
 *
 * Ajouter/retirer un canal sur un script déjà **en cours d'exécution** passe exclusivement par
 * [evalUpsertChannel]/[evalRemoveChannel] via `Script.Eval` (voir `DeviceRepository`) — jamais par
 * [generateSupervisor], qui redéploierait tout le script et réinitialiserait la mémoire de tous
 * les canaux déjà suivis (bug de conception vécu et corrigé sur le bouton et le seuil avant
 * d'attaquer ce lot, voir BACKLOG.md). [generateSupervisor] ne sert donc qu'au tout premier
 * déploiement (aucun script existant, ou existant mais arrêté).
 */
object PresenceScriptGenerator {

    /** Un seul nom, fixe : un seul script de ce type par appareil physique. */
    const val SCRIPT_NAME = "hestia_presence"

    private const val SUPERVISOR_MARKER = "// hestia_presence_supervisor:"

    /**
     * Nom **hérité** (avant le 2026-08-18), un par canal — reconnu uniquement pour migrer une
     * configuration existante vers le script partagé, jamais recréé. Voir [DeviceRepository.
     * loadOrMigratePresenceScript].
     */
    fun legacyScriptName(switchId: Int): String = "hestia_presence_$switchId"

    private const val LEGACY_MARKER = "// hestia_windows:"

    /**
     * Config d'un canal suivi par le superviseur. [windows] : ses plages, chacune avec ses propres
     * jours (2026-08-18, fusion Planning/Présence — `Date.getDay()` en JS suit déjà la même
     * convention que [PresenceWindow.days], 0 = dimanche … 6 = samedi, aucune conversion). [name]
     * sert uniquement au titre des notifications ntfy de ce canal.
     */
    data class ChannelConfig(
        val switchId: Int,
        val name: String,
        val windows: List<PresenceWindow>,
    )

    /** Littéral JS des plages d'un canal : `[[début,fin,marge,[jours]],…]`. */
    private fun windowsLiteral(windows: List<PresenceWindow>): String =
        windows.joinToString(",", "[", "]") {
            "[${it.startMinutes},${it.endMinutes},${it.marginMinutes},[${it.days.sorted().joinToString(",")}]]"
        }

    /** Littéral JS d'un canal, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private fun channelLiteral(c: ChannelConfig): String =
        "{ switchId: ${c.switchId}, name: \"${NtfyScriptSupport.jsString(c.name)}\", windows: ${windowsLiteral(c.windows)} }"

    /** Littéral JS d'un état de suivi vierge, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private const val FRESH_STATE = "{ day: null, on: [], off: [] }"

    /**
     * Premier déploiement du script partagé (aucun script existant, ou existant mais arrêté) —
     * [configs] contient en pratique un seul canal (celui qu'on vient de configurer). [ntfyTopic]
     * non nul = notifie via ntfy à chaque bascule ([ntfyStartBody]/[ntfyEndBody] = textes début/
     * fin, génériques : plusieurs plages possibles par canal, le script ne sait pas laquelle a
     * déclenché au moment de basculer — seul le titre, le nom du canal, varie, résolu à
     * l'exécution comme pour le minuteur bouton et le seuil).
     *
     * Un créneau de nuit (qui passe minuit) est actif soit le soir d'un jour autorisé, soit le
     * matin qui suit un jour autorisé (même logique que [kapoue.hestia.domain.model.Planning.
     * isActiveNow], traduite en JS) — l'heure de bascule du matin réutilise le tirage aléatoire du
     * jour courant (le script ne garde qu'un seul jour de tirages à la fois par canal).
     */
    fun generateSupervisor(
        configs: List<ChannelConfig>,
        ntfyTopic: String? = null,
        ntfyStartBody: String = "",
        ntfyEndBody: String = "",
    ): String {
        val marker = configs.joinToString(",", "[", "]") { "[${it.switchId},${windowsLiteral(it.windows)}]" }
        val cfgArray = configs.joinToString(",\n          ", "[\n          ", "\n        ]") { channelLiteral(it) }
        val notifyStart = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyStartBody)
        val notifyEnd = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyEndBody)
        return """
        // Généré par Hestia — simulation de présence (plusieurs canaux)
        $SUPERVISOR_MARKER$marker
        let CFG = $cfgArray;
        let STATE = [];
        for (let i = 0; i < CFG.length; i++) {
          STATE.push($FRESH_STATE);
        }

        function rnd(m) { return Math.floor(Math.random() * (2 * m + 1)) - m; }

        function planDay(state, windows, day) {
          state.day = day;
          state.on = [];
          state.off = [];
          for (let j = 0; j < windows.length; j++) {
            state.on.push(windows[j][0] + rnd(windows[j][2]));
            state.off.push(windows[j][1] + rnd(windows[j][2]));
          }
        }
        function notifyStart(name) { $notifyStart }
        function notifyEnd(name) { $notifyEnd }

        Timer.set(60000, true, function () {
          let sys = Shelly.getComponentStatus("sys");
          if (!sys || !sys.unixtime) return;
          let d = new Date(sys.unixtime * 1000);
          let now = d.getHours() * 60 + d.getMinutes();
          let today = d.getDay();
          let yesterday = (today + 6) % 7;
          let day = Math.floor(sys.unixtime / 86400);
          for (let i = 0; i < CFG.length; i++) {
            let cfg = CFG[i];
            let s = STATE[i];
            if (s.day !== day) planDay(s, cfg.windows, day);
            let st = Shelly.getComponentStatus("switch", cfg.switchId);
            if (!st) continue;
            let inWin = false;
            for (let j = 0; j < cfg.windows.length; j++) {
              let w = cfg.windows[j];
              let onAt = s.on[j];
              let offAt = s.off[j];
              let over = w[0] > w[1];
              let days = w[3];
              let appliesToday = days.indexOf(today) >= 0;
              let appliesYesterday = days.indexOf(yesterday) >= 0;
              let match = over
                ? ((appliesToday && now >= onAt) || (appliesYesterday && now < offAt))
                : (appliesToday && now >= onAt && now < offAt);
              if (match) { inWin = true; break; }
            }
            if (inWin && !st.output) { Shelly.call("Switch.Set", { id: cfg.switchId, on: true }); notifyStart(cfg.name); }
            if (!inWin && st.output) { Shelly.call("Switch.Set", { id: cfg.switchId, on: false }); notifyEnd(cfg.name); }
          }
        });
        """.trimIndent()
    }

    /**
     * Code `Script.Eval` pour ajouter [config], ou le remplacer s'il existe déjà (nouvelles plages
     * pour un canal déjà suivi — repart avec un état de suivi vierge, ce qui est le comportement
     * voulu). Reconstruit `CFG`/`STATE` par un tableau tampon (`for`+`push`), jamais `.splice()`.
     */
    fun evalUpsertChannel(config: ChannelConfig): String = """
        (function () {
          let kept = [];
          let keptState = [];
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId !== ${config.switchId}) { kept.push(CFG[i]); keptState.push(STATE[i]); }
          }
          kept.push(${channelLiteral(config)});
          keptState.push($FRESH_STATE);
          CFG = kept;
          STATE = keptState;
        })();
    """.trimIndent()

    /** Code `Script.Eval` pour retirer le canal [switchId] du suivi ; ne fait rien s'il est absent. */
    fun evalRemoveChannel(switchId: Int): String = """
        (function () {
          let kept = [];
          let keptState = [];
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId !== $switchId) { kept.push(CFG[i]); keptState.push(STATE[i]); }
          }
          CFG = kept;
          STATE = keptState;
        })();
    """.trimIndent()

    /** Code `Script.Eval` pour lire les plages actuellement suivies de tous les canaux. */
    fun evalReadConfig(): String = """
        (function () {
          let r = [];
          for (let i = 0; i < CFG.length; i++) {
            r.push([CFG[i].switchId, CFG[i].windows]);
          }
          return JSON.stringify(r);
        })();
    """.trimIndent()

    /**
     * Décode `[[canal,[[début,fin,marge,[jours]],…]],…]`, qu'il vienne du marqueur du script
     * (texte enregistré, script arrêté) ou du résultat de [evalReadConfig] (script en cours) — même
     * forme dans les deux cas, une seule fonction de lecture.
     */
    fun parseChannels(json: String): List<Pair<Int, List<PresenceWindow>>> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { channelEl ->
            val channelArr = channelEl as? JsonArray ?: return@mapNotNull null
            val switchId = channelArr.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val windowsArr = channelArr.getOrNull(1) as? JsonArray ?: return@mapNotNull null
            val windows = windowsArr.mapNotNull { parseWindow(it) }
            switchId to windows
        }
    }

    /** Relit le marqueur du script superviseur (texte enregistré) — voir [parseChannels]. */
    fun parseSupervisor(code: String): List<Pair<Int, List<PresenceWindow>>>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(SUPERVISOR_MARKER) } ?: return null
        return parseChannels(line.trim().removePrefix(SUPERVISOR_MARKER).trim())
    }

    /**
     * Relit les plages d'un ancien script **par canal** (avant le 2026-08-18), pour la migration
     * vers le script partagé uniquement — voir [DeviceRepository.loadOrMigratePresenceScript].
     * Retourne null si le marqueur est absent ou illisible.
     */
    fun parseLegacyChannel(code: String): List<PresenceWindow>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(LEGACY_MARKER) } ?: return null
        val payload = line.trim().removePrefix(LEGACY_MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<List<JsonElement>>>(payload) }.getOrNull() ?: return null
        return rows.mapNotNull { parseWindow(JsonArray(it)) }
    }

    private fun parseWindow(el: JsonElement): PresenceWindow? {
        val r = el as? JsonArray ?: return null
        if (r.size < 3) return null
        val start = r[0].jsonPrimitive.intOrNull ?: return null
        val end = r[1].jsonPrimitive.intOrNull ?: return null
        val margin = r[2].jsonPrimitive.intOrNull ?: return null
        // Marqueur d'avant le 2026-08-18 (fusion Planning/Présence) : pas de 4ᵉ élément, la plage
        // s'appliquait tous les jours — on le retrouve à l'identique plutôt que de la faire
        // disparaître silencieusement d'un appareil déjà configuré.
        val days = (r.getOrNull(3) as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet() ?: (0..6).toSet()
        return PresenceWindow(
            startHour = start / 60, startMinute = start % 60,
            endHour = end / 60, endMinute = end % 60,
            marginMinutes = margin,
            days = days,
        )
    }
}

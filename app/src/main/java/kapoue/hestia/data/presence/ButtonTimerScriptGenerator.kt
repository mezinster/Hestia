package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport
import kapoue.hestia.domain.model.PresenceWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Script **persistant** : veille en permanence un appui sur le bouton physique et arme un
 * minuteur natif par-dessus (avec coupure sur seuil optionnelle), sans toucher au comportement
 * natif du bouton — repose sur le champ `source` de `Switch.GetStatus`, pas sur un composant
 * Input (absent sur les modèles mono-canal validés en direct : pas d'appui long/double possible
 * sur ce matériel, le bouton est câblé en dur au relais).
 *
 * **Un seul script par appareil physique, pas par canal** (2026-08-17, script « superviseur ») :
 * le moteur de scripts Shelly n'autorise que **3 scripts activés simultanément par appareil**
 * (erreur RPC `-108` au-delà) — un bloc à 4 canaux avec un minuteur bouton sur chacun aurait
 * besoin de 4 scripts en permanence, saturant la limite à lui seul et bloquant silencieusement
 * toute autre fonctionnalité à base de script (seuil, présence, notifs) sur le bloc entier, quel
 * que soit le canal. Un seul script surveille donc désormais tous les canaux configurés à la
 * fois, chacun avec son propre état (armé, minuteur, seuil) — totalement indépendant des autres.
 *
 * **La valeur exacte de `source` varie selon le modèle** : `"button"` sur Plug M Gen3 (validé en
 * direct), `"short_push"` sur Strip 4 (validé en direct après un bug où le script ne s'armait
 * jamais — la comparaison ne matchait pas). [isButtonSource] accepte les deux, plus les variantes
 * de pression plausibles (`long_push`, `double_push`, `triple_push`) par prudence, même non
 * confirmées sur du matériel réel.
 *
 * Contrairement aux autres scripts de coupure (déployés une fois, se désactivent après usage),
 * celui-ci ne se supprime jamais lui-même : après chaque cycle (fin naturelle, coupure sur
 * seuil, ou nouvel appui pendant qu'il tourne), il se remet en veille pour l'appui suivant.
 * `enable:true` (contrairement au script de coupure d'un planning) : doit redémarrer seul après
 * un redémarrage de l'appareil, la fonctionnalité ne doit pas se désactiver silencieusement.
 */
object ButtonTimerScriptGenerator {

    /** Un seul nom, fixe : un seul script de ce type par appareil physique désormais. */
    const val SCRIPT_NAME = "hestia_button_timer"

    private const val MARKER = "// hestia_button_timer:"
    private const val BELOW_SEC = 60
    /** Grâce avant surveillance quand [ChannelConfig.durationSec] est null (sans limite de durée). */
    private const val UNLIMITED_GRACE_SEC = 15 * 60

    /**
     * Config d'un canal surveillé par le script. [durationSec] null = sans limite de durée (la
     * prise reste allumée jusqu'à la coupure sur seuil, si [thresholdW] en pose une). Les deux
     * peuvent être absents à la fois (retour David, 2026-09-11) : l'appui bouton allume alors la
     * prise sans aucune limite automatique. [name] sert uniquement au titre des notifications
     * ntfy de ce canal.
     *
     * [blocked] : plages où un appui bouton ne doit **pas** armer de minuteur (conflit bouton /
     * présence, voir BACKLOG.md § Conflit minuteur bouton / présence, 2026-08-24) — poussées par
     * `DeviceRepository.applyPresenceChannel` à chaque création/modification/suppression d'une
     * présence sur ce canal, vide si aucune présence ne le gouverne. [blockedOffToday] : jour
     * (`Math.floor(unixtime/86400)`, calculé côté script pour rester cohérent avec sa propre
     * horloge) où le blocage ci-dessus doit être ignoré malgré tout — poussé par
     * `DeviceRepository.stopPresenceForToday`, s'auto-annule le lendemain sans action requise
     * (le jour ne correspond plus), sur le même principe que `STATE[i].day` côté présence.
     */
    data class ChannelConfig(
        val switchId: Int,
        val durationSec: Int?,
        val thresholdW: Int?,
        val name: String,
        val blocked: List<PresenceWindow> = emptyList(),
        val blockedOffToday: Int? = null,
    )

    /**
     * Littéral JS des plages interdites d'un canal : `[[début,fin,0,[jours]],…]` — même forme que
     * les plages de présence, mais élargie de part et d'autre de la marge aléatoire de présence
     * (`marginMinutes`) pour couvrir toute bascule possible, jamais juste l'horaire nominal ; la
     * marge n'est donc plus utile une fois exportée, le troisième élément reste `0`. Repasse par
     * un modulo pour rester dans `[0,1440)` si l'élargissement traverse minuit.
     */
    private fun blockedLiteral(windows: List<PresenceWindow>): String =
        windows.joinToString(",", "[", "]") {
            val start = Math.floorMod(it.startMinutes - it.marginMinutes, 1440)
            val end = Math.floorMod(it.endMinutes + it.marginMinutes, 1440)
            "[$start,$end,0,[${it.days.sorted().joinToString(",")}]]"
        }

    /** Littéral JS d'un canal, partagé entre [generate] et [evalUpsertChannel]. */
    private fun channelLiteral(c: ChannelConfig): String {
        val graceSec = if (c.durationSec == null) UNLIMITED_GRACE_SEC else 0
        val durationLiteral = c.durationSec?.toString() ?: "null"
        val thresholdLiteral = c.thresholdW?.toString() ?: "null"
        val blockedOffTodayLiteral = if (c.blockedOffToday != null) ", blockedOffToday: ${c.blockedOffToday}" else ""
        return "{ switchId: ${c.switchId}, durationSec: $durationLiteral, thresholdW: $thresholdLiteral, " +
            "belowSec: $BELOW_SEC, graceSec: $graceSec, name: \"${NtfyScriptSupport.jsString(c.name)}\", " +
            "blocked: ${blockedLiteral(c.blocked)}$blockedOffTodayLiteral }"
    }

    /** Littéral JS d'un état de suivi vierge, partagé entre [generate] et [evalUpsertChannel]. */
    private const val FRESH_STATE = "{ armed: false, wasOn: false, belowSince: null, onSince: null }"

    /**
     * [configs] : un élément par canal configuré (les autres canaux de l'appareil, absents de la
     * liste, ne sont pas concernés). [ntfyTopic] non nul = notifie via ntfy à la fin
     * ([ntfyEndBody]) et à une coupure sur seuil ([ntfyCutoffBody]) — texte générique, identique
     * pour tous les canaux, seul le titre (nom de la prise) varie, résolu à l'exécution.
     * [ntfyCutoffBody] n'est qu'un préfixe : la durée de charge écoulée (ex. « 1h32 »), calculée
     * par le script lui-même à l'exécution (`fmtDur`), y est ajoutée à la suite (2026-09-17). Un
     * appui bouton qui annule le minuteur en cours (source de nouveau un appui bouton au moment de
     * l'extinction, voir `isButtonSource`) ne notifie jamais — c'est une action manuelle délibérée.
     *
     * Ne sert plus qu'au premier déploiement (aucun script existant, ou existant mais arrêté) ou à
     * une reprise après script mort — voir [evalUpsertChannel]/[evalRemoveChannel] pour modifier un
     * seul canal sur un script déjà en cours d'exécution, sans perdre la mémoire des autres (bug
     * confirmé en direct le 2026-08-18, voir BACKLOG.md : redéployer tout le script réinitialise le
     * suivi de **tous** les canaux, pas seulement celui qu'on change — un canal dont le minuteur
     * natif tournait encore perdait le sien, silencieusement, sans notif de fin ni protection seuil
     * pour le reste de son cycle).
     */
    fun generate(
        configs: List<ChannelConfig>,
        ntfyTopic: String? = null,
        ntfyEndBody: String = "",
        ntfyCutoffBody: String = "",
        /** État vivant à reprendre tel quel (JSON de `STATE`, lu via [evalReadFull]) — pour le
         * réalignement de la flash après une mutation `Eval` (2026-08-22, voir `DeviceRepository.
         * realignButtonTimerFlash`) : le script redémarre avec exactement la mémoire qu'il avait,
         * minuteurs armés compris. Null = premier déploiement, état vierge par canal. */
        initialStateJson: String? = null,
    ): String {
        val marker = configs.joinToString(",", "[", "]") {
            "[${it.switchId},${it.durationSec ?: "null"},${it.thresholdW ?: "null"}]"
        }
        val cfgArray = configs.joinToString(",\n          ", "[\n          ", "\n        ]") { channelLiteral(it) }
        val notifyEnd = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyEndBody)
        // Corps dynamique : durationSec (durée de charge écoulée) n'est connu qu'à l'exécution,
        // passé en paramètre par l'appelant plutôt que recalculé ici (retour David, 2026-09-17) —
        // voir fmtDur ci-dessous.
        val notifyCutoff = NtfyScriptSupport.callDynamicTitleAndBody(
            ntfyTopic, "name", "\"${NtfyScriptSupport.jsString(ntfyCutoffBody)} \" + fmtDur(durationSec)",
        )
        val stateInit = if (initialStateJson != null) {
            "let STATE = $initialStateJson;"
        } else {
            "let STATE = [];\n        for (let i = 0; i < CFG.length; i++) {\n          STATE.push($FRESH_STATE);\n        }"
        }
        return """
        // Généré par Hestia — minuteur déclenché par le bouton physique (plusieurs canaux)
        $MARKER$marker
        let CFG = $cfgArray;
        $stateInit

        // La valeur exacte de "source" pour un appui bouton varie selon le modèle de prise
        // (ex. "button" sur Plug M, "short_push" sur Strip 4) — accepte les variantes plausibles.
        function isButtonSource(src) {
          return src === "button" || src === "short_push" || src === "long_push" || src === "double_push" || src === "triple_push";
        }

        // Conflit bouton / présence (BACKLOG.md, 2026-08-24) : un canal gouverné par une présence
        // ne doit pas armer son propre minuteur pendant la fenêtre de présence, sinon les deux se
        // marchent dessus. Validé en direct sur la Strip4 le 2026-08-24 (Lot 1).
        function isBlockedNow(cfg) {
          let sys = Shelly.getComponentStatus("sys");
          if (!sys || !sys.unixtime) return false;
          if (cfg.blockedOffToday !== undefined && cfg.blockedOffToday !== null) {
            let day = Math.floor(sys.unixtime / 86400);
            if (cfg.blockedOffToday === day) return false;
          }
          if (!cfg.blocked || cfg.blocked.length === 0) return false;
          let d = new Date(sys.unixtime * 1000);
          let now = d.getHours() * 60 + d.getMinutes();
          let today = d.getDay();
          let yesterday = (today + 6) % 7;
          for (let j = 0; j < cfg.blocked.length; j++) {
            let w = cfg.blocked[j];
            let over = w[0] > w[1];
            let days = w[3];
            let appliesToday = days.indexOf(today) >= 0;
            let appliesYesterday = days.indexOf(yesterday) >= 0;
            let match = over
              ? ((appliesToday && now >= w[0]) || (appliesYesterday && now < w[1]))
              : (appliesToday && now >= w[0] && now < w[1]);
            if (match) return true;
          }
          return false;
        }

        // Durée écoulée depuis l'allumage, format « 1h32 » (≥ 1 h) ou « 45min » (sinon) — pour la
        // notif de coupure (retour David, 2026-09-17).
        function fmtDur(sec) {
          let m = Math.floor(sec / 60);
          let h = Math.floor(m / 60);
          m = m % 60;
          if (h > 0) return h + "h" + (m < 10 ? "0" : "") + m;
          return m + "min";
        }
        function notifyEnd(name) { $notifyEnd }
        function notifyCutoff(name, durationSec) { $notifyCutoff }

        Timer.set(1000, true, function () {
          for (let i = 0; i < CFG.length; i++) {
            let cfg = CFG[i];
            let s = STATE[i];
            let st = Shelly.getComponentStatus("switch", cfg.switchId);
            if (!st) continue;

            if (!s.armed) {
              // En veille : un appui qui vient d'allumer arme le minuteur par-dessus (ou, sans
              // durée, laisse simplement l'allumage natif du bouton tel quel).
              if (st.output && !s.wasOn && isButtonSource(st.source) && !isBlockedNow(cfg)) {
                s.armed = true;
                s.belowSince = null;
                s.onSince = null;
                if (cfg.durationSec !== null) {
                  Shelly.call("Switch.Set", { id: cfg.switchId, on: true, toggle_after: cfg.durationSec });
                }
              }
              s.wasOn = st.output;
              continue;
            }

            if (s.wasOn && !st.output) {
              // Éteinte pendant que le minuteur tournait : fin naturelle, coupure, ou action
              // manuelle. Un nouvel appui bouton pour annuler ne notifie jamais (délibéré).
              if (!isButtonSource(st.source)) {
                notifyEnd(cfg.name);
              }
              s.armed = false;
              s.belowSince = null;
              s.wasOn = false;
              continue;
            }
            s.wasOn = st.output;

            let sys = Shelly.getComponentStatus("sys");
            let now = (sys && sys.unixtime) ? sys.unixtime : null;
            if (s.onSince === null && now !== null) s.onSince = now;
            if (cfg.graceSec > 0 && s.onSince !== null && now !== null && (now - s.onSince) < cfg.graceSec) continue;

            if (cfg.thresholdW !== null && st.output) {
              let p = st.apower;
              if (p !== undefined && p !== null && p < cfg.thresholdW) {
                if (now === null) continue;
                if (s.belowSince === null) s.belowSince = now;
                if (now - s.belowSince >= cfg.belowSec) {
                  Shelly.call("Switch.Set", { id: cfg.switchId, on: false });
                  notifyCutoff(cfg.name, now - s.onSince);
                  s.armed = false;
                  s.belowSince = null;
                  s.wasOn = false;
                }
              } else {
                s.belowSince = null;
              }
            }
          }
        });
        """.trimIndent()
    }

    /**
     * Relit (canal, durée ou null si illimité, seuil) pour chaque canal configuré, ou null si
     * illisible — depuis le **texte enregistré** du script (`Script.GetCode`). Fiable uniquement
     * quand le script n'est pas en cours d'exécution (rien de vivant à côté qui aurait pu diverger
     * depuis) : sinon, préférer [parseEvalResult] sur une lecture via `Script.Eval` (voir
     * `DeviceRepository`), seule source à jour une fois qu'on modifie un canal sans redéployer.
     */
    fun parse(code: String): List<Triple<Int, Int?, Int?>>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        val payload = line.trim().removePrefix(MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<List<Int?>>>(payload) }.getOrNull() ?: return null
        return rows.mapNotNull { r ->
            if (r.size < 3 || r[0] == null) return@mapNotNull null
            Triple(r[0]!!, r[1], r[2])
        }
    }

    /**
     * Code `Script.Eval` pour ajouter/remplacer [config] dans `CFG`/`STATE` d'un script **déjà en
     * cours d'exécution**, sans jamais toucher aux autres canaux (même technique que
     * [kapoue.hestia.data.presence.ChargeScriptGenerator.evalUpsertChannel], validée en direct le
     * 2026-08-18). Reconstruit via un tableau tampon (`for`+`push`), jamais `.splice()`.
     *
     * [config] vient toujours de l'écran réglages du bouton, qui ne connaît rien des plages
     * interdites poussées séparément par la présence ([evalSetBlocked]/[evalSetBlockedOffToday]) —
     * sans reprise explicite, un simple changement de durée/seuil écraserait silencieusement le
     * blocage en cours. `blocked`/`blockedOffToday` de l'ancienne entrée sont donc repris tels
     * quels sur la nouvelle avant remplacement.
     */
    fun evalUpsertChannel(config: ChannelConfig): String = """
        (function () {
          let kept = [];
          let keptState = [];
          let oldBlocked = null;
          let oldBlockedOffToday = null;
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId !== ${config.switchId}) {
              kept.push(CFG[i]);
              keptState.push(STATE[i]);
            } else {
              if (CFG[i].blocked !== undefined) oldBlocked = CFG[i].blocked;
              if (CFG[i].blockedOffToday !== undefined) oldBlockedOffToday = CFG[i].blockedOffToday;
            }
          }
          let fresh = ${channelLiteral(config)};
          if (oldBlocked !== null) fresh.blocked = oldBlocked;
          if (oldBlockedOffToday !== null) fresh.blockedOffToday = oldBlockedOffToday;
          kept.push(fresh);
          keptState.push($FRESH_STATE);
          CFG = kept;
          STATE = keptState;
        })();
    """.trimIndent()

    /**
     * Code `Script.Eval` pour mettre à jour uniquement les plages interdites du canal [switchId],
     * sans toucher au reste de sa config ni à son état — poussé par `DeviceRepository.
     * applyPresenceChannel` à chaque création/modification/suppression d'une présence sur ce
     * canal. Ne fait rien si ce canal n'a pas de minuteur bouton configuré (rien à bloquer).
     *
     * Efface au passage un éventuel `blockedOffToday` : côté présence, créer/modifier une plage
     * réinitialise déjà `STATE` du canal (voir `PresenceScriptGenerator.evalUpsertChannel`), ce
     * qui annule implicitement une coupure « aujourd'hui » précédente — le bouton doit suivre le
     * même raisonnement, sans quoi il resterait débloqué pour le reste de la journée après une
     * modification qui, du point de vue de l'utilisateur, réactive la présence.
     */
    fun evalSetBlocked(switchId: Int, windows: List<PresenceWindow>): String = """
        (function () {
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId === $switchId) {
              CFG[i].blocked = ${blockedLiteral(windows)};
              delete CFG[i].blockedOffToday;
              return "ok";
            }
          }
          return "no_channel";
        })();
    """.trimIndent()

    /**
     * Code `Script.Eval` pour suspendre le blocage du canal [switchId] pour la journée en cours
     * uniquement — poussé par `DeviceRepository.stopPresenceForToday`. Calcule le jour lui-même
     * (`Math.floor(sys.unixtime/86400)`, horloge du script) plutôt que de le recevoir de Kotlin :
     * reste cohérent avec la comparaison faite dans `isBlockedNow`, quelle que soit l'horloge du
     * téléphone. S'auto-annule le lendemain (le jour ne correspond plus), rien à nettoyer.
     */
    fun evalSetBlockedOffToday(switchId: Int): String = """
        (function () {
          let sys = Shelly.getComponentStatus("sys");
          if (!sys || !sys.unixtime) return "no_time";
          let day = Math.floor(sys.unixtime / 86400);
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId === $switchId) {
              CFG[i].blockedOffToday = day;
              return "ok";
            }
          }
          return "no_channel";
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

    /** Code `Script.Eval` pour lire la config actuelle (canal, durée, seuil) de tous les canaux suivis. */
    fun evalReadConfig(): String = """
        (function () {
          let r = [];
          for (let i = 0; i < CFG.length; i++) {
            let c = CFG[i];
            r.push([c.switchId, c.durationSec, c.thresholdW]);
          }
          return JSON.stringify(r);
        })();
    """.trimIndent()

    /** Décode le JSON renvoyé par [evalReadConfig] (champ `result` de `Script.Eval`). */
    fun parseEvalResult(json: String): List<Triple<Int, Int?, Int?>> {
        val rows = runCatching { Json.decodeFromString<List<List<Int?>>>(json) }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { r ->
            if (r.size < 3 || r[0] == null) return@mapNotNull null
            Triple(r[0]!!, r[1], r[2])
        }
    }

    /**
     * Code `Script.Eval` pour lire (canal, seuil) des seuls canaux **actuellement armés** avec un
     * seuil configuré — contrairement à [evalReadConfig], qui liste tous les canaux configurés
     * pour un futur appui, armés ou non. Sert à afficher « Actif · Coupure à X W » sur un minuteur
     * bouton sans limite de durée (aucun décompte natif pour le signaler autrement), validé en
     * direct sur la Strip4 le 2026-08-22 (`CFG`/`STATE` alignés par indice, croisés ici).
     */
    fun evalReadArmedThresholds(): String = """
        (function () {
          let r = [];
          for (let i = 0; i < CFG.length; i++) {
            if (STATE[i].armed && CFG[i].thresholdW !== null) {
              r.push([CFG[i].switchId, CFG[i].thresholdW]);
            }
          }
          return JSON.stringify(r);
        })();
    """.trimIndent()

    /** Décode le JSON renvoyé par [evalReadArmedThresholds] (champ `result` de `Script.Eval`). */
    fun parseArmedThresholds(json: String): List<Pair<Int, Int>> {
        val rows = runCatching { Json.decodeFromString<List<List<Int>>>(json) }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { r -> if (r.size < 2) null else r[0] to r[1] }
    }

    /** Code `Script.Eval` pour lire le snapshot vivant complet (config **et** état) — réalignement flash. */
    fun evalReadFull(): String = "JSON.stringify({cfg:CFG,state:STATE})"

    /** Décode une plage `[début,fin,marge,[jours]]` telle qu'écrite par [blockedLiteral] (marge toujours 0, déjà consommée à l'export). */
    private fun parseBlockedWindow(el: JsonElement): PresenceWindow? {
        val arr = el as? JsonArray ?: return null
        if (arr.size < 4) return null
        val start = arr[0].jsonPrimitive.intOrNull ?: return null
        val end = arr[1].jsonPrimitive.intOrNull ?: return null
        val days = (arr[3] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet() ?: return null
        return PresenceWindow(start / 60, start % 60, end / 60, end % 60, marginMinutes = 0, days = days)
    }

    /**
     * Décode le snapshot de [evalReadFull] : la config typée (pour régénérer le script, marqueur
     * compris) et l'état brut (JSON de `STATE`, réinjecté tel quel via `initialStateJson` de
     * [generate] — jamais interprété par Hestia, seule sa position par canal compte). Null si
     * illisible, ou si config et état ne sont plus alignés (jamais vu, pur garde-fou).
     *
     * `blocked`/`blockedOffToday` sont relus ici comme le reste : sans ça, tout réalignement de
     * flash (déclenché par n'importe quelle mutation, pas seulement celles de la présence)
     * effacerait silencieusement le blocage en cours sur ce canal.
     */
    fun parseLiveSnapshot(json: String): Pair<List<ChannelConfig>, String>? = runCatching {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
        val cfgArr = root["cfg"] as? JsonArray ?: return null
        val stateArr = root["state"] as? JsonArray ?: return null
        if (cfgArr.size != stateArr.size) return null
        val configs = cfgArr.map { el ->
            val obj = el as? JsonObject ?: return null
            val switchId = obj["switchId"]?.jsonPrimitive?.intOrNull ?: return null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val durationSec = obj["durationSec"]?.jsonPrimitive?.intOrNull
            val thresholdW = obj["thresholdW"]?.jsonPrimitive?.intOrNull
            val blocked = (obj["blocked"] as? JsonArray)?.mapNotNull { parseBlockedWindow(it) }.orEmpty()
            val blockedOffToday = obj["blockedOffToday"]?.jsonPrimitive?.intOrNull
            ChannelConfig(switchId, durationSec, thresholdW, name, blocked, blockedOffToday)
        }
        configs to stateArr.toString()
    }.getOrNull()
}

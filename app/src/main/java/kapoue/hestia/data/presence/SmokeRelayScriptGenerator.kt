package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport

/**
 * Script relais **partagé par appareil physique**, jamais posé sur un détecteur de fumée
 * lui-même : contrairement aux autres scripts (présence, minuteur bouton), celui-ci ne pilote
 * aucun canal de l'appareil qui l'héberge — il reçoit, en simple HTTP **GET**, l'appel du webhook
 * natif d'un détecteur de fumée (incapable de POST, voir SMOKE-DETECTOR.md § Lot 4 — vérifié dans
 * la doc officielle Shelly le 2026-09-01) et relaie la notif ntfy à sa place, pour n'importe quel
 * détecteur connu de Hestia.
 *
 * Déployé de façon **opportuniste** sur jusqu'à 5 appareils scriptables ayant de la place (limite
 * dure de 3 scripts actifs par appareil, voir `ButtonTimerScriptGenerator`) — jamais un appareil
 * désigné à l'avance dans un réglage, pour éviter le point de panne unique si celui-ci est éteint
 * (2026-09-01, retour David : « je vois bien TOUS les appareils compatibles, tant pis pour les
 * notifs en double »). Toujours évictable : un vrai réglage métier (présence, minuteur bouton,
 * coupure sur seuil) sur l'appareil qui l'héberge a toujours priorité, voir
 * `DeviceRepository.deploySmokeRelay` — jamais posé si ça saturerait la limite.
 *
 * `names` associe l'adresse MAC (sans deux-points, majuscules — [Device.cloudId]) de chaque
 * détecteur à son nom affiché, **jamais l'inverse** : le webhook natif ne peut transmettre que des
 * paramètres ASCII dans son URL — un `é` accentué y arrive corrompu en GET (vérifié en direct le
 * 2026-09-01, `"Détecteur".length` renvoyait 10 au lieu de 9). Seule la MAC (ASCII) transite donc
 * par l'URL du webhook ; le nom, lui, est poussé ici séparément par `Script.Eval` **en POST**
 * (depuis l'app), qui ne souffre pas de ce problème — même principe que le reste des scripts
 * générés par Hestia.
 *
 * Sans état à faire persister (`STATE`) : contrairement au minuteur bouton ou à la présence, une
 * notif n'a rien à retenir d'un appel à l'autre. Pas de déduplication ici non plus — le webhook
 * natif porte son propre `repeat_period` côté détecteur si besoin.
 */
object SmokeRelayScriptGenerator {

    /** Un seul nom, fixe : un seul script de ce type par appareil physique, partagé par tous les détecteurs relayés. */
    const val SCRIPT_NAME = "hestia_smoke_relay"

    /** [mac] sans deux-points, majuscules ([kapoue.hestia.data.local.entity.Device.cloudId]) ; [name] affiché dans la notif (titre). */
    data class DeviceName(val mac: String, val name: String)

    /**
     * Textes des 3 événements natifs exposés par ce modèle de détecteur (`Webhook.ListSupported`,
     * vérifié en direct le 2026-09-01 : pas d'événement batterie basse sur ce matériel).
     */
    data class Bodies(val alarm: String, val alarmOff: String, val alarmTest: String)

    private fun namesLiteral(names: List<DeviceName>): String =
        names.joinToString(",", "[", "]") { "[\"${it.mac}\",\"${NtfyScriptSupport.jsString(it.name)}\"]" }

    private fun bodiesLiteral(bodies: Bodies): String =
        "{ alarm: \"${NtfyScriptSupport.jsString(bodies.alarm)}\", " +
            "alarm_off: \"${NtfyScriptSupport.jsString(bodies.alarmOff)}\", " +
            "alarm_test: \"${NtfyScriptSupport.jsString(bodies.alarmTest)}\" }"

    private fun topicLiteral(topic: String?): String =
        topic?.let { "\"${NtfyScriptSupport.jsString(it)}\"" } ?: "null"

    /**
     * Premier déploiement (ou reprise après script arrêté/mort) ; les mises à jour ultérieures
     * (nouveau détecteur, renommage, changement de sujet ntfy) passent par les `eval*` ci-dessous,
     * sans jamais redémarrer — même discipline que les autres scripts superviseurs de Hestia.
     */
    fun generate(topic: String?, names: List<DeviceName>, bodies: Bodies): String = """
        // Généré par Hestia — relais ntfy pour les détecteurs de fumée (webhook natif GET-only)
        let CFG = { topic: ${topicLiteral(topic)}, names: ${namesLiteral(names)}, bodies: ${bodiesLiteral(bodies)} };

        // event : "alarm" / "alarm_off" / "alarm_test" (Webhook.ListSupported). mac : sans
        // deux-points, majuscules — jamais de recherche par clé dynamique sur un objet (comparaison
        // === en boucle for, seule technique déjà éprouvée en production côté Hestia).
        function notifySmoke(event, mac) {
          if (!CFG.topic) return;
          let name = mac;
          for (let i = 0; i < CFG.names.length; i++) {
            if (CFG.names[i][0] === mac) { name = CFG.names[i][1]; break; }
          }
          let body = "";
          if (event === "alarm") body = CFG.bodies.alarm;
          else if (event === "alarm_off") body = CFG.bodies.alarm_off;
          else if (event === "alarm_test") body = CFG.bodies.alarm_test;
          if (body === "") return;
          Shelly.call("HTTP.Request", { method: "POST", url: "https://ntfy.sh/" + CFG.topic, body: body, timeout: 5, headers: { Title: name } });
        }
    """.trimIndent()

    /** Code `Script.Eval` pour changer le sujet ntfy (activation/désactivation comprise, `null` = pas de notif). */
    fun evalSetTopic(topic: String?): String = "CFG.topic = ${topicLiteral(topic)};"

    /** Code `Script.Eval` pour changer les 3 textes de notif (langue de l'app, voir strings.xml). */
    fun evalSetBodies(bodies: Bodies): String = "CFG.bodies = ${bodiesLiteral(bodies)};"

    /**
     * Code `Script.Eval` pour ajouter/remplacer le nom associé à [mac] — reconstruit via un
     * tableau tampon (`for`+`push`), jamais de recherche par clé dynamique sur un objet (même
     * prudence que [ButtonTimerScriptGenerator.evalUpsertChannel]).
     */
    fun evalUpsertName(mac: String, name: String): String {
        val escapedName = NtfyScriptSupport.jsString(name)
        return """
        (function () {
          let kept = [];
          let found = false;
          for (let i = 0; i < CFG.names.length; i++) {
            if (CFG.names[i][0] === "$mac") { kept.push(["$mac", "$escapedName"]); found = true; }
            else { kept.push(CFG.names[i]); }
          }
          if (!found) kept.push(["$mac", "$escapedName"]);
          CFG.names = kept;
        })();
        """.trimIndent()
    }

    /** Code `Script.Eval` pour retirer le nom associé à [mac] (détecteur supprimé de Hestia) ; ne fait rien s'il est absent. */
    fun evalRemoveName(mac: String): String = """
        (function () {
          let kept = [];
          for (let i = 0; i < CFG.names.length; i++) {
            if (CFG.names[i][0] !== "$mac") kept.push(CFG.names[i]);
          }
          CFG.names = kept;
        })();
    """.trimIndent()
}

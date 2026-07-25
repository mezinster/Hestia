package kapoue.hestia.data.presence

/**
 * Génère le script JavaScript de simulation de présence poussé sur l'appareil Shelly.
 * Structure **validée sur Plug M Gen3** (Timer.set, Date, Math.random, Shelly.call).
 *
 * Le script s'exécute en autonomie sur l'appareil : chaque minute, il replanifie l'heure
 * d'allumage/extinction du jour avec une marge aléatoire, et pilote le relais dans la plage.
 */
object PresenceScriptGenerator {

    /** Nom réservé du script Hestia. Ne jamais toucher un script portant un autre nom. */
    const val SCRIPT_NAME = "hestia_presence"

    fun generate(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        marginMinutes: Int,
        switchId: Int,
    ): String {
        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        return """
            // Généré par Hestia — simulation de présence
            // Plage ${"%02d".format(startHour)}:${"%02d".format(startMinute)} -> ${"%02d".format(endHour)}:${"%02d".format(endMinute)}, marge aléatoire +/- $marginMinutes min
            let CFG = { startMin: $startMin, endMin: $endMin, marginMin: $marginMinutes, switchId: $switchId };
            // Fenêtre à cheval sur minuit quand la fin est plus tôt que le début (ex. 22h -> 6h).
            let overnight = CFG.startMin > CFG.endMin;
            let planned = { onAt: null, offAt: null, day: null };

            function rnd(m) { return Math.floor(Math.random() * (2 * m + 1)) - m; }

            function planDay(day) {
              planned.day = day;
              planned.onAt = CFG.startMin + rnd(CFG.marginMin);
              planned.offAt = CFG.endMin + rnd(CFG.marginMin);
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
              let inWindow = overnight
                ? (now >= planned.onAt || now < planned.offAt)
                : (now >= planned.onAt && now < planned.offAt);
              if (inWindow && !st.output) Shelly.call("Switch.Set", { id: CFG.switchId, on: true });
              if (!inWindow && st.output) Shelly.call("Switch.Set", { id: CFG.switchId, on: false });
            });
        """.trimIndent()
    }
}

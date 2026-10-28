package kapoue.hestia.data.repository

import kapoue.hestia.data.presence.ChargeScriptGenerator

/**
 * Sort réservé au script de coupure d'un planning avec seuil lors de la mise à niveau
 * automatique du lancement (`DeviceRepository.upgradeCutoffScriptsIfDue`, 2026-10-29, idées
 * d'upstream 2.16.2 et 2.18.0).
 */
enum class CutoffScriptUpgrade {
    /** Script à jour : rien à faire. */
    NONE,

    /** Texte de notif périmé, structure à jour : script réécrit en place, programmes intacts. */
    REWRITE_TEXT,

    /** Script sans `planEnd()` : planning réenregistré à l'identique (nouveau script, nouveaux programmes). */
    RESAVE,

    /**
     * À mettre à niveau, mais planning **en cours** : jamais touché en plein créneau (son script
     * surveille la charge, état de coupure compris) — retenté au lancement suivant.
     */
    POSTPONE,
    ;

    companion object {
        fun decide(code: String, activeNow: Boolean): CutoffScriptUpgrade {
            val upgrade = when {
                ChargeScriptGenerator.lacksPlanEnd(code) -> RESAVE
                ChargeScriptGenerator.parseNotifVersion(code) < ChargeScriptGenerator.NOTIF_VERSION -> REWRITE_TEXT
                else -> return NONE
            }
            return if (activeNow) POSTPONE else upgrade
        }
    }
}

package kapoue.hestia.core.log

/**
 * Journal de diagnostic (SPEC-V1 § 7). En lot 1, seul le point d'entrée est posé :
 * l'implémentation [LogcatDiagnosticLogger] écrit vers Logcat. Le lot 5 fournira une
 * implémentation adossée à Room (tampon circulaire 2 000 entrées) sans toucher aux appelants.
 *
 * Règle permanente : ne jamais journaliser de mot de passe (remplacer par ***).
 */
interface DiagnosticLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    companion object Tags {
        const val UI = "ui"
        const val RPC = "rpc"
        const val DB = "db"
    }
}

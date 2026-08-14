package kapoue.hestia.core.log

import android.util.Log
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.local.entity.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Journal de diagnostic adossé à Room (SPEC-V1 § 7) : écriture **asynchrone** hors du fil
 * principal, tampon circulaire borné à [MAX_ENTRIES]. Une écriture qui échoue ne fait jamais
 * échouer l'action en cours (runCatching). Reste actif en production.
 */
@Singleton
class RoomDiagnosticLogger @Inject constructor(
    private val dao: DiagnosticLogDao,
) : DiagnosticLogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun info(tag: String, message: String) = write("INFO", tag, message, null)
    override fun warn(tag: String, message: String) = write("WARN", tag, message, null)
    override fun error(tag: String, message: String, throwable: Throwable?) =
        write("ERROR", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            "ERROR" -> Log.e(LOG_TAG, "[$tag] $message", throwable)
            "WARN" -> Log.w(LOG_TAG, "[$tag] $message")
            else -> Log.i(LOG_TAG, "[$tag] $message")
        }
        val fullMessage = throwable?.let { "$message : ${it.javaClass.simpleName}" } ?: message
        scope.launch {
            runCatching {
                dao.insert(DiagnosticLog(level = level, tag = tag, message = fullMessage))
                val total = dao.count()
                if (total > MAX_ENTRIES) dao.deleteOldest(total - MAX_ENTRIES)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "Hestia"
        const val MAX_ENTRIES = 250
    }
}

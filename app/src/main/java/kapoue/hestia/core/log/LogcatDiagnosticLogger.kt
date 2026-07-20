package kapoue.hestia.core.log

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** Implémentation lot 1 : écrit vers Logcat. Remplacée par une version Room au lot 5. */
@Singleton
class LogcatDiagnosticLogger @Inject constructor() : DiagnosticLogger {
    override fun info(tag: String, message: String) {
        Log.i(LOG_TAG, "[$tag] $message")
    }

    override fun warn(tag: String, message: String) {
        Log.w(LOG_TAG, "[$tag] $message")
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(LOG_TAG, "[$tag] $message", throwable)
    }

    private companion object {
        const val LOG_TAG = "Hestia"
    }
}

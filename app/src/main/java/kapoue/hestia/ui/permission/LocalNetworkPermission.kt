package kapoue.hestia.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** État de la permission réseau local. */
enum class LocalNetworkPermissionStatus {
    /** Accordée (ou implicitement disponible). */
    GRANTED,

    /** Requise mais non accordée. */
    DENIED,

    /** Non requise sur cette version d'Android (< 17) : le trafic local passe directement. */
    NOT_REQUIRED,
}

/**
 * Encapsule la permission runtime `ACCESS_LOCAL_NETWORK` (SPEC-V1 / CLAUDE.md).
 *
 * Règle : ne jamais la demander au lancement. Elle est sollicitée au premier contact réseau
 * réel, précédée d'un écran d'explication rédigé par nous (voir [PermissionExplanationDialog]).
 */
object LocalNetworkPermission {
    // Constante littérale : le trafic local n'exige cette permission qu'à partir d'Android 17.
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    // Android 17 correspond au niveau d'API 37 (cf. targetSdk du projet).
    private const val ANDROID_17_API = 37

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= ANDROID_17_API

    fun status(context: Context): LocalNetworkPermissionStatus = when {
        !isRequired -> LocalNetworkPermissionStatus.NOT_REQUIRED
        ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED ->
            LocalNetworkPermissionStatus.GRANTED
        else -> LocalNetworkPermissionStatus.DENIED
    }

    /** True si un contact réseau peut être tenté sans demander de permission. */
    fun isUsable(context: Context): Boolean =
        status(context) != LocalNetworkPermissionStatus.DENIED

    /** Ouvre la page système des réglages de l'application (Android n'expose pas d'API de révocation interne). */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

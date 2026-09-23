package kapoue.hestia.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kapoue.hestia.R

/** Onglets de la barre de navigation basse (SPEC-V1 § Navigation). */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Filled.GridView),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
    ABOUT("about", R.string.nav_about, Icons.Filled.Info),
}

/** Destinations empilées par-dessus l'onglet courant (écrans contextuels, avec bouton retour). */
object StackedRoutes {
    const val ADD_DEVICE = "add_device"
    const val EDIT_DEVICE = "edit_device"
    const val EDIT_DEVICE_ARG_ID = "deviceId"
    /** Défilement ponctuel jusqu'à la section Firmware à l'arrivée — voir le bandeau « Maj
     * dispo » du Tableau (2026-09-23), seul déclencheur pour l'instant. */
    const val EDIT_DEVICE_ARG_SCROLL_TO_FIRMWARE = "scrollToFirmware"
    const val EDIT_DEVICE_PATTERN =
        "$EDIT_DEVICE/{$EDIT_DEVICE_ARG_ID}?$EDIT_DEVICE_ARG_SCROLL_TO_FIRMWARE={$EDIT_DEVICE_ARG_SCROLL_TO_FIRMWARE}"

    fun editDevice(deviceId: Long, scrollToFirmware: Boolean = false) =
        "$EDIT_DEVICE/$deviceId?$EDIT_DEVICE_ARG_SCROLL_TO_FIRMWARE=$scrollToFirmware"

    const val DETAIL = "detail"
    const val DETAIL_ARG_ID = "deviceId"
    const val DETAIL_PATTERN = "$DETAIL/{$DETAIL_ARG_ID}"

    fun detail(deviceId: Long) = "$DETAIL/$deviceId"

    /** Journal de diagnostic (accès discret par 5 appuis sur le titre du Tableau). */
    const val DIAGNOSTIC = "diagnostic"
}

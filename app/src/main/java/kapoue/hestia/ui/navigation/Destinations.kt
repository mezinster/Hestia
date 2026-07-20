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
    const val EDIT_DEVICE_PATTERN = "$EDIT_DEVICE/{$EDIT_DEVICE_ARG_ID}"

    fun editDevice(deviceId: Long) = "$EDIT_DEVICE/$deviceId"

    const val DETAIL = "detail"
    const val DETAIL_ARG_ID = "deviceId"
    const val DETAIL_PATTERN = "$DETAIL/{$DETAIL_ARG_ID}"

    fun detail(deviceId: Long) = "$DETAIL/$deviceId"

    const val PRESENCE = "presence"
    const val PRESENCE_ARG_ID = "deviceId"
    const val PRESENCE_PATTERN = "$PRESENCE/{$PRESENCE_ARG_ID}"

    fun presence(deviceId: Long) = "$PRESENCE/$deviceId"

    /** Journal de diagnostic (accès discret par 5 appuis sur le titre du Tableau). */
    const val DIAGNOSTIC = "diagnostic"
}

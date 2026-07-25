package kapoue.hestia.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.log.LocalDiagnosticLogger
import kapoue.hestia.ui.screens.diagnostic.DiagnosticScreen
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.navigation.TopLevelDestination
import kapoue.hestia.ui.screens.about.AboutScreen
import kapoue.hestia.ui.screens.dashboard.DashboardScreen
import kapoue.hestia.ui.screens.detail.DetailScreen
import kapoue.hestia.ui.screens.device.AddEditDeviceScreen
import kapoue.hestia.ui.screens.settings.SettingsScreen

/** Coquille de l'application : barre de navigation basse à 3 onglets + graphe de navigation. */
@Composable
fun HestiaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Instrumentation de navigation (journal de diagnostic).
    val logger = LocalDiagnosticLogger.current
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            logger.info(DiagnosticLogger.UI, "Écran ouvert : ${entry.destination.route}")
        }
    }

    // La barre basse n'apparaît que sur les destinations de premier niveau.
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = backStackEntry?.destination
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.DASHBOARD.route,
            // Seule la marge basse (barre de navigation) est appliquée ici : la marge haute
            // (barre de statut) est gérée par la TopAppBar de chaque écran, pour éviter un
            // double espacement au-dessus des titres.
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(TopLevelDestination.DASHBOARD.route) {
                DashboardScreen(
                    onAddDevice = { navController.navigate(StackedRoutes.ADD_DEVICE) },
                    onOpenDetail = { id -> navController.navigate(StackedRoutes.detail(id)) },
                    onOpenDiagnostic = { navController.navigate(StackedRoutes.DIAGNOSTIC) },
                )
            }
            composable(TopLevelDestination.ABOUT.route) { AboutScreen() }

            composable(StackedRoutes.DIAGNOSTIC) {
                DiagnosticScreen(onBack = { navController.popBackStack() })
            }

            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onAddDevice = { navController.navigate(StackedRoutes.ADD_DEVICE) },
                    onEditDevice = { id -> navController.navigate(StackedRoutes.editDevice(id)) },
                )
            }

            composable(StackedRoutes.ADD_DEVICE) {
                AddEditDeviceScreen(onDone = { navController.popBackStack() })
            }

            composable(
                route = StackedRoutes.EDIT_DEVICE_PATTERN,
                arguments = listOf(
                    navArgument(StackedRoutes.EDIT_DEVICE_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                AddEditDeviceScreen(onDone = { navController.popBackStack() })
            }

            composable(
                route = StackedRoutes.DETAIL_PATTERN,
                arguments = listOf(
                    navArgument(StackedRoutes.DETAIL_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

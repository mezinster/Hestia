package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kapoue.hestia.R
import kapoue.hestia.core.util.formatTimeRange
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.permission.PermissionExplanationDialog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onAddDevice: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenDiagnostic: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showPermissionDialog by remember { mutableStateOf(false) }
    // Bascule demandée sur une prise pilotée par une simulation : en attente de confirmation.
    var pendingToggle by remember { mutableStateOf<PendingToggle?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val usable = granted || !LocalNetworkPermission.isRequired
        viewModel.updatePermission(usable)
        viewModel.refresh()
    }

    // Compteur de secondes pour décrémenter les comptes à rebours localement.
    var elapsedNow by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsedNow = SystemClock.elapsedRealtime()
        }
    }

    // Polling 5 s tant que l'écran est au premier plan ; arrêt automatique en arrière-plan.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.updatePermission(LocalNetworkPermission.isUsable(context))
            while (true) {
                viewModel.refresh()
                delay(5_000)
            }
        }
    }

    // Accès discrets, empilés sur le même geste (compteur réinitialisé après 2 s d'inactivité,
    // aucun retour visuel — outils de support, pas des fonctionnalités) :
    // 3 appuis → envoi groupé de tous les textes de notif ntfy (ne se déclenche qu'à l'arrêt à 3,
    // pas en chemin vers 5, pour ne pas polluer l'accès au journal de diagnostic).
    // 5 appuis → journal de diagnostic.
    var tapCount by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    // Le triple appui ne se distingue du chemin vers 5 qu'après une pause : s'il n'y a pas eu de
    // 4ᵉ appui dans les 2 s, et qu'on s'est arrêté pile à 3, c'est le geste ntfy.
    LaunchedEffect(tapCount) {
        if (tapCount != 3) return@LaunchedEffect
        delay(2_000)
        if (tapCount == 3) viewModel.debugSendAllNtfyTexts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                val now = SystemClock.elapsedRealtime()
                                tapCount = if (now - lastTapAt <= 2_000) tapCount + 1 else 1
                                lastTapAt = now
                                if (tapCount >= 5) {
                                    tapCount = 0
                                    onOpenDiagnostic()
                                }
                            },
                            // Appui long → bascule le mode démo (build debug uniquement).
                            onLongClick = { viewModel.toggleDemo() },
                        ),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Barre omnibus décorative en tête de grille (SPEC-V1 § 5).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )

            if (uiState.tiles.isEmpty()) {
                EmptyDashboard(onAddDevice = onAddDevice)
            } else {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh(userInitiated = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(uiState.tiles, key = { it.device.id }) { tile ->
                            DeviceTile(
                                tile = tile,
                                elapsedNow = elapsedNow,
                                onToggle = { turnOn ->
                                    // Une simulation en cours reprendrait la main : on demande
                                    // d'abord si l'on doit l'arrêter, plutôt que de laisser
                                    // l'utilisateur croire à un interrupteur défaillant.
                                    val running = tile.presence
                                    if (running != null) {
                                        pendingToggle = PendingToggle(tile.device, turnOn, running)
                                    } else {
                                        viewModel.toggle(tile.device, turnOn)
                                    }
                                },
                                onRetry = { viewModel.retry(tile.device) },
                                onGrantPermission = { showPermissionDialog = true },
                                onOpenDetail = { onOpenDetail(tile.device.id) },
                                onPlanningWindowEnded = { viewModel.refresh(force = true) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        PermissionExplanationDialog(
            onContinue = {
                showPermissionDialog = false
                permissionLauncher.launch(LocalNetworkPermission.NAME)
            },
            onDismiss = { showPermissionDialog = false },
        )
    }

    pendingToggle?.let { pending ->
        PresenceToggleDialog(
            presence = pending.presence,
            onStopSimulation = {
                viewModel.stopPresenceThenToggle(pending.device, pending.turnOn)
                pendingToggle = null
            },
            onDismiss = { pendingToggle = null },
        )
    }
}

/** Bascule demandée sur une prise pilotée par une simulation, en attente de confirmation. */
private data class PendingToggle(
    val device: Device,
    val turnOn: Boolean,
    val presence: PresenceInfo,
)

/**
 * Prévient que la prise est pilotée par un programme avant d'agir sur l'interrupteur, et
 * propose de l'arrêter. Deux issues seulement : arrêter la simulation puis basculer, ou
 * renoncer — on ne propose pas de « basculer quand même », dont l'effet ne durerait que
 * jusqu'à la prochaine action du script.
 */
@Composable
private fun PresenceToggleDialog(
    presence: PresenceInfo,
    onStopSimulation: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presence_toggle_title)) },
        text = {
            Column {
                Text(
                    text = formatTimeRange(
                        presence.startHour,
                        presence.startMinute,
                        presence.endHour,
                        presence.endMinute,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.presence_toggle_message))
            }
        },
        confirmButton = {
            TextButton(onClick = onStopSimulation) {
                Text(stringResource(R.string.presence_toggle_stop))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conflict_cancel))
            }
        },
    )
}

@Composable
private fun EmptyDashboard(onAddDevice: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.dashboard_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.dashboard_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(onClick = onAddDevice) {
            Text(stringResource(R.string.dashboard_empty_action))
        }
    }
}

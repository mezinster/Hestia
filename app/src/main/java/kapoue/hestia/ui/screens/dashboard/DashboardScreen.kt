package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.screens.detail.ConflictDialog
import kapoue.hestia.ui.screens.detail.DurationPickerSheet
import kapoue.hestia.ui.screens.detail.PendingTimer
import kapoue.hestia.ui.screens.detail.PlanningInProgressDialog
import kapoue.hestia.ui.theme.stateColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onAddDevice: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenFirmware: (Long) -> Unit,
    onOpenDiagnostic: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionUsable by viewModel.permissionUsable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bascule demandée sur une prise pilotée par une simulation : en attente de confirmation.
    var pendingToggle by remember { mutableStateOf<PendingToggle?>(null) }
    // Modale rapide d'un canal tapé dans un bloc multi-prises (conso, interrupteur, état).
    // Seul l'id est retenu, pas la tuile elle-même (retour David, 2026-09-14) : un TileUiState
    // figé au moment du tap ne se serait jamais mis à jour ensuite (la conso restait celle du
    // premier relevé jusqu'à fermer/rouvrir la modale) — on retrouve la tuile à jour dans
    // uiState.tiles à chaque recomposition à la place, comme pour tout le reste de l'écran.
    var quickSheetDeviceId by remember { mutableStateOf<Long?>(null) }
    // Lancement d'un programme depuis la modale rapide (Ergo-1, 2026-09-21) — même logique de
    // conflit planning/présence que l'écran Configurer (voir DetailScreen.requestStartTimer),
    // dupliquée ici plutôt que partagée : les deux écrans n'ont pas le même ViewModel. Le Tableau
    // gère plusieurs appareils à la fois (Détail n'en a qu'un), donc le device concerné est gardé
    // à côté du minuteur en attente plutôt qu'implicite.
    var pendingTimer by remember { mutableStateOf<Pair<Device, PendingTimer>?>(null) }
    var planningWarning by remember { mutableStateOf<Triple<Device, PendingTimer, Planning>?>(null) }
    // Sélecteur de durée « Manuel » ouvert depuis la modale rapide — id du canal concerné.
    var customTimerDeviceId by remember { mutableStateOf<Long?>(null) }

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
            viewModel.invalidateCoverEvents()
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    ) {
                        // Logo : un peu de couleur en tête d'écran (retour David, 2026-09-12) —
                        // même composition que l'icône de l'écran À propos (fond craie + picto).
                        Surface(
                            color = colorResource(R.color.ic_launcher_background),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Text(text = stringResource(R.string.app_name))
                    }
                },
            )
        },
    ) { innerPadding ->
        // Seul le haut vient d'ici (sous la TopAppBar) : le bas est déjà réservé une seule fois
        // par HestiaApp pour la barre de navigation partagée (voir son commentaire) — reprendre
        // innerPadding en entier ajoutait un second espace bas (celui, par défaut, que Scaffold
        // réserve pour les barres système même sans bottomBar propre), visible comme une bande
        // vide au-dessus des onglets (retour David, 2026-08-24).
        Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
            // Barre omnibus décorative en tête de grille (SPEC-V1 § 5).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )

            if (!permissionUsable) {
                // Aucune tuile tant que la permission manque : rien à en tirer sans elle, le
                // bandeau seul porte le message et l'action.
                PermissionBanner()
            } else if (uiState.tiles.isEmpty()) {
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
                        uiState.tiles.forEach { tile ->
                            // Plus d'en-tête de groupe séparée (picto + nom) au-dessus des
                            // tuiles : pour une prise seule ça doublait le nom déjà affiché dans
                            // la tuile ; pour un bloc, le nom du bloc est désormais affiché en
                            // titre à l'intérieur de la ligne de cercles elle-même.
                            if (tile.isFirstInGroup && tile.isMultiChannel) {
                                // Bloc multi-canaux : une seule ligne de cercles pour tout le
                                // groupe (façon vraie multiprise), pas une tuile par canal — le
                                // détail de chaque canal s'ouvre dans une modale au tap.
                                val members = uiState.tiles.filter { it.device.groupKey() == tile.device.groupKey() }
                                item(
                                    key = "strip-${tile.device.id}",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    if (tile.device.isLight) {
                                        // Variateur multi-light : le tap ouvre le Détail du canal.
                                        LightStripRow(
                                            groupLabel = tile.groupLabel,
                                            members = members,
                                            elapsedNow = elapsedNow,
                                            onOpenChannel = { onOpenDetail(it.device.id) },
                                            onOpenFirmware = onOpenFirmware,
                                        )
                                    } else {
                                        DeviceStripRow(
                                            groupLabel = tile.groupLabel,
                                            members = members,
                                            elapsedNow = elapsedNow,
                                            onTapChannel = { quickSheetDeviceId = it.device.id },
                                            onOpenFirmware = onOpenFirmware,
                                        )
                                    }
                                }
                            }
                            if (!tile.isMultiChannel) {
                                item(key = tile.device.id) {
                                    if (tile.device.isCover) {
                                        // Volet (2026-10-07) : tap = Détail, ▲ ■ ▼ directement sur la tuile.
                                        CoverTile(
                                            tile = tile,
                                            onOpen = { viewModel.coverOpen(tile.device) },
                                            onStop = { viewModel.coverStop(tile.device) },
                                            onClose = { viewModel.coverClose(tile.device) },
                                            onOpenDetail = { onOpenDetail(tile.device.id) },
                                            onOpenFirmware = { onOpenFirmware(tile.device.id) },
                                        )
                                    } else if (tile.device.isLight) {
                                        // Variateur (2026-10-07) : le tap ouvre directement le
                                        // Détail (curseur), pas la modale rapide pensée relais.
                                        LightTile(
                                            tile = tile,
                                            elapsedNow = elapsedNow,
                                            onToggle = { viewModel.toggleLight(tile.device, it) },
                                            onOpenDetail = { onOpenDetail(tile.device.id) },
                                            onOpenFirmware = { onOpenFirmware(tile.device.id) },
                                        )
                                    } else if (tile.device.type == DeviceType.SMOKE_DETECTOR) {
                                        SmokeDetectorTile(
                                            tile = tile,
                                            onOpenDetail = { onOpenDetail(tile.device.id) },
                                            onOpenFirmware = { onOpenFirmware(tile.device.id) },
                                        )
                                    } else {
                                        DeviceTile(
                                            tile = tile,
                                            elapsedNow = elapsedNow,
                                            onToggle = { turnOn ->
                                                // Présence active : le bouton ON/OFF ne fait que
                                                // couper la simulation pour aujourd'hui, jamais
                                                // relancer autre chose à la place (retour David,
                                                // 2026-08-22) — confirmation d'abord.
                                                val running = tile.presence
                                                if (running != null) {
                                                    pendingToggle = PendingToggle(tile.device, running)
                                                } else {
                                                    viewModel.toggle(tile.device, turnOn)
                                                }
                                            },
                                            // Ergo-2 (2026-09-21) : la modale rapide d'abord, comme
                                            // pour un canal de bloc — une prise seule n'a plus de
                                            // raison d'aller direct sur Configurer, un tap doit
                                            // toujours proposer l'action avant le réglage.
                                            onOpenDetail = { quickSheetDeviceId = tile.device.id },
                                            onPlanningWindowEnded = { viewModel.refresh(force = true) },
                                            onOpenFirmware = { onOpenFirmware(tile.device.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingToggle?.let { pending ->
        PresenceToggleDialog(
            presence = pending.presence,
            onStopSimulation = {
                viewModel.stopPresenceToday(pending.device)
                pendingToggle = null
            },
            onDismiss = { pendingToggle = null },
        )
    }

    // Même filtrage « désactivé aujourd'hui » que DetailScreen.requestStartTimer, mais à partir
    // des champs déjà portés par la tuile (pas besoin d'appel ViewModel dédié, le Tableau gère
    // plusieurs appareils à la fois — voir la déclaration de pendingTimer/planningWarning).
    fun Planning.isReallyActiveFor(tile: TileUiState): Boolean = isActiveNow() &&
        !(isPresence && tile.presenceDisabledToday) &&
        !(!isPresence && !once && tile.planningDisabledToday)

    fun requestStartTimer(tile: TileUiState, seconds: Int?, label: String, thresholdW: Int? = null) {
        val activePlanning = tile.plannings.firstOrNull { it.isReallyActiveFor(tile) }
        val timer = PendingTimer(seconds, label, thresholdW)
        when {
            activePlanning?.isPresence == true -> pendingTimer = tile.device to timer
            activePlanning != null -> planningWarning = Triple(tile.device, timer, activePlanning)
            else -> viewModel.startTimer(tile.device, seconds, label, thresholdW)
        }
    }

    quickSheetDeviceId?.let { id ->
        val tile = uiState.tiles.firstOrNull { it.device.id == id }
        if (tile == null) {
            // Canal supprimé (ou plus dans la liste) pendant que la modale était ouverte.
            quickSheetDeviceId = null
        } else {
            ChannelQuickSheet(
                tile = tile,
                elapsedNow = elapsedNow,
                onToggle = { turnOn ->
                    // Même règle que la tuile solo : présence active = le bouton coupe la
                    // simulation pour aujourd'hui, jamais un toggle à la place.
                    val running = tile.presence
                    quickSheetDeviceId = null
                    if (running != null) {
                        pendingToggle = PendingToggle(tile.device, running)
                    } else {
                        viewModel.toggle(tile.device, turnOn)
                    }
                },
                onLaunch = { seconds, label, thresholdW ->
                    quickSheetDeviceId = null
                    requestStartTimer(tile, seconds, label, thresholdW)
                },
                onCustom = {
                    quickSheetDeviceId = null
                    customTimerDeviceId = tile.device.id
                },
                onOpenDetail = {
                    quickSheetDeviceId = null
                    onOpenDetail(tile.device.id)
                },
                onDismiss = { quickSheetDeviceId = null },
            )
        }
    }

    customTimerDeviceId?.let { id ->
        val tile = uiState.tiles.firstOrNull { it.device.id == id }
        if (tile == null) {
            customTimerDeviceId = null
        } else {
            DurationPickerSheet(
                hasPowerMetering = tile.device.hasPowerMetering,
                title = stringResource(R.string.duration_picker_title),
                confirmLabel = stringResource(R.string.duration_picker_start),
                confirmIcon = Icons.Filled.PlayArrow,
                onDismiss = { customTimerDeviceId = null },
                onConfirm = { seconds, label, thresholdW, _ ->
                    customTimerDeviceId = null
                    requestStartTimer(tile, seconds, label, thresholdW)
                },
            )
        }
    }

    pendingTimer?.let { (device, pt) ->
        ConflictDialog(
            onCancel = { pendingTimer = null },
            onLaunchAnyway = {
                viewModel.startTimer(device, pt.seconds, pt.label, pt.thresholdW)
                pendingTimer = null
            },
            onStopPresence = {
                viewModel.stopPresenceThenStartTimer(device, pt.seconds, pt.label, pt.thresholdW)
                pendingTimer = null
            },
        )
    }

    planningWarning?.let { (device, pt, planning) ->
        PlanningInProgressDialog(
            planning = planning,
            onCancel = { planningWarning = null },
            onConfirm = {
                viewModel.startTimer(device, pt.seconds, pt.label, pt.thresholdW)
                planningWarning = null
            },
        )
    }
}

/** Interruption demandée sur une prise pilotée par une simulation, en attente de confirmation. */
private data class PendingToggle(
    val device: Device,
    val presence: PresenceInfo,
)

/**
 * Bandeau global (pas de tuile dédiée) quand la permission réseau local manque — même style et
 * même action que [kapoue.hestia.ui.screens.settings.SettingsScreen]'s PermissionSection :
 * ouvre directement les réglages système, sans redemander la permission depuis l'app.
 */
@Composable
private fun PermissionBanner() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.stateColors.offlineLed,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.dashboard_permission_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { LocalNetworkPermission.openAppSettings(context) }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_permission_open_system))
            }
        }
    }
}

/**
 * Confirme avant de couper une simulation de présence **pour aujourd'hui** depuis l'interrupteur
 * ON/OFF — visuellement un bouton ON/OFF, l'utilisateur s'attend à couper le programme en cours,
 * pas à en relancer un autre à la place (retour David, 2026-08-22). Ne touche jamais la
 * configuration récurrente (jours/horaires/marge) : la présence reprend normalement le lendemain
 * — un arrêt définitif se fait depuis l'écran Détail, pas ce bouton. Deux issues seulement :
 * couper pour aujourd'hui, ou renoncer — jamais de bascule après coup, et jamais de « basculer
 * quand même » (son effet ne durerait que jusqu'à la prochaine action du script).
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

package kapoue.hestia.ui.screens.detail

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kapoue.hestia.R
import kapoue.hestia.core.util.formatCountdown
import kapoue.hestia.core.util.formatLogTimestamp
import kapoue.hestia.data.local.entity.ActivationLog
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.ActivationAction
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.PresenceOpResult
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.ui.components.StatusBadge
import kapoue.hestia.ui.components.TimeWheelPicker
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val presenceActive by viewModel.presenceActive.collectAsStateWithLifecycle()
    val presenceWindows by viewModel.presenceWindows.collectAsStateWithLifecycle()
    val addPresenceResult by viewModel.addPresenceResult.collectAsStateWithLifecycle()
    val plannings by viewModel.plannings.collectAsStateWithLifecycle()
    val addPlanningResult by viewModel.addPlanningResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showSheet by remember { mutableStateOf(false) }
    var showAddPlanning by remember { mutableStateOf(false) }
    var showAddPresence by remember { mutableStateOf(false) }
    var editingPresence by remember { mutableStateOf<PresenceWindow?>(null) }
    var presenceToDelete by remember { mutableStateOf<PresenceWindow?>(null) }
    LaunchedEffect(addPresenceResult) {
        if (addPresenceResult is PresenceOpResult.Success) {
            showAddPresence = false
            editingPresence = null
            viewModel.clearAddPresenceResult()
        }
    }
    // Planning en cours d'édition (dialogue pré-rempli), et planning dont l'édition est bloquée
    // parce qu'il est en cours.
    var editingPlanning by remember { mutableStateOf<Planning?>(null) }
    var blockedEditPlanning by remember { mutableStateOf<Planning?>(null) }
    // Planning en attente de confirmation de suppression.
    var planningToDelete by remember { mutableStateOf<Planning?>(null) }

    // Ferme le dialogue dès qu'un ajout/édition aboutit ; les conflits le laissent ouvert.
    LaunchedEffect(addPlanningResult) {
        if (addPlanningResult is CreatePlanningResult.Success) {
            showAddPlanning = false
            editingPlanning = null
            viewModel.clearAddPlanningResult()
        }
    }
    // Minuteur en attente de résolution du conflit avec la simulation de présence.
    var pendingTimer by remember { mutableStateOf<PendingTimer?>(null) }
    // Minuteur lancé pendant un planning en cours : simple avertissement (le planning n'est pas
    // un conflit, il reprendra la main à sa prochaine occurrence).
    var planningWarning by remember { mutableStateOf<Pair<PendingTimer, Planning>?>(null) }

    fun requestStartTimer(seconds: Int, label: String, thresholdW: Int? = null) {
        val activePlanning = plannings.firstOrNull { it.isActiveNow() }
        when {
            // La présence est un vrai conflit (elle pilote la prise en continu) : on la traite d'abord.
            presenceActive -> pendingTimer = PendingTimer(seconds, label, thresholdW)
            activePlanning != null ->
                planningWarning = PendingTimer(seconds, label, thresholdW) to activePlanning
            else -> viewModel.startTimer(seconds, label, thresholdW)
        }
    }

    var elapsedNow by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsedNow = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.updatePermission(LocalNetworkPermission.isUsable(context))
            while (true) {
                viewModel.refresh()
                delay(5_000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val dev = device ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceHeader(dev)
            StatusBadge(status = status, elapsedNow = elapsedNow)

            if (dev.supportsSwitch) {
                HorizontalDivider()
                TimerSection(
                    status = status,
                    elapsedNow = elapsedNow,
                    onPreset = { seconds, label -> requestStartTimer(seconds, label) },
                    onCustom = { showSheet = true },
                    onCancel = { viewModel.cancelTimer() },
                )

                HorizontalDivider()
                PlanningSection(
                    plannings = plannings,
                    onEdit = { p ->
                        viewModel.clearAddPlanningResult()
                        // Un planning en cours ne peut pas être édité (supprimerait l'extinction
                        // active) : on l'explique au lieu d'ouvrir le dialogue.
                        if (p.isActiveNow()) blockedEditPlanning = p else editingPlanning = p
                    },
                    onAdd = {
                        viewModel.clearAddPlanningResult()
                        showAddPlanning = true
                    },
                    onDelete = { planningToDelete = it },
                )
            }

            if (dev.hasScripting) {
                HorizontalDivider()
                PresenceSection(
                    windows = presenceWindows,
                    onAdd = {
                        viewModel.clearAddPresenceResult()
                        showAddPresence = true
                    },
                    onEdit = { editingPresence = it },
                    onDelete = { presenceToDelete = it },
                )
            }

            HorizontalDivider()
            ActivitySection(logs)
        }
    }

    if (showSheet) {
        DurationPickerSheet(
            hasPowerMetering = device?.hasPowerMetering ?: false,
            onDismiss = { showSheet = false },
            onConfirm = { seconds, label, thresholdW ->
                showSheet = false
                requestStartTimer(seconds, label, thresholdW)
            },
        )
    }

    pendingTimer?.let { pt ->
        ConflictDialog(
            onCancel = { pendingTimer = null },
            onLaunchAnyway = {
                viewModel.startTimer(pt.seconds, pt.label, pt.thresholdW)
                pendingTimer = null
            },
            onStopPresence = {
                viewModel.stopPresenceThenStartTimer(pt.seconds, pt.label, pt.thresholdW)
                pendingTimer = null
            },
        )
    }

    planningWarning?.let { (pt, planning) ->
        PlanningInProgressDialog(
            planning = planning,
            onCancel = { planningWarning = null },
            onConfirm = {
                viewModel.startTimer(pt.seconds, pt.label, pt.thresholdW)
                planningWarning = null
            },
        )
    }

    if (showAddPlanning || editingPlanning != null) {
        AddPlanningDialog(
            initial = editingPlanning,
            result = addPlanningResult,
            onValidate = { sh, sm, eh, em, days ->
                val edit = editingPlanning
                if (edit != null) viewModel.updatePlanning(edit, sh, sm, eh, em, days)
                else viewModel.addPlanning(sh, sm, eh, em, days)
            },
            onDismiss = {
                showAddPlanning = false
                editingPlanning = null
                viewModel.clearAddPlanningResult()
            },
        )
    }

    blockedEditPlanning?.let {
        AlertDialog(
            onDismissRequest = { blockedEditPlanning = null },
            title = { Text(stringResource(R.string.planning_edit_blocked_title)) },
            text = { Text(stringResource(R.string.planning_edit_blocked_message)) },
            confirmButton = {
                TextButton(onClick = { blockedEditPlanning = null }) {
                    Text(stringResource(R.string.planning_ok))
                }
            },
        )
    }

    planningToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { planningToDelete = null },
            title = { Text(stringResource(R.string.planning_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.planning_delete_message,
                            "%02d:%02d – %02d:%02d".format(p.startHour, p.startMinute, p.endHour, p.endMinute),
                        ),
                    )
                    // La suppression d'un planning en cours retire l'extinction : on prévient.
                    if (p.isActiveNow()) {
                        Text(
                            text = stringResource(R.string.planning_delete_active_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlanning(p)
                    planningToDelete = null
                }) { Text(stringResource(R.string.planning_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { planningToDelete = null }) {
                    Text(stringResource(R.string.conflict_cancel))
                }
            },
        )
    }

    if (showAddPresence || editingPresence != null) {
        AddPresenceDialog(
            initial = editingPresence,
            result = addPresenceResult,
            onValidate = { sh, sm, eh, em, margin ->
                val edit = editingPresence
                val w = PresenceWindow(sh, sm, eh, em, margin)
                if (edit != null) viewModel.updatePresenceWindow(edit, w) else viewModel.addPresenceWindow(w)
            },
            onDismiss = {
                showAddPresence = false
                editingPresence = null
                viewModel.clearAddPresenceResult()
            },
        )
    }

    presenceToDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { presenceToDelete = null },
            title = { Text(stringResource(R.string.presence_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.presence_delete_message,
                            "%02d:%02d – %02d:%02d".format(w.startHour, w.startMinute, w.endHour, w.endMinute),
                        ),
                    )
                    if (w.isActiveNow()) {
                        Text(
                            text = stringResource(R.string.presence_delete_active_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePresenceWindow(w)
                    presenceToDelete = null
                }) { Text(stringResource(R.string.planning_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { presenceToDelete = null }) {
                    Text(stringResource(R.string.conflict_cancel))
                }
            },
        )
    }
}

@Composable
private fun PresenceSection(
    windows: List<PresenceWindow>,
    onAdd: () -> Unit,
    onEdit: (PresenceWindow) -> Unit,
    onDelete: (PresenceWindow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.presence_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (windows.isEmpty()) {
            Text(
                text = stringResource(R.string.presence_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            windows.forEach { w -> PresenceRow(w, onEdit = { onEdit(w) }, onDelete = { onDelete(w) }) }
        }
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.presence_add))
        }
    }
}

@Composable
private fun PresenceRow(window: PresenceWindow, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = "%02d:%02d – %02d:%02d".format(window.startHour, window.startMinute, window.endHour, window.endMinute),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.presence_margin_value, window.marginMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.presence_delete))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPresenceDialog(
    initial: PresenceWindow?,
    result: PresenceOpResult?,
    onValidate: (Int, Int, Int, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var startHour by remember { mutableStateOf(initial?.startHour ?: 19) }
    var startMinute by remember { mutableStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(initial?.endHour ?: 23) }
    var endMinute by remember { mutableStateOf(initial?.endMinute ?: 0) }
    var margin by remember { mutableStateOf(initial?.marginMinutes ?: 20) }

    val startMin = startHour * 60 + startMinute
    val endMin = endHour * 60 + endMinute
    val valid = startMin != endMin

    val errorText: String? = when {
        result is PresenceOpResult.PlanningOverlap -> stringResource(R.string.presence_conflict_planning)
        result is PresenceOpResult.Error -> stringResource(R.string.planning_error_generic)
        startMin == endMin -> stringResource(R.string.presence_error_end_before_start)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.presence_dialog_title else R.string.presence_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.planning_start), style = MaterialTheme.typography.bodyMedium)
                        TimeWheelPicker(startHour, startMinute) { h, m -> startHour = h; startMinute = m }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.planning_end), style = MaterialTheme.typography.bodyMedium)
                        TimeWheelPicker(endHour, endMinute) { h, m -> endHour = h; endMinute = m }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.presence_margin),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { margin = (margin - 5).coerceAtLeast(0) }) { Text("−") }
                    Text(stringResource(R.string.presence_margin_value, margin), fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { margin = (margin + 5).coerceAtMost(120) }) { Text("+") }
                }
                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onValidate(startHour, startMinute, endHour, endMinute, margin) },
            ) { Text(stringResource(R.string.planning_validate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_cancel)) }
        },
    )
}

/** Minuteur en attente de résolution du conflit présence (durée, libellé, seuil de coupure). */
private data class PendingTimer(val seconds: Int, val label: String, val thresholdW: Int?)

@Composable
private fun ConflictDialog(
    onCancel: () -> Unit,
    onLaunchAnyway: () -> Unit,
    onStopPresence: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = { Text(stringResource(R.string.conflict_message)) },
        // Trois choix empilés verticalement (pleine largeur) pour rester lisibles.
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.TextButton(
                    onClick = onStopPresence,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_stop_presence)) }
                androidx.compose.material3.TextButton(
                    onClick = onLaunchAnyway,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_launch_anyway)) }
                androidx.compose.material3.TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_cancel)) }
            }
        },
    )
}

/**
 * Avertissement (et non blocage) quand un minuteur est lancé alors qu'un planning est en cours :
 * les deux coexistent, mais la fin du minuteur éteindra la prise avant la fin du créneau. Le
 * planning n'est pas modifié et reprendra la main à sa prochaine occurrence.
 */
@Composable
private fun PlanningInProgressDialog(
    planning: Planning,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val end = "%02d:%02d".format(planning.endHour, planning.endMinute)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.timer_planning_active_title)) },
        text = { Text(stringResource(R.string.timer_planning_active_message, end)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.timer_planning_active_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// Ordre d'affichage lundi → dimanche ; l'indice suit le cron Shelly (0 = dimanche).
private val WEEK_DAYS = listOf(1, 2, 3, 4, 5, 6, 0)

@Composable
private fun PlanningSection(
    plannings: List<Planning>,
    onEdit: (Planning) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Planning) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.planning_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (plannings.isEmpty()) {
            Text(
                text = stringResource(R.string.planning_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            plannings.forEach { p -> PlanningRow(p, onEdit = { onEdit(p) }, onDelete = { onDelete(p) }) }
        }
        val atLimit = plannings.size >= 10
        OutlinedButton(onClick = onAdd, enabled = !atLimit) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.planning_add))
        }
        if (atLimit) {
            Text(
                text = stringResource(R.string.planning_limit_reached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanningRow(planning: Planning, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Un tap sur les horaires ouvre l'édition (sauf si le planning est en cours).
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = "%02d:%02d – %02d:%02d".format(
                    planning.startHour, planning.startMinute, planning.endHour, planning.endMinute,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = daysSummary(planning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.planning_delete))
        }
    }
}

@Composable
private fun daysSummary(planning: Planning): String {
    if (planning.everyDay) return stringResource(R.string.planning_every_day)
    // Libellés résolus ici (contexte composable), puis assemblés hors lambda composable.
    val labels = mapOf(
        1 to stringResource(R.string.planning_day_mon),
        2 to stringResource(R.string.planning_day_tue),
        3 to stringResource(R.string.planning_day_wed),
        4 to stringResource(R.string.planning_day_thu),
        5 to stringResource(R.string.planning_day_fri),
        6 to stringResource(R.string.planning_day_sat),
        0 to stringResource(R.string.planning_day_sun),
    )
    return WEEK_DAYS.filter { it in planning.days }.mapNotNull { labels[it] }.joinToString(", ")
}

@Composable
private fun dayLabel(day: Int): String = stringResource(
    when (day) {
        1 -> R.string.planning_day_mon
        2 -> R.string.planning_day_tue
        3 -> R.string.planning_day_wed
        4 -> R.string.planning_day_thu
        5 -> R.string.planning_day_fri
        6 -> R.string.planning_day_sat
        else -> R.string.planning_day_sun
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddPlanningDialog(
    initial: Planning?,
    result: CreatePlanningResult?,
    onValidate: (Int, Int, Int, Int, Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var startHour by remember { mutableStateOf(initial?.startHour ?: 9) }
    var startMinute by remember { mutableStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(initial?.endHour ?: 17) }
    var endMinute by remember { mutableStateOf(initial?.endMinute ?: 0) }
    // Deux modes exclusifs : « tous les jours » OU une sélection de jours précis. [everyDay]
    // porte l'état propre du chip « Tous les jours » ; [days] les jours précis (mode contraire).
    var everyDay by remember { mutableStateOf(initial?.everyDay ?: true) }
    var days by remember { mutableStateOf(if (initial != null && !initial.everyDay) initial.days else emptySet()) }
    val effectiveDays = if (everyDay) setOf(0, 1, 2, 3, 4, 5, 6) else days

    val startMin = startHour * 60 + startMinute
    val endMin = endHour * 60 + endMinute
    // Début == fin interdit (créneau nul ou de 24 h, ambigu) ; fin < début = créneau de nuit, OK.
    val valid = (everyDay || days.isNotEmpty()) && startMin != endMin

    val errorText: String? = when {
        result is CreatePlanningResult.Conflict -> stringResource(
            R.string.planning_conflict,
            "%02d:%02d – %02d:%02d".format(
                result.existing.startHour, result.existing.startMinute,
                result.existing.endHour, result.existing.endMinute,
            ),
        )
        result is CreatePlanningResult.PresenceOverlap -> stringResource(R.string.planning_conflict_presence)
        result is CreatePlanningResult.LimitReached -> stringResource(R.string.planning_limit_reached)
        result is CreatePlanningResult.Error -> stringResource(R.string.planning_error_generic)
        !everyDay && days.isEmpty() -> stringResource(R.string.planning_error_no_day)
        startMin == endMin -> stringResource(R.string.planning_error_range)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.planning_dialog_title else R.string.planning_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.planning_start), style = MaterialTheme.typography.bodyMedium)
                        TimeWheelPicker(startHour, startMinute) { h, m -> startHour = h; startMinute = m }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.planning_end), style = MaterialTheme.typography.bodyMedium)
                        TimeWheelPicker(endHour, endMinute) { h, m -> endHour = h; endMinute = m }
                    }
                }

                Text(stringResource(R.string.planning_days), style = MaterialTheme.typography.bodyMedium)
                // Deux modes exclusifs. « Tous les jours » a son propre état : le taper l'active
                // et vide la sélection précise ; taper un jour bascule en mode « jours précis »
                // et éteint « Tous les jours ». Jamais les deux allumés en même temps.
                FilterChip(
                    selected = everyDay,
                    onClick = {
                        everyDay = true
                        days = emptySet()
                    },
                    label = { Text(stringResource(R.string.planning_all_days)) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEK_DAYS.forEach { d ->
                        FilterChip(
                            selected = !everyDay && d in days,
                            onClick = {
                                everyDay = false
                                days = if (d in days) days - d else days + d
                            },
                            label = { Text(dayLabel(d)) },
                        )
                    }
                }

                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onValidate(startHour, startMinute, endHour, endMinute, effectiveDays) },
            ) { Text(stringResource(R.string.planning_validate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_cancel)) }
        },
    )
}

@Composable
private fun DeviceHeader(device: Device) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = iconFor(device.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column {
            Text(device.name, style = MaterialTheme.typography.titleLarge)
            Text(
                text = device.ipAddress,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            device.model?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerSection(
    status: TileStatus,
    elapsedNow: Long,
    onPreset: (Int, String) -> Unit,
    onCustom: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_timer_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        val online = status as? TileStatus.Online
        val remaining = online?.timerEndsAtElapsed?.let { ((it - elapsedNow) / 1000).coerceAtLeast(0) }

        if (remaining != null && remaining > 0) {
            Text(
                text = stringResource(R.string.detail_timer_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatCountdown(remaining),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = onCancel) {
                Text(stringResource(R.string.detail_timer_cancel))
            }
        } else {
            if (status is TileStatus.Offline) {
                Text(
                    text = stringResource(R.string.detail_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3600, 7200, 10800).forEach { seconds ->
                    val label = durationLabel(seconds)
                    OutlinedButton(onClick = { onPreset(seconds, label) }) { Text(label) }
                }
                OutlinedButton(onClick = onCustom) {
                    Text(stringResource(R.string.detail_timer_custom))
                }
            }
        }
    }
}

@Composable
private fun ActivitySection(logs: List<ActivationLog>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_activity_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (logs.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_activity_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            logs.forEach { log -> ActivityRow(log) }
        }
    }
}

@Composable
private fun ActivityRow(log: ActivationLog) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatLogTimestamp(log.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(activationLabel(log.action), style = MaterialTheme.typography.bodyMedium)
            log.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Libellé d'une durée en secondes (« 1 h », « 2 h 30 min », « 45 min »). */
@Composable
fun durationLabel(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> stringResource(R.string.duration_hours_minutes, h, m)
        h > 0 -> stringResource(R.string.duration_hours, h)
        else -> stringResource(R.string.duration_minutes, m)
    }
}

@Composable
private fun activationLabel(action: ActivationAction): String = stringResource(
    when (action) {
        ActivationAction.TURNED_ON -> R.string.activity_turned_on
        ActivationAction.TURNED_OFF -> R.string.activity_turned_off
        ActivationAction.TIMER_STARTED -> R.string.activity_timer_started
        ActivationAction.TIMER_CANCELLED -> R.string.activity_timer_cancelled
        ActivationAction.PRESENCE_DEPLOYED -> R.string.activity_presence_deployed
        ActivationAction.PRESENCE_STOPPED -> R.string.activity_presence_stopped
        ActivationAction.PLANNING_ADDED -> R.string.activity_planning_added
        ActivationAction.PLANNING_REMOVED -> R.string.activity_planning_removed
        ActivationAction.PLANNING_MODIFIED -> R.string.activity_planning_modified
    },
)

private fun iconFor(type: DeviceType): ImageVector = when (type) {
    DeviceType.PLUG -> Icons.Filled.Power
    DeviceType.LAMP -> Icons.Filled.Lightbulb
    DeviceType.SENSOR -> Icons.Filled.Sensors
}

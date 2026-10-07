package kapoue.hestia.ui.screens.detail

import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kapoue.hestia.R
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.core.util.formatDate
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.core.util.formatTimeRange
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.ui.components.StatusBadge
import kapoue.hestia.ui.components.TimeWheelPicker
import kapoue.hestia.ui.components.ValueWheelPicker
import kapoue.hestia.ui.icons.SmokeDetectorIcon
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.screens.dashboard.PresenceInfo
import kapoue.hestia.ui.screens.dashboard.SensorStatus
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.formatLastContact
import kapoue.hestia.ui.theme.stateColors
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sensorStatus by viewModel.sensorStatus.collectAsStateWithLifecycle()
    val lightStatus by viewModel.lightStatus.collectAsStateWithLifecycle()
    val lightRevision by viewModel.lightRevision.collectAsStateWithLifecycle()
    val lightError by viewModel.lightError.collectAsStateWithLifecycle()
    val cutoffState by viewModel.cutoffState.collectAsStateWithLifecycle()
    val cutoffCandidates by viewModel.cutoffCandidates.collectAsStateWithLifecycle()
    val cutoffMessage by viewModel.cutoffMessage.collectAsStateWithLifecycle()
    val activeIp by viewModel.activeIp.collectAsStateWithLifecycle()
    val plannings by viewModel.plannings.collectAsStateWithLifecycle()
    val addPlanningResult by viewModel.addPlanningResult.collectAsStateWithLifecycle()
    val pausedPlannings by viewModel.pausedPlannings.collectAsStateWithLifecycle()
    val resumePlanningError by viewModel.resumePlanningError.collectAsStateWithLifecycle()
    val buttonTimerConfig by viewModel.buttonTimerConfig.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Coupure de prise (Lot 5) : la case cochée ne resterait pas cochée si le détecteur est
    // injoignable au moment du clic, sans ce message on penserait à un bug (retour David, 2026-09-03).
    LaunchedEffect(cutoffMessage) {
        cutoffMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_LONG).show()
            viewModel.consumeCutoffMessage()
        }
    }

    LaunchedEffect(lightError) {
        lightError?.let {
            Toast.makeText(context, resources.getString(it.res), Toast.LENGTH_LONG).show()
            viewModel.consumeLightError()
        }
    }

    // Lecture locale pure (pas de RPC), réévaluée à chaque recomposition — donc à jour après
    // chaque relevé, comme les autres États collectés ci-dessus. Une présence désactivée pour
    // aujourd'hui (bouton ON/OFF du Tableau) ne doit jamais se dire « en cours » ici (retour
    // David, 2026-08-22 : le badge et la liste des plannings l'ignoraient tous les deux).
    val presenceDisabledToday = viewModel.isPresenceDisabledToday()
    // Même principe côté planning récurrent (bouton ON/OFF app ou physique) — jamais pour un
    // planning Unique, qui n'a pas de « lendemain » à distinguer.
    val planningDisabledToday = viewModel.isPlanningDisabledToday()
    fun Planning.isReallyActive(): Boolean = isActiveNow() &&
        !(isPresence && presenceDisabledToday) &&
        !(!isPresence && !once && planningDisabledToday)

    // Emplacement (1 ou 2) du réglage Perso en cours d'ajout/édition/suppression ; null = fermé.
    var editingPresetSlot by remember { mutableStateOf<Int?>(null) }
    var deletingPresetSlot by remember { mutableStateOf<Int?>(null) }
    var showAddPlanning by remember { mutableStateOf(false) }
    var showButtonTimerSheet by remember { mutableStateOf(false) }
    var showButtonTimerDisableConfirm by remember { mutableStateOf(false) }
    // Planning en cours d'édition (dialogue pré-rempli), et planning dont l'édition est bloquée
    // parce qu'il est en cours.
    var editingPlanning by remember { mutableStateOf<Planning?>(null) }
    var blockedEditPlanning by remember { mutableStateOf<Planning?>(null) }
    // Planning en attente de confirmation de suppression.
    var planningToDelete by remember { mutableStateOf<Planning?>(null) }
    // Planning en attente de confirmation de mise en pause (uniquement s'il est en cours).
    var planningToPause by remember { mutableStateOf<Planning?>(null) }
    // Planning en pause en attente de confirmation d'oubli définitif.
    var pausedPlanningToDelete by remember { mutableStateOf<PausedPlanning?>(null) }

    // Ferme le dialogue dès qu'un ajout/édition aboutit ; les conflits le laissent ouvert.
    LaunchedEffect(addPlanningResult) {
        if (addPlanningResult is CreatePlanningResult.Success) {
            showAddPlanning = false
            editingPlanning = null
            viewModel.clearAddPlanningResult()
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
                // Pas de titre ici (retour David, 2026-09-17) : DeviceHeader juste en dessous
                // affiche déjà le nom, en plus visible (icône + IP + modèle) — le répéter ici
                // faisait doublon pur, sans rien apporter de plus.
                title = {},
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
            DeviceHeader(dev, activeIp ?: dev.ipAddress)

            if (dev.isLight) {
                // Variateur (2026-10-07) : chemin séparé, aucune section relais (minuteur,
                // planning, présence) — même principe que le détecteur de fumée ci-dessous.
                HorizontalDivider()
                LightSection(
                    status = lightStatus,
                    showPower = dev.hasPowerMetering,
                    revision = lightRevision,
                    onSet = { on, brightness -> viewModel.setLight(on, brightness) },
                )
                return@Column
            }

            if (dev.type == DeviceType.SMOKE_DETECTOR) {
                // Chemin entièrement séparé (voir SMOKE-DETECTOR.md) : StatusBadge est construit
                // autour de TileStatus (relais/minuteur/planning), sans objet ici.
                HorizontalDivider()
                SmokeDetectorSection(
                    sensorStatus = sensorStatus,
                    onMute = { viewModel.muteAlarm() },
                )
                HorizontalDivider()
                SmokeCutoffSection(
                    state = cutoffState,
                    candidates = cutoffCandidates.filter { it.id != dev.id },
                    onTargetsChange = { viewModel.setCutoffTargets(it) },
                )
                return@Column
            }

            // Même classification que le Tableau (voir StatusBadge) : présence et planning
            // reconstruits depuis la même liste unifiée que le reste de l'écran, jamais une
            // requête de plus.
            val activePresence = plannings.firstOrNull { it.isPresence && it.isReallyActive() }
            val activePlanning = plannings.firstOrNull { !it.isPresence && it.isReallyActive() }
            StatusBadge(
                status = status,
                elapsedNow = elapsedNow,
                presence = activePresence?.let { PresenceInfo(it.startHour, it.startMinute, it.endHour, it.endMinute) },
                activePlanning = activePlanning,
            )

            // Consommation instantanée, synchronisée sur le même relevé 5 s que le reste de
            // l'écran — jusqu'ici absente ici, ce qui donnait l'impression à tort que le seul
            // nombre visible pendant un minuteur (« Coupure à X W », un seuil fixe, pas une
            // mesure) était censé varier (retour David, 2026-09-14). Même format que la tuile
            // du Tableau (formatPower), pour qu'ils affichent toujours la même valeur.
            if (dev.hasPowerMetering) {
                (status as? TileStatus.Online)?.powerWatts?.let { watts ->
                    Text(
                        text = formatPower(watts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (dev.supportsSwitch) {
                HorizontalDivider()
                val presets = listOf(
                    PersonalPreset(1, dev.presetName, dev.presetDurationSeconds, dev.presetThresholdW, dev.presetUnlimited),
                    PersonalPreset(2, dev.preset2Name, dev.preset2DurationSeconds, dev.preset2ThresholdW, dev.preset2Unlimited),
                )
                TimerSection(
                    presets = presets,
                    onAddPreset = { slot -> editingPresetSlot = slot },
                    onEditPreset = { slot -> editingPresetSlot = slot },
                    onDeletePreset = { slot -> deletingPresetSlot = slot },
                )

                HorizontalDivider()
                PlanningSection(
                    plannings = plannings,
                    presenceDisabledToday = presenceDisabledToday,
                    planningDisabledToday = planningDisabledToday,
                    onEdit = { p ->
                        viewModel.clearAddPlanningResult()
                        // Un planning en cours ne peut pas être édité (supprimerait l'extinction
                        // active) : on l'explique au lieu d'ouvrir le dialogue.
                        if (p.isReallyActive()) blockedEditPlanning = p else editingPlanning = p
                    },
                    onAdd = {
                        viewModel.clearAddPlanningResult()
                        showAddPlanning = true
                    },
                    onDelete = { planningToDelete = it },
                    onPause = { p -> if (p.isReallyActive()) planningToPause = p else viewModel.pausePlanning(p) },
                    pausedPlannings = pausedPlannings,
                    onResume = { viewModel.resumePlanning(it) },
                    onDeletePaused = { pausedPlanningToDelete = it },
                )
            }

            if (dev.hasScripting) {
                HorizontalDivider()
                ButtonTimerSection(
                    config = buttonTimerConfig,
                    onConfigure = { showButtonTimerSheet = true },
                    onDisable = { showButtonTimerDisableConfirm = true },
                )
            }
        }
    }

    editingPresetSlot?.let { slot ->
        // Pré-remplit avec le réglage existant en édition, sinon la même valeur par défaut que
        // "Manuel" (30 min, sans coupure).
        val existingName = if (slot == 1) device?.presetName else device?.preset2Name
        val existingSeconds = if (slot == 1) device?.presetDurationSeconds else device?.preset2DurationSeconds
        val existingThreshold = if (slot == 1) device?.presetThresholdW else device?.preset2ThresholdW
        val existingUnlimited = if (slot == 1) device?.presetUnlimited else device?.preset2Unlimited
        val isNew = existingSeconds == null && existingUnlimited != true
        DurationPickerSheet(
            hasPowerMetering = device?.hasPowerMetering ?: false,
            title = stringResource(if (isNew) R.string.timer_preset_new_title else R.string.timer_preset_edit_title),
            confirmLabel = stringResource(R.string.timer_preset_save),
            confirmIcon = Icons.Filled.Save,
            initialHours = (existingSeconds ?: 1800) / 3600,
            initialMinutes = ((existingSeconds ?: 1800) % 3600) / 60,
            initialCutoffEnabled = existingThreshold != null,
            initialThresholdW = existingThreshold ?: 10,
            initialUnlimited = existingUnlimited ?: false,
            showNameField = true,
            initialName = existingName.orEmpty(),
            onDismiss = { editingPresetSlot = null },
            onConfirm = { seconds, _, thresholdW, name ->
                editingPresetSlot = null
                viewModel.savePreset(slot, name, seconds, thresholdW)
            },
        )
    }

    deletingPresetSlot?.let { slot ->
        DeletePresetConfirmDialog(
            onConfirm = {
                deletingPresetSlot = null
                viewModel.deletePreset(slot)
            },
            onDismiss = { deletingPresetSlot = null },
        )
    }


    if (showAddPlanning || editingPlanning != null) {
        AddPlanningDialog(
            initial = editingPlanning,
            result = addPlanningResult,
            hasCutoff = (device?.hasScripting ?: false) && (device?.hasPowerMetering ?: false),
            hasPresence = device?.hasScripting ?: false,
            onValidate = { sh, sm, eh, em, days, date, thresholdW, marginMinutes ->
                val edit = editingPlanning
                if (edit != null) viewModel.updatePlanning(edit, sh, sm, eh, em, days, date, thresholdW, marginMinutes)
                else viewModel.addPlanning(sh, sm, eh, em, days, date, thresholdW, marginMinutes)
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
                            formatTimeRange(p.startHour, p.startMinute, p.endHour, p.endMinute),
                        ),
                    )
                    // La suppression d'un planning en cours retire l'extinction : on prévient.
                    if (p.isReallyActive()) {
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

    // Confirmation de mise en pause : uniquement pour un planning en cours (même avertissement
    // que la suppression, la prise s'éteint puisque le programme est retiré de l'appareil).
    planningToPause?.let { p ->
        AlertDialog(
            onDismissRequest = { planningToPause = null },
            title = { Text(stringResource(R.string.planning_pause_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.planning_pause_message,
                            formatTimeRange(p.startHour, p.startMinute, p.endHour, p.endMinute),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.planning_pause_active_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.pausePlanning(p)
                    planningToPause = null
                }) { Text(stringResource(R.string.planning_pause_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { planningToPause = null }) {
                    Text(stringResource(R.string.conflict_cancel))
                }
            },
        )
    }

    pausedPlanningToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pausedPlanningToDelete = null },
            title = { Text(stringResource(R.string.planning_paused_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.planning_paused_delete_message,
                        formatTimeRange(p.startHour, p.startMinute, p.endHour, p.endMinute),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePausedPlanning(p)
                    pausedPlanningToDelete = null
                }) { Text(stringResource(R.string.planning_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pausedPlanningToDelete = null }) {
                    Text(stringResource(R.string.conflict_cancel))
                }
            },
        )
    }

    resumePlanningError?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeResumePlanningError() },
            title = { Text(stringResource(R.string.planning_resume_error_title)) },
            text = { createPlanningResultMessage(result)?.let { Text(it) } },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeResumePlanningError() }) {
                    Text(stringResource(R.string.planning_ok))
                }
            },
        )
    }


    if (showButtonTimerSheet) {
        val existing = buttonTimerConfig
        DurationPickerSheet(
            hasPowerMetering = device?.hasPowerMetering ?: false,
            title = stringResource(R.string.detail_button_timer_dialog_title),
            confirmLabel = stringResource(R.string.timer_preset_save),
            confirmIcon = Icons.Filled.Save,
            initialHours = (existing?.durationSeconds ?: 1800) / 3600,
            initialMinutes = ((existing?.durationSeconds ?: 1800) % 3600) / 60,
            initialCutoffEnabled = existing?.thresholdW != null,
            initialThresholdW = existing?.thresholdW ?: 10,
            initialUnlimited = existing?.enabled == true && existing.durationSeconds == null,
            onDismiss = { showButtonTimerSheet = false },
            onConfirm = { seconds, _, thresholdW, _ ->
                showButtonTimerSheet = false
                viewModel.setButtonTimer(true, seconds, thresholdW)
            },
        )
    }

    if (showButtonTimerDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showButtonTimerDisableConfirm = false },
            title = { Text(stringResource(R.string.detail_button_timer_disable_confirm_title)) },
            text = { Text(stringResource(R.string.detail_button_timer_disable_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showButtonTimerDisableConfirm = false
                    viewModel.setButtonTimer(false, 1800, null)
                }) { Text(stringResource(R.string.detail_button_timer_disable)) }
            },
            dismissButton = {
                TextButton(onClick = { showButtonTimerDisableConfirm = false }) {
                    Text(stringResource(R.string.conflict_cancel))
                }
            },
        )
    }
}

/**
 * Minuteur déclenché par le bouton physique : un appui arme un minuteur (avec coupure sur seuil
 * optionnelle) au lieu d'une simple bascule. [config] reflète toujours l'état réel déployé sur
 * l'appareil (jamais supposé) — null tant qu'il n'a pas encore été relu.
 */
@Composable
private fun ButtonTimerSection(
    config: DeviceRepository.ButtonTimerConfig?,
    onConfigure: () -> Unit,
    onDisable: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_button_timer_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.detail_button_timer_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (config?.enabled == true) {
            val seconds = config.durationSeconds
            PersonalPresetRow(
                label = if (seconds != null) {
                    stringResource(R.string.detail_button_timer_row_label, durationLabel(seconds))
                } else {
                    stringResource(R.string.detail_button_timer_row_label_unlimited)
                },
                detail = if (seconds == null && config.thresholdW != null) {
                    stringResource(R.string.timer_preset_unlimited_detail, config.thresholdW)
                } else if (seconds == null) {
                    // Ni durée ni coupure sur seuil (retour David, 2026-09-11) : l'appui bouton
                    // allume la prise sans aucune limite automatique.
                    stringResource(R.string.timer_preset_no_limit_detail)
                } else if (config.thresholdW != null) {
                    stringResource(R.string.timer_preset_cutoff_detail, config.thresholdW)
                } else {
                    stringResource(R.string.timer_preset_no_cutoff)
                },
                onEdit = onConfigure,
                onDelete = onDisable,
            )
        } else {
            OutlinedButton(onClick = onConfigure) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.detail_button_timer_configure))
            }
        }
    }
}

/** Minuteur en attente de résolution du conflit présence (durée, libellé, seuil de coupure). */
/** internal (pas private) : réutilisée par la modale rapide du Tableau (voir DeviceTile.ChannelQuickSheet). */
internal data class PendingTimer(val seconds: Int?, val label: String, val thresholdW: Int?)

@Composable
internal fun ConflictDialog(
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
internal fun PlanningInProgressDialog(
    planning: Planning,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val end = formatClockTime(planning.endHour, planning.endMinute)
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

private val PLANNING_CUTOFF_THRESHOLDS_W = listOf(5, 10, 20, 30, 40, 50)
private const val DEFAULT_PLANNING_THRESHOLD_W = 10

@Composable
private fun PlanningSection(
    plannings: List<Planning>,
    presenceDisabledToday: Boolean,
    planningDisabledToday: Boolean,
    onEdit: (Planning) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Planning) -> Unit,
    onPause: (Planning) -> Unit,
    pausedPlannings: List<PausedPlanning>,
    onResume: (PausedPlanning) -> Unit,
    onDeletePaused: (PausedPlanning) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.planning_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (plannings.isEmpty() && pausedPlannings.isEmpty()) {
            Text(
                text = stringResource(R.string.planning_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            plannings.forEach { p ->
                PlanningRow(
                    p,
                    isActive = p.isActiveNow() &&
                        !(p.isPresence && presenceDisabledToday) &&
                        !(!p.isPresence && !p.once && planningDisabledToday),
                    onEdit = { onEdit(p) },
                    onDelete = { onDelete(p) },
                    onPause = { onPause(p) },
                )
            }
        }
        // Le plafond ne concerne que les programmes cron : une simulation de présence n'en
        // consomme aucun (voir DeviceRepository.MAX_PLANNINGS).
        val atLimit = plannings.count { !it.isPresence } >= 10
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
        if (pausedPlannings.isNotEmpty()) {
            Text(
                text = stringResource(R.string.planning_paused_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pausedPlannings.forEach { p ->
                PausedPlanningRow(p, onResume = { onResume(p) }, onDelete = { onDeletePaused(p) })
            }
        }
    }
}

@Composable
private fun PlanningRow(planning: Planning, isActive: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onPause: () -> Unit) {
    // Mise en avant du planning qui pilote réellement la prise en ce moment (2026-08-17) — même
    // couleur que sur le Tableau, cohérence du vocabulaire visuel (Présence et Planifié ont
    // chacun la leur depuis le 2026-08-22). Jamais de couleur seule : le petit texte « En cours »
    // accompagne toujours la couleur.
    val colors = MaterialTheme.stateColors
    val activeColor = if (planning.isPresence) colors.presenceText else colors.plannedText
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Un tap sur les horaires ouvre l'édition (sauf si le planning est en cours).
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimeRange(
                        planning.startHour, planning.startMinute, planning.endHour, planning.endMinute,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (isActive) activeColor else Color.Unspecified,
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.planning_row_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = activeColor,
                    )
                }
            }
            Text(
                text = daysSummary(planning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            planning.cutoffThresholdW?.let {
                Text(
                    text = stringResource(R.string.timer_preset_cutoff_detail, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            planning.marginMinutes?.let {
                Text(
                    text = stringResource(R.string.presence_row_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onPause) {
            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.planning_pause))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.planning_delete))
        }
    }
}

/** Config identique à un [Planning], juste pour réutiliser [daysSummary] et le formatage horaire. */
private fun PausedPlanning.toDisplayPlanning(): Planning = Planning(
    startHour = startHour, startMinute = startMinute, endHour = endHour, endMinute = endMinute,
    days = days.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
    date = date?.let { LocalDate.parse(it) },
    cutoffThresholdW = cutoffThresholdW,
    marginMinutes = marginMinutes,
)

@Composable
private fun PausedPlanningRow(paused: PausedPlanning, onResume: () -> Unit, onDelete: () -> Unit) {
    val display = remember(paused) { paused.toDisplayPlanning() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTimeRange(
                    display.startHour, display.startMinute, display.endHour, display.endMinute,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = daysSummary(display),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            display.cutoffThresholdW?.let {
                Text(
                    text = stringResource(R.string.timer_preset_cutoff_detail, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            display.marginMinutes?.let {
                Text(
                    text = stringResource(R.string.presence_row_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onResume) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.planning_resume))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.planning_paused_delete))
        }
    }
}

/**
 * Résumé des jours d'un planning **existant** (liste) — noms complets ([dayLabelFull]), à ne pas
 * confondre avec [dayLabel] (abrégés, réservé aux puces de sélection du dialogue, où la place
 * manque). Confusion repérée en test réel le 2026-08-18 : « Mer » à côté d'une marge de présence
 * se lisait mal, sans lien évident avec un jour de la semaine.
 */
@Composable
private fun daysSummary(planning: Planning): String {
    val date = planning.date
    if (date != null) {
        return if (date == LocalDate.now()) {
            stringResource(R.string.planning_today)
        } else {
            "${dayLabelFull(date.dayOfWeek.value % 7)} ${formatDate(date)}"
        }
    }
    if (planning.everyDay) return stringResource(R.string.planning_every_day)
    // Libellés résolus ici (contexte composable), puis assemblés hors lambda composable.
    val labels = mapOf(
        1 to stringResource(R.string.planning_day_full_mon),
        2 to stringResource(R.string.planning_day_full_tue),
        3 to stringResource(R.string.planning_day_full_wed),
        4 to stringResource(R.string.planning_day_full_thu),
        5 to stringResource(R.string.planning_day_full_fri),
        6 to stringResource(R.string.planning_day_full_sat),
        0 to stringResource(R.string.planning_day_full_sun),
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

@Composable
private fun dayLabelFull(day: Int): String = stringResource(
    when (day) {
        1 -> R.string.planning_day_full_mon
        2 -> R.string.planning_day_full_tue
        3 -> R.string.planning_day_full_wed
        4 -> R.string.planning_day_full_thu
        5 -> R.string.planning_day_full_fri
        6 -> R.string.planning_day_full_sat
        else -> R.string.planning_day_full_sun
    },
)

/** Message associé à un résultat de création/modification/réactivation de planning, s'il y en a un. */
@Composable
private fun createPlanningResultMessage(result: CreatePlanningResult): String? = when (result) {
    is CreatePlanningResult.Conflict -> stringResource(
        R.string.planning_conflict,
        formatTimeRange(
            result.existing.startHour, result.existing.startMinute,
            result.existing.endHour, result.existing.endMinute,
        ),
    )
    CreatePlanningResult.PastOnce -> stringResource(R.string.planning_error_past)
    CreatePlanningResult.LimitReached -> stringResource(R.string.planning_limit_reached)
    CreatePlanningResult.Error -> stringResource(R.string.planning_error_generic)
    CreatePlanningResult.Success -> null
}

/**
 * Un seul dialogue pour les deux réalisations d'un planning depuis la fusion Planning/Présence du
 * 2026-08-18 : [hasPresence] affiche l'interrupteur « Simuler une présence » (masqué si l'appareil
 * ne fait pas tourner de scripts). Présence et Unique sont mutuellement exclusifs (une simulation
 * n'a de sens que récurrente), tout comme présence et coupure sur seuil (décidé le 2026-08-18,
 * voir [kapoue.hestia.domain.model.Planning]) — activer l'un désactive l'autre.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddPlanningDialog(
    initial: Planning?,
    result: CreatePlanningResult?,
    hasCutoff: Boolean,
    hasPresence: Boolean,
    onValidate: (Int, Int, Int, Int, Set<Int>, LocalDate?, Int?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var startHour by remember { mutableStateOf(initial?.startHour ?: 9) }
    var startMinute by remember { mutableStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(initial?.endHour ?: 17) }
    var endMinute by remember { mutableStateOf(initial?.endMinute ?: 0) }
    // Deux modes exclusifs : « tous les jours » OU une sélection de jours précis. [everyDay]
    // porte l'état propre du chip « Tous les jours » ; [days] les jours précis (mode contraire).
    var everyDay by remember { mutableStateOf(initial?.date == null && (initial?.everyDay ?: true)) }
    var days by remember { mutableStateOf(if (initial != null && !initial.everyDay) initial.days else emptySet()) }
    // Planning Unique (une seule occurrence, à une date précise) : mode à part, incompatible avec
    // « Tous les jours » et avec une simulation de présence. [onceDate] par défaut aujourd'hui,
    // choisi via les chips Aujourd'hui/jours.
    var once by remember { mutableStateOf(initial?.date != null) }
    var onceDate by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    // Coupure sur seuil (Unique comme récurrent, script dédié réarmé à chaque occurrence) —
    // incompatible avec une simulation de présence.
    var cutoffEnabled by remember { mutableStateOf(initial?.cutoffThresholdW != null) }
    var cutoffThreshold by remember { mutableStateOf(initial?.cutoffThresholdW ?: DEFAULT_PLANNING_THRESHOLD_W) }
    // Simulation de présence : marge aléatoire, jamais Unique, jamais de coupure sur seuil.
    var presenceEnabled by remember { mutableStateOf(initial?.marginMinutes != null) }
    var margin by remember { mutableStateOf(initial?.marginMinutes ?: 20) }
    val effectiveDays = if (everyDay) setOf(0, 1, 2, 3, 4, 5, 6) else days

    val startMin = startHour * 60 + startMinute
    val endMin = endHour * 60 + endMinute
    // Début == fin interdit (créneau nul ou de 24 h, ambigu) ; fin < début = créneau de nuit, OK.
    val valid = (once || everyDay || days.isNotEmpty()) && startMin != endMin

    // Empêche un double-clic pendant l'aller-retour réseau (~1 s) de créer un doublon — vécu en
    // vrai par David, 2026-08-22. Repasse à false dès qu'un résultat arrive : en erreur, pour
    // permettre de réessayer ; en succès, sans effet visible puisque le dialogue se ferme.
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(result) { if (result != null) submitting = false }

    val resultMessage = result?.let { createPlanningResultMessage(it) }
    val errorText: String? = when {
        resultMessage != null -> resultMessage
        !once && !everyDay && days.isEmpty() -> stringResource(R.string.planning_error_no_day)
        startMin == endMin -> stringResource(R.string.planning_error_range)
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Contenu variable (seuil et présence ajoutent chacun de la hauteur) : sans défilement,
                // un contenu plus haut que l'écran écrasait le bouton "Enregistrer" au lieu de le
                // pousser hors champ — repéré en test réel le 2026-08-18, seuil activé.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(if (initial == null) R.string.planning_dialog_title else R.string.planning_edit_title),
                style = MaterialTheme.typography.titleMedium,
            )
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

            // Unique masqué pendant une simulation de présence (mutuellement exclusifs) : rien à
            // désactiver, juste rien à montrer tant que présence est active.
            if (!presenceEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.planning_once),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = once,
                        onCheckedChange = { checked ->
                            once = checked
                            if (checked) {
                                everyDay = false
                            } else if (!everyDay && days.isEmpty()) {
                                // Retour au récurrent sans sélection restante (rien à préserver) :
                                // « Tous les jours » par défaut plutôt qu'un état invalide.
                                everyDay = true
                            }
                        },
                    )
                }
            }

            if (once) {
                Text(stringResource(R.string.planning_once_day), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = onceDate == LocalDate.now(),
                        onClick = { onceDate = LocalDate.now() },
                        label = { Text(stringResource(R.string.planning_today)) },
                    )
                    WEEK_DAYS.forEach { d ->
                        val occurrence = ScheduleCodec.nextOccurrence(d)
                        FilterChip(
                            selected = onceDate == occurrence,
                            onClick = { onceDate = occurrence },
                            label = { Text(dayLabel(d)) },
                        )
                    }
                }
            } else {
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
            }

            // Simulation de présence : masquée pendant Unique (mutuellement exclusifs).
            if (hasPresence && !once) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.presence_toggle_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = presenceEnabled,
                        onCheckedChange = { checked ->
                            presenceEnabled = checked
                            if (checked) cutoffEnabled = false
                        },
                    )
                }
                if (presenceEnabled) {
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
                }
            }

            // Coupure sur seuil : Unique comme récurrent, script dédié par planning — jamais avec
            // une simulation de présence (mutuellement exclusifs).
            if (hasCutoff && !presenceEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.timer_cutoff_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = cutoffEnabled, onCheckedChange = { cutoffEnabled = it })
                }
                if (cutoffEnabled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ValueWheelPicker(
                            values = PLANNING_CUTOFF_THRESHOLDS_W,
                            value = cutoffThreshold,
                            onChange = { cutoffThreshold = it },
                        )
                        Text(" Watts", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                enabled = valid && !submitting,
                onClick = {
                    submitting = true
                    onValidate(
                        startHour, startMinute, endHour, endMinute, effectiveDays,
                        if (once) onceDate else null,
                        if (hasCutoff && !presenceEnabled && cutoffEnabled) cutoffThreshold else null,
                        if (hasPresence && presenceEnabled) margin else null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Coche + texte au participe passé pendant l'appel réseau (retour David,
                // 2026-08-24 puis 2026-09-03) : confirme que le clic a bien été pris en compte,
                // pendant que le bouton reste désactivé.
                Icon(
                    if (submitting) Icons.Filled.Check else Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(if (submitting) R.string.planning_validate_done else R.string.planning_validate))
            }
        }
    }
}

@Composable
private fun DeviceHeader(device: Device, displayIp: String) {
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
                text = displayIp,
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

/**
 * Écran Détail d'un détecteur de fumée — voir SMOKE-DETECTOR.md. Volontairement séparé du reste
 * de l'écran (StatusBadge/TimerSection/PlanningSection/ButtonTimerSection), tous construits
 * autour d'un relais que ce type d'appareil n'a pas.
 */
@Composable
private fun SmokeDetectorSection(sensorStatus: SensorStatus, onMute: () -> Unit) {
    val colors = MaterialTheme.stateColors
    val stateColor: Color
    val stateLabel: String
    when (sensorStatus) {
        SensorStatus.Loading -> {
            stateColor = colors.idleText
            stateLabel = stringResource(R.string.state_loading)
        }
        SensorStatus.Offline -> {
            stateColor = colors.idleText
            stateLabel = stringResource(R.string.sensor_state_unreachable)
        }
        is SensorStatus.Online -> when {
            sensorStatus.alarm -> {
                stateColor = colors.offlineText
                stateLabel = stringResource(R.string.sensor_state_alarm)
            }
            sensorStatus.mute -> {
                stateColor = colors.idleText
                stateLabel = stringResource(R.string.sensor_state_mute)
            }
            sensorStatus.batteryError -> {
                stateColor = colors.idleText
                stateLabel = stringResource(R.string.sensor_state_battery_error)
            }
            else -> {
                stateColor = colors.activeText
                stateLabel = stringResource(R.string.sensor_state_normal)
            }
        }
    }
    val online = sensorStatus as? SensorStatus.Online

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stateLabel, style = MaterialTheme.typography.titleMedium, color = stateColor)
        // Rien tant qu'il n'y a pas de vraie donnée à dater (voir DeviceTile.SmokeDetectorTile).
        online?.updatedAtEpochSec?.let { epochSec ->
            Text(
                text = formatLastContact(epochSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        online?.batteryPercent?.let { percent ->
            val batteryColor = if (percent < 30) colors.warningText else MaterialTheme.colorScheme.onSurface
            Text(text = stringResource(R.string.sensor_battery_label, percent), color = batteryColor)
        }
        online?.temperatureC?.let { temperature ->
            Text(text = stringResource(R.string.sensor_temperature_label, temperature))
        }

        // Grisé hors alarme réelle : couper une alarme silencieuse n'a pas de sens (et
        // Smoke.Mute ne fait qu'assourdir le son, ne touche pas à la détection elle-même).
        Button(
            onClick = onMute,
            enabled = online?.alarm == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.VolumeOff, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.sensor_mute_action))
        }
        Text(
            text = stringResource(R.string.sensor_battery_threshold_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Pas de bouton Test : confirmé absent de l'API RPC Shelly (recherché le 2026-08-31),
        // le test ne se déclenche que physiquement sur l'appareil.
        Text(
            text = stringResource(R.string.sensor_no_remote_test),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Coupure d'une ou plusieurs prises en cas d'alarme réelle (Lot 5, voir SMOKE-DETECTOR.md) —
 * webhook natif du détecteur → `Switch.Set` direct sur chaque prise visée, **autonome, sans app
 * ni script** (contrairement au relais ntfy du Lot 4, qui n'avait pas cette option puisque ntfy
 * exige un POST). Pas de case « activé » séparée de la liste : au moins une prise cochée EST
 * l'état activé, tout décocher désactive.
 *
 * [DeviceRepository.SmokeCutoffState.Unknown] (détecteur jamais joint avec succès, endormi la
 * plupart du temps) affiche un état **distinct** d'une coupure désactivée — sans quoi un réglage
 * qui vient tout juste d'être fait donnerait l'impression de ne pas avoir pris (retour David,
 * 2026-09-03 : « ça laisse penser que la config n'est pas passée »).
 */
@Composable
private fun SmokeCutoffSection(
    state: DeviceRepository.SmokeCutoffState,
    candidates: List<Device>,
    onTargetsChange: (List<DeviceRepository.SmokeCutoffTarget>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sensor_cutoff_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.sensor_cutoff_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state !is DeviceRepository.SmokeCutoffState.Configured) {
            // Jamais lu avec succès : pas d'interrupteur à afficher (rien à activer/désactiver
            // tant qu'on ne sait pas ce qu'il y a vraiment), juste l'explication.
            Text(
                text = stringResource(R.string.sensor_cutoff_wake_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        val targets = state.targets

        // Replié tant qu'aucune cible n'est configurée ; s'ouvre tout seul dès que la lecture
        // réelle (fetch) en trouve — jamais géré comme un simple booléen local qui pourrait
        // diverger de ce que l'appareil a vraiment en mémoire.
        var expanded by remember { mutableStateOf(targets.isNotEmpty()) }
        LaunchedEffect(targets) { if (targets.isNotEmpty()) expanded = true }
        var manualIp by remember { mutableStateOf("") }

        fun toggle(target: DeviceRepository.SmokeCutoffTarget, checked: Boolean) {
            onTargetsChange(if (checked) targets + target else targets - target)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = expanded,
                onCheckedChange = { checked ->
                    expanded = checked
                    if (!checked) onTargetsChange(emptyList())
                },
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.sensor_cutoff_enable))
        }

        if (expanded) {
            for (device in candidates) {
                val target = DeviceRepository.SmokeCutoffTarget(device.ipAddress, device.switchId)
                val checked = target in targets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggle(target, !checked) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle(target, it) })
                    Text("${device.name} — ${device.ipAddress}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Cibles saisies à la main (IP absente de la liste des appareils connus) : sans
            // ligne dédiée, elles étaient ajoutées mais invisibles — aucun moyen de les voir ni
            // de les retirer (retour David, 2026-09-03).
            val knownIps = candidates.map { it.ipAddress to it.switchId }.toSet()
            val manualTargets = targets.filter { (it.ip to it.switchId) !in knownIps }
            for (target in manualTargets) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = target.ip,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onTargetsChange(targets - target) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sensor_cutoff_manual_remove))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text(stringResource(R.string.sensor_cutoff_manual_ip)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val ip = manualIp.trim()
                    if (ip.isNotEmpty()) {
                        onTargetsChange(targets + DeviceRepository.SmokeCutoffTarget(ip, 0))
                        manualIp = ""
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sensor_cutoff_manual_add))
                }
            }
            Text(
                text = stringResource(R.string.sensor_cutoff_dhcp_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Un des deux emplacements « Perso » ([slot] = 1 ou 2). Emplacement vide = ni [durationSeconds]
 * ni [unlimited]. [unlimited] = sans limite de durée, coupure sur seuil uniquement ([thresholdW]
 * alors toujours non nul) ; [durationSeconds] est alors ignoré (valeur résiduelle possible).
 */
/** internal (pas private) : réutilisée par la modale rapide du Tableau (voir DeviceTile.ChannelQuickSheet). */
internal data class PersonalPreset(
    val slot: Int,
    val name: String?,
    val durationSeconds: Int?,
    val thresholdW: Int?,
    val unlimited: Boolean = false,
) {
    val configured: Boolean get() = durationSeconds != null || unlimited
}

/**
 * Gestion des réglages Perso — créer, modifier, supprimer (jamais les lancer : ça, c'est la
 * modale rapide du Tableau désormais, voir DeviceTile.ChannelQuickSheet). Configurer ne montre
 * plus l'état d'un minuteur en cours ni de bouton Annuler — les deux vivaient ici avant l'ergonomie
 * à deux niveaux du 2026-09-21 (un tap = agir, deux taps = régler), désormais redondants avec la
 * modale.
 */
@Composable
private fun TimerSection(
    presets: List<PersonalPreset>,
    onAddPreset: (slot: Int) -> Unit,
    onEditPreset: (slot: Int) -> Unit,
    onDeletePreset: (slot: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_timer_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        presets.forEach { p ->
            if (p.configured) {
                PersonalPresetRow(
                    label = if (p.unlimited) {
                        p.name.orEmpty()
                    } else {
                        stringResource(R.string.timer_preset_row_label, p.name.orEmpty(), durationLabel(p.durationSeconds!!))
                    },
                    detail = if (p.unlimited && p.thresholdW != null) {
                        stringResource(R.string.timer_preset_unlimited_detail, p.thresholdW)
                    } else if (p.unlimited) {
                        // Ni durée ni coupure sur seuil (retour David, 2026-09-11) : simple
                        // allumage sans aucune limite automatique.
                        stringResource(R.string.timer_preset_no_limit_detail)
                    } else if (p.thresholdW != null) {
                        stringResource(R.string.timer_preset_cutoff_detail, p.thresholdW)
                    } else {
                        stringResource(R.string.timer_preset_no_cutoff)
                    },
                    onEdit = { onEditPreset(p.slot) },
                    onDelete = { onDeletePreset(p.slot) },
                )
            } else {
                OutlinedButton(onClick = { onAddPreset(p.slot) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.timer_preset_add_slot, p.slot))
                }
            }
        }
    }
}

/** Ligne du réglage personnalisé enregistré, présentée comme un [PlanningRow]. */
@Composable
private fun PersonalPresetRow(label: String, detail: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Un tap sur la durée ouvre l'édition, pré-remplie avec le réglage existant.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.timer_preset_delete))
        }
    }
}

@Composable
private fun DeletePresetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_preset_delete_confirm_title)) },
        text = { Text(stringResource(R.string.timer_preset_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.timer_preset_delete_confirm_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
private fun iconFor(type: DeviceType): ImageVector = when (type) {
    DeviceType.PLUG -> Icons.Filled.Power
    DeviceType.LAMP -> Icons.Filled.Lightbulb
    DeviceType.SENSOR -> Icons.Filled.Sensors
    // Picto dessiné à la main (voir SmokeDetectorIcon) — aucune icône Material standard ne
    // représente un vrai détecteur de fumée ; Filled.SmokeFree (essayé un temps) est en fait
    // « interdiction de fumer », confusion vécue en test réel le 2026-08-31.
    DeviceType.SMOKE_DETECTOR -> SmokeDetectorIcon
}

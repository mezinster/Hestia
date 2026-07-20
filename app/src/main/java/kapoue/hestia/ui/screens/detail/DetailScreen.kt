package kapoue.hestia.ui.screens.detail

import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.ui.components.StatusBadge
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onOpenPresence: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val presenceActive by viewModel.presenceActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showSheet by remember { mutableStateOf(false) }
    // Minuteur en attente de résolution du conflit avec la simulation de présence.
    var pendingTimer by remember { mutableStateOf<Pair<Int, String>?>(null) }

    fun requestStartTimer(seconds: Int, label: String) {
        if (presenceActive) pendingTimer = seconds to label else viewModel.startTimer(seconds, label)
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
            }

            if (dev.hasScripting) {
                HorizontalDivider()
                PresenceSummary(active = presenceActive, onConfigure = onOpenPresence)
            }

            HorizontalDivider()
            ActivitySection(logs)
        }
    }

    if (showSheet) {
        DurationPickerSheet(
            onDismiss = { showSheet = false },
            onConfirm = { seconds, label ->
                showSheet = false
                requestStartTimer(seconds, label)
            },
        )
    }

    pendingTimer?.let { (seconds, label) ->
        ConflictDialog(
            onCancel = { pendingTimer = null },
            onLaunchAnyway = {
                viewModel.startTimer(seconds, label)
                pendingTimer = null
            },
            onStopPresence = {
                viewModel.stopPresenceThenStartTimer(seconds, label)
                pendingTimer = null
            },
        )
    }
}

@Composable
private fun PresenceSummary(active: Boolean, onConfigure: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.presence_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(if (active) R.string.presence_state_active else R.string.presence_state_inactive),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton(
            onClick = onConfigure,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(stringResource(R.string.presence_configure))
        }
    }
}

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
    },
)

private fun iconFor(type: DeviceType): ImageVector = when (type) {
    DeviceType.PLUG -> Icons.Filled.Power
    DeviceType.LAMP -> Icons.Filled.Lightbulb
    DeviceType.SENSOR -> Icons.Filled.Sensors
}

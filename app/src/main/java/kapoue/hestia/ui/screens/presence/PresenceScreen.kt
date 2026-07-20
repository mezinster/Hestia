package kapoue.hestia.ui.screens.presence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kapoue.hestia.R
import kapoue.hestia.ui.permission.LocalNetworkPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresenceScreen(
    onBack: () -> Unit,
    viewModel: PresenceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.updatePermission(LocalNetworkPermission.isUsable(context))
            viewModel.refreshState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presence_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.presence_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(
                    if (state.deployed) R.string.presence_state_active else R.string.presence_state_inactive,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            state.deviceTimeLabel?.let {
                Text(
                    text = stringResource(R.string.presence_clock_device_time, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.clockDrifted) {
                ClockWarning(driftSeconds = state.clockDriftSeconds ?: 0)
            }

            HorizontalDivider()

            TimeField(
                label = stringResource(R.string.presence_start_time),
                hour = state.startHour,
                minute = state.startMinute,
                onChange = viewModel::onStartTime,
            )
            TimeField(
                label = stringResource(R.string.presence_end_time),
                hour = state.endHour,
                minute = state.endMinute,
                onChange = viewModel::onEndTime,
            )
            MarginField(
                minutes = state.marginMinutes,
                onChange = viewModel::onMarginChange,
            )

            state.error?.let { message ->
                Text(
                    text = message.arg?.let { stringResource(message.res, it) } ?: stringResource(message.res),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { if (state.deployed) viewModel.stop() else viewModel.deploy() },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.presence_deploying))
                } else {
                    Text(stringResource(if (state.deployed) R.string.presence_stop else R.string.presence_deploy))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    hour: Int,
    minute: Int,
    onChange: (Int, Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(140.dp), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { showDialog = true }) {
            Text("%02d:%02d".format(hour, minute), fontFamily = FontFamily.Monospace)
        }
    }
    if (showDialog) {
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(timeState.hour, timeState.minute)
                    showDialog = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = { TimeInput(state = timeState) },
        )
    }
}

@Composable
private fun MarginField(minutes: Int, onChange: (Int) -> Unit) {
    // État texte local : la valeur n'est pas re-tronquée à chaque frappe (contrairement à un
    // champ contrôlé) ; on ne remonte que les valeurs valides, et on resynchronise si la
    // valeur change de l'extérieur.
    var text by remember { mutableStateOf(minutes.toString()) }
    LaunchedEffect(minutes) {
        if (text.toIntOrNull() != minutes) text = minutes.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit).take(3)
            text = filtered
            filtered.toIntOrNull()?.let(onChange)
        },
        label = { Text(stringResource(R.string.presence_margin)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.width(220.dp),
    )
}

@Composable
private fun ClockWarning(driftSeconds: Long) {
    val driftLabel = stringResource(R.string.duration_minutes, (driftSeconds / 60).toInt())
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.presence_clock_warning, driftLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

package kapoue.hestia.ui.screens.device

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapoue.hestia.R
import kapoue.hestia.core.util.isValidIpv4
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.permission.LocalNetworkPermissionStatus
import kapoue.hestia.ui.permission.PermissionExplanationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditDeviceScreen(
    onDone: () -> Unit,
    viewModel: AddEditDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    // 1 ou 2 = boîte de dialogue d'édition de cet emplacement IP ouverte, null = fermée.
    var editingIpSlot by remember { mutableStateOf<Int?>(null) }

    // Se referme lorsque l'opération est terminée (hors composition).
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            viewModel.testAndAdd()
        } else {
            permissionDenied = true
        }
    }

    fun requestBack() {
        if (state.isDirty) showUnsavedDialog = true else onDone()
    }

    // Bouton retour Android géré nativement (SPEC-V1 § Navigation).
    BackHandler(enabled = true) { requestBack() }

    fun onPrimaryAction() {
        if (state.isEditMode) {
            viewModel.saveEdit()
            return
        }
        if (!viewModel.validate()) return
        when (LocalNetworkPermission.status(context)) {
            LocalNetworkPermissionStatus.NOT_REQUIRED,
            LocalNetworkPermissionStatus.GRANTED -> viewModel.testAndAdd()
            LocalNetworkPermissionStatus.DENIED -> showPermissionDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.edit_device_title else R.string.add_device_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.add_device_name_label)) },
                placeholder = { Text(stringResource(R.string.add_device_name_hint)) },
                isError = state.nameError != null,
                supportingText = when (val nameError = state.nameError) {
                    null -> if (state.isGroupEdit) {
                        { Text(stringResource(R.string.add_device_name_group_hint)) }
                    } else {
                        null
                    }
                    else -> {
                        { Text(stringResource(nameError.res)) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            if (state.isEditMode) {
                IpLocationsSection(
                    slot1Name = state.ipName,
                    slot1Ip = state.ipAddress,
                    slot2Name = state.ip2Name,
                    slot2Ip = state.ip2Address,
                    onEditSlot1 = { editingIpSlot = 1 },
                    onEditSlot2 = { editingIpSlot = 2 },
                    onAddSlot2 = { editingIpSlot = 2 },
                    onDeleteSlot2 = viewModel::onDeleteIpSlot2,
                )
            } else {
                OutlinedTextField(
                    value = state.ipAddress,
                    onValueChange = viewModel::onIpChange,
                    label = { Text(stringResource(R.string.add_device_ip_label)) },
                    placeholder = { Text(stringResource(R.string.add_device_ip_hint)) },
                    isError = state.ipError != null,
                    supportingText = state.ipError?.let { { Text(stringResource(it.res)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            DeviceTypeDropdown(
                selected = state.type,
                onSelected = viewModel::onTypeChange,
            )

            // Erreur globale (réseau / RPC / doublon) — message actionnable.
            state.error?.let { message ->
                val text = message.arg?.let { stringResource(message.res, it) }
                    ?: stringResource(message.res)
                Text(text = text, color = MaterialTheme.colorScheme.error)
            }

            if (permissionDenied) {
                Text(
                    text = stringResource(R.string.error_permission_denied_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Bloc de sélection des canaux (appareil multi-canaux détecté).
            state.channelSelection?.let { selection ->
                ChannelSelectionBlock(
                    model = selection.model,
                    channels = selection.channels,
                    selected = selection.selected,
                    onToggle = viewModel::toggleChannel,
                )
            }

            Button(
                onClick = { onActionOrConfirm(state, viewModel, ::onPrimaryAction) },
                enabled = !state.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.add_device_testing))
                } else {
                    Text(primaryButtonLabel(state))
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

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onDiscard = {
                showUnsavedDialog = false
                onDone()
            },
            onKeep = { showUnsavedDialog = false },
        )
    }

    editingIpSlot?.let { slot ->
        IpLocationDialog(
            initialName = if (slot == 1) state.ipName.orEmpty() else state.ip2Name.orEmpty(),
            defaultNamePlaceholder = stringResource(
                if (slot == 1) R.string.add_device_ip_location_default_name_1 else R.string.add_device_ip_location_default_name_2,
            ),
            initialIp = if (slot == 1) state.ipAddress else state.ip2Address.orEmpty(),
            onConfirm = { name, ip ->
                if (slot == 1) viewModel.onEditIpSlot1(name, ip) else viewModel.onEditIpSlot2(name, ip)
                editingIpSlot = null
            },
            onDismiss = { editingIpSlot = null },
        )
    }
}

/** Aiguille le bouton principal : confirmer la sélection de canaux, sinon action primaire. */
private fun onActionOrConfirm(
    state: AddEditUiState,
    viewModel: AddEditDeviceViewModel,
    primaryAction: () -> Unit,
) {
    if (state.channelSelection != null) {
        viewModel.confirmChannelSelection()
    } else {
        primaryAction()
    }
}

@Composable
private fun primaryButtonLabel(state: AddEditUiState): String = when {
    state.channelSelection != null -> stringResource(R.string.add_device_add_selected)
    state.isEditMode -> stringResource(R.string.add_device_save)
    else -> stringResource(R.string.add_device_test_and_add)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTypeDropdown(
    selected: DeviceType,
    onSelected: (DeviceType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(deviceTypeLabel(selected)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.add_device_type_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DeviceType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(deviceTypeLabel(type))) },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun deviceTypeLabel(type: DeviceType): Int = when (type) {
    DeviceType.PLUG -> R.string.device_type_plug
    DeviceType.LAMP -> R.string.device_type_lamp
    DeviceType.SENSOR -> R.string.device_type_sensor
}

@Composable
private fun ChannelSelectionBlock(
    model: String?,
    channels: List<Int>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.add_device_channels_title),
            style = MaterialTheme.typography.titleSmall,
        )
        model?.let {
            Text(
                text = stringResource(R.string.add_device_model_detected, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = stringResource(R.string.add_device_channels_prompt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        channels.forEach { channelId ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = channelId in selected,
                    onCheckedChange = { onToggle(channelId) },
                )
                Text(stringResource(R.string.add_device_channel_label, channelId))
            }
        }
    }
}

@Composable
private fun UnsavedChangesDialog(
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.unsaved_changes_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.unsaved_changes_keep))
            }
        },
    )
}

/**
 * Les 1 ou 2 adresses IP de l'appareil (ex. domicile / vacances) — Hestia bascule automatiquement
 * sur la deuxième si la première est injoignable. Toujours au moins un emplacement : la poubelle
 * n'apparaît que sur le 2ᵉ, jamais sur le seul restant.
 */
@Composable
private fun IpLocationsSection(
    slot1Name: String?,
    slot1Ip: String,
    slot2Name: String?,
    slot2Ip: String?,
    onEditSlot1: () -> Unit,
    onEditSlot2: () -> Unit,
    onAddSlot2: () -> Unit,
    onDeleteSlot2: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.add_device_ip_locations_title),
            style = MaterialTheme.typography.titleSmall,
        )
        IpLocationRow(
            name = slot1Name ?: stringResource(R.string.add_device_ip_location_default_name_1),
            ip = slot1Ip,
            onEdit = onEditSlot1,
            onDelete = null,
        )
        if (slot2Ip != null) {
            IpLocationRow(
                name = slot2Name ?: stringResource(R.string.add_device_ip_location_default_name_2),
                ip = slot2Ip,
                onEdit = onEditSlot2,
                onDelete = onDeleteSlot2,
            )
        } else {
            OutlinedButton(onClick = onAddSlot2, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.add_device_add_second_ip))
            }
        }
    }
}

@Composable
private fun IpLocationRow(name: String, ip: String, onEdit: () -> Unit, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ip,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.add_device_ip_location_edit))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.add_device_ip_location_delete))
            }
        }
    }
}

/** Nom (facultatif, préremplit avec le nom par défaut en filigrane) + IP d'un emplacement. */
@Composable
private fun IpLocationDialog(
    initialName: String,
    defaultNamePlaceholder: String,
    initialIp: String,
    onConfirm: (name: String, ip: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var ip by remember { mutableStateOf(initialIp) }
    var ipError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_device_ip_location_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MAX_IP_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.add_device_ip_location_name_label)) },
                    placeholder = { Text(defaultNamePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it; ipError = false },
                    label = { Text(stringResource(R.string.add_device_ip_label)) },
                    placeholder = { Text(stringResource(R.string.add_device_ip_hint)) },
                    isError = ipError,
                    supportingText = if (ipError) {
                        { Text(stringResource(R.string.error_ip_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedIp = ip.trim()
                if (!isValidIpv4(trimmedIp)) {
                    ipError = true
                } else {
                    onConfirm(name.trim(), trimmedIp)
                }
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MAX_IP_NAME_LENGTH = 20

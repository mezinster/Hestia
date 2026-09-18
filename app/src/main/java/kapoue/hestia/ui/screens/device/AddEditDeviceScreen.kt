package kapoue.hestia.ui.screens.device

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.Switch
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
import kapoue.hestia.domain.model.CloudInfo
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.LedNightModeState
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
    var showFirmwareInstallConfirm by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    // Confirmation avant suppression de la 2ᵉ adresse IP (retour David, 2026-09-17) : le geste
    // reste sans effet tant qu'Enregistrer n'est pas tapé (comme tout ce formulaire), mais une
    // suppression appelle une confirmation par habitude d'usage, contrairement à une simple
    // modification de champ.
    var showDeleteIp2Confirm by remember { mutableStateOf(false) }

    // Se referme lorsque l'opération est terminée (hors composition).
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    LaunchedEffect(state.firmwareInstallMessage) {
        state.firmwareInstallMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_LONG).show()
            viewModel.consumeFirmwareInstallMessage()
        }
    }

    LaunchedEffect(state.rebootMessage) {
        state.rebootMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_LONG).show()
            viewModel.consumeRebootMessage()
        }
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
        // Détecteur de fumée : pas de contact réseau à l'ajout (voir SMOKE-DETECTOR.md), donc
        // pas besoin de la permission réseau local à ce stade — elle sera demandée au premier
        // vrai appel RPC, comme pour tout appareil.
        if (state.type == DeviceType.SMOKE_DETECTOR) {
            viewModel.addSmokeDetector()
            return
        }
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
                    onDeleteSlot2 = { showDeleteIp2Confirm = true },
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

            // En édition, le type ne se change plus du tout, quel que soit l'appareil — une
            // prise EST une prise (retour David, 2026-08-31) : le proposer comme un champ
            // modifiable laissait croire qu'un appareil pouvait changer de nature, et pour un
            // détecteur de fumée c'était même risqué (les capacités enregistrées à l'ajout,
            // supportsSwitch = false notamment, ne seraient jamais recalculées). Affiché comme
            // FirmwareSection ci-dessous (titre + texte), pas comme un champ grisé.
            if (state.isEditMode) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.add_device_type_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(deviceTypeLabel(state.type)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                DeviceTypeDropdown(
                    selected = state.type,
                    onSelected = viewModel::onTypeChange,
                )
            }

            // Pas de test de connexion possible pour ce type (voir SMOKE-DETECTOR.md) — insiste
            // sur ntfy et le Cloud à la place, seul moyen réaliste d'être alerté vu que l'appareil
            // dort la majeure partie du temps.
            if (!state.isEditMode && state.type == DeviceType.SMOKE_DETECTOR) {
                Text(
                    text = stringResource(R.string.add_device_smoke_detector_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isEditMode) {
                // Pas de composant LED sur un détecteur de fumée (réglage propre aux prises/
                // blocs multi-canaux) — la section était affichée sans rien faire, confusion
                // repérée en test réel le 2026-08-31.
                if (state.type != DeviceType.SMOKE_DETECTOR) {
                    HorizontalDivider()
                    LedSection(state = state.ledState, onToggle = viewModel::onToggleLed)
                }

                HorizontalDivider()
                FirmwareSection(
                    deviceType = state.type,
                    driver = state.driver,
                    result = state.firmwareCheck,
                    checking = state.firmwareChecking,
                    installing = state.firmwareInstalling,
                    rebooting = state.rebooting,
                    onCheck = viewModel::checkFirmwareUpdate,
                    onInstall = { showFirmwareInstallConfirm = true },
                    onReboot = { showRebootConfirm = true },
                )

                HorizontalDivider()
                CloudSection(
                    info = state.cloudInfo,
                    toggling = state.cloudToggling,
                    onToggle = viewModel::onToggleCloud,
                )
            }

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
                    // Picto disquette uniquement pour une vraie modification (retour David,
                    // 2026-08-24) — pas pour « tester et ajouter » ni « ajouter la sélection »,
                    // qui ne sont pas des enregistrements au même sens.
                    if (state.isEditMode && state.channelSelection == null) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    }
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

    if (showFirmwareInstallConfirm) {
        FirmwareInstallConfirmDialog(
            onConfirm = {
                showFirmwareInstallConfirm = false
                viewModel.installFirmwareUpdate()
            },
            onDismiss = { showFirmwareInstallConfirm = false },
        )
    }

    if (showRebootConfirm) {
        RebootConfirmDialog(
            onConfirm = {
                showRebootConfirm = false
                viewModel.rebootDevice()
            },
            onDismiss = { showRebootConfirm = false },
        )
    }

    if (showDeleteIp2Confirm) {
        DeleteIp2ConfirmDialog(
            onConfirm = {
                showDeleteIp2Confirm = false
                viewModel.onDeleteIpSlot2()
            },
            onDismiss = { showDeleteIp2Confirm = false },
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
    state.type == DeviceType.SMOKE_DETECTOR -> stringResource(R.string.add_device_add_smoke_detector)
    else -> stringResource(R.string.add_device_test_and_add)
}

/**
 * Types proposés à la création d'un appareil (voir [DeviceTypeDropdown]) — [DeviceType.LAMP] et
 * [DeviceType.SENSOR] existent dans l'enum mais rien dans Hestia ne les distingue encore d'une
 * prise classique ; les proposer donnerait l'impression d'un vrai support qui n'existe pas.
 */
private val addDeviceSelectableTypes = listOf(DeviceType.PLUG, DeviceType.SMOKE_DETECTOR)

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
            // Seuls les types réellement pris en charge par Hestia aujourd'hui (retour David,
            // 2026-09-03) : LAMP/SENSOR existent dans l'enum mais rien dans l'app ne les distingue
            // encore d'une prise, autant ne pas les proposer avant qu'ils aient un vrai sens.
            addDeviceSelectableTypes.forEach { type ->
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
    DeviceType.SMOKE_DETECTOR -> R.string.device_type_smoke_detector
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

/** Interrupteur LED : allumée (réduite la nuit) ou éteinte en permanence, un seul réglage par appareil physique. */
@Composable
private fun LedSection(state: LedNightModeState?, onToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.add_device_led_title),
            style = MaterialTheme.typography.titleSmall,
        )
        when (state) {
            null -> Text(
                text = stringResource(R.string.add_device_led_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LedNightModeState.UNAVAILABLE -> Text(
                text = stringResource(R.string.add_device_led_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.add_device_led_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state == LedNightModeState.ON, onCheckedChange = onToggle)
            }
        }
    }
}

/**
 * Vérification manuelle de mise à jour firmware — jamais automatique (seul appel du projet qui
 * sort du réseau local). Une version bêta disponible n'est jamais proposée à l'installation,
 * seulement signalée. Par appareil physique (2026-08-19) : déplacé depuis l'écran Détail, où il
 * était dupliqué à l'identique sur chaque canal d'un même bloc (un seul firmware, un seul
 * redémarrage, quel que soit le nombre de canaux — même logique que la LED d'état ci-dessus).
 */
@Composable
private fun FirmwareSection(
    deviceType: DeviceType,
    driver: DriverType,
    result: FirmwareCheckResult?,
    checking: Boolean,
    installing: Boolean,
    rebooting: Boolean,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
) {
    // Désigne l'appareil par son type (« Prise », « Lampe », « Capteur ») plutôt que par le mot
    // générique « appareil » — évite aussi tout accord de genre dans la phrase (le nom sert
    // d'étiquette, pas de sujet grammatical : « Prise : à jour », pas « Ta prise est à jour »).
    val noun = stringResource(nounFor(deviceType))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.firmware_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        when (result) {
            null -> Text(
                text = stringResource(R.string.firmware_check_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is FirmwareCheckResult.UpToDate -> Text(
                stringResource(R.string.firmware_up_to_date, noun, result.installedVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            is FirmwareCheckResult.BetaOnly -> Text(
                stringResource(R.string.firmware_beta_only, noun, result.installedVersion, result.betaVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            is FirmwareCheckResult.UpdateAvailable -> Text(
                stringResource(R.string.firmware_update_available, noun, result.installedVersion, result.newVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            FirmwareCheckResult.Error -> Text(
                stringResource(R.string.firmware_check_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = !checking && !installing) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.firmware_check_button))
            }
            if (result is FirmwareCheckResult.UpdateAvailable) {
                Button(onClick = onInstall, enabled = !installing) {
                    Text(stringResource(R.string.firmware_install_button))
                }
            }
        }

        // Redémarrage (dépannage) : méthode RPC propre à Shelly, absente si un jour Hestia gère
        // une autre marque — c'est pourquoi ce bloc est distinct du reste, propre au pilote.
        if (driver == DriverType.SHELLY_GEN2) {
            Text(
                text = stringResource(R.string.firmware_reboot_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onReboot, enabled = !rebooting) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.firmware_reboot_button))
            }
        }
    }
}

private fun nounFor(type: DeviceType): Int = when (type) {
    DeviceType.PLUG -> R.string.device_type_plug
    DeviceType.LAMP -> R.string.device_type_lamp
    DeviceType.SENSOR -> R.string.device_type_sensor
    DeviceType.SMOKE_DETECTOR -> R.string.device_type_smoke_detector
}

/**
 * Cloud Shelly — canal opt-in du firmware, désactivé par défaut (voir CLAUDE.md). Hestia ne fait
 * qu'activer/désactiver ce canal et afficher son état ; la création d'un compte Shelly et
 * l'appairage de l'appareil se font entièrement en dehors de l'application (appli Shelly ou
 * control.shelly.cloud) — aucun champ d'identifiant ici.
 */
@Composable
private fun CloudSection(info: CloudInfo?, toggling: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val cloudUrl = stringResource(R.string.cloud_portal_url)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.cloud_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.cloud_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (info) {
            null -> Text(
                text = stringResource(R.string.cloud_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloudInfo.Unavailable -> Text(
                text = stringResource(R.string.cloud_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is CloudInfo.Available -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.cloud_toggle_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = info.enabled, onCheckedChange = onToggle, enabled = !toggling)
                }
                Text(
                    text = stringResource(
                        if (info.connected) R.string.cloud_status_connected else R.string.cloud_status_disconnected,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                info.macId?.let { macId ->
                    Text(
                        text = stringResource(R.string.cloud_id_label, macId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        TextButton(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cloudUrl))) } },
        ) {
            Text(stringResource(R.string.cloud_portal_link))
        }
    }
}

@Composable
private fun RebootConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.firmware_reboot_confirm_title)) },
        text = { Text(stringResource(R.string.firmware_reboot_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.firmware_reboot_confirm_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Purement une confirmation de geste (retour David, 2026-09-17) : la suppression elle-même ne
 * prend effet qu'au « Enregistrer » de l'écran, comme tout le reste de ce formulaire — ce popup
 * n'existe que pour éviter un tap malheureux sur la poubelle, pas pour avertir d'un effet
 * irréversible.
 */
@Composable
private fun DeleteIp2ConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_device_ip_location_delete_confirm_title)) },
        text = { Text(stringResource(R.string.add_device_ip_location_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.timer_preset_delete_confirm_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun FirmwareInstallConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.firmware_install_confirm_title)) },
        text = { Text(stringResource(R.string.firmware_install_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.firmware_install_confirm_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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

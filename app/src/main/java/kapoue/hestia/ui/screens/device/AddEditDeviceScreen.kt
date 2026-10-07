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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
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
import kapoue.hestia.ui.icons.SmokeDetectorIcon
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.permission.LocalNetworkPermissionStatus
import kapoue.hestia.ui.permission.PermissionExplanationDialog
import kotlinx.coroutines.delay

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

    // Défilement ponctuel jusqu'à la section Firmware à l'arrivée depuis le bandeau « Maj dispo »
    // du Tableau (2026-09-23) — même mécanisme que le défilement vers ntfy dans Réglages, mais un
    // simple argument de navigation suffit ici : cet écran empilé est toujours recomposé à neuf,
    // pas besoin d'un coordinateur partagé pour rattraper une course de navigation.
    val firmwareBringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (viewModel.scrollToFirmwareOnLoad) firmwareBringIntoViewRequester.bringIntoView()
    }

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
            // Reprend l'ajout interrompu par la demande de permission — détecteur de fumée
            // compris depuis ce lot (retour David, 2026-09-23), jamais figé sur testAndAdd.
            if (state.type == DeviceType.SMOKE_DETECTOR) viewModel.addSmokeDetector() else viewModel.testAndAdd()
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
        // Détecteur de fumée : depuis ce lot, l'ajout tente une vraie lecture RPC (nom de
        // l'appareil, après le réveil manuel demandé à l'écran) — même besoin de la permission
        // réseau local qu'une prise, jamais de contact sans elle (CLAUDE.md).
        val addAction = if (state.type == DeviceType.SMOKE_DETECTOR) viewModel::addSmokeDetector else viewModel::testAndAdd
        when (LocalNetworkPermission.status(context)) {
            LocalNetworkPermissionStatus.NOT_REQUIRED,
            LocalNetworkPermissionStatus.GRANTED -> addAction()
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
            if (state.isEditMode) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.add_device_name_label)) },
                    placeholder = { Text(stringResource(nameHintFor(state.type))) },
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

                HorizontalDivider()

                // Le type ne se change plus du tout en édition, quel que soit l'appareil — une
                // prise EST une prise (retour David, 2026-08-31) : le proposer comme un champ
                // modifiable laissait croire qu'un appareil pouvait changer de nature, et pour un
                // détecteur de fumée c'était même risqué (les capacités enregistrées à l'ajout,
                // supportsSwitch = false notamment, ne seraient jamais recalculées).
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

                // Pas de composant LED sur un détecteur de fumée (réglage propre aux prises/
                // blocs multi-canaux) — la section était affichée sans rien faire, confusion
                // repérée en test réel le 2026-08-31.
                if (state.type != DeviceType.SMOKE_DETECTOR) {
                    HorizontalDivider()
                    LedSection(state = state.ledState, onToggle = viewModel::onToggleLed)
                }

                HorizontalDivider()
                Column(modifier = Modifier.bringIntoViewRequester(firmwareBringIntoViewRequester)) {
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
                }

                HorizontalDivider()
                CloudSection(
                    info = state.cloudInfo,
                    toggling = state.cloudToggling,
                    onToggle = viewModel::onToggleCloud,
                )
            } else {
                // Toujours visibles (retour David, 2026-09-23) : celle non choisie se grise à 50 %
                // plutôt que de disparaître, pour pouvoir se raviser sans devoir tout recommencer.
                DeviceTypeTiles(
                    selectedType = state.type.takeIf { state.typeChosen },
                    onSelected = viewModel::onTypeChosen,
                )

                if (state.typeChosen) {
                    HorizontalDivider()

                    // IP avant Nom (retour David, 2026-09-23 : plus logique, et c'est l'IP qui
                    // déclenche la sonde automatique pour une prise — inutile d'attendre le Nom).
                    val focusManager = LocalFocusManager.current
                    OutlinedTextField(
                        value = state.ipAddress,
                        onValueChange = viewModel::onIpChange,
                        label = { Text(stringResource(R.string.add_device_ip_label)) },
                        placeholder = { Text(stringResource(R.string.add_device_ip_hint)) },
                        isError = state.ipError != null,
                        supportingText = state.ipError?.let { { Text(stringResource(it.res)) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                viewModel.onIpImeAction()
                                focusManager.moveFocus(FocusDirection.Down)
                            },
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Sonde automatique (prise uniquement, retour David, 2026-09-23) : débounce
                    // après la dernière frappe, ou touche Suivant du clavier — jamais besoin de
                    // quitter le champ (peu fiable, voir échange).
                    if ((state.type == DeviceType.PLUG || state.type == DeviceType.LAMP || state.type == DeviceType.SHUTTER) && state.autoProbing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_device_testing), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    HorizontalDivider()

                    val foundName = state.foundDeviceName
                    if ((state.type == DeviceType.PLUG || state.type == DeviceType.LAMP || state.type == DeviceType.SHUTTER) && foundName != null) {
                        // Nom déjà trouvé sur la prise par la sonde automatique : plus besoin du
                        // champ Nom éditable, l'appareil fait autorité (retour David, 2026-09-23).
                        Text(
                            text = stringResource(R.string.add_device_found_name, foundName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.add_device_name_label)) },
                            placeholder = { Text(stringResource(nameHintFor(state.type))) },
                            isError = state.nameError != null,
                            supportingText = state.nameError?.let { { Text(stringResource(it.res)) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.type == DeviceType.SMOKE_DETECTOR) {
                        // Pas de test de connexion complet pour ce type (voir SMOKE-DETECTOR.md) —
                        // insiste sur ntfy et le Cloud à la place, seul moyen réaliste d'être alerté
                        // vu que l'appareil dort la majeure partie du temps.
                        Text(
                            text = stringResource(R.string.add_device_smoke_detector_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Réveil manuel (retour David, 2026-09-23) : seulement une fois IP et Nom
                        // renseignés, pour ne pas gâcher les 2 minutes de fenêtre pendant que
                        // l'utilisateur finit de remplir le formulaire.
                        if (state.ipAddress.isNotBlank() && state.name.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.add_device_smoke_wake_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Rien de tout ça tant que le type n'est pas encore choisi (écran des deux tuiles).
            if (state.isEditMode || state.typeChosen) {
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

                val confirmedName = state.confirmedDeviceName
                if (confirmedName != null) {
                    // Ajout réussi (prise ou détecteur) : nom réellement retenu affiché quelques
                    // secondes avant la fermeture automatique de l'écran (retour David,
                    // 2026-09-23) — rassure que l'appareil a bien été contacté et lu.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.add_device_confirmed_name, confirmedName),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // Détecteur de fumée (ajout) : bouton masqué tant que l'utilisateur n'a pas eu
                    // le temps de lire le texte de réveil (retour David, 2026-09-23) — jamais pour
                    // une prise ni en édition.
                    var smokeSubmitVisible by remember { mutableStateOf(false) }
                    val smokeWakeHintVisible = !state.isEditMode && state.type == DeviceType.SMOKE_DETECTOR &&
                        state.ipAddress.isNotBlank() && state.name.isNotBlank()
                    LaunchedEffect(smokeWakeHintVisible) {
                        smokeSubmitVisible = false
                        if (smokeWakeHintVisible) {
                            delay(3_000)
                            smokeSubmitVisible = true
                        }
                    }
                    val showButton = state.isEditMode || state.type != DeviceType.SMOKE_DETECTOR || smokeSubmitVisible
                    if (showButton) {
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
 * Choix du type d'appareil à l'ajout, en tuiles plutôt qu'en menu déroulant (retour David,
 * 2026-09-23) — toujours visibles, y compris après le choix : celle non retenue se grise à 50 %
 * au lieu de disparaître, pour pouvoir se raviser sans recommencer tout le formulaire (retour
 * David, même jour). [selectedType] = null tant que rien n'a encore été choisi (aucune des deux
 * grisée dans ce cas). [DeviceType.LAMP] est proposé depuis le 2026-10-07 (variateurs, canaux
 * light:N) ; [DeviceType.SENSOR] existe dans l'enum mais rien dans Hestia ne le distingue encore
 * d'une prise classique ; le proposer donnerait l'impression d'un vrai support qui n'existe pas.
 */
@Composable
private fun DeviceTypeTiles(selectedType: DeviceType?, onSelected: (DeviceType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.add_device_type_prompt), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DeviceTypeTile(
                icon = Icons.Filled.Power,
                label = stringResource(deviceTypeLabel(DeviceType.PLUG)),
                dimmed = selectedType != null && selectedType != DeviceType.PLUG,
                onClick = { onSelected(DeviceType.PLUG) },
                modifier = Modifier.weight(1f),
            )
            DeviceTypeTile(
                icon = Icons.Filled.Lightbulb,
                label = stringResource(deviceTypeLabel(DeviceType.LAMP)),
                dimmed = selectedType != null && selectedType != DeviceType.LAMP,
                onClick = { onSelected(DeviceType.LAMP) },
                modifier = Modifier.weight(1f),
            )
        }
        // Deux lignes de deux : à quatre sur une seule ligne (~73 dp par tuile sur 360 dp), les
        // libellés (« Détecteur de fumée »…) seraient coupés en pleine lettre.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DeviceTypeTile(
                icon = Icons.Filled.Blinds,
                label = stringResource(deviceTypeLabel(DeviceType.SHUTTER)),
                dimmed = selectedType != null && selectedType != DeviceType.SHUTTER,
                onClick = { onSelected(DeviceType.SHUTTER) },
                modifier = Modifier.weight(1f),
            )
            DeviceTypeTile(
                icon = SmokeDetectorIcon,
                label = stringResource(deviceTypeLabel(DeviceType.SMOKE_DETECTOR)),
                dimmed = selectedType != null && selectedType != DeviceType.SMOKE_DETECTOR,
                onClick = { onSelected(DeviceType.SMOKE_DETECTOR) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeviceTypeTile(
    icon: ImageVector,
    label: String,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.alpha(if (dimmed) 0.5f else 1f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun deviceTypeLabel(type: DeviceType): Int = when (type) {
    // Tout appareil à relais marche/arrêt passe par ce type, pas seulement une prise : le dire
    // évite qu'un possesseur de relais (1PM, Pro…) croie son appareil non pris en charge.
    DeviceType.PLUG -> R.string.device_type_plug_or_relay
    DeviceType.LAMP -> R.string.device_type_dimmer
    DeviceType.SENSOR -> R.string.device_type_sensor
    DeviceType.SMOKE_DETECTOR -> R.string.device_type_smoke_detector
    DeviceType.SHUTTER -> R.string.device_type_shutter
}

/** Exemple affiché en filigrane du champ Nom — « Prise scooter » n'a aucun sens pour un
 * détecteur de fumée (retour David, 2026-09-23). */
private fun nameHintFor(type: DeviceType): Int = when (type) {
    DeviceType.SMOKE_DETECTOR -> R.string.add_device_name_hint_smoke_detector
    else -> R.string.add_device_name_hint
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

        // Un seul bouton à la fois (retour David, 2026-09-25) : une maj disponible est déjà
        // annoncée en toutes lettres juste au-dessus (versions installée/disponible) — Vérifier
        // n'apporte plus rien à ce stade, et les deux boutons côte à côte poussaient Installer
        // sur 2 lignes, trop à l'étroit. Vérifier ne réapparaît qu'après une installation (le
        // résultat devient alors obsolète, voir installFirmwareUpdate) ou tant qu'aucune maj
        // n'est connue.
        if (result is FirmwareCheckResult.UpdateAvailable) {
            Button(onClick = onInstall, enabled = !installing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.firmware_install_button))
            }
        } else {
            OutlinedButton(onClick = onCheck, enabled = !checking) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.firmware_check_button))
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
    DeviceType.LAMP -> R.string.device_type_dimmer
    DeviceType.SENSOR -> R.string.device_type_sensor
    DeviceType.SMOKE_DETECTOR -> R.string.device_type_smoke_detector
    DeviceType.SHUTTER -> R.string.device_type_shutter
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

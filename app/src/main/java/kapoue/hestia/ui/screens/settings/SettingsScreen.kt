package kapoue.hestia.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kapoue.hestia.R
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.data.notifications.ProgrammationNotifier
import kapoue.hestia.domain.model.ThemeMode
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.permission.LocalNetworkPermissionStatus
import kapoue.hestia.ui.theme.stateColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAddDevice: () -> Unit,
    onEditDevice: (Long) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val activeIp by viewModel.activeIp.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val ntfyEnabled by viewModel.ntfyEnabled.collectAsStateWithLifecycle()
    val ntfyTopic by viewModel.ntfyTopic.collectAsStateWithLifecycle()
    val cloudAuthKey by viewModel.cloudAuthKey.collectAsStateWithLifecycle()
    val cloudServer by viewModel.cloudServer.collectAsStateWithLifecycle()
    val cloudTesting by viewModel.cloudTesting.collectAsStateWithLifecycle()
    val cloudTestMessage by viewModel.cloudTestMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceToDelete by remember { mutableStateOf<Device?>(null) }
    var groupToDelete by remember { mutableStateOf<List<Device>?>(null) }
    var channelToRename by remember { mutableStateOf<Device?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // À chaque reprise, revérifier la connectivité (si la permission le permet) et réconcilier
    // l'interrupteur Notifications avec l'autorisation système : si elle a été retirée (révocation
    // à la fermeture, ou désactivation depuis les réglages Android), l'interrupteur repasse à OFF.
    LaunchedEffectOnResume(lifecycleOwner) {
        viewModel.checkConnectivity(LocalNetworkPermission.isUsable(context))
        viewModel.reconcileNotifications(ProgrammationNotifier.canPost(context))
    }

    // Sélecteurs de fichier (Storage Access Framework — aucune permission de stockage).
    val exportFilename = stringResource(R.string.backup_export_filename)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingImportUri = it } }

    // Demande de l'autorisation POST_NOTIFICATIONS (Android 13+), déclenchée uniquement à
    // l'activation des notifications. Refus : on laisse l'interrupteur éteint et on explique.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNotificationsEnabled(true)
        } else {
            Toast.makeText(context, context.getString(R.string.settings_notifications_denied), Toast.LENGTH_LONG).show()
        }
    }
    val onToggleNotifications: (Boolean) -> Unit = { enabled ->
        when {
            // OFF : on coupe les notifications (worker annulé + garde-fou). On ne révoque PAS
            // l'autorisation système : Android ne le fait proprement que de façon différée (état
            // « Toujours demander » bancal, qui casse silencieusement l'envoi). Elle reste dormante,
            // inoffensive tant que c'est OFF. La réconciliation à la reprise gère le cas où
            // l'utilisateur la retire lui-même depuis les réglages Android.
            !enabled -> viewModel.setNotificationsEnabled(false)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                viewModel.setNotificationsEnabled(true)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED ->
                viewModel.setNotificationsEnabled(true)
            else -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Retour utilisateur (export/import) en Toast.
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_SHORT).show()
            viewModel.consumeBackupMessage()
        }
    }

    LaunchedEffect(cloudTestMessage) {
        cloudTestMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_LONG).show()
            viewModel.consumeCloudTestMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { innerPadding ->
        // Seul le haut vient d'ici, même raison qu'au Tableau (voir son commentaire) — le bas est
        // déjà réservé une seule fois par HestiaApp pour la barre de navigation partagée.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(stringResource(R.string.settings_devices_title)) }

            if (devices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                // Regroupé par appareil physique (même IP) — `devices` est déjà dans cet ordre
                // contigu (voir SettingsViewModel). Une prise seule garde sa ligne unique
                // d'aujourd'hui ; un bloc multi-canaux devient un en-tête + une ligne par canal.
                val groups = devices.groupBy { it.ipAddress }.entries.toList()
                groups.forEachIndexed { groupIndex, entry ->
                    val members = entry.value
                    val isFirst = groupIndex == 0
                    val isLast = groupIndex == groups.lastIndex
                    if (members.size == 1) {
                        val device = members[0]
                        item(key = device.id) {
                            DeviceRow(
                                device = device,
                                online = connectivity[device.id],
                                displayIp = activeIp[device.id] ?: device.ipAddress,
                                isFirst = isFirst,
                                isLast = isLast,
                                onEdit = { onEditDevice(device.id) },
                                onDelete = { deviceToDelete = device },
                                onMoveUp = { viewModel.moveUp(device) },
                                onMoveDown = { viewModel.moveDown(device) },
                            )
                        }
                    } else {
                        val head = members.first()
                        item(key = "group-${head.ipAddress}") {
                            DeviceGroupHeaderRow(
                                deviceName = head.deviceName.ifBlank { head.name },
                                channelCount = members.size,
                                online = connectivity[head.id],
                                displayIp = activeIp[head.id] ?: head.ipAddress,
                                isFirst = isFirst,
                                isLast = isLast,
                                onEdit = { onEditDevice(head.id) },
                                onMoveUp = { viewModel.moveUp(head) },
                                onMoveDown = { viewModel.moveDown(head) },
                                onDeleteGroup = { groupToDelete = members },
                            )
                        }
                        items(members, key = { it.id }) { channel ->
                            ChannelSubRow(
                                device = channel,
                                online = connectivity[channel.id],
                                onRename = { channelToRename = channel },
                            )
                        }
                    }
                }
            }

            // Ajout d'un appareil en fin de liste (comme « Exporter la config »), plutôt qu'un
            // bouton flottant « + » qui n'avait pas sa place par-dessus le contenu.
            item {
                Button(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.settings_add_device))
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PermissionSection() }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                BackupSection(
                    onExport = { exportLauncher.launch(exportFilename) },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { AppearanceSection(themeMode, viewModel::setThemeMode) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                NotificationsSection(
                    enabled = notificationsEnabled,
                    onToggle = onToggleNotifications,
                    supersededByNtfy = ntfyEnabled,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                NtfySection(
                    enabled = ntfyEnabled,
                    topic = ntfyTopic.orEmpty(),
                    onToggle = viewModel::setNtfyEnabled,
                    onTopicChange = viewModel::setNtfyTopic,
                    onTest = viewModel::testNtfy,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                CloudSection(
                    authKey = cloudAuthKey.orEmpty(),
                    server = cloudServer.orEmpty(),
                    testing = cloudTesting,
                    onTest = viewModel::testCloudKey,
                    onClear = viewModel::clearCloudCredentials,
                )
            }
        }
    }

    pendingImportUri?.let { uri ->
        ImportConfirmDialog(
            onConfirm = {
                viewModel.importFrom(uri)
                pendingImportUri = null
            },
            onCancel = { pendingImportUri = null },
        )
    }

    deviceToDelete?.let { device ->
        DeleteDeviceDialog(
            device = device,
            onConfirm = {
                viewModel.deleteDevice(device)
                deviceToDelete = null
            },
            onDismiss = { deviceToDelete = null },
        )
    }

    groupToDelete?.let { members ->
        DeleteDeviceGroupDialog(
            deviceName = members.first().deviceName.ifBlank { members.first().name },
            channelCount = members.size,
            onConfirm = {
                viewModel.deleteDeviceGroup(members)
                groupToDelete = null
            },
            onDismiss = { groupToDelete = null },
        )
    }

    channelToRename?.let { device ->
        RenameChannelDialog(
            initialName = device.name,
            onConfirm = { newName ->
                viewModel.renameChannel(device, newName)
                channelToRename = null
            },
            onDismiss = { channelToRename = null },
        )
    }
}

@Composable
private fun LaunchedEffectOnResume(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    block: suspend () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { block() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DeviceRow(
    device: Device,
    online: Boolean?,
    /** IP effectivement utilisée (1ᵉʳ ou 2ᵉ emplacement) — jamais `device.ipAddress` en dur ici. */
    displayIp: String,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    // Détecteur de fumée : "Hors ligne" en rouge prêtait à confusion (appareil endormi, pas
    // vraiment hors réseau — retour David, 2026-09-01) — label et couleur neutres pour ce type,
    // cohérent avec la tuile du Tableau (déjà en gris, jamais rouge, pour ce même état).
    val isSensor = device.type == DeviceType.SMOKE_DETECTOR
    val connectivityLabel = when (online) {
        true -> stringResource(R.string.settings_connectivity_online)
        false -> stringResource(if (isSensor) R.string.sensor_state_unreachable else R.string.settings_connectivity_offline)
        null -> stringResource(R.string.settings_connectivity_checking)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectivityIndicator(online, neutralOffline = isSensor)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$displayIp · ${stringResource(R.string.settings_device_channel, device.switchId)} · $connectivityLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.settings_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.settings_move_down))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_open_web)) },
                        onClick = {
                            menuExpanded = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$displayIp")))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_edit_device)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_delete_device)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * En-tête d'un bloc multi-canaux (ex. Strip 4) : nom de l'appareil (partagé, jamais un nom de
 * canal), IP partagée, et les actions communes à tout le bloc — monter/descendre une seule fois
 * (déplace le groupe entier), un crayon dédié pour modifier nom/IP/type (plutôt que caché dans le
 * menu, pour bien distinguer « modifier l'appareil » de « renommer un canal », voir [ChannelSubRow]).
 */
@Composable
private fun DeviceGroupHeaderRow(
    deviceName: String,
    channelCount: Int,
    online: Boolean?,
    displayIp: String,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val connectivityLabel = when (online) {
        true -> stringResource(R.string.settings_connectivity_online)
        false -> stringResource(R.string.settings_connectivity_offline)
        null -> stringResource(R.string.settings_connectivity_checking)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.ViewModule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(deviceName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$displayIp · ${stringResource(R.string.settings_group_channels, channelCount)} · $connectivityLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.settings_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.settings_move_down))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_open_web)) },
                        onClick = {
                            menuExpanded = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$displayIp")))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_edit_device)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_delete_group)) },
                        onClick = {
                            menuExpanded = false
                            onDeleteGroup()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Ligne d'un canal au sein d'un bloc multi-canaux : juste son propre nom (renommable
 * individuellement, ex. « Frigo ») — l'IP et l'ouverture web sont communes au bloc, affichées une
 * seule fois sur son en-tête ([DeviceGroupHeaderRow]). Pas de suppression individuelle ici,
 * contrairement à [DeviceRow] (appareil mono-canal) : un canal fait partie d'un bloc physique
 * multi-prises, le retirer seul n'a pas de sens (retour David, 2026-09-01 — « on affiche tout,
 * libre à l'utilisateur d'utiliser les prises »). Seul [DeviceGroupHeaderRow.onDeleteGroup]
 * retire le bloc entier, tous canaux confondus.
 */
@Composable
private fun ChannelSubRow(device: Device, online: Boolean?, onRename: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectivityIndicator(online)
        Spacer(Modifier.size(10.dp))
        Text(device.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRename) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.settings_rename_channel),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DeleteDeviceGroupDialog(deviceName: String, channelCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_group_title)) },
        text = { Text(stringResource(R.string.delete_group_message, deviceName, channelCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_device_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_device_cancel)) }
        },
    )
}

@Composable
private fun RenameChannelDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rename_channel_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.add_device_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Indicateur de **connectivité réseau** d'un appareil dans les Réglages. Volontairement
 * distinct de la LED ronde du Tableau (qui, elle, signale l'état ON/OFF) : ici une icône
 * Wi-Fi (forme différente) pour dire « joignable », jamais le vert « allumé ». Le gris est
 * évité pour l'état transitoire (il se lirait « désactivé ») au profit d'un spinner.
 */
/**
 * [neutralOffline] : gris plutôt que rouge quand `online == false` — pour un détecteur de fumée,
 * ce n'est pas une alerte (l'appareil dort la majeure partie du temps, voir SMOKE-DETECTOR.md),
 * contrairement à une prise réellement injoignable.
 */
@Composable
private fun ConnectivityIndicator(online: Boolean?, neutralOffline: Boolean = false) {
    when (online) {
        true -> Icon(
            imageVector = Icons.Filled.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        false -> Icon(
            imageVector = Icons.Filled.WifiOff,
            contentDescription = null,
            tint = if (neutralOffline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.stateColors.offlineLed,
            modifier = Modifier.size(18.dp),
        )
        null -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
    }
}

/**
 * Alignée sur le langage visuel de la liste des appareils juste au-dessus (Card + icône de
 * connectivité colorée) plutôt qu'un simple texte suivi d'un lien — même icône Wifi/WifiOff que
 * [ConnectivityIndicator], sans dépendance supplémentaire.
 */
@Composable
private fun PermissionSection() {
    val context = LocalContext.current
    val status = remember { LocalNetworkPermission.status(context) }
    val statusRes = when (status) {
        LocalNetworkPermissionStatus.GRANTED -> R.string.settings_permission_granted
        LocalNetworkPermissionStatus.DENIED -> R.string.settings_permission_denied
        LocalNetworkPermissionStatus.NOT_REQUIRED -> R.string.settings_permission_not_required
    }
    val (icon, tint) = when (status) {
        LocalNetworkPermissionStatus.GRANTED -> Icons.Filled.Wifi to MaterialTheme.colorScheme.primary
        LocalNetworkPermissionStatus.DENIED -> Icons.Filled.WifiOff to MaterialTheme.stateColors.offlineLed
        LocalNetworkPermissionStatus.NOT_REQUIRED -> Icons.Filled.Wifi to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        SectionTitle(stringResource(R.string.settings_permission_section))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (status != LocalNetworkPermissionStatus.NOT_REQUIRED) {
                    IconButton(onClick = { LocalNetworkPermission.openAppSettings(context) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_permission_open_system),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.settings_appearance_section))
        ThemeMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                Text(stringResource(themeModeLabel(mode)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/**
 * Interrupteur des notifications de bornes de programmation. Opt-in : à l'activation, l'écran
 * demande l'autorisation système avant d'appeler le ViewModel. Le libellé rappelle le délai
 * inhérent (le worker passe ~toutes les 15 min : notification différée, pas instantanée).
 */
/** [supersededByNtfy] : ntfy actif = ce système est désactivé et l'interrupteur grisé. */
@Composable
private fun NotificationsSection(enabled: Boolean, onToggle: (Boolean) -> Unit, supersededByNtfy: Boolean) {
    Column {
        SectionTitle(stringResource(R.string.settings_notifications_section))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notifications_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_notifications_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (supersededByNtfy) {
                    Text(
                        text = stringResource(R.string.settings_notifications_superseded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Switch(checked = enabled && !supersededByNtfy, onCheckedChange = onToggle, enabled = !supersededByNtfy)
        }
    }
}

/**
 * Notifications instantanées via ntfy — service externe, opt-in. Le texte explicatif (avec le
 * lien F-Droid) passe **avant** l'interrupteur pour que l'intérêt soit clair avant de l'activer.
 * Un encadré d'aide guide vers l'appli ntfy tant qu'aucun sujet n'est renseigné. L'envoi groupé de
 * tous les textes de notif (outil de support) se déclenche depuis le Tableau (3 appuis sur le
 * titre, à côté des 5 appuis du journal de diagnostic), pas ici.
 */
@Composable
private fun NtfySection(
    enabled: Boolean,
    topic: String,
    onToggle: (Boolean) -> Unit,
    onTopicChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    // Champ local : ne se recale sur [topic] qu'à la composition initiale, pas à chaque frappe
    // (sinon la resynchro déclenchée à chaque caractère créerait un aller-retour permanent).
    var topicField by remember { mutableStateOf(topic) }
    var topicVisible by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.settings_ntfy_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )

        val fdroidLink = LinkAnnotation.Url("https://f-droid.org/packages/io.heckel.ntfy/")
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.settings_ntfy_desc_before))
                withLink(fdroidLink) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                        append("F-Droid")
                    }
                }
                append(stringResource(R.string.settings_ntfy_desc_after))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_ntfy_toggle_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Spacer(Modifier.size(8.dp))
            if (topic.isBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        text = stringResource(R.string.settings_ntfy_topic_hint_setup),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
            }

            OutlinedTextField(
                value = topicField,
                onValueChange = { topicField = it },
                label = { Text(stringResource(R.string.settings_ntfy_topic_label)) },
                placeholder = { Text(stringResource(R.string.settings_ntfy_topic_placeholder)) },
                singleLine = true,
                visualTransformation = if (topicVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { topicVisible = !topicVisible }) {
                        Icon(
                            if (topicVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (topicVisible) R.string.settings_ntfy_hide else R.string.settings_ntfy_reveal,
                            ),
                        )
                    }
                },
                // La resynchro (RPC vers chaque appareil) ne part qu'une fois la saisie terminée,
                // pas à chaque caractère.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused && topicField != topic) onTopicChange(topicField) },
            )
            Text(
                text = stringResource(R.string.settings_ntfy_topic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            Button(
                onClick = {
                    // Enregistre d'abord une saisie pas encore validée (perte de focus non passée
                    // par là si on appuie directement sur Tester), pour tester la bonne valeur.
                    if (topicField != topic) onTopicChange(topicField)
                    onTest()
                },
                enabled = topicField.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.settings_ntfy_test))
            }
        }
    }
}

/**
 * Cloud Shelly, repli à distance (opt-in, désactivé par défaut — voir CLAUDE.md). Ne pilote encore
 * rien : cette section ne fait qu'enregistrer la clé + le serveur (chiffrés, jamais journalisés)
 * et vérifier qu'ils sont valides. Le repli réel (allumer/éteindre, lire l'état, hors réseau
 * local) sera branché dans un lot ultérieur.
 */
@Composable
private fun CloudSection(
    authKey: String,
    server: String,
    testing: Boolean,
    onTest: (authKey: String, server: String) -> Unit,
    onClear: () -> Unit,
) {
    var keyField by remember { mutableStateOf(authKey) }
    var serverField by remember { mutableStateOf(server) }
    var keyVisible by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.settings_cloud_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.settings_cloud_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        val portalLink = LinkAnnotation.Url("https://control.shelly.cloud")
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.settings_cloud_where_before))
                withLink(portalLink) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                        append("control.shelly.cloud")
                    }
                }
                append(stringResource(R.string.settings_cloud_where_after))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        OutlinedTextField(
            value = keyField,
            onValueChange = { keyField = it },
            label = { Text(stringResource(R.string.settings_cloud_key_label)) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (keyVisible) R.string.settings_cloud_key_hide else R.string.settings_cloud_key_reveal,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = serverField,
            onValueChange = { serverField = it },
            label = { Text(stringResource(R.string.settings_cloud_server_label)) },
            placeholder = { Text(stringResource(R.string.settings_cloud_server_placeholder)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))

        Button(
            onClick = { onTest(keyField, serverField) },
            enabled = !testing && keyField.isNotBlank() && serverField.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            }
            Text(stringResource(R.string.settings_cloud_test))
        }

        if (authKey.isNotBlank() || server.isNotBlank()) {
            TextButton(
                onClick = {
                    keyField = ""
                    serverField = ""
                    onClear()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_cloud_clear))
            }
        }
    }
}

@Composable
private fun BackupSection(onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.settings_backup_section))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.settings_backup_export))
        }
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.settings_backup_import))
        }
    }
}

@Composable
private fun ImportConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_import_confirm_title)) },
        text = { Text(stringResource(R.string.backup_import_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_import_confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteDeviceDialog(
    device: Device,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_device_title)) },
        text = { Text(stringResource(R.string.delete_device_message, device.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_device_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.delete_device_cancel))
            }
        },
    )
}

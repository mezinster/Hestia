package kapoue.hestia.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.material3.RadioButton
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
import kapoue.hestia.data.local.entity.Device
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
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceToDelete by remember { mutableStateOf<Device?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // À chaque reprise, revérifier la connectivité (si la permission le permet).
    LaunchedEffectOnResume(lifecycleOwner) {
        viewModel.checkConnectivity(LocalNetworkPermission.isUsable(context))
    }

    // Sélecteurs de fichier (Storage Access Framework — aucune permission de stockage).
    val exportFilename = stringResource(R.string.backup_export_filename)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingImportUri = it } }

    // Retour utilisateur (export/import) en Toast.
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, context.getString(it.res), Toast.LENGTH_SHORT).show()
            viewModel.consumeBackupMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                itemsIndexed(devices, key = { _, d -> d.id }) { index, device ->
                    DeviceRow(
                        device = device,
                        online = connectivity[device.id],
                        isFirst = index == 0,
                        isLast = index == devices.lastIndex,
                        onEdit = { onEditDevice(device.id) },
                        onDelete = { deviceToDelete = device },
                        onMoveUp = { viewModel.moveUp(device) },
                        onMoveDown = { viewModel.moveDown(device) },
                    )
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
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val connectivityLabel = when (online) {
        true -> stringResource(R.string.settings_connectivity_online)
        false -> stringResource(R.string.settings_connectivity_offline)
        null -> stringResource(R.string.settings_connectivity_checking)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectivityIndicator(online)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${device.ipAddress} · ${stringResource(R.string.settings_device_channel, device.switchId)} · $connectivityLabel",
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
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${device.ipAddress}")))
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
 * Indicateur de **connectivité réseau** d'un appareil dans les Réglages. Volontairement
 * distinct de la LED ronde du Tableau (qui, elle, signale l'état ON/OFF) : ici une icône
 * Wi-Fi (forme différente) pour dire « joignable », jamais le vert « allumé ». Le gris est
 * évité pour l'état transitoire (il se lirait « désactivé ») au profit d'un spinner.
 */
@Composable
private fun ConnectivityIndicator(online: Boolean?) {
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
            tint = MaterialTheme.stateColors.offlineLed,
            modifier = Modifier.size(18.dp),
        )
        null -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun PermissionSection() {
    val context = LocalContext.current
    val status = remember { LocalNetworkPermission.status(context) }
    Column {
        SectionTitle(stringResource(R.string.settings_permission_section))
        val statusRes = when (status) {
            LocalNetworkPermissionStatus.GRANTED -> R.string.settings_permission_granted
            LocalNetworkPermissionStatus.DENIED -> R.string.settings_permission_denied
            LocalNetworkPermissionStatus.NOT_REQUIRED -> R.string.settings_permission_not_required
        }
        Text(
            text = stringResource(statusRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != LocalNetworkPermissionStatus.NOT_REQUIRED) {
            TextButton(onClick = { LocalNetworkPermission.openAppSettings(context) }) {
                Text(stringResource(R.string.settings_permission_open_system))
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

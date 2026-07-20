package kapoue.hestia.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.AndroidEntryPoint
import kapoue.hestia.R
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.ui.theme.HestiaTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran de configuration lancé à l'ajout d'un widget (SPEC-V1 § 8) : choix du canal lié.
 * Tant que l'utilisateur n'a pas choisi, le résultat reste CANCELED (le widget n'est pas ajouté).
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var repository: DeviceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Par défaut : annulé (l'utilisateur peut revenir en arrière sans lier de canal).
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            HestiaTheme {
                WidgetConfigScreen(onDeviceChosen = { device -> bindAndFinish(appWidgetId, device) })
            }
        }
    }

    private fun bindAndFinish(appWidgetId: Int, device: Device) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            WidgetUpdater.bind(applicationContext, glanceId, device.id, device.name)
            // Relève l'état réel une fois (au premier plan → permission OK) : le widget n'affiche
            // pas « Lecture » indéfiniment en attendant la prochaine ouverture de l'application.
            val output = (repository.getStatus(device) as? RpcResult.Success)?.value?.output
            val status = when (output) {
                true -> WidgetState.ON
                false -> WidgetState.OFF
                null -> WidgetState.OFFLINE
            }
            WidgetUpdater.setStatus(applicationContext, glanceId, status, device.name)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    onDeviceChosen: (Device) -> Unit,
    viewModel: WidgetConfigViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) },
    ) { innerPadding ->
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.widget_config_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onDeviceChosen(device) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${device.ipAddress} · ${stringResource(R.string.settings_device_channel, device.switchId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

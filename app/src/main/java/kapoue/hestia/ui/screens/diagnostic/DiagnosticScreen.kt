package kapoue.hestia.ui.screens.diagnostic

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapoue.hestia.R
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kapoue.hestia.ui.permission.LocalNetworkPermissionStatus
import kapoue.hestia.ui.theme.LocalIsDarkTheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current

    val themeLabel = stringResource(if (isDark) R.string.diagnostic_theme_dark else R.string.diagnostic_theme_light)
    val permissionLabel = stringResource(
        when (LocalNetworkPermission.status(context)) {
            LocalNetworkPermissionStatus.GRANTED -> R.string.settings_permission_granted
            LocalNetworkPermissionStatus.DENIED -> R.string.settings_permission_denied
            LocalNetworkPermissionStatus.NOT_REQUIRED -> R.string.settings_permission_not_required
        },
    )

    LaunchedEffect(Unit) {
        viewModel.load(themeLabel, permissionLabel)
    }

    val subject = stringResource(R.string.diagnostic_subject)
    val chooser = stringResource(R.string.diagnostic_share_chooser)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_title)) },
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
                .padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.diagnostic_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            // Aperçu du contenu exact qui sera partagé (défilement vertical et horizontal).
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            Button(
                onClick = { shareDiagnostic(context, report, subject, chooser) },
                enabled = report.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.diagnostic_share))
            }
        }
    }
}

/** Partage le journal en pièce jointe .txt via la feuille de partage système (aucun serveur). */
private fun shareDiagnostic(context: Context, text: String, subject: String, chooserTitle: String) {
    runCatching {
        val file = File(context.cacheDir, "hestia-diagnostic.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}

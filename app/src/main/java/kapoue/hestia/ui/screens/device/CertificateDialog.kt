package kapoue.hestia.ui.screens.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kapoue.hestia.R

/**
 * Demande à l'utilisateur de faire confiance au certificat d'un appareil HTTPS-only. L'empreinte
 * complète est affichée en police à chasse fixe pour être comparée à celle de l'interface web de
 * l'appareil ; rien n'est épinglé avant le tap sur « Faire confiance ».
 */
@Composable
fun CertificateDialog(
    prompt: CertificatePrompt,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tls_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (prompt.changed) R.string.tls_dialog_message_changed else R.string.tls_dialog_message,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tls_dialog_fingerprint),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    prompt.certificate.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(stringResource(R.string.tls_dialog_trust)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tls_dialog_cancel)) }
        },
    )
}

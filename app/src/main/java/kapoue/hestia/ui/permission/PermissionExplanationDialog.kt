package kapoue.hestia.ui.permission

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kapoue.hestia.R

/**
 * Écran d'explication affiché **avant** la demande système de la permission réseau local
 * (SPEC-V1 / CLAUDE.md) : pourquoi elle est nécessaire, ce qu'elle permet, et l'engagement
 * qu'aucune adresse autre que celles saisies par l'utilisateur n'est contactée.
 */
@Composable
fun PermissionExplanationDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_explain_title)) },
        text = { Text(stringResource(R.string.permission_explain_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.permission_explain_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_explain_cancel))
            }
        },
    )
}

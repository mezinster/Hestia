package kapoue.hestia.ui.screens.about

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.core.util.QrGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val fdroidUrl = stringResource(R.string.fdroid_url)
    val gplUrl = stringResource(R.string.gpl_url)
    val mastodonUrl = stringResource(R.string.mastodon_url)
    val telegramUrl = stringResource(R.string.telegram_url)

    val qrSizePx = with(LocalDensity.current) { 200.dp.roundToPx() }
    val qrBitmap = remember(fdroidUrl) { QrGenerator.generate(fdroidUrl, qrSizePx) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("?")
    }

    // Luminosité écran à fond pendant l'affichage du QR ; retour auto au re-appui / appui ailleurs.
    var brightBoosted by remember { mutableStateOf(false) }
    LaunchedEffect(brightBoosted) { activity?.setScreenBrightness(brightBoosted) }
    DisposableEffect(Unit) { onDispose { activity?.setScreenBrightness(false) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_about)) }) },
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (brightBoosted) brightBoosted = false }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Logo : le symbole conserve son fond craie (SPEC — c'est un logo, pas un élément d'UI).
        Surface(
            color = colorResource(R.color.ic_launcher_background),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(96.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Hestia", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.about_version, version ?: "?"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section(R.string.about_name_origin_title, R.string.about_name_origin)
        Section(R.string.about_positioning_title, R.string.about_positioning)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_positioning_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section(R.string.about_permission_title, R.string.about_permission_body)
        Section(R.string.about_independence_title, R.string.about_independence)

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_compat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Licence : le lien « (en savoir plus) » passe à la ligne, juste sous le texte.
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.about_license_link),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { context.openUrl(gplUrl) },
        )

        // Contact : réseaux sociaux, mêmes liens texte que la licence (pas d'icône de marque).
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_contact_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.about_contact_body), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Text(
                text = stringResource(R.string.about_contact_mastodon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { context.openUrl(mastodonUrl) },
            )
            Text(
                text = stringResource(R.string.about_contact_telegram),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { context.openUrl(telegramUrl) },
            )
        }

        // Partage : bouton (texte + lien F-Droid via le système Android) ou QR code, au choix.
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_share_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        val shareText = stringResource(R.string.about_share_text, fdroidUrl)
        Button(onClick = {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
        }) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.about_share_button))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_share_or),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .size(220.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { brightBoosted = !brightBoosted },
        ) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (brightBoosted) R.string.about_qr_hint_max else R.string.about_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    }
}

@Composable
private fun Section(titleRes: Int, bodyRes: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Activity.setScreenBrightness(max: Boolean) {
    window.attributes = window.attributes.apply {
        screenBrightness = if (max) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun android.content.Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

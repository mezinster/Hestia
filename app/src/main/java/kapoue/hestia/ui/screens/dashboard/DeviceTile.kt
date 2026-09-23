package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.core.util.formatCountdown
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.core.util.formatTimeRange
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.ui.icons.SmokeDetectorIcon
import kapoue.hestia.ui.screens.detail.PersonalPreset
import kapoue.hestia.ui.screens.detail.durationLabel
import kapoue.hestia.ui.theme.StateColorSet
import kapoue.hestia.ui.theme.stateColors

/**
 * Tuile d'un canal sur le Tableau. Cadre carré arrondi + disque intérieur à trois trous, façon
 * prise française, teinté selon l'état ; **jamais de couleur seule** : le libellé texte
 * accompagne toujours l'état (SPEC).
 *
 * Deux signaux de couleur distincts, volontairement séparés (2026-08-17) :
 * - l'**anneau** du cercle reflète un fait physique — le courant passe ([TileStatus.Online.output])
 *   ou non — peu importe le régime (manuel, minuteur, présence, planning) ;
 * - le **fond de la tuile** et le **texte** reflètent le régime — actif seul, piloté par un
 *   programme, éteint, ou indisponible — indépendamment de l'état ON/OFF du moment (ex. une
 *   présence en pause reste « Planifié » en orange, même si le cercle est gris à cet instant).
 *
 * @param elapsedNow SystemClock.elapsedRealtime() courant, rafraîchi à la seconde par le parent
 *   pour décrémenter le compte à rebours localement.
 */
@Composable
fun DeviceTile(
    tile: TileUiState,
    elapsedNow: Long,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onPlanningWindowEnded: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    // elapsedNow (rafraîchi à la seconde par le parent) force le recalcul du planning en cours
    // au fil du temps, sans attendre le prochain relevé réseau.
    val activePlanning = remember(tile.plannings, elapsedNow) { tile.plannings.firstOrNull { it.isActiveNow() } }
    val visual = tile.status.toVisual(colors, elapsedNow, tile.presence, activePlanning, tile.pendingThresholdW, tile.onSinceElapsed, tile.presenceDisabledToday, tile.planningDisabledToday)

    // Fin de créneau : dès que le planning en cours cesse de l'être, on force un relevé pour
    // confirmer l'extinction tout de suite (sinon la tuile afficherait le dernier état connu —
    // « Actif » — pendant quelques secondes avant « Repos »).
    val inPlanningWindow = activePlanning != null
    var wasInWindow by remember { mutableStateOf(inPlanningWindow) }
    LaunchedEffect(inPlanningWindow) {
        if (wasInWindow && !inPlanningWindow) onPlanningWindowEnded()
        wasInWindow = inPlanningWindow
    }

    Surface(
        color = visual.bgColor,
        shape = RoundedCornerShape(12.dp),
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = tile.device.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ConnectivityBadge(tile.status, modifier = Modifier.padding(start = 4.dp))
                }

                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlugCircle(visual = visual)
                }

                if (!visual.loading) {
                    Spacer(Modifier.height(6.dp))
                    val stateLine = visual.countdown?.let { "${visual.label} · $it" } ?: visual.label
                    Text(
                        text = stateLine,
                        color = visual.textColor,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Seuil sur sa propre ligne, jamais concaténé à la ligne ci-dessus : la
                    // combinaison sur une seule ligne entrait en collision avec l'interrupteur
                    // (retour David, 2026-08-22). Toujours rendue, même vide (retour David,
                    // 2026-09-23) : sinon une tuile Actif-avec-seuil a une ligne de plus qu'une
                    // tuile Indisponible à côté dans la même rangée de la grille à 2 colonnes,
                    // hauteurs différentes, moche — même principe déjà appliqué au bloc
                    // multiprises et au détecteur de fumée.
                    Text(
                        text = visual.thresholdText.orEmpty(),
                        color = visual.thresholdTextColor ?: visual.textColor,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    val powerWatts = (tile.status as? TileStatus.Online)
                        ?.powerWatts?.takeIf { tile.device.hasPowerMetering }
                    Text(
                        text = powerWatts?.let { formatPower(it) }.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    val interactive = tile.status is TileStatus.Online
                    val checked = (tile.status as? TileStatus.Online)?.output == true
                    RoundToggleButton(
                        enabled = interactive,
                        checked = checked,
                        onClick = { onToggle(!checked) },
                    )
                }
            }
            if (tile.device.firmwareUpdateAvailable) {
                FirmwareUpdateBanner(onClick = onOpenFirmware)
            }
        }
    }
}

/**
 * Bandeau « Maj dispo » (2026-09-23) — vérification automatique du firmware, une fois par jour,
 * pour les appareils avec le Cloud activé (voir DeviceRepository.checkFirmwareUpdatesIfDue).
 * Pleine largeur en bas de la tuile, son propre tap cible (jamais mêlé au tap normal de la
 * tuile qui ouvre la modale rapide, Ergo-1/2) — direct vers Modifier l'appareil, où vit la
 * section Firmware. Même bleu que l'état Présence, décliné en fond ([StateColorSet.presenceBg]).
 */
@Composable
private fun FirmwareUpdateBanner(onClick: () -> Unit) {
    val colors = MaterialTheme.stateColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(colors.presenceBg)
            .padding(vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = colors.presenceText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.tile_firmware_update_banner),
            color = colors.presenceText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Tuile d'un détecteur de fumée — voir SMOKE-DETECTOR.md. Volontairement distincte de
 * [DeviceTile] : pas de fait physique marche/arrêt, pas de minuteur, pas d'interrupteur (pas de
 * relais sur ce type d'appareil).
 */
@Composable
fun SmokeDetectorTile(
    tile: TileUiState,
    onOpenDetail: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    val sensor = tile.sensorStatus ?: SensorStatus.Loading

    val bgColor: Color
    val stateColor: Color
    val stateLabel: String
    when (sensor) {
        SensorStatus.Loading -> {
            bgColor = colors.idleBg
            stateColor = colors.idleText
            stateLabel = stringResource(R.string.state_loading)
        }
        SensorStatus.Offline -> {
            bgColor = colors.idleBg
            stateColor = colors.idleText
            stateLabel = stringResource(R.string.sensor_state_unreachable)
        }
        is SensorStatus.Online -> when {
            sensor.alarm -> {
                bgColor = colors.offlineBg
                stateColor = colors.offlineText
                stateLabel = stringResource(R.string.sensor_state_alarm)
            }
            sensor.mute -> {
                bgColor = colors.idleBg
                stateColor = colors.idleText
                stateLabel = stringResource(R.string.sensor_state_mute)
            }
            sensor.batteryError -> {
                bgColor = colors.idleBg
                stateColor = colors.idleText
                stateLabel = stringResource(R.string.sensor_state_battery_error)
            }
            else -> {
                bgColor = colors.activeBg
                stateColor = colors.activeText
                stateLabel = stringResource(R.string.sensor_state_normal)
            }
        }
    }

    val online = sensor as? SensorStatus.Online
    val batteryPercent = online?.batteryPercent
    // Premier passage de seuil (2026-08-31) : orange sous 30 %, à affiner visuellement plus tard
    // (voir SMOKE-DETECTOR.md) — jamais affiché sans le texte de pourcentage à côté (SPEC : jamais
    // la couleur seule).
    val batteryColor = when {
        batteryPercent == null -> colors.idleText
        batteryPercent < 30 -> colors.warningText
        else -> colors.idleText
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = tile.device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (online?.viaCloud == true) {
                    Icon(
                        imageVector = Icons.Filled.SettingsInputAntenna,
                        contentDescription = stringResource(R.string.tile_via_cloud),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(start = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Même picto que l'écran Détail (dessiné sur mesure, voir SmokeDetectorIcon) —
                // un picto de pile ici prêtait à confusion sur la nature de l'appareil (retour
                // David, test réel 2026-08-31).
                Icon(
                    imageVector = SmokeDetectorIcon,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = stateLabel,
                color = stateColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // Toujours affiché, même vide (jamais omis) : réserve la même hauteur de ligne que la
            // batterie soit connue ou non, pour que les tuiles d'un même rang ne sautent pas en
            // hauteur selon l'état de chaque détecteur (retour David, 2026-09-01).
            Text(
                text = batteryPercent?.let { "$it %" }.orEmpty(),
                color = batteryColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Texte vide plutôt qu'omis tant qu'il n'y a pas de vraie donnée à dater — pas de
            // texte trompeur (« Jamais contacté » pendant le chargement, retour David,
            // 2026-09-01), mais la ligne reste réservée pour que les tuiles d'un même rang ne
            // sautent pas en hauteur selon l'état de chaque détecteur (même retour, suite).
            Spacer(Modifier.height(8.dp))
            Text(
                text = online?.updatedAtEpochSec?.let { formatLastContact(it) }.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            }
            if (tile.device.firmwareUpdateAvailable) {
                FirmwareUpdateBanner(onClick = onOpenFirmware)
            }
        }
    }
}

/**
 * « Il y a 3 min »/« hier » — délégué à `DateUtils` (Android), qui applique déjà la langue du
 * système sans qu'Hestia ait à gérer les pluriels de chaque langue lui-même. Jamais garanti frais
 * (voir SMOKE-DETECTOR.md) — l'affichage lui-même le porte, pas juste une valeur muette.
 *
 * Appelant responsable de ne pas appeler cette fonction sans donnée réelle ([epochSec] non nul
 * en amont) : pendant le chargement ou sans contact cette fois-ci, ne rien afficher plutôt qu'un
 * texte par défaut trompeur (« Jamais contacté » alors qu'on n'a simplement pas encore de
 * réponse) — retour David, 2026-09-01.
 */
@Composable
internal fun formatLastContact(epochSec: Long): String {
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        epochSec * 1000,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    )
    return stringResource(R.string.sensor_last_contact, relative)
}

// Plus de bouton « Réessayer » dédié : le rafraîchissement auto (5 s) + le tirage manuel
// suffisent à rattraper un appareil redevenu joignable (voir DashboardViewModel.refresh).

/**
 * Picto Wifi (local) ou antenne (repli cloud) à côté du nom — jamais silencieux sur la provenance
 * de l'état affiché (voir CLAUDE.md). Rien n'est affiché tant qu'aucune lecture n'a réussi
 * (Chargement/Indisponible) : on ne peut alors revendiquer aucun des deux chemins.
 */
@Composable
private fun ConnectivityBadge(status: TileStatus, modifier: Modifier = Modifier) {
    val online = status as? TileStatus.Online ?: return
    Icon(
        imageVector = if (online.viaCloud) Icons.Filled.SettingsInputAntenna else Icons.Filled.Wifi,
        contentDescription = stringResource(
            if (online.viaCloud) R.string.tile_via_cloud else R.string.tile_via_local,
        ),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(14.dp),
    )
}

/**
 * [enabled] = le bouton est-il cliquable (état connu/joignable) — n'a jamais représenté l'état
 * marche/arrêt lui-même, malgré son nom. [checked] = l'état réel de la prise (retour David,
 * 2026-09-21) : sans lui, le bouton avait toujours le même look qu'elle soit allumée ou éteinte,
 * seul un simple picto d'alimentation neutre — fond et contour teintés en vert (même couleur
 * d'état que le reste de l'appli, voir StateColors) quand elle est réellement allumée.
 */
@Composable
private fun RoundToggleButton(enabled: Boolean, checked: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.stateColors
    Surface(
        shape = CircleShape,
        color = if (checked) colors.activeBg else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> MaterialTheme.colorScheme.outlineVariant
                checked -> colors.activeLed
                else -> MaterialTheme.colorScheme.outline
            },
        ),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = stringResource(R.string.tile_toggle),
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    checked -> colors.activeText
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal data class TileVisual(
    val bgColor: Color,
    val ringColor: Color,
    val textColor: Color,
    val label: String,
    val countdown: String?,
    /**
     * Vrai seulement quand [countdown] est un vrai compte à rebours qui descend vers zéro (un
     * minuteur natif en cours) — jamais pour une plage horaire fixe (Présence, Planifié) ni pour
     * une durée qui grimpe (« depuis », déjà préfixé dans la chaîne elle-même). Sert uniquement à
     * savoir si le connecteur « pour encore »/« for another » a un sens devant [label] (retour
     * David, 2026-09-21 : « Planifié pour encore 14:00 - 17:00 » ne voulait rien dire, le
     * connecteur avait été ajouté pour tout [countdown] non nul sans cette distinction).
     */
    val showsRemaining: Boolean = false,
    val dashed: Boolean,
    val loading: Boolean = false,
    /**
     * Seuil de coupure surveillant ce canal, sur sa **propre ligne** — jamais concaténé au
     * [countdown] : la combinaison des deux (ex. « Actif · depuis 12:34 · Coupure à 5 W »)
     * entrait en collision avec l'interrupteur sur la tuile prise seule, en une seule ligne trop
     * longue (retour David, 2026-08-22).
     */
    val thresholdText: String? = null,
    /** Couleur du [thresholdText], si différente de [textColor] — cas de « désactivé aujourd'hui »
     * (retour David, 2026-08-24 : le vert de « Actif » ne doit pas déteindre sur une note qui
     * n'indique pas elle-même un état actif, juste une explication). Null = reprend [textColor]. */
    val thresholdTextColor: Color? = null,
    /** Vrai quand l'anneau est vert (courant réel) — épaissi dans ce cas précis pour rééquilibrer
     * le disque désormais plus présent (retour David, 2026-08-22 : test d'un disque à 70 %
     * d'opacité + anneau doublé uniquement quand vert). */
    val ringEmphasis: Boolean = false,
)

/**
 * Cadre carré arrondi + disque intérieur à trois trous, façon prise française — inspiré du picto
 * Material Design Icons "power-socket-fr" (retour David, 2026-09-04 : la version tout en cercle,
 * juste 3 trous sans cadre, "n'était pas super belle"). Cadre = fait physique (courant ou non),
 * disque = régime (même couleur que le texte d'état en dessous — Actif vert, Présence indigo,
 * Planifié violet…), lisible même sans lire le texte (retour David, 2026-08-22 : avant ça, le
 * disque suivait aussi le fait physique, aucune distinction visuelle entre les régimes sans lire
 * le texte). Le texte de régime lui-même (« Planifié », compte à rebours…) est affiché par
 * l'appelant sur sa propre ligne, sous le cadre — trop à l'étroit à l'intérieur d'un cercle de
 * 76dp dès qu'il dépassait un mot court (retour de test réel, 2026-08-22).
 */
@Composable
private fun PlugCircle(visual: TileVisual) {
    val holeColor = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(76.dp)) {
            // Cadre carré arrondi façon prise française (retour David, 2026-09-04, la version à
            // 3 trous sans cadre "n'était pas super belle") : l'anneau (fait physique) devient un
            // rectangle arrondi au lieu d'un cercle, le disque (régime) reste un cercle à
            // l'intérieur — même principe de double couleur qu'avant, juste une nouvelle forme.
            val strokeWidthPx = (if (visual.ringEmphasis) 4.dp else 2.dp).toPx()
            val squareSize = size.minDimension - strokeWidthPx
            val cornerRadiusPx = squareSize * 0.22f
            val squareTopLeft = Offset((size.width - squareSize) / 2f, (size.height - squareSize) / 2f)
            val innerCircleRadius = size.minDimension * 0.32f
            drawCircle(color = visual.textColor.copy(alpha = 0.7f), radius = innerCircleRadius)
            drawRoundRect(
                color = visual.ringColor,
                topLeft = squareTopLeft,
                size = Size(squareSize, squareSize),
                cornerRadius = CornerRadius(cornerRadiusPx),
                style = Stroke(
                    width = strokeWidthPx,
                    pathEffect = if (visual.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                ),
            )
            // Un trou au-dessus du centre, deux de part et d'autre à hauteur du centre — même
            // disposition que le picto de référence. Toujours resserrés (pas jusqu'au bord) : les
            // 2 trous d'origine, trop espacés/bas, évoquaient un visage souriant (retour de test
            // réel, 2026-08-17) — la 3ᵉ position en haut casse cette lecture (plus d'alignement
            // horizontal façon yeux+bouche).
            val holeRadius = innerCircleRadius * 0.15f
            val holeOffset = innerCircleRadius * 0.57f
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x, center.y - holeOffset))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x - holeOffset, center.y))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x + holeOffset, center.y))
        }
        if (visual.loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = visual.ringColor,
            )
        }
    }
}

/**
 * `internal` (pas `private`) : réutilisé par [kapoue.hestia.ui.components.StatusBadge] sur
 * l'écran Détail, pour que la classification état/couleur ne vive qu'à un seul endroit
 * (2026-08-22 — le badge avait sa propre logique plus pauvre, désynchronisée : ne distinguait ni
 * Présence ni Planifié, et le minuteur en cours y restait « Minuterie » orange après que le
 * Tableau soit passé à « Actif » vert).
 */
@Composable
internal fun TileStatus.toVisual(
    colors: StateColorSet,
    elapsedNow: Long,
    presence: PresenceInfo?,
    activePlanning: Planning?,
    pendingThresholdW: Int?,
    onSinceElapsed: Long?,
    presenceDisabledToday: Boolean,
    planningDisabledToday: Boolean,
): TileVisual = when (this) {
    TileStatus.Loading -> TileVisual(
        bgColor = colors.idleBg,
        ringColor = colors.idleLed,
        textColor = colors.idleText,
        label = stringResource(R.string.state_loading),
        countdown = null,
        dashed = false,
        loading = true,
    )
    is TileStatus.Online -> {
        // Anneau ET fond = fait physique (courant ou non), même en présence d'un programme —
        // seul le texte porte la nuance "Planifié" (2026-08-17, retour de test réel : un fond
        // orange quand une programmation tourne prêtait à confusion, gardé uniquement en texte).
        val ringColor = if (output) colors.activeLed else colors.idleLed
        val physicalBg = if (output) colors.activeBg else colors.idleBg
        val remaining = timerEndsAtElapsed?.let { ((it - elapsedNow) / 1000).coerceAtLeast(0) }
        when {
            // Planning récurrent désactivé pour aujourd'hui (bouton ON/OFF, app ou physique) :
            // priorité sur tout le reste — plus jamais la couleur « Planifié », juste Actif/Éteint
            // selon l'état réel du moment, avec un texte fixe qui reste tant que le jour n'est pas
            // passé (retour David, 2026-08-22 : contrairement à la présence, un simple retour à
            // l'état physique suffit, pas besoin de nuancer la couleur).
            //
            // [!activePlanning.isPresence] : bug trouvé en direct le 2026-08-24 — sans ce garde,
            // un vieux mémo `planningDisabledToday` laissé par un planning précis testé plus tôt
            // sur ce canal masquait complètement une présence par ailleurs active et non désactivée
            // (la branche suivante, qui vérifie `presenceDisabledToday`, n'était alors jamais
            // atteinte). Les deux mémos sont indépendants (voir AppPreferences) : celui d'un
            // planning précis ne doit jamais influencer l'affichage d'une présence, et inversement.
            activePlanning != null && planningDisabledToday && !activePlanning.once && !activePlanning.isPresence -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor,
                textColor = if (output) colors.activeText else colors.idleText,
                label = stringResource(if (output) R.string.state_active else R.string.state_idle),
                countdown = null,
                dashed = false,
                // Sur sa propre ligne, jamais concaténé au libellé (« Éteint planning désactivé
                // aujourd'hui » ne voulait rien dire collé, et poussait l'interrupteur en dessous
                // — retour David, 2026-08-22).
                thresholdText = stringResource(R.string.tile_planning_disabled_today),
                // Neutre, jamais vert : ce texte n'indique pas lui-même un état actif, juste une
                // explication — le vert d'« Actif » juste au-dessus n'a pas à déteindre dessus
                // (retour David, 2026-08-24).
                thresholdTextColor = colors.idleText,
                ringEmphasis = output,
            )
            // Présence désactivée pour aujourd'hui : même traitement que Planning ci-dessus
            // (retour David, 2026-08-22) — priorité sur la branche « presence != null » normale
            // juste en dessous, donc plus jamais la couleur indigo une fois désactivée.
            presence != null && presenceDisabledToday -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor,
                textColor = if (output) colors.activeText else colors.idleText,
                label = stringResource(if (output) R.string.state_active else R.string.state_idle),
                countdown = null,
                dashed = false,
                thresholdText = stringResource(R.string.tile_presence_disabled_today),
                thresholdTextColor = colors.idleText,
                ringEmphasis = output,
            )
            // Minuteur natif en cours (bouton avec durée, ou Perso/Manuel) : reclassé « Actif »
            // depuis « Planifié » (retour David, 2026-08-22) — un minuteur lancé maintenant n'est
            // pas de la planification (début/fin décidés à l'avance), juste un allumage manuel
            // avec un décompte. Couleur verte au passage (n'empruntait plus l'orange à tort).
            remaining != null && remaining > 0 -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor, textColor = colors.activeText,
                label = stringResource(R.string.state_active),
                countdown = formatCountdown(remaining),
                showsRemaining = true,
                dashed = false,
                // Le seuil réutilise le libellé déjà utilisé pour ce même réglage dans l'écran
                // Détail (timer_preset_cutoff_detail) — pas de « 10 W » nu, ambigu avec la vraie
                // consommation instantanée affichée juste en dessous sur la tuile (retour David,
                // 2026-08-22).
                thresholdText = pendingThresholdW?.let { stringResource(R.string.timer_preset_cutoff_detail, it) },
                ringEmphasis = output,
            )
            presence != null -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor, textColor = colors.presenceText,
                label = stringResource(R.string.state_presence),
                countdown = formatTimeRange(presence.startHour, presence.startMinute, presence.endHour, presence.endMinute),
                dashed = false,
                ringEmphasis = output,
            )
            activePlanning != null -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor, textColor = colors.plannedText,
                label = stringResource(R.string.state_planned),
                countdown = "%02d:%02d – %02d:%02d".format(
                    activePlanning.startHour, activePlanning.startMinute,
                    activePlanning.endHour, activePlanning.endMinute,
                ),
                dashed = false,
                // Déjà relu par DeviceRepository.getPlannings (Script.GetCode du script de
                // coupure dédié à ce planning, fiable — jamais modifié par Eval après son premier
                // déploiement) : juste jamais affiché jusqu'ici (bonus repéré le 2026-08-22).
                thresholdText = activePlanning.cutoffThresholdW?.let { stringResource(R.string.timer_preset_cutoff_detail, it) },
                ringEmphasis = output,
            )
            output -> TileVisual(
                bgColor = colors.activeBg, ringColor = ringColor, textColor = colors.activeText,
                label = stringResource(R.string.state_active),
                ringEmphasis = true,
                // Durée du ON en cours (voir DashboardViewModel.onSinceElapsed) : absente tant
                // qu'aucune référence fiable n'a encore été observée pour ce canal. Préfixée
                // (« depuis »/« for ») pour ne pas se lire comme un décompte qui descend, alors
                // que celui-ci grimpe (retour David, 2026-08-22).
                countdown = onSinceElapsed?.let {
                    stringResource(R.string.tile_on_since, formatCountdown(((elapsedNow - it) / 1000).coerceAtLeast(0)))
                },
                dashed = false,
                // Seuil d'un minuteur « sans limite de durée » surveillé par hestia_charge (voir
                // DashboardViewModel.activeChargeThresholds) — sinon invisible faute de décompte
                // natif à côté duquel l'afficher (retour David, 2026-08-22).
                thresholdText = pendingThresholdW?.let { stringResource(R.string.timer_preset_cutoff_detail, it) },
            )
            else -> TileVisual(
                bgColor = colors.idleBg, ringColor = ringColor, textColor = colors.idleText,
                label = stringResource(R.string.state_idle),
                countdown = null,
                dashed = false,
            )
        }
    }
    TileStatus.Offline -> TileVisual(
        bgColor = colors.offlineBg,
        ringColor = colors.offlineLed,
        textColor = colors.offlineText,
        label = stringResource(R.string.state_offline),
        countdown = null,
        dashed = true,
    )
}

/**
 * Bloc multi-canaux (2026-08-17) : une ligne de petits cercles façon vraie multiprise, plutôt
 * que la grille 2×2 d'avant qui ne ressemblait à rien de réel. Un canal = un cercle (même
 * logique d'anneau que [PlugCircle]) avec son nom (tronqué, pas la place pour plus) et son état
 * sur deux lignes en dessous — jamais de couleur seule, même à cette échelle. Pas de bouton
 * ON/OFF direct ici (contrairement à une prise seule) : le détail (conso, interrupteur, état
 * complet) s'ouvre dans une modale au tap, pour ne pas surcharger un espace aussi compact.
 */
@Composable
fun DeviceStripRow(
    groupLabel: String,
    members: List<TileUiState>,
    elapsedNow: Long,
    onTapChannel: (TileUiState) -> Unit,
    onOpenFirmware: (Long) -> Unit,
) {
    val colors = MaterialTheme.stateColors
    Surface(
        color = colors.idleBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = groupLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Tous les canaux d'un même bloc partagent la même IP, donc le même chemin
                    // (local/cloud) — un seul picto pour le groupe, pas un par mini-cercle.
                    val groupOnline = members.firstNotNullOfOrNull { it.status as? TileStatus.Online }
                    ConnectivityBadge(groupOnline ?: TileStatus.Offline, modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    members.forEach { member ->
                        MiniPlugCircle(
                            tile = member,
                            elapsedNow = elapsedNow,
                            onClick = { onTapChannel(member) },
                        )
                    }
                }
            }
            // Firmware partagé par tous les canaux d'un même appareil physique (même IP) : un
            // seul bandeau pour le bloc entier, jamais répété par mini-cercle (retour David,
            // 2026-09-23) — vrai si n'importe quel membre porte le drapeau, ils sont tous
            // identiques en pratique (écrits ensemble par checkFirmwareUpdatesIfDue).
            if (members.any { it.device.firmwareUpdateAvailable }) {
                FirmwareUpdateBanner(onClick = { onOpenFirmware(members.first().device.id) })
            }
        }
    }
}

private const val STRIP_NAME_MAX_LENGTH = 12

@Composable
private fun MiniPlugCircle(tile: TileUiState, elapsedNow: Long, onClick: () -> Unit) {
    val colors = MaterialTheme.stateColors
    val activePlanning = remember(tile.plannings, elapsedNow) { tile.plannings.firstOrNull { it.isActiveNow() } }
    val visual = tile.status.toVisual(colors, elapsedNow, tile.presence, activePlanning, tile.pendingThresholdW, tile.onSinceElapsed, tile.presenceDisabledToday, tile.planningDisabledToday)
    // Même logique que PlugCircle : disque = teinte d'état du canal, trous = blanc/surface.
    val holeColor = MaterialTheme.colorScheme.surface
    // Toujours rendu, même vide (retour David, 2026-09-03) : réserve la même hauteur pour tous
    // les canaux d'un même bloc, sinon un seul canal actif avec conso déséquilibre visuellement
    // la ligne (les canaux éteints, sans 3ᵉ ligne, se retrouvent avec un grand vide en dessous).
    val powerWatts = (tile.status as? TileStatus.Online)?.powerWatts?.takeIf { tile.device.hasPowerMetering }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            // Même principe que PlugCircle (cadre carré arrondi + disque intérieur, retour David
            // 2026-09-04), à l'échelle de ce petit cercle.
            val strokeWidthPx = (if (visual.ringEmphasis) 3.dp else 1.5.dp).toPx()
            val squareSize = size.minDimension - strokeWidthPx
            val cornerRadiusPx = squareSize * 0.22f
            val squareTopLeft = Offset((size.width - squareSize) / 2f, (size.height - squareSize) / 2f)
            val innerCircleRadius = size.minDimension * 0.32f
            drawCircle(color = visual.textColor.copy(alpha = 0.7f), radius = innerCircleRadius)
            drawRoundRect(
                color = visual.ringColor,
                topLeft = squareTopLeft,
                size = Size(squareSize, squareSize),
                cornerRadius = CornerRadius(cornerRadiusPx),
                style = Stroke(
                    width = strokeWidthPx,
                    pathEffect = if (visual.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null,
                ),
            )
            val holeRadius = innerCircleRadius * 0.15f
            val holeOffset = innerCircleRadius * 0.57f
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x, center.y - holeOffset))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x - holeOffset, center.y))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x + holeOffset, center.y))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tile.device.name.take(STRIP_NAME_MAX_LENGTH),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = visual.label,
            color = visual.textColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = powerWatts?.let { formatPower(it) }.orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * Modale rapide ouverte au tap d'**une prise, seule ou dans un bloc** (retour David, 2026-09-21 —
 * jusqu'ici réservée aux blocs, une prise seule allait direct sur Configurer, sans raison d'être) :
 * état, consommation, interrupteur, et le lancement d'un programme (durées fixes, Perso déjà
 * configurés, Manuel) — un tap pour agir. Un lien vers Configurer pour tout le reste (créer/modifier
 * un Perso, planning, minuteur bouton, seuils…) — deux taps pour régler.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelQuickSheet(
    tile: TileUiState,
    elapsedNow: Long,
    onToggle: (Boolean) -> Unit,
    /** (seconds, libellé, seuil W) — null pour seconds = sans limite de durée. */
    onLaunch: (Int?, String, Int?) -> Unit,
    onCustom: () -> Unit,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    val activePlanning = remember(tile.plannings, elapsedNow) { tile.plannings.firstOrNull { it.isActiveNow() } }
    val visual = tile.status.toVisual(colors, elapsedNow, tile.presence, activePlanning, tile.pendingThresholdW, tile.onSinceElapsed, tile.presenceDisabledToday, tile.planningDisabledToday)
    val online = tile.status as? TileStatus.Online
    val powerWatts = online?.powerWatts?.takeIf { tile.device.hasPowerMetering }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = tile.device.name, style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // « Actif pour encore » et non juste « Actif » quand un vrai compte à
                        // rebours suit : les deux collés se lisaient comme une heure plutôt qu'une
                        // durée restante (retour David, 2026-09-18). Seulement pour un minuteur
                        // natif (showsRemaining) — jamais pour une plage horaire fixe (Présence,
                        // Planifié : « Planifié pour encore 14:00 - 17:00 » ne voulait rien dire,
                        // retour David, 2026-09-21).
                        val label = if (visual.showsRemaining) {
                            "${visual.label} ${stringResource(R.string.timer_countdown_connector)}"
                        } else {
                            visual.label
                        }
                        Text(text = label, color = visual.textColor, style = MaterialTheme.typography.bodyLarge)
                        visual.countdown?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = it,
                                color = visual.textColor,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    visual.thresholdText?.let {
                        Text(text = it, color = visual.thresholdTextColor ?: visual.textColor, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (powerWatts != null) {
                        Text(
                            text = formatPower(powerWatts),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                RoundToggleButton(
                    enabled = online != null,
                    checked = online?.output == true,
                    onClick = { onToggle(online?.output != true) },
                )
            }

            // Lancement d'un programme — seulement si rien n'est déjà en cours (visual.countdown
            // non nul = un minuteur tourne déjà, le relancer n'aurait pas de sens ; l'interrupteur
            // ci-dessus permet déjà de couper avant terme si besoin). Séparée visuellement des
            // infos ci-dessus par un simple espacement (retour David, 2026-09-21).
            if (tile.device.supportsSwitch && visual.countdown == null) {
                HorizontalDivider()
                val presets = listOf(
                    PersonalPreset(1, tile.device.presetName, tile.device.presetDurationSeconds, tile.device.presetThresholdW, tile.device.presetUnlimited),
                    PersonalPreset(2, tile.device.preset2Name, tile.device.preset2DurationSeconds, tile.device.preset2ThresholdW, tile.device.preset2Unlimited),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3600, 7200, 10800).forEach { seconds ->
                        val label = durationLabel(seconds)
                        OutlinedButton(onClick = { onLaunch(seconds, label, null) }) { Text(label) }
                    }
                    presets.forEach { p ->
                        if (p.configured) {
                            val label = p.name ?: p.durationSeconds?.let { durationLabel(it) }.orEmpty()
                            OutlinedButton(onClick = {
                                if (p.unlimited) onLaunch(null, label, p.thresholdW) else p.durationSeconds?.let { onLaunch(it, label, p.thresholdW) }
                            }) {
                                Text(p.name ?: stringResource(R.string.timer_preset_chip))
                            }
                        }
                    }
                    OutlinedButton(onClick = onCustom) {
                        Text(stringResource(R.string.detail_timer_custom))
                    }
                }
            }

            TextButton(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tile_configure))
            }
        }
    }
}

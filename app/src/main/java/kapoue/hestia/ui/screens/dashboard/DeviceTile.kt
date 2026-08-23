package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.Offset
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
import kapoue.hestia.ui.theme.StateColorSet
import kapoue.hestia.ui.theme.stateColors

/**
 * Tuile d'un canal sur le Tableau. Cercle inspiré de la vraie prise (deux trous), teinté selon
 * l'état ; **jamais de couleur seule** : le libellé texte accompagne toujours l'état (SPEC).
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
                // Seuil sur sa propre ligne, jamais concaténé à la ligne ci-dessus : la combinaison
                // sur une seule ligne entrait en collision avec l'interrupteur (retour David,
                // 2026-08-22).
                visual.thresholdText?.let {
                    Text(
                        text = it,
                        color = visual.textColor,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                    onClick = { onToggle(!checked) },
                )
            }
        }
    }
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

@Composable
private fun RoundToggleButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = stringResource(R.string.tile_toggle),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
    val dashed: Boolean,
    val loading: Boolean = false,
    /**
     * Seuil de coupure surveillant ce canal, sur sa **propre ligne** — jamais concaténé au
     * [countdown] : la combinaison des deux (ex. « Actif · depuis 12:34 · Coupure à 5 W »)
     * entrait en collision avec l'interrupteur sur la tuile prise seule, en une seule ligne trop
     * longue (retour David, 2026-08-22).
     */
    val thresholdText: String? = null,
    /** Vrai quand l'anneau est vert (courant réel) — épaissi dans ce cas précis pour rééquilibrer
     * le disque désormais plus présent (retour David, 2026-08-22 : test d'un disque à 70 %
     * d'opacité + anneau doublé uniquement quand vert). */
    val ringEmphasis: Boolean = false,
)

/**
 * Cercle inspiré de la vraie prise (deux trous) : anneau = fait physique (courant ou non),
 * disque = régime (même couleur que le texte d'état en dessous — Actif vert, Présence indigo,
 * Planifié violet…), lisible même sans lire le texte (retour David, 2026-08-22 : avant ça, le
 * disque suivait aussi le fait physique, aucune distinction visuelle entre les régimes sans lire
 * le texte). Le texte de régime lui-même (« Planifié », compte à rebours…) est affiché par
 * l'appelant sur sa propre ligne, sous le cercle — trop à l'étroit à l'intérieur d'un cercle de
 * 76dp dès qu'il dépassait un mot court (retour de test réel, 2026-08-22).
 */
@Composable
private fun PlugCircle(visual: TileVisual) {
    val holeColor = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(76.dp)) {
            // Anneau doublé quand vert (courant réel) pour rééquilibrer le disque, lui-même
            // atténué à 70 % d'opacité — sans ça le disque plein paraissait trop présent/appuyé
            // (retour David, 2026-08-22).
            val strokeWidthPx = (if (visual.ringEmphasis) 4.dp else 2.dp).toPx()
            val radius = size.minDimension / 2 - strokeWidthPx / 2
            drawCircle(color = visual.textColor.copy(alpha = 0.7f), radius = radius + strokeWidthPx)
            drawCircle(
                color = visual.ringColor,
                radius = radius,
                style = Stroke(
                    width = strokeWidthPx,
                    pathEffect = if (visual.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                ),
            )
            // Resserrés et remontés (2026-08-17, retour de test réel) : trop espacés/bas, ils
            // évoquaient un visage souriant plutôt que les deux trous d'une prise.
            // Trous à 50 % de la hauteur totale du cercle (= centre vertical) et à ~33 % du
            // diamètre depuis chaque bord (donc 17 % depuis le centre), comme sur la vraie prise.
            val holeRadius = size.minDimension * 0.075f
            val holeOffsetX = size.minDimension * 0.17f
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x - holeOffsetX, center.y))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x + holeOffsetX, center.y))
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
            activePlanning != null && planningDisabledToday && !activePlanning.once -> TileVisual(
                bgColor = physicalBg, ringColor = ringColor,
                textColor = if (output) colors.activeText else colors.idleText,
                label = stringResource(if (output) R.string.state_active else R.string.state_idle),
                countdown = null,
                dashed = false,
                // Sur sa propre ligne, jamais concaténé au libellé (« Éteint planning désactivé
                // aujourd'hui » ne voulait rien dire collé, et poussait l'interrupteur en dessous
                // — retour David, 2026-08-22).
                thresholdText = stringResource(R.string.tile_planning_disabled_today),
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
) {
    val colors = MaterialTheme.stateColors
    Surface(
        color = colors.idleBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.size(44.dp)) {
            // Même équilibrage que PlugCircle : anneau doublé quand vert, disque à 70 % d'opacité.
            val strokeWidthPx = (if (visual.ringEmphasis) 3.dp else 1.5.dp).toPx()
            val radius = size.minDimension / 2 - strokeWidthPx / 2
            drawCircle(color = visual.textColor.copy(alpha = 0.7f), radius = radius + strokeWidthPx)
            drawCircle(
                color = visual.ringColor,
                radius = radius,
                style = Stroke(
                    width = strokeWidthPx,
                    pathEffect = if (visual.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null,
                ),
            )
            // Trous à 50 % de la hauteur totale du cercle (= centre vertical) et à ~33 % du
            // diamètre depuis chaque bord (donc 17 % depuis le centre), comme sur la vraie prise.
            val holeRadius = size.minDimension * 0.075f
            val holeOffsetX = size.minDimension * 0.17f
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x - holeOffsetX, center.y))
            drawCircle(color = holeColor, radius = holeRadius, center = Offset(center.x + holeOffsetX, center.y))
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
    }
}

/**
 * Modale ouverte au tap d'un canal du bloc multi-prises : consommation, interrupteur, état
 * complet (avec compte à rebours si un programme est en cours) — et un lien vers l'écran détail
 * complet pour tout ce que la modale ne montre pas (plannings, présence, seuils…).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelQuickSheet(
    tile: TileUiState,
    elapsedNow: Long,
    onToggle: (Boolean) -> Unit,
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
                        Text(text = visual.label, color = visual.textColor, style = MaterialTheme.typography.bodyLarge)
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
                        Text(text = it, color = visual.textColor, style = MaterialTheme.typography.bodyMedium)
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
                    onClick = { onToggle(online?.output != true) },
                )
            }

            TextButton(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tile_open_detail))
            }
        }
    }
}

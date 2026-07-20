package kapoue.hestia.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interrupteur à bascule stylisé « tableau électrique » (SPEC-V1 § 5) : rectangulaire à angles
 * peu arrondis (2 dp), et non le toggle Material arrondi.
 *
 * Affordance : une **piste** visible avec sa teinte propre, un **libellé ON/OFF** monospace sur
 * le côté libre, et une **poignée** contrastée (grise au repos, verte quand allumé) qui glisse
 * d'un bord à l'autre. L'état reste doublé du libellé texte de la tuile (jamais la couleur seule).
 */
@Composable
fun BreakerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackWidth = 64.dp
    val trackHeight = 30.dp
    val thumbWidth = 28.dp
    val inset = 3.dp

    val trackColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        checked -> onColor.copy(alpha = 0.30f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val thumbColor = when {
        !enabled -> MaterialTheme.colorScheme.outline
        checked -> onColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbWidth - inset else inset,
        label = "thumbOffset",
    )

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(2.dp))
            .background(trackColor)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Libellé ON/OFF, affiché sur le côté opposé à la poignée.
        Text(
            text = if (checked) "ON" else "OFF",
            color = if (checked) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = if (checked) TextAlign.Start else TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp),
        )
        // Poignée physique : bordure + teinte contrastée, verte quand allumé.
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .padding(vertical = inset)
                .size(width = thumbWidth, height = trackHeight - inset * 2)
                .clip(RoundedCornerShape(2.dp))
                .background(thumbColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
    }
}

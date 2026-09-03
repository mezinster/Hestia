package kapoue.hestia.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Picto « relais des alertes détecteur de fumée » (Réglages, Lot 4b) — icône officielle Google,
 * "device_hub" du catalogue Material Symbols Outlined, exportée en Compose depuis
 * `fonts.gstatic.com` (2026-09-01, choisie par David plutôt que `Icons.Filled.Cast`). Tracé repris
 * tel quel, même méthode que [SmokeDetectorIcon].
 */
val SmokeRelayIcon: ImageVector
    get() {
        if (_smokeRelayIcon != null) return _smokeRelayIcon!!
        _smokeRelayIcon = ImageVector.Builder(
            name = "SmokeRelay",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 48f,
            viewportHeight = 48f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(6f, 42f)
                verticalLineTo(33f)
                horizontalLineToRelative(7.7f)
                lineToRelative(8.8f, -8.8f)
                verticalLineTo(16.75f)
                quadToRelative(-1.75f, -0.6f, -2.88f, -2.03f)
                reflectiveQuadTo(18.5f, 11.5f)
                quadToRelative(0f, -2.29f, 1.61f, -3.9f)
                reflectiveQuadTo(24.01f, 6f)
                reflectiveQuadTo(27.9f, 7.6f)
                reflectiveQuadToRelative(1.6f, 3.9f)
                quadToRelative(0f, 1.78f, -1.13f, 3.22f)
                reflectiveQuadTo(25.5f, 16.75f)
                verticalLineTo(24.2f)
                lineTo(34.3f, 33f)
                horizontalLineTo(42f)
                verticalLineToRelative(9f)
                horizontalLineTo(33f)
                verticalLineTo(36.2f)
                lineToRelative(-9f, -9f)
                lineToRelative(-9f, 9f)
                verticalLineTo(42f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
        return _smokeRelayIcon!!
    }

private var _smokeRelayIcon: ImageVector? = null

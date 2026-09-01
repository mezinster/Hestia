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
 * Détecteur de fumée — icône officielle Google, "detector_smoke" du catalogue Material Symbols
 * Outlined, exportée en Compose depuis `fonts.gstatic.com` par David (2026-09-01) après trois
 * essais maison écartés en test réel (`Filled.SmokeFree` = « interdiction de fumer » ; un premier
 * dessin « façon méduse » ; un second « façon soleil/engrenage »). Tracé repris tel quel — plus
 * fiable qu'un dessin à la main que je ne peux jamais prévisualiser dans l'app avant que David ne
 * recompile. N'existe pas dans `material-icons-extended` (jeu d'icônes plus ancien qu'Hestia
 * utilise par ailleurs), d'où l'export direct plutôt qu'un `Icons.Filled.*`.
 */
val SmokeDetectorIcon: ImageVector
    get() {
        if (_smokeDetectorIcon != null) return _smokeDetectorIcon!!
        _smokeDetectorIcon = ImageVector.Builder(
            name = "SmokeDetector",
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
                moveTo(25.65f, 44.6f)
                lineTo(22.8f, 43.7f)
                lineToRelative(0.7f, -2.1f)
                quadToRelative(0.4f, -1.2f, 0.2f, -2.55f)
                reflectiveQuadTo(22.8f, 36.6f)
                quadTo(21.6f, 34.7f, 21.33f, 32.85f)
                reflectiveQuadTo(21.7f, 28.8f)
                lineToRelative(0.65f, -2.1f)
                lineToRelative(2.85f, 0.9f)
                lineToRelative(-0.7f, 2.1f)
                quadTo(24.05f, 31f, 24.28f, 32.3f)
                reflectiveQuadToRelative(0.93f, 2.4f)
                quadToRelative(1.2f, 1.95f, 1.45f, 3.8f)
                reflectiveQuadToRelative(-0.35f, 4f)
                lineToRelative(-0.65f, 2.1f)
                close()
                moveToRelative(-8.8f, 0f)
                lineTo(14f, 43.7f)
                lineToRelative(0.7f, -2.1f)
                quadToRelative(0.4f, -1.15f, 0.2f, -2.58f)
                reflectiveQuadTo(14f, 36.55f)
                quadToRelative(-1.2f, -1.7f, -1.5f, -3.72f)
                reflectiveQuadTo(12.9f, 28.8f)
                lineToRelative(0.65f, -2.1f)
                lineToRelative(2.85f, 0.9f)
                lineToRelative(-0.7f, 2.1f)
                quadTo(15.3f, 31f, 15.48f, 32.35f)
                reflectiveQuadToRelative(0.93f, 2.4f)
                quadToRelative(1.2f, 1.8f, 1.42f, 3.77f)
                reflectiveQuadTo(17.5f, 42.5f)
                lineToRelative(-0.65f, 2.1f)
                close()
                moveToRelative(17.4f, 0f)
                lineTo(31.4f, 43.7f)
                lineToRelative(0.7f, -2.1f)
                quadToRelative(0.4f, -1.15f, 0.2f, -2.58f)
                reflectiveQuadTo(31.4f, 36.55f)
                quadTo(30.2f, 34.9f, 29.9f, 32.83f)
                reflectiveQuadTo(30.3f, 28.8f)
                lineToRelative(0.65f, -2.1f)
                lineToRelative(2.85f, 0.9f)
                lineToRelative(-0.7f, 2.1f)
                quadTo(32.65f, 31f, 32.88f, 32.33f)
                reflectiveQuadTo(33.8f, 34.7f)
                quadToRelative(1.2f, 1.85f, 1.45f, 3.8f)
                reflectiveQuadToRelative(-0.35f, 4f)
                lineToRelative(-0.65f, 2.1f)
                close()
                moveTo(9f, 9f)
                verticalLineToRelative(3f)
                horizontalLineTo(39f)
                verticalLineTo(9f)
                horizontalLineTo(9f)
                close()
                moveToRelative(6.05f, 6f)
                lineToRelative(0.9f, 3f)
                horizontalLineToRelative(16.1f)
                lineToRelative(0.9f, -3f)
                horizontalLineTo(15.05f)
                close()
                moveToRelative(0.9f, 6f)
                quadToRelative(-1f, 0f, -1.78f, -0.58f)
                reflectiveQuadTo(13.1f, 18.9f)
                lineTo(11.85f, 15f)
                horizontalLineTo(9f)
                quadTo(7.75f, 15f, 6.88f, 14.13f)
                reflectiveQuadTo(6f, 12f)
                verticalLineTo(6f)
                horizontalLineTo(42f)
                verticalLineToRelative(6f)
                quadToRelative(0f, 1.25f, -0.88f, 2.13f)
                reflectiveQuadTo(39f, 15f)
                horizontalLineTo(36.15f)
                lineToRelative(-1.5f, 4.05f)
                quadToRelative(-0.35f, 0.85f, -1.13f, 1.4f)
                reflectiveQuadTo(31.8f, 21f)
                horizontalLineTo(15.95f)
                close()
                moveTo(9f, 9f)
                verticalLineToRelative(3f)
                verticalLineTo(9f)
                close()
            }
        }.build()
        return _smokeDetectorIcon!!
    }

private var _smokeDetectorIcon: ImageVector? = null

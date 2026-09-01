package kapoue.hestia.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Détecteur de fumée mural (boîtier à coins coupés + grille + volutes de fumée) — n'existe dans
 * aucun jeu d'icônes Material standard. `Filled.SmokeFree` (utilisé un temps) est en fait le
 * picto « interdiction de fumer », pas un détecteur — confusion vécue en test réel le
 * 2026-08-31 (David : « rien à voir avec un détecteur »), corrigée en dessinant celui-ci à la
 * main plutôt que de réutiliser un picto au sens différent. Dessiné en traits (pas de
 * remplissage) sur une grille 24×24 comme les icônes Material, à partir d'une capture fournie
 * par David. La couleur du trait ci-dessous n'a pas d'importance : `Icon(tint = …)` la remplace
 * entièrement au dessin.
 */
val SmokeDetectorIcon: ImageVector
    @Composable
    get() = remember {
        ImageVector.Builder(
            name = "SmokeDetector",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Boîtier : rectangle à coins coupés (évite les arcs, plus simple à tracer juste).
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                lineTo(19f, 6f)
                lineTo(19f, 8f)
                lineTo(17f, 10f)
                lineTo(7f, 10f)
                lineTo(5f, 8f)
                lineTo(5f, 6f)
                close()

                // Grille (volets d'aération), 4 traits verticaux courts à l'intérieur du boîtier.
                moveTo(8f, 6f)
                lineTo(8f, 8.5f)
                moveTo(10.5f, 6f)
                lineTo(10.5f, 8.5f)
                moveTo(13.5f, 6f)
                lineTo(13.5f, 8.5f)
                moveTo(16f, 6f)
                lineTo(16f, 8.5f)

                // Volutes de fumée sous le boîtier, 3 ondulations.
                moveTo(9f, 11.5f)
                quadraticBezierTo(7f, 13.5f, 9f, 15.5f)
                quadraticBezierTo(11f, 17.5f, 9f, 19.5f)
                moveTo(12f, 11.5f)
                quadraticBezierTo(10f, 13.5f, 12f, 15.5f)
                quadraticBezierTo(14f, 17.5f, 12f, 19.5f)
                moveTo(15f, 11.5f)
                quadraticBezierTo(13f, 13.5f, 15f, 15.5f)
                quadraticBezierTo(17f, 17.5f, 15f, 19.5f)
            }
        }.build()
    }

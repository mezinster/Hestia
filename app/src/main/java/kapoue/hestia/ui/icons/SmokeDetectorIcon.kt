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
 * Détecteur de fumée vu de dessous (dôme + rebord intérieur + chambre centrale + grille) —
 * n'existe dans aucun jeu d'icônes Material standard. Deux essais précédents écartés en test réel
 * le 2026-08-31 : `Filled.SmokeFree` (picto « interdiction de fumer »), puis un premier dessin
 * maison jugé « on dirait une méduse », puis un second jugé « on dirait un soleil/engrenage »
 * (grille trop large, pas de rebord). **Contrairement aux deux précédents, ce tracé a été
 * vérifié visuellement avant d'être transcrit ici** : dessiné en SVG, rendu et capturé via Chrome
 * headless (même méthode que pour la bannière F-Droid), comparé côté à côté avec la capture
 * fournie par David jusqu'à correspondance, *puis seulement* recopié en tracé Compose — les deux
 * tentatives précédentes avaient été écrites à l'aveugle, sans aucun moyen de les prévisualiser
 * avant que David ne recompile.
 *
 * Dessiné en traits (pas de remplissage) sur une grille 24×24 comme les icônes Material. La
 * couleur du trait ci-dessous n'a pas d'importance : `Icon(tint = …)` la remplace entièrement.
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
                strokeLineWidth = 0.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Dôme extérieur : large et plat, bas droit.
                moveTo(4f, 10f)
                curveTo(4f, 5.5f, 7.5f, 3f, 12f, 3f)
                curveTo(16.5f, 3f, 20f, 5.5f, 20f, 10f)
                lineTo(4f, 10f)
                close()

                // Rebord intérieur (liseré parallèle, effet 3D du dôme).
                moveTo(6f, 9.3f)
                curveTo(6f, 6.2f, 8.5f, 4.3f, 12f, 4.3f)
                curveTo(15.5f, 4.3f, 18f, 6.2f, 18f, 9.3f)

                // Chambre centrale (cercle, centre 12/15, rayon 3 — 4 arcs cubiques, k = r*0.5523).
                moveTo(12f, 12f)
                curveTo(13.657f, 12f, 15f, 13.343f, 15f, 15f)
                curveTo(15f, 16.657f, 13.657f, 18f, 12f, 18f)
                curveTo(10.343f, 18f, 9f, 16.657f, 9f, 15f)
                curveTo(9f, 13.343f, 10.343f, 12f, 12f, 12f)
                close()

                // Grille : 5 traits verticaux courts entre le bas du dôme et la chambre.
                moveTo(8f, 10.6f)
                lineTo(8f, 12f)
                moveTo(10f, 10.6f)
                lineTo(10f, 12f)
                moveTo(12f, 10.6f)
                lineTo(12f, 12f)
                moveTo(14f, 10.6f)
                lineTo(14f, 12f)
                moveTo(16f, 10.6f)
                lineTo(16f, 12f)
            }
        }.build()
    }

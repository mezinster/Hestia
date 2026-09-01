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
 * Détecteur de fumée vu de dessous (dôme extérieur + chambre centrale + grille radiale) —
 * n'existe dans aucun jeu d'icônes Material standard. Deux essais précédents écartés en test
 * réel le 2026-08-31 : `Filled.SmokeFree` (picto « interdiction de fumer », pas un détecteur),
 * puis un premier dessin maison (boîtier à coins coupés + volutes de fumée, jugé par David
 * « on dirait une méduse »). Reparti d'une capture fournie par David : deux cercles concentriques
 * reliés par des traits radiaux courts (grille d'aération), motif classique du picto détecteur.
 * Les cercles sont approximés par des polygones à 12 côtés (calculés à la main, `arcTo` du DSL
 * `path{}` étant plus risqué à écrire juste sans prévisualisation possible ici). Dessiné en
 * traits (pas de remplissage) sur une grille 24×24 comme les icônes Material. La couleur du
 * trait ci-dessous n'a pas d'importance : `Icon(tint = …)` la remplace entièrement au dessin.
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
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Dôme extérieur : polygone à 12 côtés, centre (12,12), rayon 8.
                moveTo(20f, 12f)
                lineTo(18.93f, 16f)
                lineTo(16f, 18.93f)
                lineTo(12f, 20f)
                lineTo(8f, 18.93f)
                lineTo(5.07f, 16f)
                lineTo(4f, 12f)
                lineTo(5.07f, 8f)
                lineTo(8f, 5.07f)
                lineTo(12f, 4f)
                lineTo(16f, 5.07f)
                lineTo(18.93f, 8f)
                close()

                // Chambre centrale : même principe, rayon 3.5.
                moveTo(15.5f, 12f)
                lineTo(15.03f, 13.75f)
                lineTo(13.75f, 15.03f)
                lineTo(12f, 15.5f)
                lineTo(10.25f, 15.03f)
                lineTo(8.97f, 13.75f)
                lineTo(8.5f, 12f)
                lineTo(8.97f, 10.25f)
                lineTo(10.25f, 8.97f)
                lineTo(12f, 8.5f)
                lineTo(13.75f, 8.97f)
                lineTo(15.03f, 10.25f)
                close()

                // Grille radiale : 8 traits courts entre les deux cercles (rayon 4.5 à 7).
                moveTo(16.5f, 12f)
                lineTo(19f, 12f)
                moveTo(15.18f, 15.18f)
                lineTo(16.95f, 16.95f)
                moveTo(12f, 16.5f)
                lineTo(12f, 19f)
                moveTo(8.82f, 15.18f)
                lineTo(7.05f, 16.95f)
                moveTo(7.5f, 12f)
                lineTo(5f, 12f)
                moveTo(8.82f, 8.82f)
                lineTo(7.05f, 7.05f)
                moveTo(12f, 7.5f)
                lineTo(12f, 5f)
                moveTo(15.18f, 8.82f)
                lineTo(16.95f, 7.05f)
            }
        }.build()
    }

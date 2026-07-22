# Règles ProGuard/R8 — minification ACTIVÉE sur la variante release (voir build.gradle.kts).
#
# Room, Hilt, OkHttp et Compose embarquent leurs propres règles de conservation : rien à écrire
# pour eux. Le seul point sensible est kotlinx.serialization, qui résout ses sérialiseurs par le
# nom des classes et de leurs objets Companion — R8 les renommerait ou les supprimerait.
#
# R8 ne casse jamais la compilation : il casse à l'exécution. Toute modification ici doit être
# vérifiée sur un APK release réel (sérialisation RPC, sauvegarde/restauration surtout).
#
# kotlinx.serialization : conserver les classes @Serializable et leurs sérialiseurs générés.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kapoue.hestia.** {
    *** Companion;
}
-keepclasseswithmembers class kapoue.hestia.** {
    kotlinx.serialization.KSerializer serializer(...);
}

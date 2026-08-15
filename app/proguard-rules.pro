# Règles ProGuard/R8 — minification ACTIVÉE sur la variante release (voir build.gradle.kts).
#
# Room, Hilt, OkHttp et Compose embarquent leurs propres règles de conservation : rien à écrire
# pour eux. Le seul point sensible est kotlinx.serialization, qui résout ses sérialiseurs par le
# nom des classes et de leurs objets Companion — R8 les renommerait ou les supprimerait.
#
# R8 casse le plus souvent à l'exécution, pas à la compilation — mais pas toujours : des classes
# manquantes référencées par une dépendance (voir règle Tink ci-dessous) font échouer le build
# lui-même. Comme assembleDebug ne fait jamais tourner R8 (seule la variante release l'active),
# ce genre de problème ne se voit qu'en release réel — ou, découvert le 2026-08-15, sur le build
# F-Droid lui-même (échec CI sur kapoue.hestia:32, jamais reproduit en local). Toute modification
# ici doit être vérifiée sur un APK release réel (sérialisation RPC, sauvegarde/restauration surtout).
#
# Tink (via androidx.security:security-crypto, chiffrement du sujet ntfy) référence des annotations
# Error Prone absentes du classpath — de simples marqueurs de compilation, jamais utilisés à
# l'exécution, mais sans cette règle R8 refuse de continuer (confirmé par l'échec de build F-Droid
# ci-dessus, 4 classes manquantes : CanIgnoreReturnValue, CheckReturnValue, Immutable, RestrictedApi).
-dontwarn com.google.errorprone.annotations.**

# kotlinx.serialization : conserver les classes @Serializable et leurs sérialiseurs générés.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kapoue.hestia.** {
    *** Companion;
}
-keepclasseswithmembers class kapoue.hestia.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Règles ProGuard/R8 — minification désactivée en V1 (voir build.gradle.kts).
# kotlinx.serialization : conserver les classes @Serializable et leurs sérialiseurs générés.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kapoue.hestia.** {
    *** Companion;
}
-keepclasseswithmembers class kapoue.hestia.** {
    kotlinx.serialization.KSerializer serializer(...);
}

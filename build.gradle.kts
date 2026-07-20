// Fichier de build racine — déclare les plugins, appliqués dans les modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Pas de kotlin.android : depuis AGP 9.0 le support Kotlin est intégré (built-in Kotlin).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

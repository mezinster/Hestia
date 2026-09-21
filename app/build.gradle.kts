plugins {
    alias(libs.plugins.android.application)
    // Support Kotlin fourni par AGP 9.0 (built-in Kotlin) : ne pas appliquer kotlin.android.
    // La version de Kotlin est déterminée par les plugins compilateur ci-dessous (2.3.10).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "kapoue.hestia"
    compileSdk = 37

    defaultConfig {
        applicationId = "kapoue.hestia"
        minSdk = 30
        targetSdk = 37
        // Doit rester un littéral : fdroidserver lit ce fichier par expression régulière, il ne
        // l'exécute pas. Une variable ici et checkupdates échoue sur « vercode=None ».
        //
        // 30 et non 1 : les essais de découpage par ABI ont installé des versionCode 21 à 24 sur
        // les appareils de test, et Android refuse d'installer par-dessus un code inférieur.
        // Repartir au-dessus évite de désinstaller (donc de perdre la base locale) à chaque test.
        // Le versionCode est arbitraire et n'a pas à suivre le versionName ; seul compte qu'il
        // croisse d'une publication à l'autre — et rien n'a encore été publié.
        versionCode = 51
        versionName = "2.14.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 : indispensable ici, car material-icons-extended embarque plusieurs milliers
            // d'icônes compilées en code alors que l'application en utilise quinze. Sans
            // minification, l'APK atteint 47 Mo dont ~48 Mo de DEX décompressé.
            // Les règles de conservation sont dans proguard-rules.pro (sérialisation surtout).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Avec le built-in Kotlin d'AGP 9, le jvmTarget de Kotlin reprend automatiquement
    // compileOptions.targetCompatibility (17) : plus de bloc kotlinOptions à maintenir.

    buildFeatures {
        compose = true
    }

    // Le schéma Room est exporté pour permettre le suivi des migrations en revue.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager : réveils périodiques (~15 min) pour les notifications de bornes de
    // programmation. Aucun scheduler ne pilote l'appareil (interdit) — il ne fait que LIRE
    // l'état et notifier. Le worker récupère ses dépendances Hilt via EntryPointAccessors,
    // ce qui évite hilt-work et toute modification de l'Application/manifest.
    implementation(libs.androidx.work.runtime)

    // Chiffrement local (Android Keystore) du sujet ntfy — un secret au même titre qu'un mot de
    // passe, jamais stocké en clair.
    implementation(libs.androidx.security.crypto)

    // Génération locale du QR code (Apache 2.0, sans service Google — F-Droid OK).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

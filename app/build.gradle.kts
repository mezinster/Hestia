import com.android.build.api.variant.FilterConfiguration.FilterType.ABI

plugins {
    alias(libs.plugins.android.application)
    // Support Kotlin fourni par AGP 9.0 (built-in Kotlin) : ne pas appliquer kotlin.android.
    // La version de Kotlin est déterminée par les plugins compilateur ci-dessous (2.3.10).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Découpage par ABI (exigé par F-Droid) : Compose embarque une bibliothèque native
// (libandroidx.graphics.path.so) compilée pour 4 architectures. Sans découpage, chaque
// utilisateur téléchargerait 3 architectures qu'il n'utilisera jamais.
//
// F-Droid impose l'ordre armeabi-v7a < arm64-v8a < x86 < x86_64, avec le chiffre d'ABI
// en DERNIÈRE position du versionCode → versionCode = 10 * base + rang.
// Doit rester synchronisé avec le champ VercodeOperation de fdroid/kapoue.hestia.yml.
//
// Base à 2 (et non 1) pour éviter le versionCode 13 (cf. CLAUDE.md § Versionnement).
// Indolore : le versionCode 1 n'a jamais été publié.
val abiVersionCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)

// Le serveur F-Droid construit **un APK à la fois** : il refuse un dossier de sortie qui en
// contient plusieurs. Chaque bloc de build de la recette passe donc -PabiFilter=<abi> pour
// restreindre le découpage à une seule architecture. Sans la propriété (build local, Android
// Studio), les quatre architectures sont produites comme avant.
val abiFilter: String? = providers.gradleProperty("abiFilter").orNull

android {
    namespace = "kapoue.hestia"
    compileSdk = 37

    defaultConfig {
        applicationId = "kapoue.hestia"
        minSdk = 30
        targetSdk = 37
        // Doit rester un littéral : fdroidserver lit ce fichier par expression régulière, il ne
        // l'exécute pas. Une variable ici et checkupdates échoue sur « vercode=None ».
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Un APK par architecture, pas d'APK universel (voir le commentaire en tête de fichier).
    // Avec -PabiFilter=<abi>, une seule architecture est produite : c'est ce dont F-Droid a
    // besoin pour ne trouver qu'un APK par build.
    splits {
        abi {
            isEnable = true
            reset()
            include(*(abiFilter?.let { arrayOf(it) } ?: abiVersionCodes.keys.toTypedArray()))
            isUniversalApk = false
        }
    }

    // Le schéma Room est exporté pour permettre le suivi des migrations en revue.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// Attribue à chaque APK d'ABI son propre versionCode (10 * base + rang), en miroir exact
// du VercodeOperation de la recette F-Droid. Sans cela, les 4 APK porteraient le même
// versionCode et F-Droid ne saurait pas lequel servir.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            abiVersionCodes[abi]?.let { rank ->
                // La base est relue depuis defaultConfig : une seule source de vérité, et le
                // littéral reste lisible par fdroidserver.
                val base = output.versionCode.get() ?: 0
                output.versionCode.set(10 * base + rank)
            }
        }
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

    // Génération locale du QR code (Apache 2.0, sans service Google — F-Droid OK).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

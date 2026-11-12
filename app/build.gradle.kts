plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Each CI run produces a higher versionCode so a new APK installs over the old one.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.dialorsd.jarvis"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dialorsd.jarvis"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "3.0.$runNumber"
    }

    signingConfigs {
        // Fixed keystore committed to this private repo so every build can update the previous one.
        create("jarvis") {
            storeFile = rootProject.file("keystore/jarvis.jks")
            storePassword = "jarvis-sideload"
            keyAlias = "jarvis"
            keyPassword = "jarvis-sideload"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // WebSocket client for the Gemini Live API (the only new dependency in v3).
    implementation("com.squareup.okhttp3:okhttp:5.3.1")
}

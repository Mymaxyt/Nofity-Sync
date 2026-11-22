import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.notifysync.app"
    // Neueste stabile Plattform, die beim Bau sicher verfügbar ist. Sobald das SDK für die
    // nächste Android-Version installiert ist, hier und bei targetSdk hochzählen.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notifysync.app"
        minSdk = 33 // Android 13
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2-test"
    }

    // Fester Test-Schlüssel (wie der Android-Debug-Schlüssel, kein Geheimnis), damit sich neue
    // Versionen aus dem CI über die alte installieren lassen, ohne die Kopplung zu verlieren.
    signingConfigs {
        getByName("debug") {
            storeFile = file("test-signing.keystore")
            storePassword = "android"
            keyAlias = "notifysync-test"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Nur Test-App: mit dem Debug-Schlüssel signieren, damit sie sich direkt installieren lässt.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Cloud-Relay: Firestore transportiert nur verschlüsselte Pakete, Anmeldung anonym.
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")

    // QR-Code für die Kopplung
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

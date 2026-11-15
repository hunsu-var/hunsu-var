import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.hunsuvar"
    compileSdk = 35
    useLibrary("android.car") // AAOS CarPropertyManager (speed, gear)

    defaultConfig {
        applicationId = "app.hunsuvar"
        minSdk = 28 // Pleos: required for signing-key rotation (cybersecurity)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing: keystore.properties (gitignored) next to the root build.gradle.kts.
    // Without that file the release build is produced unsigned (assembleDebug / :core:test keep working).
    val ksFile = rootProject.file("keystore.properties")
    if (ksFile.exists()) {
        val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}

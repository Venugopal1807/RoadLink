import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.roadlink.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roadlink.spike"
        // minSdk 26 is a CAPABILITY floor, not a market-share choice:
        // startAdvertisingSet (BLE 5 extended advertising), PendingIntent-based
        // background scanning, and the modern foreground-service model all
        // require API 26. See docs/phase1-spike-plan.md A.3.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-s0-s5"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Both phones install the same APK. The role (peripheral vs central)
            // is chosen at runtime, not at build time.
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Deliberately no dependencies. See gradle/libs.versions.toml for why.
dependencies {
}

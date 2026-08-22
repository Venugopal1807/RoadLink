import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.roadlink"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roadlink"
        // Same capability floor as :spike-ble - the BLE peripheral role and the
        // modern foreground-service model both require API 26.
        // See docs/phase1-spike-plan.md A.3.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2-delivery-spine"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The backend base URL. Overridable per build without touching code.
        // 10.0.2.2 is the host loopback as seen from an Android emulator; on a
        // physical phone this must be the LAN IP of the machine running uvicorn.
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${project.findProperty("roadlink.backendUrl") ?: "http://10.0.2.2:8000"}\"",
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ksp {
    // Lets the Room durability test open the generated schema; also gives us a
    // checked-in migration history the moment the schema changes.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}

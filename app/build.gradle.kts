import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.alexeygrigorev.openscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.alexeygrigorev.openscan"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Opt-in telemetry endpoint (docs/PRIVACY.md). Both blank by default:
        // without them the built APK ships an inert ".invalid" endpoint and
        // uploads fail fast even when the user opts in. Never commit real
        // values — CI reads them from repo secrets, locals from
        // ~/.gradle/gradle.properties.
        buildConfigField(
            "String",
            "TELEMETRY_UPLOAD_URL",
            "\"${telemetryConfigValue("OPENSCAN_TELEMETRY_UPLOAD_URL")}\"",
        )
        buildConfigField(
            "String",
            "TELEMETRY_TOKEN",
            "\"${telemetryConfigValue("OPENSCAN_TELEMETRY_UPLOAD_TOKEN")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Distribution flavors (docs/FEATURES.md, product rule 3 — zero permission
    // creep): `play` keeps capture inside Google Play services' ML Kit
    // document scanner and never gains the CAMERA permission; `foss` swaps
    // capture for our own CameraX + OpenCV batch pipeline (app/src/foss) and
    // is the flavor F-Droid / de-Googled devices get. Everything else —
    // library, editing, OCR, exports, telemetry toggle — is shared main code.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
    }

    // Release signing: CI provides the keystore via env vars (see the release
    // workflow). Without ANDROID_RELEASE_KEYSTORE_BASE64 set, release builds
    // stay unsigned so local developers can still assemble; they usually just
    // build debug.
    val releaseKeystoreB64 = System.getenv("ANDROID_RELEASE_KEYSTORE_BASE64")
    if (releaseKeystoreB64 != null) {
        val keystoreFile = File(buildDir, "release-keystore.jks")
        Base64.getDecoder().decode(releaseKeystoreB64).let { keystoreFile.writeBytes(it) }
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
        }
        buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** Gradle property first, then environment variable, then empty. */
fun telemetryConfigValue(name: String): String =
    (project.findProperty(name) as? String)
        ?: System.getenv(name)
        ?: ""

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Capture is flavor-specific: GMS scanner on play (no CAMERA permission),
    // CameraX own pipeline on foss. OCR (bundled, offline) and everything else
    // stay in main for both. Quoted configuration names: flavor accessors
    // don't exist at script-compile time in the Kotlin DSL.
    "playImplementation"(libs.mlkit.document.scanner)
    "fossImplementation"(libs.androidx.camera.core)
    "fossImplementation"(libs.androidx.camera.camera2)
    "fossImplementation"(libs.androidx.camera.lifecycle)
    "fossImplementation"(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.pdfbox.android)
    implementation(libs.opencv)

    testImplementation(libs.junit)
    // android.jar ships org.json as stubs that throw in local JVM tests; the
    // real artifact provides the same API for the release-check parser tests.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
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

    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.pdfbox.android)
    implementation(libs.opencv)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

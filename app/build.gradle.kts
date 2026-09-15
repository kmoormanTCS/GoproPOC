import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Release signing credentials for the TCS-owned key.
 *
 * The keystore and its password are deliberately NOT in this repo: the repo is
 * public, and a signing key there would let anyone build an APK that Android
 * accepts as a genuine update to this app.
 *
 * The key still has to be shared, though, and for a reason worth recording:
 * before it existed, every developer's Android Studio signed with its own
 * generated ~/.android/debug.keystore, so an APK built on one machine could not
 * update an install from another and MDM rejected it with "App signature
 * mismatch with an existing version".
 *
 * Resolution order, first hit wins:
 *   1. keystore.properties at the repo root (gitignored) — the local default.
 *   2. Environment variables — for CI.
 *
 * With neither, debug builds still work (signed with the local Android Studio
 * debug key) and assembleRelease produces an UNSIGNED APK. See README.md.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val tcsStorePath = signingValue("storeFile", "TCS_KEYSTORE_FILE")
val tcsStorePass = signingValue("storePassword", "TCS_KEYSTORE_PASSWORD")
val tcsKeyAlias = signingValue("keyAlias", "TCS_KEY_ALIAS")
val tcsKeyPass = signingValue("keyPassword", "TCS_KEY_PASSWORD")

val tcsKeystore = tcsStorePath?.let { rootProject.file(it) }
val hasTcsSigning = tcsKeystore != null && tcsKeystore.exists() &&
        tcsStorePass != null && tcsKeyAlias != null && tcsKeyPass != null

if (!hasTcsSigning) {
    logger.warn(
        "\n[TCS] No signing key configured — release builds will be UNSIGNED and " +
        "cannot be deployed. Create keystore.properties (see README.md#signing).\n"
    )
}

android {
    namespace = "net.thompsoncs.truckcapture"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.thompsoncs.truckcapture"
        minSdk = 26
        targetSdk = 35
        // 3.0   = the multi-screen TCS-branded rework of the 2.x single-screen build.
        // 3.0.1 = TCS launcher icon + "360 Capture" label, so it is told apart
        //         from the old com.example.truckcapture install on the tablets.
        // versionCode must increase on every build pushed through MDM, or the
        // device treats it as already-installed and silently skips the update.
        // 3.0.2 = app-bar Back arrow, so Uploads/Review/manual entry can be
        //         left without relying on the system Back button.
        // 3.0.3 = raw QR payload and the parse breakdown now appear in the
        //         on-screen diagnostics log, for validating live truck codes.
        // 3.1 = in-app field guide, plus a live "can this upload right now"
        //       line on Home that explains the GoPro-Wi-Fi-has-no-internet trap.
        versionCode = 6
        versionName = "3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasTcsSigning) {
            create("tcs") {
                storeFile = tcsKeystore
                storePassword = tcsStorePass
                keyAlias = tcsKeyAlias
                keyPassword = tcsKeyPass
            }
        }
    }

    buildTypes {
        release {
            if (hasTcsSigning) signingConfig = signingConfigs.getByName("tcs")
            // R8 left off on purpose: the AWS SDK and ML Kit both resolve
            // classes reflectively, and shrinking them needs keep rules this
            // POC has not been tested against. Revisit before any real release.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug uses the same key when it is available, so a debug build can
        // replace an MDM-installed release build during testing. Without it,
        // AGP falls back to the local Android Studio debug key.
        debug {
            if (hasTcsSigning) signingConfig = signingConfigs.getByName("tcs")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.amazonaws:aws-android-sdk-s3:2.75.0")
    implementation("com.amazonaws:aws-android-sdk-cognitoidentityprovider:2.75.0")
    implementation("com.amazonaws:aws-android-sdk-core:2.75.0")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

// ML Kit barcode scanning (on-device, offline)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
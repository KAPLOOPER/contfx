plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every CI build is numbered by its workflow run, so each one is a newer version than
// the last and installs as an update. Local builds count as build 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release builds are signed with the app's own key (keystore/continuum.p12, locked with
// the SIGNING_PASSWORD secret), so updates install over the top without a reinstall.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.continuum.fx"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.continuum.fx"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "UPDATE_REPO", "\"KAPLOOPER/contfx\"")
    }

    signingConfigs {
        create("continuum") {
            storeFile = rootProject.file("keystore/continuum.p12")
            storeType = "PKCS12"
            storePassword = signingPassword
            keyAlias = "continuum"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            // R8 drops the library code the app never calls (most of Compose, CameraX and the
            // rest) and keeps every name as written, so crash reports read like the source.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the password (local builds, pull requests) fall back to the debug key;
            // those builds can't update a CI-installed copy, which is expected.
            signingConfig = signingConfigs.getByName(if (signingPassword != null) "continuum" else "debug")
        }
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
        // The volume panel's Shizuku helper talks over AIDL.
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}

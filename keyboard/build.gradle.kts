plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

// Every CI build is numbered by its workflow run, so each is newer than the last and
// installs as an update (including over the old FUTO-based builds). Local builds are 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Signed with the same key as Continuum FX, so updates install over the top.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.continuum.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.continuum.keyboard"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
    }

    signingConfigs {
        create("continuum") {
            storeFile = file("../keystore/continuum.p12")
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
            signingConfig = signingConfigs.getByName(if (signingPassword != null) "continuum" else "debug")
        }
    }

    // Phones only: leaving out emulator code keeps the download smaller.
    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.autofill:autofill:1.1.0")

    // Scan text: the camera and on-device text recognition.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}

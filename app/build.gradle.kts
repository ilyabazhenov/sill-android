plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.remotemac.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.remotemac.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    // The release key comes from the environment: RM_KEYSTORE (path), RM_KEYSTORE_PASSWORD, RM_KEY_ALIAS.
    // Without it the release build is debug-signed, for testing only.
    val releaseKeystore = System.getenv("RM_KEYSTORE")?.let(::file)
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("RM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RM_KEY_ALIAS") ?: "remotemac"
            keyPassword = System.getenv("RM_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
}

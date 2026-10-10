plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.thakaan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thakaan"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        // QNN loads its skel libraries from nativeLibraryDir by path, so they must be extracted, not memory-mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // The on-device language model library is built with a newer Kotlin; its classes are read, not recompiled.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.1.10909125"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.51.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.51.0")
    // Face landmarks and blendshapes for the optional face checks (#31); the model file is a local asset, no network at run time.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    // Gemma 4 E2B phrasing (#57) on the GPU; the .litertlm file is pushed to the phone, never bundled or downloaded at run time.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    // Offline digit recogniser for the speech code (#34); small models are pushed to the phone, nothing is downloaded at run time.
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}


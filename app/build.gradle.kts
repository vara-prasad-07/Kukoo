plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.kukoo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kukoo"
        // GenieX requires 27; its own binding module is built against minSdk 27.
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GenieX and sherpa-onnx both ship arm64-v8a only. Keeping x86_64 here would
        // package an APK whose native libs cannot resolve on that ABI.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // The vendored llama.cpp CMake build is disabled: inference now runs through GenieX on the
    // NPU, and compiling llama.cpp added many minutes to every build. NativeLlamaEngine catches
    // the missing libkukoo_llama.so and reports unavailable, so nothing breaks. To bring the CPU
    // fallback back, restore the externalNativeBuild blocks and ndkVersion below.
    //
    // ndkVersion = "27.2.12479018"
    // externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // GenieX dlopen()s its plugins by absolute path out of applicationInfo.nativeLibraryDir, so the
    // .so files must be real files on disk. Modern AGP defaults to leaving them compressed inside
    // the APK, which makes init fail with "Cannot find libgeniex_plugin_qairt.so".
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // --- on-device AI -----------------------------------------------------------------
    // Qualcomm GenieX: Qwen3-VL-4B-Instruct on the Hexagon NPU via the qairt runtime.
    implementation(libs.geniex.android)
    // sherpa-onnx: offline STT (Moonshine) and TTS (Kokoro / Piper) in one AAR.
    // Downloaded from https://github.com/k2-fsa/sherpa-onnx/releases into app/libs/.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
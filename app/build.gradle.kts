// Android application module: presentation only. All agent logic lives in :core.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional on-device inference. llama.cpp lives outside the repository (like
// sdk.dir) and is ignored by git; without it the project builds exactly as it
// did before the migration — plain Kotlin, no native library.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val llamaCppDir: String = localProperties.getProperty("llama.cpp.dir").orEmpty()

android {
    namespace = "com.waqti.agent"
    compileSdk = 36

    if (llamaCppDir.isNotBlank()) {
        ndkVersion = "28.2.13676358"
    }

    defaultConfig {
        applicationId = "com.waqti.agent"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        if (llamaCppDir.isNotBlank()) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLAMA_CPP_DIR=$llamaCppDir",
                        // Gradle appends these after AGP's own
                        // -DCMAKE_TOOLCHAIN_FILE=<ndk>/android.toolchain.cmake,
                        // and CMake keeps the LAST one, so the on-device
                        // toolchain in this repository wins.
                        "-DCMAKE_TOOLCHAIN_FILE=${file("src/main/cpp/waqti.android.toolchain.cmake")}",
                    )
                }
            }
        }
    }

    if (llamaCppDir.isNotBlank()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            // Not shippable yet: validation builds are debug-signed.
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}

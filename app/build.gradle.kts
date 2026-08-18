plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.airvoice.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airvoice.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    aaptOptions {
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // sherpa-onnx AAR（内含 onnxruntime，勿再单独引入 onnxruntime-android）
    implementation(fileTree("libs") { include("*.aar") })
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

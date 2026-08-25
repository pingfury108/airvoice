import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// 发布签名配置（仅存于构建机，不入库；缺失时 release 构建保持未签名）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.airvoice.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airvoice.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        ndk { abiFilters += listOf("arm64-v8a") } // 只打真机架构，去掉 x86 模拟器库
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProps.getProperty("keyAlias", "")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
            storePassword = keystoreProps.getProperty("storePassword", "")
            keystoreProps.getProperty("storeFile")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // JNI 调用多，暂不开混淆
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

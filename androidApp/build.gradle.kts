plugins {
    alias(libs.plugins.androidApplication)
    // 不要加 org.jetbrains.kotlin.android —— AGP 9.0 起内置 Kotlin 支持，
    // 加了会直接构建失败（"no longer required for Kotlin support since AGP 9.0"）。
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.boomsset"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.boomsset"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // FragmentActivity 从这里来 —— BiometricPrompt 硬性要求，见 AGENTS.md 约束 6
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
}

plugins {
    alias(libs.plugins.androidApplication)
    // Do NOT add org.jetbrains.kotlin.android — AGP 9.0+ has built-in Kotlin support,
    // and adding it fails the build outright ("no longer required for Kotlin support since AGP 9.0").
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
    // FragmentActivity comes from here — hard requirement of BiometricPrompt, see AGENTS.md constraint 6
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
}

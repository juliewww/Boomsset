plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // AGP 9：KMP 模块用 com.android.kotlin.multiplatform.library，配置写在 kotlin{} 里，
    // 顶层的 android{} 块已经不存在了。注意这个插件只支持单 variant，没有 buildType/flavor。
    // 注意是 android {} 而不是 androidLibrary{} —— 后者在 AGP 9.1 已废弃。
    // 而这个 android{} 在 kotlin{} 里面，不是顶层的那个（顶层的对 KMP 模块已不存在）。
    android {
        namespace = "com.boomsset.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // 必须显式开启。新的 KMP Android 插件默认**不建**测试 target ——
        // 不加这行，commonTest 里的测试在 JVM 上无处运行，而且不会有任何报错提示。
        withHostTestBuilder {}
    }

    // 只有 arm64。iosX64 已被 Kotlin/CMP 移除，Intel Mac 跑不了。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotest.assertions.core)
        }
    }
}

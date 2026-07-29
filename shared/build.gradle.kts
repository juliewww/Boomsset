plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("BoomssetDatabase") {
            packageName.set("com.boomsset.db")
            // schema 变更后要生成 migration，先把 verifyMigrations 开着，
            // 免得改了表结构却忘了写 .sqm
            verifyMigrations.set(true)
        }
    }
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

            // api 而不是 implementation：Koin 的 Module / KoinApplication 出现在
            // di/Modules.kt 里 initKoin() 的公开签名上，androidApp 调它时需要能看到这些类型。
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.vico.compose.m3)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.datastore.preferences.core)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            // Darwin 引擎（走 NSURLSession）。不要用已废弃的 DarwinLegacy。
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotest.assertions.core)
            // MockEngine：测试里绝不打真网络
            implementation(libs.ktor.client.mock)
        }

        // 任务名是 testAndroidHostTest。这个 source set 只在
        // android { withHostTestBuilder {} } 开启后才存在。
        getByName("androidHostTest").dependencies {
            // JDBC driver 让 schema、CHECK 约束、seed 幂等性能在真实 SQLite 上验证，
            // 而不是只验证"能编译"
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

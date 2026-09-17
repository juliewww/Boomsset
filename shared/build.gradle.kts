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
            // Schema changes need a migration generated; keep verifyMigrations on
            // so we don't change the table structure and forget to write a .sqm
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    // AGP 9: KMP modules use com.android.kotlin.multiplatform.library, configured inside kotlin{} —
    // the top-level android{} block no longer exists. Note this plugin only supports a single variant, no buildType/flavor.
    // Note it's android {}, not androidLibrary{} — the latter was deprecated in AGP 9.1.
    // And this android{} is nested inside kotlin{}, not the top-level one (which no longer exists for KMP modules).
    android {
        namespace = "com.boomsset.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // Must be explicitly enabled. The new KMP Android plugin does **not** create a test target by default —
        // without this line, tests in commonTest have nowhere to run on the JVM, with no error to warn you.
        withHostTestBuilder {}
    }

    // arm64 only. iosX64 has been removed by Kotlin/CMP, so Intel Macs can't run this.
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

            // api, not implementation: Koin's Module / KoinApplication appear in the
            // public signature of initKoin() in di/Modules.kt — androidApp needs to see these types when calling it.
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
            implementation(libs.androidx.biometric)
            // api, not implementation: CurrentActivityHolder's signature has FragmentActivity in it,
            // androidApp's MainActivity needs to see this type to register itself
            api(libs.androidx.fragment.ktx)
            implementation(libs.androidx.core.ktx)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
            // Darwin engine (uses NSURLSession). Do not use the deprecated DarwinLegacy.
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotest.assertions.core)
            // MockEngine: tests must never hit the real network
            implementation(libs.ktor.client.mock)
        }

        // The task is named testAndroidHostTest. This source set only exists once
        // android { withHostTestBuilder {} } is enabled.
        getByName("androidHostTest").dependencies {
            // JDBC driver lets schema, CHECK constraints, and seed idempotency be verified against
            // real SQLite, rather than only proving "it compiles"
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

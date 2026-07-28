// 根工程不应用任何插件，只声明给子模块用。
// 每个 plugin 都要 apply false —— 否则 AGP 9 会抱怨在非 Android 工程上应用了 Android 插件。
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
}

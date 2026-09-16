// The root project applies no plugins itself — it only declares them for subprojects to use.
// Every plugin needs apply false — otherwise AGP 9 complains about applying an Android plugin to a non-Android project.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
}

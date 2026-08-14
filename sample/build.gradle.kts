// Every plugin used anywhere in sample/, declared once with `apply false` — standard Gradle
// multi-module practice, and avoids a Kotlin-Gradle-plugin classloader mismatch otherwise
// triggered by asymmetric plugin application across subprojects.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.screenshot) apply false
    alias(libs.plugins.composePreviewToolkit) apply false
    alias(libs.plugins.composePreviewToolkitNavGraph) apply false
}

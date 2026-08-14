// Every plugin used anywhere in sample/, declared once with `apply false`, so every leaf module's
// resolved plugin classpath is identical — avoids a Kotlin-Gradle-plugin classloader mismatch. See
// nav-graph-gradle-plugin's kdoc and HayatoYagi/compose-preview-toolkit#53.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.screenshot) apply false
    alias(libs.plugins.composePreviewToolkit) apply false
    alias(libs.plugins.composePreviewToolkitNavGraph) apply false
}

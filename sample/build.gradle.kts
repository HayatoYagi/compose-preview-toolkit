plugins {
    // No explicit Kotlin Android plugin: AGP 9's built-in Kotlin support handles it.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Declared here (apply false) purely so their versions are resolvable: the
    // compose-preview-toolkit plugin below applies both by bare id (no version) via
    // pluginManager.apply(...). An unversioned imperative apply only resolves if that plugin id
    // was already resolved somewhere in this build's `plugins{}` resolution — this is that
    // somewhere (all requests in one `plugins{}` block resolve together before any of them apply).
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.screenshot) apply false
    // Written exactly as a real consumer would write it. This always targets the version
    // currently under local development (see sample/settings.gradle.kts for why that's
    // permanently ahead of whatever's actually published) — resolves via `mavenLocal()` after
    // running the publish step described in the README. Bump alongside the root
    // gradle.properties `version=` whenever that changes.
    id("io.github.hayatoyagi.compose-preview-toolkit") version "0.1.0"
}

android {
    namespace = "io.github.hayatoyagi.composepreviewtoolkit.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.hayatoyagi.composepreviewtoolkit.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

// Zero extra configuration needed: the plugin's bundled ScreenshotPreview/PreviewSet
// annotations (from the `annotations` module) are used by default.

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.androidx.activity.compose)
}

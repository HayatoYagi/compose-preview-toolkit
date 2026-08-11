plugins {
    // No explicit Kotlin Android plugin: AGP 9's built-in Kotlin support handles it.
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    // See sample/app/build.gradle.kts for why this is a separate plugin id from Phase 1's; this
    // module deliberately does NOT apply the Phase 1 screenshot-testing plugin — that's an
    // optional nice-to-have, not needed to exercise node extraction.
    id("io.github.hayatoyagi.compose-preview-toolkit.navgraph") version "0.1.0"
}

android {
    namespace = "io.github.hayatoyagi.composepreviewtoolkit.sample.featurea"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.navigation3.runtime)
}

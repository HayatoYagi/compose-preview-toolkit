plugins {
    // No explicit Kotlin Android plugin: AGP 9's built-in Kotlin support handles it.
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    // Declared here (apply false) purely so their versions are resolvable in *this* project — see
    // sample/app/build.gradle.kts's comment on the same two lines for why: the
    // compose-preview-toolkit plugin below applies both by bare id (no version) via
    // pluginManager.apply(...), which only resolves if requested with a version somewhere in this
    // project's own plugin resolution.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.screenshot) apply false
    // Written exactly as a real consumer would write it — see sample/app/build.gradle.kts's
    // comment on the same id. Applied here too (not just on sample/app) so the gallery site's
    // FeatureARoute node gets a real screenshot thumbnail via the naming heuristic, same as
    // HomeRoute already does — see README.md's Sample App section.
    id("io.github.hayatoyagi.compose-preview-toolkit") version "0.1.0"
    // See sample/app/build.gradle.kts for why this is a separate plugin id from Phase 1's.
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

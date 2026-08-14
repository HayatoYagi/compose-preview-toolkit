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
    // A version-catalog-based consumer would declare this exactly this way — see
    // sample/app/build.gradle.kts's comment on the same id. Applied here too (not just on
    // sample/app) so the gallery site's FeatureBRoute node gets a real screenshot thumbnail via
    // the naming heuristic, same as HomeRoute already does — see README.md's Sample App section.
    alias(libs.plugins.composePreviewToolkit)
    // Not applied here: :app discovers and scans this module via its own project dependency
    // without needing the plugin applied on every dependency — see nav-graph-gradle-plugin's kdoc.
}

android {
    namespace = "io.github.hayatoyagi.composepreviewtoolkit.sample.featureb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Needed for the "restart" affordance in FeatureBNavEntries.kt: feature-b calls
    // navigateTo(FeatureARoute) directly (pattern (ii) — no callback threaded up to app level for
    // this particular edge, unlike featureANavEntries's onProceedClick), which requires FeatureARoute
    // itself on the compile classpath.
    implementation(project(":feature-a"))

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.navigation3.runtime)
}

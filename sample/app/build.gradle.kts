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
    id("io.github.hayatoyagi.compose-preview-toolkit") version "0.2.0"
    // Separate plugin id from the one above (see nav-graph-gradle-plugin's kdoc for why) — applied
    // here too since `app` wires FeatureARoute/FeatureBRoute into its own NavDisplay and therefore
    // has its own `entry<HomeRoute> {}` registration worth including in a node index.
    id("io.github.hayatoyagi.compose-preview-toolkit.navgraph") version "0.2.0"
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

// annotationFqn is left at its default (@ScreenshotPreview from the `annotations` module), but
// extraPreviewAnnotationFqn is pointed at this app's own light/dark annotation instead of the
// plugin's plain single-preview default — demonstrates the "bring your own multi-preview
// annotation" extension point.
composePreviewToolkit {
    extraPreviewAnnotationFqn.set("io.github.hayatoyagi.composepreviewtoolkit.sample.LightDarkPreview")
}

// `app` is the "aggregator" module: it's the one that actually wires FeatureARoute/FeatureBRoute
// into its own NavDisplay (see AppNavHost.kt), so it's the natural place to aggregate node +
// screenshot indexes across the whole app's graph and generate the gallery site.
composePreviewToolkitNavGraph {
    graphModules.set(setOf(":app", ":feature-a", ":feature-b"))
}

dependencies {
    implementation(project(":feature-a"))
    implementation(project(":feature-b"))

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
}

plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    alias(libs.plugins.gradlePluginPublish)
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    website.set("https://github.com/HayatoYagi/compose-preview-toolkit")
    vcsUrl.set("https://github.com/HayatoYagi/compose-preview-toolkit")
    plugins {
        create("composePreviewToolkitNavGraph") {
            id = "io.github.hayatoyagi.compose-preview-toolkit.navgraph"
            implementationClass = "io.github.hayatoyagi.composepreviewtoolkit.gradle.ComposePreviewToolkitNavGraphPlugin"
            displayName = "Compose Preview Toolkit - Nav Graph"
            description =
                "Statically extracts a Compose Navigation3 nav graph (nodes from entry<X> {} " +
                    "registrations) via Kotlin PSI analysis, for compose-preview-toolkit."
            tags.set(listOf("android", "compose", "navigation", "navigation3", "preview"))
        }
    }
}

dependencies {
    // A plain project dependency, not a version-embedded runtime-fetched artifact like Phase 1's
    // ksp-processor: nav-graph-psi-analyzer's KtFile/NavNodeScanner classes are used directly
    // inside this plugin's own task action code (GenerateDebugNavGraph), not applied to a
    // *consumer's* buildscript classpath by coordinate string at apply-time the way the KSP
    // processor is. So there's no need for this module's own generatePluginVersion-style
    // PLUGIN_VERSION embedding trick — implementation(project(...)) is enough.
    //
    // KNOWN LIMITATION: this transitively pulls in kotlin-compiler-embeddable (nav-graph-psi-
    // analyzer exposes it via api(...), since its public API returns KtFile/KtCallExpression
    // types from it), which becomes part of the classpath of any consumer applying this plugin.
    // Building compose-preview-toolkit-sample's :app/:feature-a/:feature-b (which apply this
    // plugin alongside AGP's built-in Kotlin support) confirms Kotlin's Gradle plugin emits a
    // "'kotlin-compiler-embeddable' Artifact Present in Build Classpath ... along Kotlin Gradle
    // plugin" warning (https://kotl.in/gradle/internal-compiler-symbols) at configuration time.
    // Real compilation (compileDebugKotlin) still succeeds — no miscompilation observed — so this
    // is accepted as a warning-only known limitation rather than attempting classloader
    // isolation/shading, which is a materially larger task. See the matching note on
    // kotlin-compiler-embeddable in the root gradle/libs.versions.toml.
    implementation(project(":nav-graph-psi-analyzer"))

    // Compile-time dependency purely to reuse ScreenshotPreviewProcessorProvider.DEFAULT_INDEX_FILE_NAME
    // (the "ComposePreviewToolkitScreenshotIndex" base name) when globbing a graph module's KSP
    // output resources for site generation — the same reasoning gradle-plugin's own build.gradle.kts
    // gives for its own dependency on this module, applied here cross-plugin instead of
    // cross-processor.
    implementation(project(":ksp-processor"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    alias(libs.plugins.gradlePluginPublish)
}

kotlin {
    jvmToolchain(17)
}

// Embeds this module's own release version into a generated Kotlin constant, so the plugin can
// resolve the correct published `compose-preview-toolkit-nav-graph-psi-analyzer` coordinate at
// apply-time without hand-syncing version numbers in two places — same mechanism as
// gradle-plugin's own generatePluginVersion, needed here for the same reason: see the
// nav-graph-psi-analyzer dependency comment below.
val generatedVersionDir = layout.buildDirectory.dir("generated/pluginVersion/kotlin")

val generatePluginVersion by tasks.registering {
    val outputDir = generatedVersionDir
    val versionValue = project.version.toString()
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile
            .resolve("io/github/hayatoyagi/composepreviewtoolkit/gradle/PluginVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.hayatoyagi.composepreviewtoolkit.gradle

            internal const val PLUGIN_VERSION = "$versionValue"

            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatePluginVersion)
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
    // compileOnly, not implementation: KotlinPsiParser/NavNodeScanner/NavEdgeScanner (and their
    // kotlin-compiler-embeddable dependency) are only ever used inside NavGraphScanWorkAction,
    // which runs in a Gradle Worker with an isolated classloader.
    compileOnly(project(":nav-graph-psi-analyzer"))

    implementation(project(":nav-graph-model"))

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

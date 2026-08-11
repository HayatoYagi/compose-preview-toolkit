plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    alias(libs.plugins.gradlePluginPublish)
}

kotlin {
    jvmToolchain(17)
}

// Embeds this module's own release version into a generated Kotlin constant, so the plugin
// can reference the correct published `compose-preview-toolkit-ksp-processor` artifact
// coordinate at apply-time without hand-syncing version numbers in two places.
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
        create("composePreviewToolkit") {
            id = "io.github.hayatoyagi.compose-preview-toolkit"
            implementationClass = "io.github.hayatoyagi.composepreviewtoolkit.gradle.ComposePreviewToolkitPlugin"
            displayName = "Compose Preview Toolkit"
            description =
                "Generates AGP Compose Preview Screenshot Testing wrappers from a single " +
                    "marker annotation on your @Preview functions, instead of hand-duplicating " +
                    "them in the screenshotTest source set."
            tags.set(listOf("android", "compose", "screenshot-testing", "ksp", "preview"))
        }
    }
}

dependencies {
    // Compile-time dependency on the processor lets us reference its KSP option-key constants
    // directly instead of duplicating the literal strings across modules.
    implementation(project(":ksp-processor"))

    // AGP, KSP and the AGP screenshot-testing plugin are intentionally NOT compile dependencies
    // here: this plugin applies them by id via pluginManager.apply(...), which resolves through
    // the consuming build's own pluginManagement { repositories { ... } }. Compiling against
    // them would pin consumers to one AGP version; see ComposePreviewToolkitPlugin's reflection
    // helpers for the same reasoning applied to source-set wiring.

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

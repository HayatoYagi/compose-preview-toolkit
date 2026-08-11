package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.ksp.ScreenshotPreviewProcessorProvider
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Generates AGP-official Compose Preview Screenshot Testing wrappers (`@PreviewTest`
 * functions in the `debugScreenshotTest` source set) from a single marker annotation on your
 * `@Preview` functions in `src/main`, so you don't have to hand-duplicate them.
 *
 * See the README for setup and the `composePreviewToolkit { ... }` extension for
 * configuration.
 */
class ComposePreviewToolkitPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "composePreviewToolkit",
            ComposePreviewToolkitExtension::class.java,
        ).apply {
            annotationFqn.convention(ScreenshotPreviewProcessorProvider.DEFAULT_ANNOTATION_FQN)
            extraPreviewAnnotationFqn.convention(DEFAULT_EXTRA_PREVIEW_ANNOTATION_FQN)
            composeVersion.convention(DEFAULT_COMPOSE_VERSION)
            screenshotValidationApiVersion.convention(DEFAULT_SCREENSHOT_VALIDATION_API_VERSION)
        }

        target.enableScreenshotTestSourceSet()

        target.pluginManager.apply("com.android.compose.screenshot")
        target.pluginManager.apply("com.google.devtools.ksp")

        // Must happen right here, eagerly — not in afterEvaluate: the KSP Gradle plugin's own
        // afterEvaluate hook (registered by pluginManager.apply above, so it runs before any
        // afterEvaluate block *we* register) decides whether kspDebugKotlin should be skipped
        // based on whether the kspDebug configuration already has the processor dependency at
        // that point. The annotationFqn arg is still fully lazy via the Provider overload, so a
        // consumer's later `composePreviewToolkit { annotationFqn.set(...) }` still take effect.
        target.dependencies.add("kspDebug", "io.github.hayatoyagi:compose-preview-toolkit-ksp-processor:$PLUGIN_VERSION")
        target.kspArg(ScreenshotPreviewProcessorProvider.MODULE_NAME_OPTION, target.path)
        target.kspArg(ScreenshotPreviewProcessorProvider.ANNOTATION_FQN_OPTION, extension.annotationFqn)

        val generatedScreenshotPreviewDir =
            target.layout.buildDirectory.dir("generated/composePreviewToolkit/screenshotPreviewTests/debug/kotlin")
        val screenshotPreviewReferenceDir =
            target.layout.projectDirectory.dir("src/screenshotTestDebug/reference")

        val generateScreenshotPreviewTests =
            target.tasks.register(
                "generateDebugScreenshotPreviewTests",
                GenerateScreenshotPreviewTests::class.java,
            ) {
                it.previewIndexFiles.from(
                    target.layout.buildDirectory.dir("generated/ksp/debug/resources")
                        .map { dir ->
                            dir.asFileTree.matching { filter ->
                                filter.include("**/${ScreenshotPreviewProcessorProvider.DEFAULT_INDEX_FILE_NAME}*.txt")
                            }
                        },
                )
                it.outputDirectory.set(generatedScreenshotPreviewDir)
            }

        val cleanupScreenshotPreviewReferences =
            target.tasks.register(
                "cleanupDebugScreenshotPreviewReferences",
                CleanupScreenshotPreviewReferences::class.java,
            ) {
                it.dependsOn(generateScreenshotPreviewTests)
                it.previewIndexFiles.from(
                    target.layout.buildDirectory.dir("generated/ksp/debug/resources")
                        .map { dir ->
                            dir.asFileTree.matching { filter ->
                                filter.include("**/${ScreenshotPreviewProcessorProvider.DEFAULT_INDEX_FILE_NAME}*.txt")
                            }
                        },
                )
                it.referenceDirectory.set(screenshotPreviewReferenceDir)
            }

        // Deferred to afterEvaluate: the consumer's `composePreviewToolkit { ... }` block (if
        // any) runs later in their build script than this plugin's apply(), and AGP's
        // debugScreenshotTest source set doesn't exist until com.android.compose.screenshot has
        // finished configuring the variant.
        target.afterEvaluate {
            generateScreenshotPreviewTests.configure {
                it.extraPreviewAnnotationFqn.set(extension.extraPreviewAnnotationFqn)
            }

            target.dependencies.add(
                "screenshotTestImplementation",
                "com.android.tools.screenshot:screenshot-validation-api:${extension.screenshotValidationApiVersion.get()}",
            )
            target.dependencies.add(
                "implementation",
                "androidx.compose.ui:ui-tooling-preview:${extension.composeVersion.get()}",
            )
            target.dependencies.add(
                "debugImplementation",
                "androidx.compose.ui:ui-tooling:${extension.composeVersion.get()}",
            )
            if (extension.annotationFqn.get() == ScreenshotPreviewProcessorProvider.DEFAULT_ANNOTATION_FQN ||
                extension.extraPreviewAnnotationFqn.get() == DEFAULT_EXTRA_PREVIEW_ANNOTATION_FQN
            ) {
                target.dependencies.add(
                    "implementation",
                    "io.github.hayatoyagi:compose-preview-toolkit-annotations:$PLUGIN_VERSION",
                )
            }

            target.registerGeneratedScreenshotPreviewSourceSet(generatedScreenshotPreviewDir.get().asFile)

            target.tasks.findByName("kspDebugKotlin")?.let { kspDebugKotlin ->
                generateScreenshotPreviewTests.configure { it.dependsOn(kspDebugKotlin) }
            }
            listOf(
                "compileDebugScreenshotTestKotlin",
                "compileDebugScreenshotTestJavaWithJavac",
                "kspDebugScreenshotTestKotlin",
            ).forEach { taskName ->
                target.tasks.findByName(taskName)?.dependsOn(generateScreenshotPreviewTests)
            }
            listOf(
                "updateDebugScreenshotTest",
                "validateDebugScreenshotTest",
            ).forEach { taskName ->
                target.tasks.findByName(taskName)?.apply {
                    dependsOn(
                        if (taskName == "updateDebugScreenshotTest") {
                            cleanupScreenshotPreviewReferences
                        } else {
                            generateScreenshotPreviewTests
                        },
                    )
                    onlyIf(HasGeneratedScreenshotPreviewTests(generatedScreenshotPreviewDir.get().asFile))
                }
            }
        }
    }
}

/**
 * Enables AGP's experimental `screenshotTest` source set. Uses reflection rather than a typed
 * `com.android.build.api.dsl.CommonExtension` reference so this plugin doesn't have to compile
 * against — and therefore pin consumers to — one specific AGP version.
 */
private fun Project.enableScreenshotTestSourceSet() {
    @Suppress("UNCHECKED_CAST")
    val experimentalProperties = extensions.getByName("android")
        .javaClass
        .getMethod("getExperimentalProperties")
        .invoke(extensions.getByName("android")) as MutableMap<String, Any>
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

/**
 * Adds the KSP-generated wrapper source directory to both AGP's `screenshotTest` source set and
 * the Kotlin plugin's `debugScreenshotTest` source set. As of AGP 9.2 there is no dedicated
 * Android Components API for contributing generated Kotlin sources to the screenshot-test
 * source set, so this reaches in via reflection — replace with a typed API once AGP exposes one.
 */
@Suppress("DEPRECATION")
private fun Project.registerGeneratedScreenshotPreviewSourceSet(generatedSourceDir: java.io.File) {
    extensions.getByName("kotlin")
        .sourceSet("debugScreenshotTest")
        .sourceDirectorySet("kotlin")
        .srcDir(generatedSourceDir)

    extensions.getByName("android")
        .sourceSet("screenshotTest")
        .sourceDirectorySet("java")
        .srcDir(generatedSourceDir)
}

private fun Any.sourceSet(name: String): Any =
    javaClass.getMethod("getSourceSets")
        .invoke(this)
        .let { sourceSets -> sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, name) }

private fun Any.sourceDirectorySet(name: String): Any {
    val getterName = "get${name.replaceFirstChar { it.uppercaseChar() }}"
    return javaClass.getMethod(getterName).invoke(this)
}

private fun Any.srcDir(path: Any) {
    javaClass.getMethod("srcDir", Any::class.java).invoke(this, path)
}

/** Sets a KSP processor option by reflection, avoiding a compile dependency on the KSP Gradle plugin. */
private fun Project.kspArg(
    key: String,
    value: String,
) {
    val kspExtension = extensions.getByName("ksp")
    kspExtension.javaClass
        .getMethod("arg", String::class.java, String::class.java)
        .invoke(kspExtension, key, value)
}

/**
 * Same as [kspArg], but takes a lazy [Provider] — used for options that may be reconfigured by
 * the consumer's `composePreviewToolkit { ... }` block, which runs after this plugin's apply()
 * (and therefore after the eager call site that registers this arg).
 */
private fun Project.kspArg(
    key: String,
    value: org.gradle.api.provider.Provider<String>,
) {
    val kspExtension = extensions.getByName("ksp")
    kspExtension.javaClass
        .getMethod("arg", String::class.java, org.gradle.api.provider.Provider::class.java)
        .invoke(kspExtension, key, value)
}

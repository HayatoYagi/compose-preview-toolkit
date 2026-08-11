package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Deletes reference PNGs left behind by renamed/removed `@ScreenshotPreview` functions, so
 * `updateDebugScreenshotTest` doesn't accumulate baseline images nothing generates anymore.
 */
@DisableCachingByDefault(
    because = "Deletes files under referenceDirectory as a side effect without declaring them " +
        "as task outputs, so a cache hit would incorrectly skip real cleanup work.",
)
abstract class CleanupScreenshotPreviewReferences : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val previewIndexFiles: ConfigurableFileCollection

    @get:Internal
    abstract val referenceDirectory: DirectoryProperty

    @TaskAction
    fun cleanup() {
        val referenceRoot = referenceDirectory.get().asFile
        if (!referenceRoot.exists()) return

        val activeWrappersByPackagePath = previewIndexFiles.files
            .filter { it.isFile }
            .flatMap { file ->
                file.readLines()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { PreviewEntry.parse(it) }
                    .toList()
            }
            .groupBy(
                keySelector = { it.packageName.replace('.', File.separatorChar) },
                valueTransform = { it.wrapperName },
            )
            .mapValues { (_, wrappers) -> wrappers.toSet() }

        referenceRoot
            .walkTopDown()
            .filter { it.isDirectory && it.name == GENERATED_REFERENCE_DIRECTORY }
            .forEach { generatedReferenceDirectory ->
                val packagePath = generatedReferenceDirectory.parentFile
                    .relativeTo(referenceRoot)
                    .path
                val activeWrappers = activeWrappersByPackagePath[packagePath].orEmpty()

                generatedReferenceDirectory
                    .listFiles { file -> file.isFile && file.extension == "png" }
                    .orEmpty()
                    .filterNot { referenceFile ->
                        activeWrappers.any { wrapperName -> referenceFile.name.startsWith("${wrapperName}_") }
                    }
                    .forEach { it.delete() }

                generatedReferenceDirectory.deleteIfEmpty()
            }
    }

    private fun File.deleteIfEmpty() {
        if (listFiles()?.isEmpty() == true) delete()
    }

    private data class PreviewEntry(
        val packageName: String,
        val wrapperName: String,
    ) {
        companion object {
            fun parse(line: String): PreviewEntry {
                val parts = line.split('\t')
                require(parts.size == 3) { "Invalid screenshot preview index line: $line" }
                return PreviewEntry(
                    packageName = parts[0],
                    wrapperName = parts[1],
                )
            }
        }
    }

    companion object {
        // Matches the Kotlin file-class name AGP derives from the generated
        // GeneratedScreenshotPreviews.kt file in GenerateScreenshotPreviewTests.
        private const val GENERATED_REFERENCE_DIRECTORY = "GeneratedScreenshotPreviewsKt"
    }
}

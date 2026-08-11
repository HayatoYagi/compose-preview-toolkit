package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateScreenshotPreviewTests : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val previewIndexFiles: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val extraPreviewAnnotationFqn: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()

        val extraAnnotationFqn = extraPreviewAnnotationFqn.orNull
        val extraAnnotationSimpleName = extraAnnotationFqn?.substringAfterLast('.')

        val entries = previewIndexFiles.files
            .filter { it.isFile }
            .flatMap { file ->
                file.readLines()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { PreviewEntry.parse(it) }
                    .toList()
            }
            .distinctBy { "${it.packageName}.${it.wrapperName}" }
            .sortedWith(compareBy<PreviewEntry> { it.packageName }.thenBy { it.wrapperName })

        entries.groupBy { it.packageName }.forEach { (packageName, packageEntries) ->
            val packagePath = packageName.replace('.', '/')
            val outputFile = outputRoot.resolve("$packagePath/GeneratedScreenshotPreviews.kt")
            outputFile.parentFile.mkdirs()
            outputFile.writeText(buildFile(packageName, packageEntries, extraAnnotationFqn, extraAnnotationSimpleName))
        }
    }

    private fun buildFile(
        packageName: String,
        entries: List<PreviewEntry>,
        extraAnnotationFqn: String?,
        extraAnnotationSimpleName: String?,
    ): String =
        buildString {
            appendLine("package $packageName")
            appendLine()
            if (extraAnnotationFqn != null) {
                appendLine("import $extraAnnotationFqn")
            }
            appendLine("import androidx.compose.runtime.Composable")
            appendLine("import com.android.tools.screenshot.PreviewTest")
            appendLine()

            entries.forEach { entry ->
                appendLine("@PreviewTest")
                if (extraAnnotationSimpleName != null) {
                    appendLine("@$extraAnnotationSimpleName")
                }
                appendLine("@Composable")
                appendLine("fun ${entry.wrapperName}() {")
                appendLine("    ${entry.callExpression}()")
                appendLine("}")
                appendLine()
            }
        }
}

private data class PreviewEntry(
    val packageName: String,
    val wrapperName: String,
    val callExpression: String,
) {
    companion object {
        fun parse(line: String): PreviewEntry {
            val parts = line.split('\t')
            require(parts.size == 3) { "Invalid screenshot preview index line: $line" }
            return PreviewEntry(
                packageName = parts[0],
                wrapperName = parts[1],
                callExpression = parts[2],
            )
        }
    }
}

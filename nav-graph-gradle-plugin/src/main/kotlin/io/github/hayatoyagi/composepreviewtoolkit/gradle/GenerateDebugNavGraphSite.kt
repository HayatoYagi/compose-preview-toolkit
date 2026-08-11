package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.parseNavNodeIndex
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Base64

/**
 * Aggregates node indexes ([nodeIndexFiles], written by each `graphModules` project's own
 * `generateDebugNavGraph` task) and Phase 1 screenshot indexes/baselines
 * ([screenshotIndexFiles]/[screenshotReferenceImages]) across every project configured via
 * `composePreviewToolkitNavGraph { graphModules.set(...) }`, then renders a single self-contained
 * thumbnail-gallery `index.html` ([buildGallerySiteHtml]). Deliberately node+screenshot only —
 * edge/graph rendering is not part of this task; see the Phase 2 design doc for the full nav
 * graph design.
 *
 * All three input file collections may legitimately be empty for a given graph module (a module
 * that hasn't applied the navgraph/Phase-1 plugins, or hasn't run its own generation task yet) —
 * that's not an error, it just means that module contributes nothing. `ComposePreviewToolkitNavGraphPlugin`
 * is responsible for making sure this task's real Gradle task dependencies (on each graph module's
 * `generateDebugNavGraph` / `kspDebugKotlin`) are wired so those upstream outputs actually exist by
 * the time this task runs, rather than this task racing them.
 *
 * Deterministic given its declared inputs, so cacheable like `GenerateDebugNavGraph`/
 * `GenerateScreenshotPreviewTests`.
 */
@CacheableTask
abstract class GenerateDebugNavGraphSite : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeIndexFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screenshotIndexFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screenshotReferenceImages: ConfigurableFileCollection

    @get:Input
    abstract val routeNameSuffixesToStrip: SetProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val nodes: List<NavNode> =
            nodeIndexFiles.files
                .filter { it.isFile }
                .flatMap { file -> file.reader().use { reader -> parseNavNodeIndex(reader) } }

        val screenshotEntries =
            screenshotIndexFiles.files
                .filter { it.isFile }
                .flatMap { file -> parseScreenshotIndex(file.readText()) }

        val referenceImages = screenshotReferenceImages.files.filter { it.isFile }

        val entries = buildGalleryEntries(
            nodes = nodes,
            screenshotEntries = screenshotEntries,
            referenceImages = referenceImages,
            suffixesToStrip = routeNameSuffixesToStrip.get(),
        )

        val cards = entries.map { entry ->
            GalleryCard(
                qualifiedName = entry.node.qualifiedName,
                simpleName = entry.node.simpleName,
                thumbnailDataUri = entry.thumbnail?.let { file ->
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(file.readBytes())
                },
            )
        }

        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        outputDir.resolve("index.html").writeText(buildGallerySiteHtml(cards))
    }
}

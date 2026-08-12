package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.KotlinPsiParser
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdge
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.parseNavEdgeIndex
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.parseNavNodeIndex
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
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
 * Aggregates node indexes ([nodeIndexFiles]) and screenshot indexes/baselines
 * ([screenshotIndexFiles]/[screenshotReferenceImages]) — written by each `graphModules` project's
 * own `generateDebugNavGraph` task / the screenshot-testing plugin's KSP processor — across every
 * project configured via `composePreviewToolkitNavGraph { graphModules.set(...) }`, then renders a
 * single self-contained `index.html` with both a Mermaid.js nav graph diagram and a thumbnail
 * gallery ([buildGallerySiteHtml]).
 *
 * ## Why edges are (re-)scanned here rather than purely aggregated from [edgeIndexFiles]
 *
 * [edgeIndexFiles] globs each graph module's own `generateDebugNavGraph`-written edge index — but
 * that task only ever sees *its own* module's sources (see `GenerateDebugNavGraph`'s kdoc), and in
 * practice this makes it blind to almost every real edge: [NavEdgeScanner]'s call-graph
 * reachability search routinely needs to resolve a route's `entry<X> {}` registration (which lives
 * in the module that *owns* that route) together with a `navigateTo(...)` call site that reaches
 * it (which, for both of the sample's real wiring shapes, lives in a *different* module — either
 * the app-level `NavHost` for the callback-threaded pattern, or a sibling feature module for the
 * direct-call pattern). A single-module scan can supply neither side of that unless both happen to
 * live in the same module, which never occurs in `compose-preview-toolkit-sample`. Confirmed
 * empirically while implementing this task: wiring only [edgeIndexFiles] produced zero edges for
 * all three of the sample's real, verified-working edges. Edge detection was always meant to run
 * over the whole project's sources at once, not module-by-module.
 *
 * So this task additionally re-parses the raw `.kt` sources of every `graphModules` project
 * ([edgeSourceFiles]) and runs one project-wide [NavEdgeScanner.scan] call over all of them
 * together — the same shape [NavEdgeScannerTest]'s own multi-file fixtures already exercise, and
 * the only scan scope that can see both ends of a cross-module edge. [edgeIndexFiles] is still
 * aggregated and unioned in (harmless — a module-local edge is a strict special case of what the
 * project-wide scan already finds, so this never produces duplicates once deduplicated, and keeps
 * per-module edge indexes meaningful in isolation for any future single-module consumer).
 *
 * All input file collections may legitimately be empty for a given graph module (a module that
 * hasn't applied the navgraph/Phase-1 plugins, or hasn't run its own generation task yet) — that's
 * not an error, it just means that module contributes nothing. `ComposePreviewToolkitNavGraphPlugin`
 * is responsible for making sure this task's real Gradle task dependencies (on each graph module's
 * `generateDebugNavGraph` / `kspDebugKotlin`) are wired so those upstream outputs actually exist by
 * the time this task runs, rather than this task racing them; [edgeSourceFiles] needs no such
 * dependency since it's plain, always-present source, not a generated artifact.
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
    abstract val edgeIndexFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val edgeSourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val entryFunctionNames: SetProperty<String>

    @get:Input
    abstract val navigateCallNames: SetProperty<String>

    @get:Input
    abstract val callGraphResolutionDepth: Property<Int>

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

        val indexedEdges: List<NavEdge> =
            edgeIndexFiles.files
                .filter { it.isFile }
                .flatMap { file -> file.reader().use { reader -> parseNavEdgeIndex(reader) } }

        val scannedEdgeResult = KotlinPsiParser().use { parser ->
            val ktFiles = edgeSourceFiles.files
                .filter { it.isFile }
                .map { file -> parser.parse(file) }
            NavEdgeScanner(
                entryFunctionNames = entryFunctionNames.get(),
                navigateCallNames = navigateCallNames.get(),
                callGraphResolutionDepth = callGraphResolutionDepth.get(),
            ).scan(ktFiles)
        }
        scannedEdgeResult.warnings.forEach { warning -> logger.warn("nav-graph edge scan: $warning") }

        val edges = (indexedEdges + scannedEdgeResult.edges).distinct()

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

        // entries.map { it.node } is already deduplicated by qualifiedName and sorted (see
        // buildGalleryEntries), which is exactly the deterministic node ordering buildMermaidGraph
        // needs so its positional node ids (n0, n1, ...) don't shuffle between otherwise-identical
        // runs. Both the graph and the gallery are therefore driven off the same node list.
        val mermaidGraph = buildMermaidGraph(nodes = entries.map { it.node }, edges = edges)

        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        outputDir.resolve("index.html").writeText(buildGallerySiteHtml(cards, mermaidGraph))
    }
}

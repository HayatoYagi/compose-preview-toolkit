package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNodeScanner
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import java.util.Base64
import javax.inject.Inject

/**
 * Aggregates screenshot indexes/baselines ([screenshotIndexFiles]/[screenshotReferenceImages]) —
 * written by the screenshot-testing plugin's KSP processor — across every project
 * `ComposePreviewToolkitNavGraphPlugin.discoverGraphModules` resolves for this aggregator module,
 * combines that with nodes/edges derived from its own project-wide PSI scan (see below), then
 * renders a single self-contained `index.html`: a Mermaid.js nav graph diagram whose nodes each
 * embed their own representative screenshot thumbnail, clickable to reveal every screenshot
 * matched to that route ([buildGallerySiteHtml]).
 *
 * ## Why this scans every graph module's raw sources together, project-wide
 *
 * A scan scoped to a single module's own sources breaks in two related ways for a multi-module
 * app:
 *
 * - **Edges**: [NavEdgeScanner]'s call-graph reachability search routinely needs to resolve a
 *   route's `entry<X> {}` registration (which lives in the module that *owns* that route) together
 *   with a `navigateTo(...)` call site that reaches it (which, for both of the sample's real
 *   wiring shapes, lives in a *different* module — either the app-level `NavHost` for the
 *   callback-threaded pattern, or a sibling feature module for the direct-call pattern). A
 *   single-module scan can supply neither side of that unless both happen to live in the same
 *   module, which never occurs in `compose-preview-toolkit-sample` — a module-local-only edge scan
 *   finds zero of its three real edges.
 * - **Nodes**: a module-local scan resolving a route's `qualifiedName`/`packageName` can only see
 *   declarations inside its own sources. When an `entry<X> {}` registration's route is actually
 *   *declared* in a different module (e.g. an `api` module) than the one that *registers* it (e.g.
 *   a sibling `impl` module) — a routine split in a real multi-module app — that module-local scan
 *   can't resolve the real declaration at all.
 *
 * So this task re-parses the raw `.kt` sources of every discovered graph module project
 * ([edgeSourceFiles] — the name undersells it, it's the basis for node detection too) and runs one
 * project-wide [NavNodeScanner.scan]/[NavEdgeScanner.scan] pair over all of them together (inside
 * [NavGraphScanWorkAction], see its kdoc for why) — the same shape [NavEdgeScannerTest]'s own
 * multi-file fixtures already exercise, and the only scan scope that can see both a cross-module
 * edge and a cross-module node's real declaration. An unresolvable route declaration is a hard
 * failure (see `EntryRegistrations.kt`'s `resolveDeclaration`/`toNavNode`).
 *
 * All input file collections may legitimately be empty for a given graph module (e.g. one that
 * hasn't applied the screenshot-testing plugin, so has no screenshot index or baselines) — that's
 * not an error, it just means that module contributes nothing on that axis.
 * `ComposePreviewToolkitNavGraphPlugin` is responsible for making sure this
 * task's real Gradle task dependency on each graph module's `kspDebugKotlin` (for
 * [screenshotIndexFiles]) is wired so those upstream outputs actually exist by the time this task
 * runs; [edgeSourceFiles] needs no such dependency since it's plain, always-present source, not a
 * generated artifact.
 *
 * Deterministic given its declared inputs, so cacheable like `GenerateScreenshotPreviewTests`.
 */
@CacheableTask
abstract class GenerateDebugNavGraphSite @Inject constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val edgeSourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val entryFunctionNames: SetProperty<String>

    @get:Input
    abstract val navigateCallNames: SetProperty<String>

    @get:Input
    abstract val callGraphResolutionDepth: Property<Int>

    /**
     * Fallback base directory for a node's `filePath` when the git repository root can't be
     * determined (see `EntryRegistrations.kt`'s `locateCallSite`) — normally the aggregator
     * project's own directory. Deliberately `@Internal`, not `@Input`/`@InputDirectory`: it's an
     * absolute, machine-specific path, and baking that into this `@CacheableTask`'s cache key
     * would make it needlessly cache-miss across machines/checkouts for a fallback that's never
     * even consulted in the common case where git is available.
     */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

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

    /**
     * Isolated classpath [NavGraphScanWorkAction] runs on — this task's own compile classpath
     * deliberately doesn't include `KotlinPsiParser`/`NavNodeScanner`/`NavEdgeScanner` at runtime
     * (see `ComposePreviewToolkitNavGraphPlugin`'s kdoc), so this has to be supplied explicitly.
     */
    @get:Classpath
    abstract val navGraphScanWorkerClasspath: ConfigurableFileCollection

    @TaskAction
    fun generate() {
        val scanResultFile = temporaryDir.resolve("navGraphScanResult.bin")
        val workQueue = workerExecutor.classLoaderIsolation { spec ->
            spec.classpath.from(navGraphScanWorkerClasspath)
        }
        workQueue.submit(NavGraphScanWorkAction::class.java) { params ->
            params.sourceFiles.from(edgeSourceFiles)
            params.entryFunctionNames.set(entryFunctionNames)
            params.navigateCallNames.set(navigateCallNames)
            params.callGraphResolutionDepth.set(callGraphResolutionDepth)
            params.fallbackBaseDirectory.set(projectDirectory)
            params.outputFile.set(scanResultFile)
        }
        workQueue.await()

        val (nodes, edges, warnings) = readNavGraphScanResult(scanResultFile)
        warnings.forEach { warning -> logger.warn("nav-graph edge scan: $warning") }

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

        // Both auto-populated by GitHub Actions, both absent for a local ./gradlew run — read here
        // (the task's own I/O boundary) rather than in NavGraphSite.kt, matching this codebase's
        // existing split of "pure/testable HTML generation" (NavGraphSite.kt) from "env/file reads"
        // (this task). See buildSourceLink's kdoc for the full gating logic.
        val githubRepository = System.getenv("GITHUB_REPOSITORY")
        val githubSha = System.getenv("GITHUB_SHA")

        // entries is already deduplicated by qualifiedName and sorted (see buildGalleryEntries),
        // which is exactly the deterministic node ordering buildMermaidGraph/buildGallerySiteHtml
        // need so their independently-derived positional node ids (n0, n1, ...) agree with each
        // other and don't shuffle between otherwise-identical runs. Both the graph and the
        // click-to-reveal modal data are therefore driven off this one same-ordered node list.
        val galleryNodes = entries.map { entry ->
            GalleryNode(
                qualifiedName = entry.node.qualifiedName,
                simpleName = entry.node.simpleName,
                packageName = entry.node.packageName,
                thumbnails = entry.thumbnails.map { file ->
                    GalleryThumbnail(
                        label = file.name,
                        dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(file.readBytes()),
                    )
                },
                filePath = entry.node.filePath,
                line = entry.node.line,
                sourceUrl = buildSourceLink(entry.node, githubRepository, githubSha),
            )
        }

        val mermaidGraph = buildMermaidGraph(nodes = galleryNodes, edges = edges)

        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        outputDir.resolve("index.html").writeText(buildGallerySiteHtml(galleryNodes, mermaidGraph))
    }
}

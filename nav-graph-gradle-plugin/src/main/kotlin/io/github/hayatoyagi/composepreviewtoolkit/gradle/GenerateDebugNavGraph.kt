package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.KotlinPsiParser
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNodeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.writeNavEdgeIndex
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.writeNavNodeIndex
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Runs [NavNodeScanner] and [NavEdgeScanner] over a module's own `src/main/kotlin` sources (the
 * same parsed [org.jetbrains.kotlin.psi.KtFile]s are reused for both scans — parsing is the
 * expensive part) and writes the resulting node and edge indexes. This is intentionally
 * module-local: a module only sees `entry<X> {}` registrations and call-graph edges reachable from
 * them written inside its own sources, not those of modules it depends on — combining node/edge
 * data across multiple modules into one graph happens in [GenerateDebugNavGraphSite], which
 * doesn't read this task's output at all (its own project-wide scan is a strict superset) and
 * carries no Gradle task dependency on this one for exactly the reason below.
 *
 * Because a module-local scan can't see declarations outside its own sources, this can throw when
 * a registered route is declared in a different module than the one registering it (a routine
 * `api`/`impl` split in a real multi-module app) — see `EntryRegistrations.kt`'s
 * `resolveDeclaration`/`toNavNode`, which has no best-effort fallback for an unresolvable
 * declaration. That's expected for this task specifically: it's a module-local sanity check, not
 * the source of truth for a multi-module app's graph — run `generateDebugNavGraphSite` on the
 * aggregator module for that, whose own project-wide scan resolves exactly this case correctly.
 *
 * [NavEdgeScanner]'s warnings are surfaced via this task's own [org.gradle.api.logging.Logger]
 * rather than failing the build: a nav edge candidate the scanner couldn't resolve is a
 * "best-effort scan gave up" case, not a build error.
 *
 * Deterministic given its declared inputs (source files, `entryFunctionNames`,
 * `navigateCallNames`, `callGraphResolutionDepth`), so this is safe to cache, matching
 * `GenerateScreenshotPreviewTests`'s reasoning in `gradle-plugin`.
 */
@CacheableTask
abstract class GenerateDebugNavGraph : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val entryFunctionNames: SetProperty<String>

    @get:Input
    abstract val navigateCallNames: SetProperty<String>

    @get:Input
    abstract val callGraphResolutionDepth: Property<Int>

    /**
     * Fallback base directory for [io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode.filePath]
     * when the git repository root can't be determined (see `NavNodeScanner.scan`'s
     * `fallbackBaseDirectory`) — normally this module's own project directory. Deliberately
     * `@Internal`, not `@Input`/`@InputDirectory`: it's an absolute, machine-specific path, and
     * baking that into this [CacheableTask]'s cache key would make the task needlessly
     * cache-miss across machines/checkouts for what is, in the overwhelmingly common case (git
     * available, which real CI/dev checkouts always are), a fallback that's never even
     * consulted — see `EntryRegistrations.kt`'s `locateCallSite`.
     */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:OutputFile
    abstract val edgeOutputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        // One KotlinPsiParser per task execution: parsing is relatively expensive, and its
        // Disposable-rooted resources must be released exactly once, after every file this
        // execution needs is parsed AND scanned (scanning still walks PSI trees rooted in the
        // parser's environment, so it must happen before close() — see KotlinPsiParser's kdoc and
        // NavNodeScannerTest's setUp/tearDown ordering for the same pattern).
        val (nodes, edgeResult) = KotlinPsiParser().use { parser ->
            val ktFiles = sourceFiles.files
                .filter { it.isFile }
                .map { file -> parser.parse(file) }
            val nodes = NavNodeScanner(entryFunctionNames = entryFunctionNames.get())
                .scan(ktFiles, fallbackBaseDirectory = projectDirectory.get().asFile)
            val edgeResult = NavEdgeScanner(
                entryFunctionNames = entryFunctionNames.get(),
                navigateCallNames = navigateCallNames.get(),
                callGraphResolutionDepth = callGraphResolutionDepth.get(),
            ).scan(ktFiles)
            nodes to edgeResult
        }

        edgeResult.warnings.forEach { warning -> logger.warn("nav-graph edge scan: $warning") }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writer().use { writer -> writeNavNodeIndex(nodes, writer) }

        val edgeOutput = edgeOutputFile.get().asFile
        edgeOutput.parentFile.mkdirs()
        edgeOutput.writer().use { writer -> writeNavEdgeIndex(edgeResult.edges, writer) }
    }
}

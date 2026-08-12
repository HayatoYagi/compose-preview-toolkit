package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.KotlinPsiParser
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNodeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.writeNavEdgeIndex
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.writeNavNodeIndex
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
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
 * indexes across multiple modules into one graph happens in [GenerateDebugNavGraphSite].
 *
 * [NavEdgeScanner]'s warnings are surfaced via this task's own [org.gradle.api.logging.Logger]
 * rather than failing the build, per the Phase 2 design doc's resilience philosophy: a nav edge
 * candidate the scanner couldn't resolve is a "best-effort scan gave up" case, not a build error.
 *
 * Deterministic given its declared inputs (source files, `entryFunctionNames`,
 * `navigateCallNames`, `callGraphResolutionDepth`), so this is safe to cache, matching
 * `GenerateScreenshotPreviewTests`'s reasoning in the Phase 1 plugin.
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
            val nodes = NavNodeScanner(entryFunctionNames = entryFunctionNames.get()).scan(ktFiles)
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

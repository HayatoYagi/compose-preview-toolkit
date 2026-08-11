package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.KotlinPsiParser
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNodeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.writeNavNodeIndex
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Runs Step A of the Phase 2 nav-graph design (see `nav-graph-psi-analyzer`'s [NavNodeScanner])
 * over a module's own `src/main/kotlin` sources and writes the resulting node index. This is
 * intentionally module-local: a module only sees `entry<X> {}` registrations written inside its
 * own sources, not those of modules it depends on — cross-module aggregation is a later PR's job.
 *
 * Deterministic given its declared inputs (source files, `entryFunctionNames`), so this is safe to
 * cache, matching `GenerateScreenshotPreviewTests`'s reasoning in the Phase 1 plugin.
 */
@CacheableTask
abstract class GenerateDebugNavGraph : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val entryFunctionNames: SetProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        // One KotlinPsiParser per task execution: parsing is relatively expensive, and its
        // Disposable-rooted resources must be released exactly once, after every file this
        // execution needs is parsed AND scanned (scanning still walks PSI trees rooted in the
        // parser's environment, so it must happen before close() — see KotlinPsiParser's kdoc and
        // NavNodeScannerTest's setUp/tearDown ordering for the same pattern).
        val nodes = KotlinPsiParser().use { parser ->
            val ktFiles = sourceFiles.files
                .filter { it.isFile }
                .map { file -> parser.parse(file) }
            NavNodeScanner(entryFunctionNames = entryFunctionNames.get()).scan(ktFiles)
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writer().use { writer -> writeNavNodeIndex(nodes, writer) }
    }
}

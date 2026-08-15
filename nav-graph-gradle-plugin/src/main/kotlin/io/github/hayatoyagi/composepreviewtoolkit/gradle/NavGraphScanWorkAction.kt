package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.KotlinPsiParser
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNodeScanner
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/** Inputs [NavGraphScanWorkAction] needs, mirroring the relevant subset of GenerateDebugNavGraphSite's own properties. */
internal interface NavGraphScanParameters : WorkParameters {
    val sourceFiles: ConfigurableFileCollection
    val entryFunctionNames: SetProperty<String>
    val callGraphResolutionDepth: Property<Int>
    val fallbackBaseDirectory: DirectoryProperty
    val outputFile: RegularFileProperty
}

/**
 * Runs [KotlinPsiParser]/[NavNodeScanner]/[NavEdgeScanner] — see
 * `ComposePreviewToolkitNavGraphPlugin`'s kdoc for why this has to happen inside a Gradle Worker
 * with an isolated classloader rather than directly in `GenerateDebugNavGraphSite`'s own task
 * action. Results cross back out via [NavGraphScanParameters.outputFile] (see
 * [writeNavGraphScanResult]/[readNavGraphScanResult]) since `WorkParameters` are input-only —
 * there's no return-value channel back to the submitting task.
 */
internal abstract class NavGraphScanWorkAction : WorkAction<NavGraphScanParameters> {
    override fun execute() {
        val (nodes, edgeResult) = KotlinPsiParser().use { parser ->
            val ktFiles = parameters.sourceFiles.files.filter { it.isFile }.map { file -> parser.parse(file) }
            val nodes = NavNodeScanner(entryFunctionNames = parameters.entryFunctionNames.get())
                .scan(ktFiles, fallbackBaseDirectory = parameters.fallbackBaseDirectory.get().asFile)
            val edgeResult = NavEdgeScanner(
                entryFunctionNames = parameters.entryFunctionNames.get(),
                callGraphResolutionDepth = parameters.callGraphResolutionDepth.get(),
            ).scan(ktFiles)
            nodes to edgeResult
        }
        writeNavGraphScanResult(
            file = parameters.outputFile.get().asFile,
            nodes = nodes,
            edges = edgeResult.edges.distinct(),
            warnings = edgeResult.warnings,
        )
    }
}

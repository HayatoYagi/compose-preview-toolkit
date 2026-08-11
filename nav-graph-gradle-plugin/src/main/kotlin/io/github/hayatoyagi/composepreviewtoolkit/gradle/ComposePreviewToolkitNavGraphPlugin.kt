package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_ENTRY_FUNCTION_NAMES
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NAV_NODE_INDEX_FILE_NAME
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Statically extracts a Compose Navigation3 nav graph from a module's own sources via
 * `nav-graph-psi-analyzer`'s PSI-based node scanning, registering a `generateDebugNavGraph` task.
 *
 * A deliberately separate plugin id from Phase 1's `io.github.hayatoyagi.compose-preview-toolkit`
 * (see the Phase 2 design doc): `nav-graph-psi-analyzer` carries `kotlin-compiler-embeddable`, a
 * heavy dependency that shouldn't land on every Phase-1-only consumer's classpath. This plugin
 * doesn't require Phase 1's plugin to also be applied — a module can use either, both, or neither.
 *
 * Unlike `ComposePreviewToolkitPlugin`, this plugin needs no KSP-apply-timing `afterEvaluate`
 * gymnastics: there's no KSP involved at all here, just plain source files read directly by the
 * task, so the extension can be wired to the task lazily and eagerly at apply() time.
 */
class ComposePreviewToolkitNavGraphPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "composePreviewToolkitNavGraph",
            ComposePreviewToolkitNavGraphExtension::class.java,
        ).apply {
            entryFunctionNames.convention(DEFAULT_ENTRY_FUNCTION_NAMES)
        }

        target.tasks.register("generateDebugNavGraph", GenerateDebugNavGraph::class.java) { task ->
            task.sourceFiles.from(
                target.layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { filter ->
                    filter.include("**/*.kt")
                },
            )
            task.entryFunctionNames.set(extension.entryFunctionNames)
            task.outputFile.set(
                target.layout.buildDirectory.file(
                    "generated/composePreviewToolkit/navGraph/debug/$NAV_NODE_INDEX_FILE_NAME.txt",
                ),
            )
        }
    }
}

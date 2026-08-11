package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.ksp.ScreenshotPreviewProcessorProvider
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
 *
 * Also registers `generateDebugNavGraphSite`, which aggregates node/screenshot indexes across the
 * modules configured via `composePreviewToolkitNavGraph { graphModules.set(...) }` on the
 * "aggregator" module (typically `app`) and renders a thumbnail-gallery `index.html`. This
 * intentionally covers nodes + screenshots only, not edges/graph rendering — see the Phase 2
 * design doc for the full nav graph design. Cross-project wiring for that task is deferred to
 * [target]'s `afterEvaluate` since `graphModules` is only known once the consumer's own
 * `composePreviewToolkitNavGraph { ... }` block has run.
 */
class ComposePreviewToolkitNavGraphPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "composePreviewToolkitNavGraph",
            ComposePreviewToolkitNavGraphExtension::class.java,
        ).apply {
            entryFunctionNames.convention(DEFAULT_ENTRY_FUNCTION_NAMES)
            routeNameSuffixesToStrip.convention(setOf("Destination", "Route"))
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

        val generateDebugNavGraphSite =
            target.tasks.register("generateDebugNavGraphSite", GenerateDebugNavGraphSite::class.java) { task ->
                task.routeNameSuffixesToStrip.set(extension.routeNameSuffixesToStrip)
                task.outputDirectory.set(target.layout.buildDirectory.dir("composePreviewToolkit/navGraphSite/debug"))
            }

        // Deferred to afterEvaluate: graphModules is only meaningful once the consumer's own
        // `composePreviewToolkitNavGraph { graphModules.set(...) }` block (if any) has run, which
        // happens after this plugin's apply(). Reading it any earlier would silently see an empty
        // set on every real consumer.
        target.afterEvaluate {
            extension.graphModules.get().forEach { path -> target.wireGraphModule(path, generateDebugNavGraphSite) }
        }
    }

    /**
     * Wires one `graphModules` entry into [generateDebugNavGraphSite]: globs its node/screenshot
     * indexes (mirroring `ComposePreviewToolkitPlugin`'s own `asFileTree.matching { ... }` glob
     * technique for the KSP output directory, just pointed at [path]'s project instead of the
     * local one) and adds real Gradle task dependencies on that project's own
     * `generateDebugNavGraph`/`kspDebugKotlin` so this task never races their outputs.
     *
     * Cross-project task dependencies use `pluginManager.withPlugin(id) { ... }` rather than
     * grabbing `tasks.named(...)` unconditionally: `[path]`'s project object always exists once
     * settings has run, but its build script may not have been evaluated yet at this point in
     * *this* project's own `afterEvaluate` (project evaluation order across a multi-project build
     * isn't guaranteed to follow `graphModules`' order, or even this project's own position in
     * `settings.gradle.kts`). `withPlugin` reacts whenever that project's plugin actually applies
     * — immediately if it already has, or later during that project's own script evaluation
     * otherwise — so this works regardless of evaluation order, without forcing eager
     * cross-project evaluation. This matters concretely for `compose-preview-toolkit-sample`:
     * `:app` lists `:feature-a`/`:feature-b` in `graphModules`, but those two are declared (and
     * therefore normally evaluated) *before* `:app` in `sample/settings.gradle.kts`.
     */
    private fun Project.wireGraphModule(
        path: String,
        generateDebugNavGraphSite: org.gradle.api.tasks.TaskProvider<GenerateDebugNavGraphSite>,
    ) {
        val graphModuleProject = project(path)

        generateDebugNavGraphSite.configure { task ->
            task.nodeIndexFiles.from(
                graphModuleProject.layout.buildDirectory.dir("generated/composePreviewToolkit/navGraph/debug")
                    .map { dir ->
                        dir.asFileTree.matching { filter -> filter.include("$NAV_NODE_INDEX_FILE_NAME.txt") }
                    },
            )
            task.screenshotIndexFiles.from(
                graphModuleProject.layout.buildDirectory.dir("generated/ksp/debug/resources")
                    .map { dir ->
                        dir.asFileTree.matching { filter ->
                            filter.include("**/${ScreenshotPreviewProcessorProvider.DEFAULT_INDEX_FILE_NAME}*.txt")
                        }
                    },
            )
            task.screenshotReferenceImages.from(
                graphModuleProject.layout.projectDirectory.dir("src/screenshotTestDebug/reference").asFileTree
                    .matching { filter -> filter.include("**/*.png") },
            )
        }

        // Real cross-project task dependency: without this, generateDebugNavGraphSite's globs
        // above would just race graphModuleProject's own generateDebugNavGraph task (or silently
        // see nothing, on a clean build where it hasn't run yet) instead of Gradle actually
        // scheduling and running it first.
        graphModuleProject.pluginManager.withPlugin(NAV_GRAPH_PLUGIN_ID) {
            generateDebugNavGraphSite.configure { task ->
                task.dependsOn(graphModuleProject.tasks.named("generateDebugNavGraph"))
            }
        }

        // Same reasoning as ComposePreviewToolkitPlugin's own eager/afterEvaluate split for
        // kspDebugKotlin: that task doesn't exist until AGP has finished creating the debug
        // variant's KSP tasks, which (mirroring ComposePreviewToolkitPlugin's own
        // `target.tasks.findByName("kspDebugKotlin")` call inside *its* afterEvaluate) is only
        // guaranteed by graphModuleProject's own afterEvaluate — nested here inside withPlugin so
        // it only runs for graph modules that actually apply Phase 1's plugin at all.
        graphModuleProject.pluginManager.withPlugin(SCREENSHOT_PLUGIN_ID) {
            graphModuleProject.afterEvaluate {
                graphModuleProject.tasks.findByName("kspDebugKotlin")?.let { kspDebugKotlin ->
                    generateDebugNavGraphSite.configure { task -> task.dependsOn(kspDebugKotlin) }
                }
            }
        }
    }

    private companion object {
        const val NAV_GRAPH_PLUGIN_ID = "io.github.hayatoyagi.compose-preview-toolkit.navgraph"
        const val SCREENSHOT_PLUGIN_ID = "io.github.hayatoyagi.compose-preview-toolkit"
    }
}

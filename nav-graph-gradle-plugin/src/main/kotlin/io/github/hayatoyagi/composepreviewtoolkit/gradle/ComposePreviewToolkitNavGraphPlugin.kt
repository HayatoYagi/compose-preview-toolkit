package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.ksp.ScreenshotPreviewProcessorProvider
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_ENTRY_FUNCTION_NAMES
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_NAVIGATE_CALL_NAMES
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NAV_EDGE_INDEX_FILE_NAME
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NAV_NODE_INDEX_FILE_NAME
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

/**
 * Statically extracts a Compose Navigation3 nav graph from a module's own sources via
 * `nav-graph-psi-analyzer`'s PSI-based node and edge scanning, registering a
 * `generateDebugNavGraph` task.
 *
 * A deliberately separate plugin id from `io.github.hayatoyagi.compose-preview-toolkit`:
 * `nav-graph-psi-analyzer` carries `kotlin-compiler-embeddable`, a heavy dependency that shouldn't
 * land on the classpath of a consumer who only wants screenshot-test generation. This plugin
 * doesn't require that other plugin to also be applied — a module can use either, both, or neither.
 *
 * Unlike `ComposePreviewToolkitPlugin`, this plugin needs no KSP-apply-timing `afterEvaluate`
 * gymnastics: there's no KSP involved at all here, just plain source files read directly by the
 * task, so the extension can be wired to the task lazily and eagerly at apply() time.
 *
 * Also registers `generateDebugNavGraphSite`, which aggregates node/edge/screenshot data across
 * every project [Project.discoverGraphModules] resolves on the "aggregator" module (typically
 * `app`, wherever this plugin is applied) and renders a self-contained `index.html` with both a
 * Mermaid.js nav graph diagram and a thumbnail gallery. Cross-project wiring for that task is
 * deferred to [target]'s `afterEvaluate` since [Project.discoverGraphModules] needs AGP to have
 * already created the debug variant's configurations, which only happens by then.
 */
class ComposePreviewToolkitNavGraphPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            "composePreviewToolkitNavGraph",
            ComposePreviewToolkitNavGraphExtension::class.java,
        ).apply {
            entryFunctionNames.convention(DEFAULT_ENTRY_FUNCTION_NAMES)
            navigateCallNames.convention(DEFAULT_NAVIGATE_CALL_NAMES)
            callGraphResolutionDepth.convention(DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH)
            routeNameSuffixesToStrip.convention(setOf("Destination", "Route"))
        }

        target.tasks.register("generateDebugNavGraph", GenerateDebugNavGraph::class.java) { task ->
            task.sourceFiles.from(
                target.layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { filter ->
                    filter.include("**/*.kt")
                },
            )
            task.entryFunctionNames.set(extension.entryFunctionNames)
            task.navigateCallNames.set(extension.navigateCallNames)
            task.callGraphResolutionDepth.set(extension.callGraphResolutionDepth)
            task.projectDirectory.set(target.layout.projectDirectory)
            task.outputFile.set(
                target.layout.buildDirectory.file(
                    "generated/composePreviewToolkit/navGraph/debug/$NAV_NODE_INDEX_FILE_NAME.txt",
                ),
            )
            task.edgeOutputFile.set(
                target.layout.buildDirectory.file(
                    "generated/composePreviewToolkit/navGraph/debug/$NAV_EDGE_INDEX_FILE_NAME.txt",
                ),
            )
        }

        val generateDebugNavGraphSite =
            target.tasks.register("generateDebugNavGraphSite", GenerateDebugNavGraphSite::class.java) { task ->
                task.routeNameSuffixesToStrip.set(extension.routeNameSuffixesToStrip)
                // Same convention-backed properties generateDebugNavGraph uses, reused here for
                // this task's own project-wide node/edge (re-)scan — see GenerateDebugNavGraphSite's
                // kdoc for why a project-wide scan is necessary at all.
                task.entryFunctionNames.set(extension.entryFunctionNames)
                task.navigateCallNames.set(extension.navigateCallNames)
                task.callGraphResolutionDepth.set(extension.callGraphResolutionDepth)
                // Same fallback-only role as GenerateDebugNavGraph's own projectDirectory: only
                // consulted for a node's filePath when the git repo root can't be determined.
                task.projectDirectory.set(target.layout.projectDirectory)
                task.outputDirectory.set(target.layout.buildDirectory.dir("composePreviewToolkit/navGraphSite/debug"))
            }

        // Deferred to afterEvaluate: discoverGraphModules() reads the resolved
        // "debugCompileClasspath" configuration, which AGP only finishes creating once this
        // project's own build script (and the plugins it applies) have been evaluated.
        target.afterEvaluate {
            target.discoverGraphModules().forEach { path -> target.wireGraphModule(path, generateDebugNavGraphSite) }
        }
    }

    /**
     * The full set of Gradle project paths `generateDebugNavGraphSite` aggregates screenshot data
     * across (and, via [GenerateDebugNavGraphSite]'s own project-wide PSI scan, node/edge data
     * across too): this project's own path plus every project dependency resolvable (transitively)
     * from its `debugCompileClasspath` configuration. There's no way to override this with a
     * narrower, manually maintained set — see [ComposePreviewToolkitNavGraphExtension]'s kdoc for
     * why an auto-discovered set is the whole point (a hand-maintained list is exactly what let a
     * route's owning `api` module go unlisted in the first place, silently producing a wrong
     * `qualifiedName` for that route).
     *
     * Deliberately uses `Configuration.incoming.resolutionResult` — Gradle's own dependency
     * resolution engine — instead of manually walking other projects' `configurations`/
     * `pluginManager` to discover what they depend on: the latter would require directly touching
     * those projects' own live build-script objects, which this avoids by only ever reading back
     * plain [String] project paths out of a resolved dependency graph.
     *
     * This does **not** avoid eager cross-project evaluation, though: resolving
     * `debugCompileClasspath`'s `resolutionResult` to real dependency projects (not just declared
     * dependency coordinates, which would be lazy) requires Gradle to know those projects' own
     * configured outputs, which forces it to fully evaluate every project this configuration
     * transitively resolves to — confirmed empirically against `compose-preview-toolkit-sample`'s
     * real `:app` -> `:feature-a`/`:feature-b` dependency shape (an earlier version of this code
     * assumed resolution was lazy here and crashed on exactly this). [wireGraphModule] is written
     * to account for that: every project this method returns a path for is guaranteed to have
     * already finished evaluating (`Project.getState().getExecuted() == true`) by the time
     * [wireGraphModule] runs for it, since resolving this method's classpath is literally what
     * forced that evaluation to happen, moments earlier in this same `afterEvaluate` callback.
     *
     * `"debugCompileClasspath"` is used to match this plugin's existing debug-build-type-only scope
     * everywhere else (`generateDebugNavGraph`, `generateDebugNavGraphSite`). If it doesn't exist
     * on this project (e.g. applied to a non-Android module, or a variant name this simple lookup
     * doesn't anticipate), this degrades to just this project's own path rather than failing the
     * build — the resulting site would only show this project's own nodes.
     *
     * Over-including a resolved dependency project that turns out to have no nav entries at all is
     * fine (see [ComposePreviewToolkitNavGraphExtension]'s kdoc) — this deliberately doesn't try to
     * filter down to "only modules that apply this plugin", which would require exactly the eager
     * cross-project introspection this design avoids.
     *
     * Called from inside [target]'s `afterEvaluate`, by which point AGP has finished creating the
     * debug variant's configurations.
     */
    private fun Project.discoverGraphModules(): Set<String> {
        val resolvedProjectPaths = configurations.findByName("debugCompileClasspath")
            ?.let { debugCompileClasspath ->
                runCatching {
                    debugCompileClasspath.incoming.resolutionResult.allComponents
                        .mapNotNull { component -> (component.id as? ProjectComponentIdentifier)?.projectPath }
                }.getOrElse { failure ->
                    logger.warn(
                        "compose-preview-toolkit-navgraph: couldn't resolve 'debugCompileClasspath' to " +
                            "discover graphModules for $path, falling back to just this project. " +
                            "Cause: ${failure.message}",
                    )
                    emptyList()
                }
            }
            .orEmpty()

        return (resolvedProjectPaths + path).toSet()
    }

    /**
     * Wires one [discoverGraphModules] entry into [generateDebugNavGraphSite]: globs its
     * screenshot indexes (mirroring `ComposePreviewToolkitPlugin`'s own `asFileTree.matching { ... }`
     * glob technique for the KSP output directory, just pointed at [path]'s project instead of the
     * local one) and adds a real Gradle task dependency on that project's own `kspDebugKotlin` so
     * this task never races its output. Also globs [path]'s raw `.kt` sources into
     * `edgeSourceFiles` — plain source, so no task dependency needed for that one —
     * since `GenerateDebugNavGraphSite` needs the actual combined project sources (not a
     * precomputed per-module index) to resolve both edges and node `qualifiedName`s that cross a
     * graph-module boundary; see that task's kdoc for why this turned out to be necessary, and
     * deliberately does **not** add a task dependency on [path]'s own `generateDebugNavGraph` —
     * that task's module-local scan can legitimately hard-fail for exactly the cross-module
     * declaration shape this task exists to resolve correctly (see `EntryRegistrations.kt`), so
     * `generateDebugNavGraphSite` must not depend on it succeeding.
     *
     * Cross-project task dependencies use `pluginManager.withPlugin(id) { ... }` rather than
     * grabbing `tasks.named(...)` unconditionally, so this works whether [path]'s plugin happened
     * to already be applied by the time this runs or not — see [discoverGraphModules]'s kdoc for
     * why, in practice, it always already has been: resolving its `debugCompileClasspath` forces
     * every project it resolves to (which is exactly the set [path] ranges over) to fully evaluate
     * first, which necessarily runs that project's own `plugins { ... }` application. `withPlugin`'s
     * callback therefore fires synchronously here rather than later, but is still the right tool
     * since it degrades correctly in the one case where a graph module *hasn't* pre-evaluated (the
     * fallback path in [discoverGraphModules] when classpath resolution itself fails).
     *
     * The nested `graphModuleProject.afterEvaluate { ... }` inside the `withPlugin` callback below
     * needs the same care for a different reason: [discoverGraphModules] having already forced
     * [path]'s project to fully evaluate means it's routinely already past the point where
     * `Project.afterEvaluate` can be called on it at all — calling it anyway throws
     * ("Cannot run Project.afterEvaluate(Action) when the project is already evaluated"), confirmed
     * empirically. Guarding on `graphModuleProject.state.executed` picks the right one of "run now"
     * vs. "run once evaluation finishes" for whichever case actually holds.
     */
    private fun Project.wireGraphModule(
        path: String,
        generateDebugNavGraphSite: org.gradle.api.tasks.TaskProvider<GenerateDebugNavGraphSite>,
    ) {
        val graphModuleProject = project(path)

        generateDebugNavGraphSite.configure { task ->
            // Raw source, not a generated artifact — needs no task dependency (mirroring
            // generateDebugNavGraph's own sourceFiles wiring). See GenerateDebugNavGraphSite's
            // kdoc for why this task needs [path]'s actual sources, not just a precomputed index: a
            // project-wide scan is the only scope that can resolve edges and node qualifiedNames
            // whose `entry<X> {}` registration and declaration (or reaching `navigateTo(...)` call
            // site) live in different graph-module projects, which is the common case in practice.
            task.edgeSourceFiles.from(
                graphModuleProject.layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { filter ->
                    filter.include("**/*.kt")
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

        // kspDebugKotlin doesn't exist until AGP has finished creating the debug variant's KSP
        // tasks, which is only guaranteed once graphModuleProject's own afterEvaluate listeners
        // (AGP's own included) have run — nested here inside withPlugin so it only runs for graph
        // modules that actually apply the screenshot-testing plugin (SCREENSHOT_PLUGIN_ID) at all.
        // See this method's kdoc for why the state.executed guard below is required here (unlike
        // ComposePreviewToolkitPlugin's own unconditional-afterEvaluate version of this same
        // pattern): graphModuleProject has routinely already finished evaluating by this point.
        graphModuleProject.pluginManager.withPlugin(SCREENSHOT_PLUGIN_ID) {
            val wireKspDependency = {
                graphModuleProject.tasks.findByName("kspDebugKotlin")?.let { kspDebugKotlin ->
                    generateDebugNavGraphSite.configure { task -> task.dependsOn(kspDebugKotlin) }
                }
                Unit
            }
            if (graphModuleProject.state.executed) {
                wireKspDependency()
            } else {
                graphModuleProject.afterEvaluate { wireKspDependency() }
            }
        }
    }

    private companion object {
        const val SCREENSHOT_PLUGIN_ID = "io.github.hayatoyagi.compose-preview-toolkit"
    }
}

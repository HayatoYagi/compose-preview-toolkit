package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.ksp.ScreenshotPreviewProcessorProvider
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_ENTRY_FUNCTION_NAMES
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_NAVIGATE_CALL_NAMES
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

/**
 * Statically extracts a Compose Navigation3 nav graph via `nav-graph-psi-analyzer`'s PSI-based
 * node and edge scanning, registering a `generateDebugNavGraphSite` task.
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
 * `generateDebugNavGraphSite` aggregates node/edge/screenshot data across
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

        val generateDebugNavGraphSite =
            target.tasks.register("generateDebugNavGraphSite", GenerateDebugNavGraphSite::class.java) { task ->
                task.routeNameSuffixesToStrip.set(extension.routeNameSuffixesToStrip)
                task.entryFunctionNames.set(extension.entryFunctionNames)
                task.navigateCallNames.set(extension.navigateCallNames)
                task.callGraphResolutionDepth.set(extension.callGraphResolutionDepth)
                // Fallback-only: only consulted for a node's filePath when the git repo root can't
                // be determined.
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
     * The full set of Gradle project paths `generateDebugNavGraphSite` aggregates node/edge/
     * screenshot data across: this project's own path plus every project dependency resolvable
     * (transitively) from its `debugCompileClasspath` configuration. Not overridable — a route
     * whose declaration lives outside the scanned set is a hard failure (see
     * `EntryRegistrations.kt`), so "everything reachable" is the only safe scope.
     *
     * Uses `Configuration.incoming.resolutionResult` — Gradle's own dependency resolution engine —
     * rather than manually walking other projects' `configurations`/`pluginManager`, so this only
     * ever reads back plain [String] project paths instead of touching those projects' live
     * build-script objects directly.
     *
     * Resolving `debugCompileClasspath` to real dependency projects (not just declared dependency
     * coordinates, which would stay lazy) forces Gradle to fully evaluate every project it resolves
     * to. [wireGraphModule] accounts for this: every path returned here is guaranteed to already be
     * fully evaluated (`Project.getState().getExecuted() == true`) by the time it runs, since
     * resolving this method's classpath is what forced that evaluation moments earlier in this same
     * `afterEvaluate` callback.
     *
     * `"debugCompileClasspath"` matches `generateDebugNavGraphSite`'s own debug-build-type-only
     * scope. If it doesn't exist on this project, this degrades to just this project's own path
     * rather than failing the build.
     *
     * Called from inside [target]'s `afterEvaluate`, once AGP has finished creating the debug
     * variant's configurations.
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
                            "discover graph modules for $path, falling back to just this project. " +
                            "Cause: ${failure.message}",
                    )
                    emptyList()
                }
            }
            .orEmpty()

        return (resolvedProjectPaths + path).toSet()
    }

    /**
     * Wires one [discoverGraphModules] entry into [generateDebugNavGraphSite]: globs its screenshot
     * indexes (mirroring `ComposePreviewToolkitPlugin`'s own glob technique for the KSP output
     * directory) and adds a task dependency on that project's own `kspDebugKotlin`. Also globs
     * [path]'s raw `.kt` sources into `edgeSourceFiles` — see `GenerateDebugNavGraphSite`'s kdoc for
     * why a combined multi-module scan is needed there.
     *
     * Two Gradle-lifecycle guards below, both stemming from [discoverGraphModules] forcing every
     * graph module to fully evaluate before this runs:
     * - `pluginManager.withPlugin(id) { ... }` (not `tasks.named(...)` directly) still works if a
     *   graph module's plugin somehow isn't applied yet, though in practice it fires synchronously.
     * - The nested `graphModuleProject.afterEvaluate { ... }` is guarded by
     *   `graphModuleProject.state.executed`: calling `afterEvaluate` on an already-evaluated project
     *   throws, so this runs the wiring immediately instead when that's already true.
     */
    private fun Project.wireGraphModule(
        path: String,
        generateDebugNavGraphSite: org.gradle.api.tasks.TaskProvider<GenerateDebugNavGraphSite>,
    ) {
        val graphModuleProject = project(path)

        generateDebugNavGraphSite.configure { task ->
            // Raw source, not a generated artifact — needs no task dependency. See
            // GenerateDebugNavGraphSite's kdoc for why this task needs [path]'s actual sources: a
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

package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.ksp.ScreenshotPreviewProcessorProvider
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_ENTRY_FUNCTION_NAMES
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.DEFAULT_NAVIGATE_CALL_NAMES
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.util.concurrent.Callable

/**
 * Statically extracts a Compose Navigation3 nav graph via `nav-graph-psi-analyzer`'s PSI-based
 * node and edge scanning, registering a `generateDebugNavGraphSite` task.
 *
 * A deliberately separate plugin id from `io.github.hayatoyagi.compose-preview-toolkit`:
 * `nav-graph-psi-analyzer` carries `kotlin-compiler-embeddable`, a heavy dependency that shouldn't
 * land on the classpath of a consumer who only wants screenshot-test generation. This plugin
 * doesn't require that other plugin to also be applied — a module can use either, both, or neither.
 *
 * [Project.discoverGraphModules] reads a dependency project's sources directly, so this plugin
 * doesn't need to be applied there for its declarations to be found — a
 * dependency module with no Compose Kotlin Gradle subplugin of its own (e.g. one that just
 * declares route types, with no Composable UI) is discovered and scanned correctly either way. The
 * one case where this plugin *should* also be applied on the dependency project: if that project
 * applies its own Compose Kotlin Gradle subplugin (`org.jetbrains.kotlin.plugin.compose`) but
 * doesn't apply this one, `kotlin-compiler-embeddable` ends up present on only some Compose
 * modules' plugin classpaths, and Gradle can resolve the Kotlin Gradle plugin via mismatched
 * classloaders across modules (a real, reproduced failure mode — Gradle logs "The Kotlin Gradle
 * plugin was loaded multiple times in different subprojects, which is not supported and may break
 * the build").
 *
 * Unlike `ComposePreviewToolkitPlugin`, this plugin needs no KSP-apply-timing `afterEvaluate`
 * gymnastics: there's no KSP involved at all here, just plain source files read directly by the
 * task, so the extension can be wired to the task lazily and eagerly at apply() time.
 *
 * `generateDebugNavGraphSite` aggregates node/edge/screenshot data across
 * every project [Project.discoverGraphModules] resolves on the "aggregator" module (typically
 * `app`, wherever this plugin is applied) and renders a self-contained `index.html` with both a
 * Mermaid.js nav graph diagram and a thumbnail gallery. Cross-project wiring for that task is
 * deferred (see [apply]'s `Callable`s) until Gradle actually needs it, since
 * [Project.discoverGraphModules] needs AGP to have already created the debug variant's
 * configurations, which only happens once every project has finished its own configuration.
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

        // Memoized: every Callable below reads this same value, but debugCompileClasspath should
        // only be resolved once. Deferred (not resolved right here) until Gradle actually needs a
        // task's dependencies/inputs, at task-graph-computation time — by then every project in the
        // build has already configured normally, so this needs none of ComposePreviewToolkitPlugin's
        // own KSP-apply-timing afterEvaluate/withPlugin machinery to know a graph module's plugins
        // are already applied.
        val graphModules by lazy { target.discoverGraphModules() }

        generateDebugNavGraphSite.configure { task ->
            task.edgeSourceFiles.from(
                Callable {
                    graphModules.map { path ->
                        target.project(path).layout.projectDirectory.dir("src/main/kotlin").asFileTree
                            .matching { filter -> filter.include("**/*.kt") }
                    }
                },
            )
            task.screenshotIndexFiles.from(
                Callable {
                    graphModules.map { path ->
                        target.project(path).layout.buildDirectory.dir("generated/ksp/debug/resources")
                            .map { dir ->
                                dir.asFileTree.matching { filter ->
                                    filter.include("**/${ScreenshotPreviewProcessorProvider.DEFAULT_INDEX_FILE_NAME}*.txt")
                                }
                            }
                    }
                },
            )
            task.screenshotReferenceImages.from(
                Callable {
                    graphModules.map { path ->
                        target.project(path).layout.projectDirectory.dir("src/screenshotTestDebug/reference")
                            .asFileTree.matching { filter -> filter.include("**/*.png") }
                    }
                },
            )
            task.dependsOn(
                Callable {
                    graphModules.mapNotNull { path ->
                        val graphModuleProject = target.project(path)
                        graphModuleProject.tasks.findByName("kspDebugKotlin")
                            .takeIf { graphModuleProject.pluginManager.findPlugin(SCREENSHOT_PLUGIN_ID) != null }
                    }
                },
            )
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
     * `"debugCompileClasspath"` matches `generateDebugNavGraphSite`'s own debug-build-type-only
     * scope. If it doesn't exist on this project, this degrades to just this project's own path
     * rather than failing the build.
     *
     * Called lazily, at task-graph-computation time (see the `Callable`s wrapping this in [apply]) —
     * by then every project in the build has already finished its own normal configuration, so
     * `debugCompileClasspath` is guaranteed to exist and every discovered path's own plugins are
     * guaranteed to already be applied.
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

    private companion object {
        const val SCREENSHOT_PLUGIN_ID = "io.github.hayatoyagi.compose-preview-toolkit"
    }
}

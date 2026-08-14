package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configuration for the `composePreviewToolkitNavGraph` Gradle extension.
 *
 * Registered under a name distinct from the screenshot-testing plugin's `composePreviewToolkit`
 * extension so a module can apply both plugins at once (e.g. a feature module using both
 * screenshot-test generation and nav-graph extraction) without a naming collision.
 *
 * Notably absent: a `graphModules` property. `generateDebugNavGraphSite` (registered on whichever
 * module applies this plugin, typically the app/aggregator module) always aggregates across this
 * project's own path plus every project dependency resolvable from its `debugCompileClasspath`
 * configuration, transitively — computed automatically by
 * `ComposePreviewToolkitNavGraphPlugin.discoverGraphModules`, with no manual override. A
 * hand-maintained module list is exactly what let a route's owning module go unlisted in a real
 * consumer, silently producing a wrong `qualifiedName` for that route; over-including an unrelated
 * dependency module with no nav entries is harmless (it just contributes nothing), so there's no
 * safe way to under-specify this that's worth exposing as a knob.
 */
abstract class ComposePreviewToolkitNavGraphExtension {
    /**
     * Callee simple names treated as Navigation3 `entry<X> { ... }` route registrations when
     * scanning for nav graph nodes. Defaults to `nav-graph-psi-analyzer`'s
     * `DEFAULT_ENTRY_FUNCTION_NAMES` (`["entry"]`), matching Nav3's own
     * `EntryProviderScope<T>.entry<K : T>(...)`.
     */
    abstract val entryFunctionNames: SetProperty<String>

    /**
     * Callee simple names treated as `navigateTo`/`navigate`-shaped calls when scanning for nav
     * graph edges. Defaults to `nav-graph-psi-analyzer`'s `DEFAULT_NAVIGATE_CALL_NAMES`
     * (`["navigateTo", "navigate"]`).
     */
    abstract val navigateCallNames: SetProperty<String>

    /**
     * Upper bound on how many call-graph hops [io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner]'s
     * bounded-depth breadth-first search will traverse from an `entry<X> { ... }` block before
     * giving up on a candidate edge (with a warning, never a build failure). Defaults to
     * `nav-graph-psi-analyzer`'s `DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH`.
     */
    abstract val callGraphResolutionDepth: Property<Int>

    /**
     * Suffixes stripped from a route's `simpleName` (case-sensitive, first matching suffix in
     * this set wins) before case-insensitively substring-matching the remainder against a
     * screenshot index entry's wrapper name, to best-effort pair a nav graph node with its
     * screenshot baseline for the gallery site. Defaults to `["Destination", "Route"]`.
     */
    abstract val routeNameSuffixesToStrip: SetProperty<String>
}

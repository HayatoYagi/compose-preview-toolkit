package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configuration for the `composePreviewToolkitNavGraph` Gradle extension.
 *
 * Registered under a name distinct from the screenshot-testing plugin's `composePreviewToolkit`
 * extension so a module can apply both plugins at once (e.g. a feature module using both
 * screenshot-test generation and nav-graph extraction) without a naming collision.
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

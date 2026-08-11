package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.provider.SetProperty

/**
 * Configuration for the `composePreviewToolkitNavGraph` Gradle extension.
 *
 * Registered under a name distinct from Phase 1's `composePreviewToolkit` extension so a module
 * can apply both plugins at once (e.g. a feature module using both screenshot-test generation and
 * nav-graph extraction) without a naming collision.
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
     * Gradle project paths (e.g. `[":feature-a", ":feature-b", ":app"]`) that
     * `generateDebugNavGraphSite` aggregates node/screenshot indexes across. Only meaningful on
     * the "aggregator" module (typically the app module) where the site-generation task is
     * registered; on other modules this property is simply unused. Deliberately has no
     * convention default: there's no sensible universal default for "which modules make up my
     * app's graph" — every consumer must set this explicitly.
     */
    abstract val graphModules: SetProperty<String>

    /**
     * Suffixes stripped from a route's `simpleName` (case-sensitive, first matching suffix in
     * this set wins) before case-insensitively substring-matching the remainder against a
     * screenshot index entry's wrapper name, to best-effort pair a nav graph node with its
     * screenshot baseline for the gallery site. Defaults to `["Destination", "Route"]`.
     */
    abstract val routeNameSuffixesToStrip: SetProperty<String>
}

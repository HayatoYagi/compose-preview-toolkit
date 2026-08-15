package io.github.hayatoyagi.composepreviewtoolkit.sample.featureb

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.FeatureARoute

/**
 * Wires [FeatureBRoute] into an `entryProvider<NavKey> { ... }` builder.
 *
 * Unlike [io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.featureANavEntries]'s
 * `onProceedClick` (a single-purpose, zero-arg callback threaded up to `sample/app`, which is what
 * actually invokes it), this function is handed [navigateTo] itself — the app's real route-carrying
 * closure — and calls it directly, inside `FeatureBNavEntries.kt`'s own `entry<FeatureBRoute> {}`
 * block, for the "restart" affordance. [navigateTo] itself still has to be threaded in from
 * `sample/app` (nothing in `feature-b` owns the real back stack), but that's just how the call gets
 * access to a working `(NavKey) -> Unit` at runtime — the graph-relevant part, where the actual
 * `NavBackStack` mutation happens, is entirely local to `sample/app`'s own `AppNavHost.kt`. Both
 * this and `featureANavEntries`'s shape are detected by the exact same
 * [io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner] call-graph algorithm,
 * with no special-casing between them.
 *
 * `entry<T>` is not imported here — see `FeatureANavEntries.kt`'s kdoc for why (it's a member
 * function of `EntryProviderScope`, resolved via this function's own extension receiver).
 */
fun EntryProviderScope<NavKey>.featureBNavEntries(navigateTo: (NavKey) -> Unit) {
    entry<FeatureBRoute> {
        FeatureBScreen(onRestartClick = { navigateTo(FeatureARoute) })
    }
}

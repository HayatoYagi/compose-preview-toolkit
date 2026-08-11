package io.github.hayatoyagi.composepreviewtoolkit.sample.featureb

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.FeatureARoute

/**
 * Wires [FeatureBRoute] into an `entryProvider<NavKey> { ... }` builder.
 *
 * Unlike [io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.featureANavEntries]'s
 * `onProceedClick` (pattern (i): the actual `navigateTo(...)` call lives at the app-level call
 * site, threaded in as a callback argument), this function's "restart" affordance is pattern
 * (ii): the `navigateTo(FeatureARoute)` call is written directly inside `FeatureBNavEntries.kt`
 * itself, an ordinary same-module call with no callback-parameter indirection for *this*
 * particular edge. [navigateTo] itself still has to be threaded in from `sample/app` (nothing in
 * `feature-b` owns the real back stack), but that's just how the call gets access to a working
 * `(NavKey) -> Unit` at runtime — the graph-relevant part, where the `navigateTo(...)` call
 * expression itself is textually written, is entirely local to this feature module. Both this and
 * `featureANavEntries`'s pattern are detected by the exact same [io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdgeScanner]
 * call-graph algorithm, with no special-casing between them.
 *
 * `entry<T>` is not imported here — see `FeatureANavEntries.kt`'s kdoc for why (it's a member
 * function of `EntryProviderScope`, resolved via this function's own extension receiver).
 */
fun EntryProviderScope<NavKey>.featureBNavEntries(navigateTo: (NavKey) -> Unit) {
    entry<FeatureBRoute> {
        FeatureBScreen(onRestartClick = { navigateTo(FeatureARoute) })
    }
}

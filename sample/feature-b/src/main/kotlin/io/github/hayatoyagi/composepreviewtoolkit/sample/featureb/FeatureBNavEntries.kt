package io.github.hayatoyagi.composepreviewtoolkit.sample.featureb

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * Wires [FeatureBRoute] into an `entryProvider<NavKey> { ... }` builder. No outgoing edges.
 *
 * `entry<T>` is not imported here — see `FeatureANavEntries.kt`'s kdoc for why (it's a member
 * function of `EntryProviderScope`, resolved via this function's own extension receiver).
 */
fun EntryProviderScope<NavKey>.featureBNavEntries() {
    entry<FeatureBRoute> { FeatureBScreen() }
}

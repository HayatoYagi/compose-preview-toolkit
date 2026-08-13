package io.github.hayatoyagi.composepreviewtoolkit.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.FeatureARoute
import io.github.hayatoyagi.composepreviewtoolkit.sample.featurea.featureANavEntries
import io.github.hayatoyagi.composepreviewtoolkit.sample.featureb.FeatureBRoute
import io.github.hayatoyagi.composepreviewtoolkit.sample.featureb.featureBNavEntries

/** Navigation3 route for the app's own start destination. */
object HomeRoute : NavKey

/**
 * App-level Nav3 host. Mirrors medimo-android's real wiring shape: the `navigateTo(FeatureBRoute)`
 * call for feature-a's "proceed" action is written *here*, at the app level, passed into
 * [featureANavEntries] as the `onProceedClick` callback argument — not inside feature-a's own
 * `entry<FeatureARoute> {}` block. This is pattern (i) of the two navigation-wiring shapes the
 * nav-graph edge detector supports.
 *
 * [featureBNavEntries] is passed [navigateTo] itself (not a single-purpose callback like
 * `onProceedClick`) — its own `entry<FeatureBRoute> {}` block calls `navigateTo(FeatureARoute)`
 * directly, inside `feature-b`'s own source, demonstrating pattern (ii): see
 * `FeatureBNavEntries.kt`'s kdoc.
 *
 * `entry<T>` is not imported here — see `feature-a`'s `FeatureANavEntries.kt` kdoc for why: it's a
 * member function of `EntryProviderScope`, resolved via `entryProvider {}`'s own receiver.
 */
@Composable
fun AppNavHost() {
    val backStack = remember { NavBackStack<NavKey>(HomeRoute) }
    val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeScreen(onGoToFeatureAClick = { navigateTo(FeatureARoute) })
            }
            featureANavEntries(onProceedClick = { navigateTo(FeatureBRoute) })
            featureBNavEntries(navigateTo = navigateTo)
        },
    )
}

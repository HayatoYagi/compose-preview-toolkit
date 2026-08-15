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
 * App-level Nav3 host, and the one place [backStack] — the app's single, real
 * `NavBackStack<NavKey>` — is constructed. [navigateTo] wraps mutating it, and demonstrates every
 * shape the nav-graph edge detector's `NavBackStack`-mutation tracking supports:
 * - Called inline, right where it's declared, for [HomeRoute]'s own "go to feature A" action.
 * - Threaded as a plain zero-arg callback into [featureANavEntries]'s `onProceedClick` and only
 *   actually invoked *here*, at the app level, not inside feature-a's own `entry<FeatureARoute> {}`
 *   block.
 * - Handed to [featureBNavEntries] as the closure itself (not a single-purpose callback) — its own
 *   `entry<FeatureBRoute> {}` block calls it directly, inside `feature-b`'s own source: see
 *   `FeatureBNavEntries.kt`'s kdoc.
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

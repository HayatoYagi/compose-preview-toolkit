package io.github.hayatoyagi.composepreviewtoolkit.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.EntryProviderScope
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
 * App-level Nav3 host. Owns the real back stack; delegates every route registration and outgoing
 * navigation call to [appNavEntries].
 */
@Composable
fun AppNavHost() {
    val backStack = remember { NavBackStack<NavKey>(HomeRoute) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            appNavEntries(onNavigate = { key -> backStack.add(key) })
        },
    )
}

/**
 * All of the app's own route registrations plus its outgoing navigation. A top-level function
 * (not a lambda inline in [AppNavHost]) specifically so [onNavigate] is a real declared-type
 * function *parameter* — the shape the nav-graph edge detector's "declared callback type"
 * detection recognizes — rather than a local `val` inside a `@Composable`, which it can't see
 * regardless of that `val`'s own type annotation (see `nav-graph-psi-analyzer`'s `NavEdgeScanner`
 * kdoc: only named-function value parameters are tracked as live callables).
 *
 * The `onNavigate(FeatureBRoute)` call for feature-a's "proceed" action is written *here*, at the
 * app level, passed into [featureANavEntries] as the `onProceedClick` callback argument — not
 * inside feature-a's own `entry<FeatureARoute> {}` block. This is pattern (i) of the two
 * navigation-wiring shapes the nav-graph edge detector supports.
 *
 * [featureBNavEntries] is passed [onNavigate] itself (not a single-purpose callback like
 * `onProceedClick`) — its own `entry<FeatureBRoute> {}` block calls `navigateTo(FeatureARoute)`
 * directly, inside `feature-b`'s own source, demonstrating pattern (ii): see
 * `FeatureBNavEntries.kt`'s kdoc.
 *
 * `entry<T>` is not imported here — see `feature-a`'s `FeatureANavEntries.kt` kdoc for why: it's a
 * member function of `EntryProviderScope`, resolved via this function's own extension receiver.
 */
private fun EntryProviderScope<NavKey>.appNavEntries(onNavigate: (NavKey) -> Unit) {
    entry<HomeRoute> {
        HomeScreen(onGoToFeatureAClick = { onNavigate(FeatureARoute) })
    }
    featureANavEntries(onProceedClick = { onNavigate(FeatureBRoute) })
    featureBNavEntries(navigateTo = onNavigate)
}

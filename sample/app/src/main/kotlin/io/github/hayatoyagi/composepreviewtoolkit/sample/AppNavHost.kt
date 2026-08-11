package io.github.hayatoyagi.composepreviewtoolkit.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * App-level Nav3 host. Mirrors medimo-android's real wiring shape (see the Phase 2 design doc's
 * Context section): the `navigateTo(FeatureBRoute)` call for feature-a's "proceed" action is
 * written *here*, at the app level, passed into [featureANavEntries] as the `onProceedClick`
 * callback argument — not inside feature-a's own `entry<FeatureARoute> {}` block.
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
            featureBNavEntries()
        },
    )
}

@Composable
private fun HomeScreen(onGoToFeatureAClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Hello from compose-preview-toolkit!",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGoToFeatureAClick) {
                Text("Go to Feature A")
            }
        }
    }
}

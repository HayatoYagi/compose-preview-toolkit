package io.github.hayatoyagi.composepreviewtoolkit.sample.featurea

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * Wires [FeatureARoute] into an `entryProvider<NavKey> { ... }` builder.
 *
 * The actual `navigateTo(...)` call for "proceed to feature-b" deliberately does NOT live inside
 * this `entry<FeatureARoute> {}` block — it's passed in as [onProceedClick] and called from
 * `sample/app`'s own nav host instead, a common real-world wiring shape: a feature module's
 * `xNavEntries(...)` function takes callback parameters for its outgoing navigation, and the
 * app-level `AppNavHost` is what actually writes `navigateTo(FeatureBRoute)`.
 *
 * `entry<T>` is intentionally NOT imported: unlike a normal top-level extension function, Nav3
 * declares it as a member function *inside* `EntryProviderScope` itself (`public fun <K : T>
 * EntryProviderScope<T>.entry(...)`), so it's only resolvable via an `EntryProviderScope<T>`
 * receiver already in scope (exactly what this function's own extension receiver provides) — an
 * `import androidx.navigation3.runtime.entry` is a real compile error ("Unresolved reference"),
 * confirmed against the actual published `navigation3-runtime` artifact, not just guessed.
 */
fun EntryProviderScope<NavKey>.featureANavEntries(onProceedClick: () -> Unit) {
    entry<FeatureARoute> { FeatureAScreen(onProceedClick) }
}

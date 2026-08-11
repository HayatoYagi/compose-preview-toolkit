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
}

package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

/**
 * Default value for `NavNodeScanner`'s `entryFunctionNames`, matching Nav3's own `entry<T> {}`.
 * Plain constants, kept in this module (not alongside the scanners in `nav-graph-psi-analyzer`)
 * so `ComposePreviewToolkitNavGraphPlugin` can use them for its extension conventions without
 * pulling `nav-graph-psi-analyzer`'s `kotlin-compiler-embeddable` dependency onto its own regular
 * runtime classpath.
 */
val DEFAULT_ENTRY_FUNCTION_NAMES = setOf("entry")

/**
 * Default bound for `NavEdgeScanner`'s breadth-first call-graph traversal. Chosen empirically:
 * against the real `sample/feature-a`/`sample/feature-b` wiring shape (a callback threaded one
 * level from a feature module's `xNavEntries(...)` up to the app's `NavHost`, matching real
 * medimo-android code), the *shortest* discoverable path from an `entry<X> {}` block to its
 * `navigateTo(...)` call resolves at depth 1 in practice — the algorithm here finds a call's
 * argument-as-value-reference (e.g. `Button(onClick = onProceedClick)`) at the same "hop" as the
 * call itself, so a single parameter round-trip (find the wiring function's own call site, look
 * at what was bound there) is usually enough. Deeper chains only occur when the same callback is
 * threaded through *multiple* intermediate composables before finally being invoked (e.g. a
 * screen composable that forwards `onProceedClick` one level further into a child composable
 * before it's finally referenced) — each such pass-through costs exactly one extra hop. 4 leaves
 * headroom for two or three such extra layers without the search needing to be re-tuned per app;
 * unlike an unbounded search, raising this is a per-project-size cost (a wider breadth-first
 * search), not a combinatorial blow-up, since simple-name-keyed lookups keep each hop O(1)-ish.
 */
const val DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH = 4

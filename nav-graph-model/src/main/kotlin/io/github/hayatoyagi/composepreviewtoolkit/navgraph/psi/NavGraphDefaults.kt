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
 * Default bound for `NavEdgeScanner`'s breadth-first call-graph traversal. Chosen empirically for
 * `NavEdgeScanner`'s current algorithm: NavBackStack-*mutation* tracking, which anchors on the
 * real `backStack.add(...)`/`addAll(...)` call and traces every hop needed to reach it — unlike
 * the retired declared-callback-type detection this replaced, it has no shortcut that lets it stop
 * early at a call whose own declared parameter type merely *looks* route-shaped (e.g.
 * `onEntryTap: (DetailRoute) -> Unit`, invoked as `onEntryTap(DetailRoute(id))`); it must keep
 * tracing through however many further indirection layers separate that call from the real
 * mutation. This value was previously tuned for the retired algorithm's typical hop count (a
 * single parameter round-trip was usually enough there); a real production regression - a
 * previously-found edge going missing after the switch to mutation tracking - showed that hop
 * count no longer applies, and this was recalibrated the same way the original value was: against
 * a real-world-shaped case, plus headroom for a couple more layers.
 *
 * A callback threaded through a project's UI layer to a concrete route-constructor call costs two
 * kinds of hop before reaching the mutation: once for each intermediate composable the callback is
 * threaded through *unchanged* on its way to being invoked (this algorithm has to both discover
 * where it's finally invoked, and then separately retrace how it was bound at each of those same
 * layers - two hops per layer, not one, since - unlike the retired algorithm - there is no way to
 * know in advance that a given parameter pass-through is where the callback will be invoked, only
 * that it might be), plus a further hop for each distinct closure the callback's real binding
 * turns out to route through before reaching `backStack.add(...)` (e.g. a UI-level callback bound,
 * at its wiring site, to a small `{ destination -> navigateTo(destination) }` pass-through, which
 * itself is a *second* route-carrying closure whose own binding - the app-root `navigateTo` local
 * val - must also be traced). A case built to mirror this real shape (see
 * `NavEdgeScannerTest`'s `... needs the recalibrated depth bound - real-world regression repro`
 * test: a callback threaded through 3 nested UI-component layers, invoked with a concrete route
 * argument, resolved back through exactly one such chained pass-through closure) needs depth 8 to
 * resolve at all. 12 leaves headroom for a couple more such UI-component layers (each costing 2,
 * not 1, hence the larger jump than the original headroom) without the search needing to be
 * re-tuned per app; unlike an unbounded search, raising this is a per-project-size cost (a wider
 * breadth-first search), not a combinatorial blow-up, since simple-name-keyed lookups keep each
 * hop O(1)-ish.
 */
const val DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH = 12

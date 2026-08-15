package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

/**
 * A single navigation edge discovered by `nav-graph-psi-analyzer`'s `NavEdgeScanner`: starting
 * from [sourceRouteQualifiedName]'s `entry<X> { ... }` registration, a bounded-depth call-graph
 * search found a reachable call mutating the app's tracked `NavBackStack` whose route argument
 * resolved to [targetRouteQualifiedName].
 *
 * Like [NavNode], this is a *syntactic*, best-effort result — no type resolution is involved, so
 * an edge here means "found by name-based analysis", not "verified by the compiler".
 *
 * See [NavNode]'s kdoc for why this type lives in its own module.
 */
data class NavEdge(
    val sourceRouteQualifiedName: String,
    val targetRouteQualifiedName: String,
)

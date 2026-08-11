package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

/**
 * A single navigation edge discovered by [NavEdgeScanner]: starting from [sourceRouteQualifiedName]'s
 * `entry<X> { ... }` registration, a bounded-depth call-graph search (see [NavEdgeScanner]'s kdoc)
 * found a `navigateTo`/`navigate`-shaped call reachable whose first argument resolved to
 * [targetRouteQualifiedName].
 *
 * Like [NavNode], this is a *syntactic*, best-effort result — no type resolution is involved, so
 * an edge here means "found by name-based analysis", not "verified by the compiler".
 */
data class NavEdge(
    val sourceRouteQualifiedName: String,
    val targetRouteQualifiedName: String,
)

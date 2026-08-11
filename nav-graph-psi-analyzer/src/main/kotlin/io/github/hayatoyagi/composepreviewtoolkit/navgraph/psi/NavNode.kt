package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

/**
 * A single Navigation3 route discovered via an `entry<X> { ... }` registration (see
 * [NavNodeScanner]). Mirrors the shape of KSP's `KSClassDeclaration.qualifiedName` reporting for
 * a nested declaration: [qualifiedName] is the package-qualified, dot-joined outer-to-inner
 * simple-name chain (e.g. `com.example.ConsultRoute.Detail` for a route nested inside a sealed
 * hierarchy), not necessarily the route's own file location.
 */
data class NavNode(
    val packageName: String,
    val simpleName: String,
    val qualifiedName: String,
)

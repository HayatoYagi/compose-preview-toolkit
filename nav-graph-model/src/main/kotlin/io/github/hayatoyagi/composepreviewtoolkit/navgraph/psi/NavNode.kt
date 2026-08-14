package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

/**
 * A single Navigation3 route discovered via an `entry<X> { ... }` registration (see
 * `nav-graph-psi-analyzer`'s `NavNodeScanner`). Mirrors the shape of KSP's
 * `KSClassDeclaration.qualifiedName` reporting for a nested declaration: [qualifiedName] is the
 * package-qualified, dot-joined outer-to-inner simple-name chain (e.g.
 * `com.example.ConsultRoute.Detail` for a route nested inside a sealed hierarchy), not necessarily
 * the route's own file location.
 *
 * [filePath]/[line] locate the `entry<X> { ... }` registration call site itself (not the bare
 * route declaration) — see `nav-graph-psi-analyzer`'s `EntryRegistrations.kt`'s `locateCallSite`
 * for how this is computed. [filePath] is always a short, human-readable path, never an absolute
 * machine-specific one: [filePathIsRepoRelative] tells callers whether it's relative to the git
 * repository root (and therefore safe to build a GitHub blob URL from, see
 * `nav-graph-gradle-plugin`'s `buildSourceLink`) or is only a best-effort fallback (relative to
 * some other base directory, or — for a synthetic/in-memory `KtFile` with no real file backing
 * it, e.g. in a unit test — just the raw name PSI was given).
 *
 * Deliberately in its own module, separate from `nav-graph-psi-analyzer`: this is a plain data
 * class with no Kotlin-compiler dependency, so code that only needs to read/pass around results
 * (like `nav-graph-gradle-plugin`'s own downstream gallery-site rendering) can depend on this
 * module alone, without pulling in `kotlin-compiler-embeddable`.
 */
data class NavNode(
    val packageName: String,
    val simpleName: String,
    val qualifiedName: String,
    val filePath: String = "",
    val line: Int = 0,
    val filePathIsRepoRelative: Boolean = false,
)

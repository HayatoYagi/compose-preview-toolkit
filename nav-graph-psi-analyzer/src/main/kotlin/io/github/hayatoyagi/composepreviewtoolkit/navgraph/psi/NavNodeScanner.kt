package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/** Default value for [NavNodeScanner]'s `entryFunctionNames`, matching Nav3's own `entry<T> {}`. */
val DEFAULT_ENTRY_FUNCTION_NAMES = setOf("entry")

/**
 * Scans a set of already-parsed [KtFile]s for Navigation3 route registrations shaped like
 * `entry<Route> { ... }`, and produces one [NavNode] per unique route found.
 *
 * This is intentionally a *syntactic* (no type resolution) scan: a [org.jetbrains.kotlin.psi.KtCallExpression]
 * counts as an entry registration when its callee simple name is in [entryFunctionNames], it has
 * exactly one type argument, and it has a trailing lambda argument — matching Nav3's
 * `fun <T : NavKey> EntryProviderScope<NavKey>.entry(...) { ... }` call shape. Because `entry<T>`'s
 * type bound already guarantees `T : NavKey` at compile time, independently verifying that the
 * resolved type is a NavKey implementation is not necessary here.
 *
 * The type argument's declaration is resolved by simple-name + package match against the supplied
 * [KtFile]s (deliberately text-based, not full semantic resolution). Routes nested inside a
 * `sealed interface`/`sealed class` (Nav3's common pattern) are supported: the type argument is
 * written as a dotted chain (e.g. `ParentRoute.Detail`), and the resulting [NavNode.qualifiedName]
 * reflects the real nesting the same way `KSClassDeclaration.qualifiedName` would report it.
 *
 * The actual entry-registration-shape detection lives in [findEntryRegistrations], shared with
 * [NavEdgeScanner] (Step B/C's call-graph reachability analysis builds on the exact same node
 * extraction, using each registration's lambda body as a depth-0 search root) so the two scanners
 * never disagree about what counts as an `entry<X> {}` registration.
 */
class NavNodeScanner(
    private val entryFunctionNames: Set<String> = DEFAULT_ENTRY_FUNCTION_NAMES,
) {
    /**
     * [fallbackBaseDirectory] is only consulted for [NavNode.filePath] when the git repository
     * root can't be determined (or a file turns out not to be under it) — see
     * `EntryRegistrations.kt`'s `locateCallSite`. Typically the owning Gradle module's project
     * directory; defaults to the JVM process's own working directory, which is a reasonable
     * fallback base outside of a Gradle-wired caller too (e.g. a plain command-line invocation).
     */
    fun scan(
        files: List<KtFile>,
        fallbackBaseDirectory: File = File("."),
    ): List<NavNode> {
        val nodesByQualifiedName = LinkedHashMap<String, NavNode>()
        findEntryRegistrations(files, entryFunctionNames, fallbackBaseDirectory).forEach { registration ->
            nodesByQualifiedName[registration.node.qualifiedName] = registration.node
        }
        return nodesByQualifiedName.values.toList()
    }
}

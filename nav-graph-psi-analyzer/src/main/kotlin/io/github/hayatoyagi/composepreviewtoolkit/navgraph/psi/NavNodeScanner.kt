package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType

/** Default value for [NavNodeScanner]'s `entryFunctionNames`, matching Nav3's own `entry<T> {}`. */
val DEFAULT_ENTRY_FUNCTION_NAMES = setOf("entry")

/**
 * Scans a set of already-parsed [KtFile]s for Navigation3 route registrations shaped like
 * `entry<Route> { ... }`, and produces one [NavNode] per unique route found.
 *
 * This is intentionally a *syntactic* (no type resolution) scan: a [KtCallExpression] counts as an
 * entry registration when its callee simple name is in [entryFunctionNames], it has exactly one
 * type argument, and it has a trailing lambda argument — matching Nav3's
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
 * Call-graph construction and edge/reachability detection between routes are not performed by
 * this scanner, which only extracts nodes.
 */
class NavNodeScanner(
    private val entryFunctionNames: Set<String> = DEFAULT_ENTRY_FUNCTION_NAMES,
) {
    fun scan(files: List<KtFile>): List<NavNode> {
        val declarationsBySimpleName: Map<String, List<KtClassOrObject>> =
            files
                .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java) }
                .filter { it.name != null }
                .groupBy { it.name!! }

        val nodesByQualifiedName = LinkedHashMap<String, NavNode>()

        files.forEach { file ->
            PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
                .filter { it.isEntryRegistration() }
                .forEach { call ->
                    val node = call.toNavNode(declarationsBySimpleName)
                    if (node != null) {
                        nodesByQualifiedName[node.qualifiedName] = node
                    }
                }
        }

        return nodesByQualifiedName.values.toList()
    }

    private fun KtCallExpression.isEntryRegistration(): Boolean {
        val calleeName = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        val typeArguments = typeArgumentList?.arguments.orEmpty()
        return calleeName in entryFunctionNames &&
            typeArguments.size == 1 &&
            lambdaArguments.isNotEmpty()
    }

    private fun KtCallExpression.toNavNode(declarationsBySimpleName: Map<String, List<KtClassOrObject>>): NavNode? {
        val typeReference = typeArgumentList?.arguments?.singleOrNull()?.typeReference ?: return null
        val writtenChain = typeReference.typeElement.userTypeChain()
        if (writtenChain.isEmpty()) return null

        val simpleName = writtenChain.last()
        val resolved = resolveDeclaration(writtenChain, declarationsBySimpleName)

        val qualifiedName =
            if (resolved != null) {
                val packageName = resolved.containingKtFile.packageFqName.asString()
                val actualChain = resolved.outerToInnerNameChain()
                joinQualifiedName(packageName, actualChain)
            } else {
                // Best-effort fallback when the route's declaration isn't among the scanned files
                // (e.g. it lives in a dependency module not passed into this scan): assume it's
                // declared in the same package as the call site and use the chain as written.
                val packageName = containingKtFile.packageFqName.asString()
                joinQualifiedName(packageName, writtenChain)
            }

        return NavNode(
            packageName = resolved?.containingKtFile?.packageFqName?.asString()
                ?: containingKtFile.packageFqName.asString(),
            simpleName = simpleName,
            qualifiedName = qualifiedName,
        )
    }

    /** Finds the [KtClassOrObject] whose outer-to-inner name chain ends with [writtenChain]. */
    private fun resolveDeclaration(
        writtenChain: List<String>,
        declarationsBySimpleName: Map<String, List<KtClassOrObject>>,
    ): KtClassOrObject? {
        val candidates = declarationsBySimpleName[writtenChain.last()].orEmpty()
        return candidates.firstOrNull { candidate ->
            candidate.outerToInnerNameChain().takeLast(writtenChain.size) == writtenChain
        }
    }

    private fun KtClassOrObject.outerToInnerNameChain(): List<String> {
        val chain = mutableListOf<String>()
        var current: KtClassOrObject? = this
        while (current != null) {
            chain.add(0, current.name.orEmpty())
            current = PsiTreeUtil.getParentOfType(current, KtClassOrObject::class.java, true)
        }
        return chain
    }

    private fun joinQualifiedName(
        packageName: String,
        chain: List<String>,
    ): String = (listOfNotNull(packageName.takeIf { it.isNotEmpty() }) + chain).joinToString(".")

    /** Reads a possibly-nested `Foo.Bar.Baz` user type reference as `["Foo", "Bar", "Baz"]`. */
    private fun org.jetbrains.kotlin.psi.KtTypeElement?.userTypeChain(): List<String> {
        var current = this as? KtUserType ?: return emptyList()
        val chain = mutableListOf<String>()
        while (true) {
            val name = current.referencedName ?: return emptyList()
            chain.add(0, name)
            current = current.qualifier ?: break
        }
        return chain
    }
}

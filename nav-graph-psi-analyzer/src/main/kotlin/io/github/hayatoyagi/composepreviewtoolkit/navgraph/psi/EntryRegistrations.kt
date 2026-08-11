package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType

/**
 * One `entry<X> { ... }` registration found while scanning a project's [KtFile]s: [node] is
 * exactly what [NavNodeScanner] reports, and [call] is the underlying [KtCallExpression] itself —
 * kept around so [NavEdgeScanner] can use the registration's trailing lambda body as a call-graph
 * search root (Step C's depth-0 root) without re-implementing the entry-registration-shape
 * detection this file already owns.
 */
internal data class EntryRegistration(
    val node: NavNode,
    val call: KtCallExpression,
)

/**
 * Shared implementation behind both [NavNodeScanner] (which only needs [EntryRegistration.node])
 * and [NavEdgeScanner] (which also needs [EntryRegistration.call] as a BFS root). See
 * [NavNodeScanner]'s kdoc for the detection shape this implements — moved here, unchanged, so it
 * has exactly one owner instead of being duplicated across the two scanners.
 */
internal fun findEntryRegistrations(
    files: List<KtFile>,
    entryFunctionNames: Set<String>,
): List<EntryRegistration> {
    val declarationsBySimpleName: Map<String, List<KtClassOrObject>> =
        files
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java) }
            .filter { it.name != null }
            .groupBy { it.name!! }

    val registrations = mutableListOf<EntryRegistration>()

    files.forEach { file ->
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .filter { it.isEntryRegistration(entryFunctionNames) }
            .forEach { call ->
                val node = call.toNavNode(declarationsBySimpleName)
                if (node != null) {
                    registrations.add(EntryRegistration(node, call))
                }
            }
    }

    return registrations
}

private fun KtCallExpression.isEntryRegistration(entryFunctionNames: Set<String>): Boolean {
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

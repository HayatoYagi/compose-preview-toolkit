package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import java.io.File

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
    fallbackBaseDirectory: File = File("."),
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
                val node = call.toNavNode(declarationsBySimpleName, fallbackBaseDirectory)
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

private fun KtCallExpression.toNavNode(
    declarationsBySimpleName: Map<String, List<KtClassOrObject>>,
    fallbackBaseDirectory: File,
): NavNode? {
    val typeReference = typeArgumentList?.arguments?.singleOrNull()?.typeReference ?: return null
    val writtenChain = typeReference.typeElement.userTypeChain()
    if (writtenChain.isEmpty()) return null

    val simpleName = writtenChain.last()
    val resolved = resolveDeclaration(writtenChain, declarationsBySimpleName)
        ?: throw IllegalStateException(unresolvedDeclarationMessage(writtenChain, fallbackBaseDirectory))

    val packageName = resolved.containingKtFile.packageFqName.asString()
    val qualifiedName = joinQualifiedName(packageName, resolved.outerToInnerNameChain())

    // The route's own declaration site (resolved above) is deliberately NOT where this points —
    // per this feature's design, the useful link for a reviewer is the entry<X> {} registration
    // call site itself (this KtCallExpression, i.e. where the screen composable is actually
    // invoked), not the bare class/object declaration.
    val location = locateCallSite(this, fallbackBaseDirectory)

    return NavNode(
        packageName = packageName,
        simpleName = simpleName,
        qualifiedName = qualifiedName,
        filePath = location.filePath,
        line = location.line,
        filePathIsRepoRelative = location.filePathIsRepoRelative,
    )
}

/**
 * There's deliberately no best-effort fallback here (e.g. guessing the route shares the call
 * site's package) for a route whose declaration can't be found among [files][findEntryRegistrations]
 * scanned: a wrong-but-plausible guess is worse than a loud failure for something this checkable —
 * matches this toolkit's stated philosophy of failing when the scanner can't determine something
 * real rather than silently producing a misleading result. In practice this should be rare: a
 * caller with proper scan scope (e.g. `nav-graph-gradle-plugin`'s `generateDebugNavGraphSite`,
 * whose scan automatically covers the full resolved project-dependency graph) will always have
 * the declaration in view unless the route type genuinely comes from outside that graph — a real
 * configuration problem worth surfacing, not papering over.
 */
private fun KtCallExpression.unresolvedDeclarationMessage(
    writtenChain: List<String>,
    fallbackBaseDirectory: File,
): String {
    val location = locateCallSite(this, fallbackBaseDirectory)
    val routeName = writtenChain.joinToString(".")
    return "compose-preview-toolkit nav-graph: could not resolve the declaration of route \"$routeName\" " +
        "registered via entry<$routeName> { ... } at ${location.filePath}:${location.line}. Its declaration " +
        "wasn't found among the scanned Kotlin sources. If it's declared in a different Gradle module, make " +
        "sure that module is actually reachable from this scan (a project dependency of whatever module is " +
        "being scanned) — this is otherwise unrecoverable rather than guessed at."
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

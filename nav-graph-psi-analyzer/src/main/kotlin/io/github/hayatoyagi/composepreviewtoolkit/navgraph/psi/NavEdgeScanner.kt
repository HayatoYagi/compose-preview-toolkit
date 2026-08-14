package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.ValueArgument

/**
 * The result of [NavEdgeScanner.scan]: [edges] is the best-effort, deduplicated list of detected
 * navigation edges; [warnings] is every case the scan gave up on rather than risk guessing wrong —
 * ambiguous callee names, call-graph traversal exceeding [NavEdgeScanner]'s configured depth
 * bound, and bound-argument expressions the traversal couldn't make sense of. Exposed as data
 * (not just logged) specifically so tests can assert on *why* an edge was or wasn't found: none of
 * these cases should ever throw and fail the whole scan.
 */
data class NavEdgeScanResult(
    val edges: List<NavEdge>,
    val warnings: List<String>,
)

/**
 * Scans a set of already-parsed [KtFile]s for Navigation3 navigation edges between routes found by
 * (the same detection as) [NavNodeScanner], via call-graph construction followed by a
 * bounded-depth reachability search.
 *
 * ## Algorithm
 *
 * For every `entry<X> { ... }` registration (found via [findEntryRegistrations], shared with
 * [NavNodeScanner]), this performs a breadth-first search over a call graph built *lazily* while
 * traversing — there is no separate up-front "build the whole call graph" pass, since the
 * interesting part of the graph (which caller supplied which argument to which parameter) can only
 * be discovered by looking at call sites as they become relevant to the search, not ahead of time.
 * Each BFS step processes a "search context": a PSI subtree to scan (initially the entry
 * registration's trailing lambda body) plus the set of the *enclosing* declaration's function-typed
 * parameter names that are lexically live at that point (its "closure environment").
 *
 * Within a context's subtree, every name reference is classified once, in priority order:
 *
 * 1. **Navigate call by name**: the reference is a call's callee and its simple name is in
 *    [navigateCallNames] → inspect the call's first argument; if it matches a known route by
 *    simple name (a bare object reference or a constructor call), emit an edge. Terminal — not
 *    traversed further.
 * 2. **Parameter reference**: the reference's simple name matches a function-typed parameter that
 *    is live in the current context — *regardless* of whether the reference is itself a call's
 *    callee (`onClick()`) or merely passed along as a value to some other call
 *    (`Button(onClick = onProceedClick)`), since from a reachability standpoint both mean
 *    "whatever gets bound to this parameter runs from here". This triggers the reverse/virtual
 *    edge: every call site of the *enclosing* declaration is found project-wide, the expression
 *    actually bound to that parameter at each call site is resolved (by argument name if named,
 *    else positionally), and search continues from there at depth + 1. The bound expression may
 *    itself be another bare parameter reference (a pass-through, e.g. a wiring function that just
 *    forwards its own parameter under the same name one level up) — that case recurses through
 *    this same handling again, costing one further hop each time, which is exactly how this
 *    module resolves `sample/feature-a`'s callback-threaded pattern without any special-casing
 *    (see class kdoc).
 *
 *    When such a reference is also itself a call's callee (e.g. `onProceedClick(FeatureBRoute)`),
 *    it's additionally checked as a **navigate call by declared type**: if the parameter's
 *    function-type signature, as written (e.g. `(NavKey) -> Unit`), has a parameter type whose
 *    simple name is `NavKey` or one of the routes found by [NavNodeScanner], this call site is
 *    treated as a terminal navigate edge exactly like case 1 — same argument resolution, run
 *    alongside the reachability threading above rather than replacing it. Purely syntactic: a
 *    parameter with no explicit type annotation is never matched this way.
 * 3. **Known function call**: the reference is a call's callee and its simple name resolves
 *    unambiguously to another parsed [KtNamedFunction] → continue the search from that function's
 *    body at depth + 1, with that function's *own* function-typed parameters as the new live set
 *    (Kotlin scoping: a callee's parameters are not the caller's).
 * 4. Otherwise → ignored. This is the common case (framework/library calls like `Button`, `Column`,
 *    `println` with no matching declaration among the parsed files) and is expected, not an error.
 *
 * A context is only ever processed once per entry registration (tracked by referential identity),
 * which is what guarantees termination in the presence of cycles (mutually recursive functions) —
 * BFS order means the first time a given PSI subtree is reached is always via a shortest path, so
 * revisiting it later via a longer path is always safe to skip.
 *
 * Callee-name resolution is deliberately simple-name-based with no type resolution (per the design
 * doc's explicit choice): a same-package match is preferred, then an explicit import match: if
 * still ambiguous, the call is dropped with a warning rather than guessed. The same applies to
 * matching a `navigateTo(...)` call's first argument against the route registry. `NavKey` is the
 * one type name matched literally throughout — it's `androidx.navigation3.runtime.NavKey`, a fixed
 * library API, not a project-specific naming convention.
 */
class NavEdgeScanner(
    private val entryFunctionNames: Set<String> = DEFAULT_ENTRY_FUNCTION_NAMES,
    private val navigateCallNames: Set<String> = DEFAULT_NAVIGATE_CALL_NAMES,
    private val callGraphResolutionDepth: Int = DEFAULT_CALL_GRAPH_RESOLUTION_DEPTH,
) {
    fun scan(files: List<KtFile>): NavEdgeScanResult {
        val warnings = mutableListOf<String>()
        val entryRegistrations = findEntryRegistrations(files, entryFunctionNames)
        if (entryRegistrations.isEmpty()) return NavEdgeScanResult(emptyList(), warnings)

        val functionsBySimpleName: Map<String, List<KtNamedFunction>> =
            files
                .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java) }
                .filter { it.name != null }
                .groupBy { it.name!! }

        val callsBySimpleName: Map<String, List<KtCallExpression>> =
            files
                .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java) }
                .mapNotNull { call -> call.calleeSimpleNameOrNull()?.let { it to call } }
                .groupBy({ it.first }, { it.second })

        val routesBySimpleName: Map<String, List<NavNode>> =
            entryRegistrations
                .map { it.node }
                .distinctBy { it.qualifiedName }
                .groupBy { it.simpleName }

        val resolver = CalleeResolver(functionsBySimpleName, callsBySimpleName, warnings)

        val edges = LinkedHashSet<NavEdge>()
        entryRegistrations.forEach { registration ->
            val lambdaBody = registration.call.entryLambdaBody() ?: return@forEach
            val owningFunction = PsiTreeUtil.getParentOfType(registration.call, KtNamedFunction::class.java)
            val traversal = CallGraphTraversal(
                sourceRoute = registration.node.qualifiedName,
                navigateCallNames = navigateCallNames,
                routesBySimpleName = routesBySimpleName,
                resolver = resolver,
                maxDepth = callGraphResolutionDepth,
                warnings = warnings,
            )
            edges += traversal.run(lambdaBody, owningFunction)
        }

        return NavEdgeScanResult(edges.toList(), warnings.distinct())
    }

    private fun KtCallExpression.entryLambdaBody(): KtExpression? =
        lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression

    private fun KtCallExpression.calleeSimpleNameOrNull(): String? =
        (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
}

/** Resolves a simple callee name to a specific [KtNamedFunction], or drops it (with a warning) if ambiguous. */
private class CalleeResolver(
    private val functionsBySimpleName: Map<String, List<KtNamedFunction>>,
    private val callsBySimpleName: Map<String, List<KtCallExpression>>,
    private val warnings: MutableList<String>,
) {
    /**
     * Resolves [name] as referenced from [fromFile]. Returns `null` when [name] doesn't match any
     * parsed function at all (the expected, non-error case for framework/library calls), or when it
     * matches more than one candidate that can't be disambiguated (same-package match, then import
     * match) — the latter logs a warning to [warnings] since it's a real "gave up" case.
     */
    fun resolveFunction(
        name: String,
        fromFile: KtFile,
        atElement: PsiElement,
    ): KtNamedFunction? {
        val candidates = functionsBySimpleName[name].orEmpty()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()

        val samePackage = candidates.filter { it.containingKtFile.packageFqName == fromFile.packageFqName }
        if (samePackage.size == 1) return samePackage.single()

        val importedFqNames = fromFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val imported = candidates.filter { it.qualifiedCallableName() in importedFqNames }
        if (imported.size == 1) return imported.single()

        warnings += "ambiguous callee \"$name\" (${candidates.size} candidates) at ${atElement.location()}"
        return null
    }

    /** Every call site project-wide that unambiguously resolves to [function]. */
    fun findCallSites(function: KtNamedFunction): List<KtCallExpression> {
        val name = function.name ?: return emptyList()
        return callsBySimpleName[name].orEmpty().filter { call ->
            resolveFunction(name, call.containingKtFile, call) === function
        }
    }
}

private fun KtNamedFunction.qualifiedCallableName(): String {
    val packageName = containingKtFile.packageFqName.asString()
    val simpleName = name.orEmpty()
    return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
}

/**
 * The function's body as a searchable [KtExpression]: [KtNamedFunction.getBodyExpression] only
 * returns non-null for `fun f() = expr` single-expression bodies — a `{ ... }` block body is
 * exposed separately via [KtNamedFunction.getBodyBlockExpression], which this prefers when
 * present since almost every real-world function (including every function in this module's own
 * fixtures and the real sample) uses a block body.
 */
private fun KtNamedFunction.searchableBody(): KtExpression? = bodyBlockExpression ?: bodyExpression

private fun KtNamedFunction.functionTypedParamNames(): Set<String> =
    valueParameters.filter { it.isFunctionTyped() }.mapNotNull { it.name }.toSet()

private fun KtParameter.isFunctionTyped(): Boolean = declaredFunctionTypeOrNull() != null

/** [KtParameter.typeReference], as written, unwrapped past `?`, if it's a function type. */
private fun KtParameter.declaredFunctionTypeOrNull(): KtFunctionType? {
    var typeElement = typeReference?.typeElement
    while (typeElement is KtNullableType) {
        typeElement = typeElement.innerType
    }
    return typeElement as? KtFunctionType
}

/** The simple name of a declared type reference (e.g. `NavKey` from `NavKey` or `NavKey?`), if any. */
private fun KtTypeReference.declaredSimpleTypeNameOrNull(): String? {
    var typeElement = this.typeElement
    while (typeElement is KtNullableType) {
        typeElement = typeElement.innerType
    }
    return (typeElement as? KtUserType)?.referencedName
}

/** One BFS step: a PSI subtree to scan, at what depth, with which function-typed parameter names are live. */
private data class SearchContext(
    val root: KtExpression,
    val depth: Int,
    val liveParams: Set<String>,
    val owningFunction: KtNamedFunction?,
)

/**
 * A single entry registration's bounded-depth reachability search (Step C). One instance per
 * `entry<X> {}` registration — the `visited` set is deliberately *not* shared across different
 * routes' searches, since two unrelated routes legitimately reaching the same shared helper
 * function must each independently be able to discover edges through it.
 */
private class CallGraphTraversal(
    private val sourceRoute: String,
    private val navigateCallNames: Set<String>,
    private val routesBySimpleName: Map<String, List<NavNode>>,
    private val resolver: CalleeResolver,
    private val maxDepth: Int,
    private val warnings: MutableList<String>,
) {
    private val visited = HashSet<KtExpression>()

    fun run(
        rootBody: KtExpression,
        owningFunction: KtNamedFunction?,
    ): List<NavEdge> {
        val edges = mutableListOf<NavEdge>()
        val queue = ArrayDeque<SearchContext>()
        queue.add(SearchContext(rootBody, 0, owningFunction?.functionTypedParamNames().orEmpty(), owningFunction))
        while (queue.isNotEmpty()) {
            val context = queue.removeFirst()
            if (!visited.add(context.root)) continue
            edges += processContext(context, queue)
        }
        return edges
    }

    private fun processContext(
        context: SearchContext,
        queue: ArrayDeque<SearchContext>,
    ): List<NavEdge> {
        val edges = mutableListOf<NavEdge>()
        PsiTreeUtil.findChildrenOfType(context.root, KtNameReferenceExpression::class.java).forEach { ref ->
            val name = ref.getReferencedName()
            val enclosingCall = ref.parent as? KtCallExpression
            val isCallee = enclosingCall?.calleeExpression === ref
            when {
                isCallee && name in navigateCallNames -> handleNavigateCall(enclosingCall, edges)
                context.owningFunction != null && name in context.liveParams -> {
                    if (isCallee) {
                        handleTypedParameterInvocation(name, context.owningFunction, enclosingCall, edges)
                    }
                    expandParameterInvocation(name, context.owningFunction, context.depth, queue)
                }
                isCallee -> {
                    val target = resolver.resolveFunction(name, ref.containingKtFile, ref) ?: return@forEach
                    enqueueFunctionBody(target, context.depth, queue)
                }
                else -> Unit
            }
        }
        return edges
    }

    private fun enqueueFunctionBody(
        function: KtNamedFunction,
        foundAtDepth: Int,
        queue: ArrayDeque<SearchContext>,
    ) {
        val newDepth = foundAtDepth + 1
        if (newDepth > maxDepth) {
            warnings += "nav edge candidate unresolved beyond depth $maxDepth from route \"$sourceRoute\" " +
                "at ${function.location()}"
            return
        }
        val body = function.searchableBody() ?: return
        queue.add(SearchContext(body, newDepth, function.functionTypedParamNames(), function))
    }

    private fun expandParameterInvocation(
        paramName: String,
        owningFunction: KtNamedFunction,
        foundAtDepth: Int,
        queue: ArrayDeque<SearchContext>,
    ) {
        val newDepth = foundAtDepth + 1
        if (newDepth > maxDepth) {
            warnings += "nav edge candidate unresolved beyond depth $maxDepth from route \"$sourceRoute\" " +
                "resolving parameter \"$paramName\" of ${owningFunction.location()}"
            return
        }
        val callSites = resolver.findCallSites(owningFunction)
        callSites.forEach { callSite ->
            val boundExpression = boundArgumentExpression(callSite, owningFunction, paramName) ?: return@forEach
            resolveBoundExpression(boundExpression, newDepth, queue)
        }
    }

    private fun resolveBoundExpression(
        expression: KtExpression,
        depth: Int,
        queue: ArrayDeque<SearchContext>,
    ) {
        when (expression) {
            is KtLambdaExpression -> {
                val body = expression.bodyExpression ?: return
                val closureOwner = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                queue.add(SearchContext(body, depth, closureOwner?.functionTypedParamNames().orEmpty(), closureOwner))
            }
            is KtCallableReferenceExpression -> {
                val refName = expression.callableReference.getReferencedName()
                val target = resolver.resolveFunction(refName, expression.containingKtFile, expression) ?: return
                val body = target.searchableBody() ?: return
                queue.add(SearchContext(body, depth, target.functionTypedParamNames(), target))
            }
            is KtNameReferenceExpression -> {
                // A pass-through: the bound argument is itself just a reference to a function-typed
                // parameter of whatever function this call site lives in (e.g. a wiring function
                // that forwards its own callback parameter one level further up under the same
                // shape). Keep unwinding one hop at a time until we hit a real lambda/callable ref.
                val enclosingFunction = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                val refName = expression.getReferencedName()
                if (enclosingFunction != null && refName in enclosingFunction.functionTypedParamNames()) {
                    expandParameterInvocation(refName, enclosingFunction, depth, queue)
                } else {
                    warnings += "could not resolve bound argument \"$refName\" for a navigate-relevant " +
                        "parameter at ${expression.location()}"
                }
            }
            else -> {
                warnings += "could not resolve bound argument expression for a navigate-relevant " +
                    "parameter at ${expression.location()}"
            }
        }
    }

    /**
     * Checks [call] — an invocation of the live function-typed parameter [paramName] of
     * [owningFunction] — against its declared type: if that type's parameter list has a type
     * whose simple name is `NavKey` or a known route, treats [call] as a terminal navigate edge
     * (same argument resolution as [handleNavigateCall]). A no-op if the parameter has no explicit
     * function-type annotation, or that type doesn't mention a route-shaped parameter.
     */
    private fun handleTypedParameterInvocation(
        paramName: String,
        owningFunction: KtNamedFunction,
        call: KtCallExpression,
        edges: MutableList<NavEdge>,
    ) {
        val parameter = owningFunction.valueParameters.firstOrNull { it.name == paramName } ?: return
        val functionType = parameter.declaredFunctionTypeOrNull() ?: return
        val paramTypeNames = functionType.parameters.mapNotNull { it.typeReference?.declaredSimpleTypeNameOrNull() }
        val isRouteShaped = paramTypeNames.any { it == "NavKey" || it in routesBySimpleName }
        if (isRouteShaped) handleNavigateCall(call, edges)
    }

    private fun handleNavigateCall(
        call: KtCallExpression,
        edges: MutableList<NavEdge>,
    ) {
        val targetSimpleName = call.firstArgumentRouteSimpleName() ?: return
        val candidates = routesBySimpleName[targetSimpleName].orEmpty()
        val target = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.single()
            else -> {
                warnings += "ambiguous navigate target route \"$targetSimpleName\" " +
                    "(${candidates.size} candidates) at ${call.location()}"
                null
            }
        }
        if (target != null) {
            edges += NavEdge(sourceRoute, target.qualifiedName)
        }
    }
}

/** Finds the expression bound to [paramName] at [callSite], by argument name if named, else positionally. */
private fun boundArgumentExpression(
    callSite: KtCallExpression,
    function: KtNamedFunction,
    paramName: String,
): KtExpression? {
    val paramIndex = function.valueParameters.indexOfFirst { it.name == paramName }
    if (paramIndex < 0) return null

    val args: List<ValueArgument> = callSite.valueArguments
    args.firstOrNull { it.isNamed() && it.getArgumentName()?.asName?.asString() == paramName }
        ?.let { return it.getArgumentExpression() }

    var positionalIndex = 0
    args.forEach { arg ->
        if (!arg.isNamed()) {
            if (positionalIndex == paramIndex) return arg.getArgumentExpression()
            positionalIndex++
        }
    }
    return null
}

private fun KtCallExpression.firstArgumentRouteSimpleName(): String? =
    valueArguments.firstOrNull()?.getArgumentExpression()?.routeSimpleNameOrNull()

private fun KtExpression.routeSimpleNameOrNull(): String? = when (this) {
    is KtCallExpression -> (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    is KtNameReferenceExpression -> getReferencedName()
    is KtDotQualifiedExpression -> selectorExpression?.routeSimpleNameOrNull()
    else -> null
}

/** A human-readable `file:line` (or just `file` if line info isn't available) for warning messages. */
private fun PsiElement.location(): String {
    val file = containingFile
    val document = try {
        file?.viewProvider?.document
    } catch (e: Exception) {
        null
    }
    val line = document?.let { doc ->
        try {
            doc.getLineNumber(textRange.startOffset) + 1
        } catch (e: Exception) {
            null
        }
    }
    val fileName = file?.name ?: "<unknown file>"
    return if (line != null) "$fileName:$line" else fileName
}

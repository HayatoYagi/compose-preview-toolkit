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
 *    [navigateCallNames] → inspect the call's first argument, read as its own dotted reference
 *    chain (e.g. `["TodoRoute", "Detail"]` for a qualified reference/constructor call
 *    `TodoRoute.Detail`, or just `["Detail"]` for a bare one). Every chain, qualified or bare,
 *    unambiguous or not, is resolved the same way to a single canonical fully-qualified name before
 *    any route lookup happens: its root identifier is resolved to its own real qualified name via
 *    the call site's file imports if one matches, else assumed to resolve within the call site's own
 *    file package, and the rest of the chain is appended onto that. That exact fully-qualified name
 *    is then looked up among known routes - a hit is the target, unconditionally; a miss means the
 *    call is dropped with a warning rather than guessed (see
 *    [CallGraphTraversal.resolveQualifiedTarget]'s kdoc). Terminal — not traversed further.
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
 *    (Kotlin scoping: a callee's parameters are not the caller's). Refused — dropped with a
 *    warning, not traversed — when the resolved function is one of [NavEdgeScanner]'s
 *    `entryHostingFunctions` (see [NavEdgeScanner.findEntryHostingFunctions]'s kdoc): a function
 *    that hosts `entry<X> {}` registrations, directly or transitively, is nav-graph
 *    *construction*, and re-entering its body from here would pull in every sibling registration
 *    wired inside it — including their own, entirely unrelated navigate calls — as if they were
 *    reachable from whatever route's search happened to rediscover it. The same refusal applies to
 *    the equivalent callable-reference case in [resolveBoundExpression].
 * 4. Otherwise → ignored. This is the common case (framework/library calls like `Button`, `Column`,
 *    `println` with no matching declaration among the parsed files) and is expected, not an error.
 *
 * A context is only ever processed once per entry registration (tracked by referential identity),
 * which is what guarantees termination in the presence of cycles (mutually recursive functions) —
 * BFS order means the first time a given PSI subtree is reached is always via a shortest path, so
 * revisiting it later via a longer path is always safe to skip. This is a narrower guarantee than
 * it might look: it only dedupes *identical* PSI subtrees, and does nothing to stop a search from
 * *legitimately* discovering an entirely different, much larger subtree via a real call edge — that
 * is what the `entryHostingFunctions` refusal above exists to prevent.
 *
 * Callee-name resolution is deliberately simple-name-based with no type resolution (per the design
 * doc's explicit choice): a same-package match is preferred, then an explicit import match: if
 * still ambiguous, the call is dropped with a warning rather than guessed. Matching a
 * `navigateTo(...)` call's first argument against the route registry follows the same
 * type-resolution-free philosophy, but resolves the written reference to one canonical
 * fully-qualified name via import-then-same-package first (see
 * [CallGraphTraversal.resolveQualifiedTarget]'s kdoc) rather than filtering candidates by name,
 * since a suffix-based filter can't reliably tell two differently-nested routes that happen to
 * share their trailing segments apart. `NavKey` is the one type name matched literally throughout —
 * it's `androidx.navigation3.runtime.NavKey`, a fixed library API, not a project-specific naming
 * convention.
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

        val distinctRoutes = entryRegistrations.map { it.node }.distinctBy { it.qualifiedName }
        val routeSimpleNames: Set<String> = distinctRoutes.mapTo(mutableSetOf()) { it.simpleName }
        val routesByQualifiedName: Map<String, NavNode> = distinctRoutes.associateBy { it.qualifiedName }

        val resolver = CalleeResolver(functionsBySimpleName, callsBySimpleName, warnings)
        val entryHostingFunctions = findEntryHostingFunctions(entryRegistrations, resolver)

        val edges = LinkedHashSet<NavEdge>()
        entryRegistrations.forEach { registration ->
            val lambdaBody = registration.call.entryLambdaBody() ?: return@forEach
            val owningFunction = PsiTreeUtil.getParentOfType(registration.call, KtNamedFunction::class.java)
            val traversal = CallGraphTraversal(
                sourceRoute = registration.node.qualifiedName,
                navigateCallNames = navigateCallNames,
                routeSimpleNames = routeSimpleNames,
                routesByQualifiedName = routesByQualifiedName,
                resolver = resolver,
                maxDepth = callGraphResolutionDepth,
                entryHostingFunctions = entryHostingFunctions,
                warnings = warnings,
            )
            edges += traversal.run(lambdaBody, owningFunction)
        }

        return NavEdgeScanResult(edges.toList(), warnings.distinct())
    }

    /**
     * Every [KtNamedFunction] that is part of nav-graph *construction* rather than a route's own
     * business logic: a function that either directly hosts an `entry<X> {}` registration (each
     * [EntryRegistration]'s own owning function, e.g. a `featureXNavEntries` wiring function or an
     * `AppNavHost`-style aggregator with entries written inline), or that calls — even indirectly,
     * through any number of hops — a function that does. Computed as the closure of "direct hosts"
     * under "is called by", via BFS over [CalleeResolver.findCallSites].
     *
     * [CallGraphTraversal] must never re-enter one of these via a known-function-call resolution
     * (see its kdoc's case 3): doing so would pull in *every* sibling `entry<X> {}` registration
     * wired inside that function — including their own, entirely unrelated navigate calls — as if
     * they were reachable from whatever single route's search happened to re-discover it. This is
     * safe to exclude unconditionally because every entry registration already gets its own,
     * correctly-scoped traversal seeded directly at [scan]'s call site above; there is never a
     * legitimate reason for a *different* route's search to re-enter this territory.
     */
    private fun findEntryHostingFunctions(
        entryRegistrations: List<EntryRegistration>,
        resolver: CalleeResolver,
    ): Set<KtNamedFunction> {
        val directHosts = entryRegistrations
            .mapNotNull { PsiTreeUtil.getParentOfType(it.call, KtNamedFunction::class.java) }

        val hosts = LinkedHashSet<KtNamedFunction>()
        val queue = ArrayDeque(directHosts)
        while (queue.isNotEmpty()) {
            val host = queue.removeFirst()
            if (!hosts.add(host)) continue
            resolver.findCallSites(host).forEach { callSite ->
                val caller = PsiTreeUtil.getParentOfType(callSite, KtNamedFunction::class.java)
                if (caller != null) queue.add(caller)
            }
        }
        return hosts
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
    private val routeSimpleNames: Set<String>,
    private val routesByQualifiedName: Map<String, NavNode>,
    private val resolver: CalleeResolver,
    private val maxDepth: Int,
    private val entryHostingFunctions: Set<KtNamedFunction>,
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
        if (blockedAsEntryHostingFunction(function)) return
        val newDepth = foundAtDepth + 1
        if (newDepth > maxDepth) {
            warnings += "nav edge candidate unresolved beyond depth $maxDepth from route \"$sourceRoute\" " +
                "at ${function.location()}"
            return
        }
        val body = function.searchableBody() ?: return
        queue.add(SearchContext(body, newDepth, function.functionTypedParamNames(), function))
    }

    /**
     * True (after recording a warning) if [function] is one of [entryHostingFunctions] — nav-graph
     * *construction*, not a route's own logic — and must not be re-entered from this route's
     * search. See [entryHostingFunctions]'s own kdoc (on [NavEdgeScanner.findEntryHostingFunctions])
     * for why this is always safe to refuse rather than guess.
     */
    private fun blockedAsEntryHostingFunction(function: KtNamedFunction): Boolean {
        if (function !in entryHostingFunctions) return false
        warnings += "nav edge candidate unresolved from route \"$sourceRoute\": " +
            "${function.name} hosts (directly or transitively) its own entry<...> registration(s) " +
            "and is only ever scanned as that registration's own search root, never re-entered from " +
            "another route's search, at ${function.location()}"
        return true
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
                if (blockedAsEntryHostingFunction(target)) return
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
        val isRouteShaped = paramTypeNames.any { it == "NavKey" || it in routeSimpleNames }
        if (isRouteShaped) handleNavigateCall(call, edges)
    }

    private fun handleNavigateCall(
        call: KtCallExpression,
        edges: MutableList<NavEdge>,
    ) {
        val chain = call.firstArgumentRouteChainOrNull() ?: return
        val target = resolveQualifiedTarget(chain, call)
        if (target != null) {
            edges += NavEdge(sourceRoute, target.qualifiedName)
        }
    }

    /**
     * Resolves [chain] (e.g. `["TodoRoute", "Detail"]` for a written qualifier, or just `["Detail"]`
     * for a bare reference - every shape goes through this, unambiguous or not) to one canonical
     * fully-qualified route name, then looks that up directly in [routesByQualifiedName] - an exact
     * match, never a suffix filter over same-leaf-name candidates the way a written qualifier like
     * `TodoRoute.Detail` could otherwise coincidentally (and wrongly) match an unrelated,
     * differently-nested route also ending in `.TodoRoute.Detail`.
     *
     * The chain's root identifier (its first segment, or its only segment for a single-segment
     * chain) is resolved to its own real qualified name first: an import in [call]'s containing
     * file whose fully-qualified name's last segment equals the root identifier wins if one exists;
     * otherwise the root is assumed to resolve within the call site's own file package, since
     * Kotlin doesn't require an import for a same-package reference. The rest of the written chain,
     * if any, is appended onto that resolved name and looked up exactly.
     *
     * This covers every legally-referenceable route with no separate fast path needed: a bare
     * simple-name reference is only valid Kotlin at all when the referenced declaration is either
     * explicitly imported or in the same package as the reference - there's no third way - and both
     * of those are exactly what root resolution above already checks. A single-candidate bare
     * reference doesn't need special-casing; it resolves through the same import-else-same-package
     * logic as everything else and lands on the same route.
     *
     * A miss - the resulting fully-qualified name doesn't match any route [NavNodeScanner] actually
     * found - is not guessed at: it's dropped with a warning, the same "don't guess" philosophy as
     * everywhere else in this class. This is also what happens when the root identifier resolves
     * through neither an import nor the same-package assumption (e.g. a call site referencing a
     * route via a class member or companion object) - the built name simply won't match anything.
     */
    private fun resolveQualifiedTarget(
        chain: List<String>,
        call: KtCallExpression,
    ): NavNode? {
        val rootFqn = resolveRootFqn(chain.first(), call.containingKtFile)
        val candidateFqn = if (chain.size > 1) "$rootFqn.${chain.drop(1).joinToString(".")}" else rootFqn
        val target = routesByQualifiedName[candidateFqn]
        if (target == null) {
            warnings += "ambiguous navigate target route \"${chain.joinToString(".")}\" at ${call.location()}"
        }
        return target
    }

    /** [rootName]'s own fully-qualified name: an import of it in [file] if there is one, else [file]'s own package. */
    private fun resolveRootFqn(
        rootName: String,
        file: KtFile,
    ): String {
        val imported = file.importDirectives
            .mapNotNull { it.importedFqName }
            .firstOrNull { it.shortName().asString() == rootName }
        if (imported != null) return imported.asString()

        val packageName = file.packageFqName.asString()
        return if (packageName.isEmpty()) rootName else "$packageName.$rootName"
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

private fun KtCallExpression.firstArgumentRouteChainOrNull(): List<String>? =
    valueArguments.firstOrNull()?.getArgumentExpression()?.routeReferenceChainOrNull()

/**
 * The route argument's own dotted reference chain, outermost-to-innermost (e.g.
 * `["TodoRoute", "Detail"]` for a qualified reference/constructor call `TodoRoute.Detail`, or just
 * `["Detail"]` for a bare one) - unlike a bare simple name, this preserves whatever qualifier the
 * call site actually wrote, so it can be resolved to a canonical fully-qualified name and looked
 * up exactly (see [CallGraphTraversal.resolveQualifiedTarget]). Returns `null` when the argument
 * isn't a recognized route-shaped expression at all (an unresolvable navigate call, same as
 * before).
 */
private fun KtExpression.routeReferenceChainOrNull(): List<String>? = when (this) {
    is KtCallExpression -> (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.let { listOf(it) }
    is KtNameReferenceExpression -> listOf(getReferencedName())
    is KtDotQualifiedExpression -> {
        val receiverChain = receiverExpression.routeReferenceChainOrNull()
        val selectorName = when (val selector = selectorExpression) {
            is KtNameReferenceExpression -> selector.getReferencedName()
            is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
        }
        if (receiverChain != null && selectorName != null) receiverChain + selectorName else null
    }
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

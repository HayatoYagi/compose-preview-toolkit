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
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.ValueArgument

/**
 * The result of [NavEdgeScanner.scan]: [edges] is the best-effort, deduplicated list of detected
 * navigation edges; [warnings] is every case the scan gave up on rather than risk guessing wrong —
 * multiple/ambiguous `NavBackStack` instances, ambiguous callee names, call-graph traversal
 * exceeding [NavEdgeScanner]'s configured depth bound, and bound-argument expressions the
 * traversal couldn't make sense of. Exposed as data (not just logged) specifically so tests can
 * assert on *why* an edge was or wasn't found: none of these cases should ever throw and fail the
 * whole scan.
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
 * Navigation3 has no prescribed "navigate" function — an app's `NavBackStack<NavKey>` is just a
 * mutable list, and every project writes its own wrapper(s) around mutating it, under whatever
 * name and however many layers of indirection it likes. Rather than chasing any particular
 * wrapper's name or declared shape, this anchors on the one thing that *is* real Nav3 API: the
 * tracked `NavBackStack<NavKey>` instance itself (see "Anchor discovery" below), and treats a call
 * that mutates it — `add`/`addAll` — as the only terminal edge condition. Detection is otherwise
 * unchanged from before: an `entry<X> { ... }` registration's trailing lambda (found via
 * [findEntryRegistrations], shared with [NavNodeScanner]) seeds a breadth-first search over a call
 * graph built *lazily* while traversing, since the interesting part of the graph (which caller
 * supplied which argument to which parameter) can only be discovered by looking at call sites as
 * they become relevant to the search, not ahead of time. Each BFS step processes a "search
 * context": a PSI subtree to scan (initially the entry registration's trailing lambda body) plus
 * the set of the *enclosing* declaration's function-typed parameter names that are lexically live
 * at that point (its "closure environment"), plus — new in this version — a small name→expression
 * substitution map (see "Route-carrying closures" below).
 *
 * ### Anchor discovery
 *
 * Once per [scan] (not once per route), [findBackStackAliasNames] computes the set of identifier
 * names presumed to denote *the* single, app-wide shared `NavBackStack` instance: the property (if
 * any) a `NavBackStack<NavKey>(...)` construction call is bound to (its type argument is always
 * syntactically present at the construction site, unlike a scattered declared-callback-type
 * annotation at every downstream layer), plus every parameter, anywhere in the scanned files, whose
 * *declared* type (as written) is `NavBackStack`. Only a single, app-wide shared instance is
 * supported: the instant more than one `NavBackStack(...)` construction site is found anywhere in
 * the scanned files, this refuses with one warning and returns no aliases at all, rather than
 * guessing which instance matters to which registration — the same "don't guess" philosophy as
 * everywhere else in this class. Treating *every* `NavBackStack`-typed parameter as another alias
 * for the same tracked instance (rather than proving each one is transitively bound to the real
 * anchor via the call-graph machinery below) is a deliberate simplification this scope limit makes
 * safe: there is nothing else a `NavBackStack`-typed name could denote once multiple independent
 * instances have already been refused.
 *
 * ### Within a context's subtree
 *
 * Every name reference is classified once, in priority order:
 *
 * 1. **Back-stack mutation call**: the reference is a call's callee (`add`/`addAll`), the call is
 *    written as `<receiver>.add(...)` / `<receiver>.addAll(...)` with a bare-name receiver, and
 *    that receiver's name is one of [findBackStackAliasNames]'s alias names → the call is a
 *    terminal navigate edge. Checked *before* case 2 so a live parameter that happens to be named
 *    `add` can never shadow a real mutation call. Route-argument resolution depends on the mutation
 *    method's arity (`add(element)` vs. `add(index, element)` vs. `addAll(elements)` vs.
 *    `addAll(index, elements)` — see [CallGraphTraversal.resolveMutationRouteArguments]'s kdoc);
 *    each resolved route expression is turned into a target via the *unchanged* qualified-route
 *    resolution machinery from PR #62 (see
 *    [CallGraphTraversal.resolveQualifiedTarget]'s kdoc) — a miss there is dropped with a warning,
 *    never guessed. Terminal — not traversed further.
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
 *    (see class kdoc). If the reference is itself a call — e.g. `navigateTo(FeatureBRoute)` where
 *    `navigateTo` is a `(NavKey) -> Unit`-typed parameter, invoked directly — its own argument
 *    expressions are remembered (see "Route-carrying closures" below) and carried alongside the
 *    reverse search.
 *
 *    The reverse edge's project-wide "every call site" search is unaware of *which* call site
 *    the current route's search actually descended from — by design, since the whole point is
 *    following reachability to wherever the parameter is really bound. This is safe as long as
 *    every one of those call sites' bound expressions belongs to that route's own logic. It stops
 *    being safe when the function being unwound is a shared, general-purpose composable that takes
 *    *more than one* function-typed parameter (e.g. a Scaffold-style wrapper with both `onBack` and
 *    a `content` slot): invoking that wrapper's `content` parameter in its own body triggers this
 *    same reverse search on `content`, which finds every route that happens to use the same
 *    wrapper — including one whose `content` argument is a lambda holding an unrelated aggregator
 *    route's own real mutation calls. [CallGraphTraversal] must never resolve into such a lambda:
 *    see [CallGraphTraversal.blockedAsAnotherRoutesEntryRegistration]'s kdoc.
 * 3. **Local-`val`-closure or known function call**: the reference is a call's callee, isn't a live
 *    parameter, and:
 *    - resolves to a local property in the *enclosing* declaration's own body with a
 *      lambda/callable-reference initializer (see
 *      [CallGraphTraversal.resolveLocalCallbackReference]'s kdoc) → treated exactly like case 2's
 *      reverse edge, but without the project-wide call-site search: a local declaration is only
 *      ever visible within its own enclosing function, so there is exactly one place its value
 *      could have come from — its own initializer, right there. Tried *before* the known-function
 *      fallback below, since in real Kotlin scoping a local declaration shadows an
 *      identically-named outer parameter or top-level function anyway. This is what lets
 *      `sample/app/AppNavHost.kt`'s `val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }`
 *      — a local closure, not a function parameter — be found the same way a parameter-threaded
 *      callback is.
 *    - otherwise, its simple name resolves unambiguously to another parsed [KtNamedFunction] →
 *      continue the search from that function's body at depth + 1, with that function's *own*
 *      function-typed parameters as the new live set (Kotlin scoping: a callee's parameters are
 *      not the caller's). Refused — dropped with a warning, not traversed — when the resolved
 *      function is one of [NavEdgeScanner]'s `entryHostingFunctions` (see
 *      [NavEdgeScanner.findEntryHostingFunctions]'s kdoc): a function that hosts `entry<X> {}`
 *      registrations, directly or transitively, is nav-graph *construction*, and re-entering its
 *      body from here would pull in every sibling registration wired inside it — including their
 *      own, entirely unrelated mutation calls — as if they were reachable from whatever route's
 *      search happened to rediscover it. The same refusal applies to the equivalent
 *      callable-reference case in [CallGraphTraversal.resolveBoundExpression].
 * 4. Otherwise → ignored. This is the common case (framework/library calls like `Button`, `Column`,
 *    `println` with no matching declaration among the parsed files) and is expected, not an error.
 *
 * ### Route-carrying closures
 *
 * Unlike a plain `() -> Unit` callback, a `(NavKey) -> Unit`-shaped closure like
 * `navigateTo`/`goTo` carries the *route itself* as its one parameter — the mutation call inside
 * it (`backStack.add(key)`) refers to that parameter by name, not to a literal route. Resolving
 * that reference requires knowing what a *specific* invocation actually passed. Whenever a
 * live-parameter (case 2) or local-`val` (case 3) reference is itself a call with concrete
 * argument expressions, those expressions are carried alongside the reverse/local resolution
 * (unaffected by how many indirection hops it takes — a plain pass-through never changes what was
 * originally passed) and bound, positionally, to the eventually-resolved
 * lambda's/function's own declared parameter list the moment one is found (see
 * [CallGraphTraversal.resolveBoundExpression]'s `KtLambdaExpression`/`KtCallableReferenceExpression`
 * cases) — becoming that one [SearchContext]'s `substitutions` map. A back-stack mutation call's
 * route argument is looked up in this map first (see
 * [CallGraphTraversal.resolveRouteArgumentAndEmitEdge]) before falling back to resolving it as
 * written. This is a purely syntactic, single-hop-of-substitution mechanism — no general
 * expression evaluation — so it resolves the common "closure whose body forwards its own parameter
 * straight into the mutation call" shape (matching `sample/`'s real `navigateTo`) but not, for
 * example, a closure that transforms the value first, or an implicit `it` parameter (which Kotlin
 * doesn't expose as a named [org.jetbrains.kotlin.psi.KtLambdaExpression.valueParameters] entry) —
 * those fall through to the same "give up with a warning" handling as any other unresolvable
 * argument.
 *
 * A context is only ever processed once per entry registration, keyed on *both* its PSI subtree
 * (by referential identity) and its `substitutions` map — which is what guarantees termination in
 * the presence of cycles (mutually recursive functions) while still allowing the same subtree to
 * be legitimately revisited under different route substitutions: BFS order means the first time a
 * given (subtree, substitutions) pair is reached is always via a shortest path, so revisiting it
 * later via a longer path is always safe to skip, but two *different* substitution maps over the
 * *same* subtree (e.g. four separate `navigateTo(SomeRoute)` calls all bottoming out at the same
 * shared route-carrying closure body, each with its own route) are genuinely different searches
 * that must each run. This is a narrower guarantee than it might look: it only dedupes *identical*
 * (subtree, substitutions) pairs, and does nothing to stop a search from *legitimately* discovering
 * an entirely different, much larger subtree via a real call edge — that is what the
 * `entryHostingFunctions` refusal above exists to prevent.
 *
 * Callee-name resolution is deliberately simple-name-based with no type resolution (per the design
 * doc's explicit choice): a same-package match is preferred, then an explicit import match: if
 * still ambiguous, the call is dropped with a warning rather than guessed. Resolving a route
 * argument follows the same type-resolution-free philosophy, but resolves the written reference to
 * one canonical fully-qualified name via import-then-same-package first (see
 * [CallGraphTraversal.resolveQualifiedTarget]'s kdoc) rather than filtering candidates by name,
 * since a suffix-based filter can't reliably tell two differently-nested routes that happen to
 * share their trailing segments apart. `NavBackStack` is the one type name matched literally
 * throughout for anchor discovery — it's `androidx.navigation3.runtime.NavBackStack`, a fixed
 * library API, not a project-specific naming convention.
 */
class NavEdgeScanner(
    private val entryFunctionNames: Set<String> = DEFAULT_ENTRY_FUNCTION_NAMES,
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
        val routesByQualifiedName: Map<String, NavNode> = distinctRoutes.associateBy { it.qualifiedName }

        val resolver = CalleeResolver(functionsBySimpleName, callsBySimpleName, warnings)
        val entryHostingFunctions = findEntryHostingFunctions(entryRegistrations, resolver)
        val entryRegistrationLambdas: Set<KtLambdaExpression> =
            entryRegistrations.mapNotNullTo(mutableSetOf()) { it.call.entryRegistrationLambda() }
        val backStackAliasNames = findBackStackAliasNames(files, warnings)

        val edges = LinkedHashSet<NavEdge>()
        entryRegistrations.forEach { registration ->
            val ownLambda = registration.call.entryRegistrationLambda() ?: return@forEach
            val lambdaBody = ownLambda.bodyExpression ?: return@forEach
            val owningFunction = PsiTreeUtil.getParentOfType(registration.call, KtNamedFunction::class.java)
            val traversal = CallGraphTraversal(
                sourceRoute = registration.node.qualifiedName,
                backStackAliasNames = backStackAliasNames,
                routesByQualifiedName = routesByQualifiedName,
                resolver = resolver,
                maxDepth = callGraphResolutionDepth,
                entryHostingFunctions = entryHostingFunctions,
                entryRegistrationLambdas = entryRegistrationLambdas,
                ownEntryRegistrationLambda = ownLambda,
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
     * wired inside that function — including their own, entirely unrelated mutation calls — as if
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

    /**
     * Every identifier name, anywhere in [files], presumed to denote *the* single, app-wide shared
     * `NavBackStack<NavKey>` instance: the property (if any) a `NavBackStack<NavKey>(...)`
     * construction call is bound to, plus every parameter, anywhere, whose *declared* type (as
     * written) is `NavBackStack`. See the class kdoc's "Anchor discovery" section for why this is a
     * deliberately coarse, single-shared-instance-only design, enforced by refusing (with a
     * warning) rather than guessing the instant more than one construction site is found.
     */
    private fun findBackStackAliasNames(
        files: List<KtFile>,
        warnings: MutableList<String>,
    ): Set<String> {
        val constructorCalls = files
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java) }
            .filter { it.calleeSimpleNameOrNull() == "NavBackStack" }

        if (constructorCalls.isEmpty()) return emptySet()
        if (constructorCalls.size > 1) {
            warnings += "found ${constructorCalls.size} separate NavBackStack<NavKey>() construction sites " +
                "(at ${constructorCalls.joinToString(", ") { it.location() }}); only a single, app-wide " +
                "shared NavBackStack instance is supported — dropping all NavBackStack-mutation-based nav edges"
            return emptySet()
        }

        val aliasNames = mutableSetOf<String>()
        val rootProperty = PsiTreeUtil.getParentOfType(constructorCalls.single(), KtProperty::class.java)
        if (rootProperty?.name != null) {
            aliasNames += rootProperty.name!!
        } else {
            warnings += "found a NavBackStack<NavKey>() construction that isn't bound to a named val/property " +
                "at ${constructorCalls.single().location()}; nav edges via NavBackStack mutation starting from " +
                "it cannot be traced"
        }

        files.flatMap { file -> PsiTreeUtil.findChildrenOfType(file, KtParameter::class.java) }
            .filter { it.typeReference?.declaredSimpleTypeNameOrNull() == "NavBackStack" }
            .mapNotNullTo(aliasNames) { it.name }

        return aliasNames
    }

    private fun KtCallExpression.entryRegistrationLambda(): KtLambdaExpression? =
        lambdaArguments.firstOrNull()?.getLambdaExpression()
}

/** [KtCallExpression.getCalleeExpression]'s referenced name, if it's a plain (unqualified) reference. */
private fun KtCallExpression.calleeSimpleNameOrNull(): String? =
    (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

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

/** [KtCallExpression] method names on a tracked `NavBackStack` alias treated as a terminal navigate edge. */
private val BACKSTACK_MUTATION_METHOD_NAMES = setOf("add", "addAll")

/**
 * One BFS step: a PSI subtree to scan, at what depth, with which function-typed parameter names
 * are live, and (see the class kdoc's "Route-carrying closures" section) a best-effort
 * name→expression substitution map for a route-carrying closure's own parameter(s), if this
 * context is the body of one.
 */
private data class SearchContext(
    val root: KtExpression,
    val depth: Int,
    val liveParams: Set<String>,
    val owningFunction: KtNamedFunction?,
    val substitutions: Map<String, KtExpression> = emptyMap(),
)

/**
 * A single entry registration's bounded-depth reachability search (Step C). One instance per
 * `entry<X> {}` registration — the `visited` set is deliberately *not* shared across different
 * routes' searches, since two unrelated routes legitimately reaching the same shared helper
 * function must each independently be able to discover edges through it.
 */
private class CallGraphTraversal(
    private val sourceRoute: String,
    private val backStackAliasNames: Set<String>,
    private val routesByQualifiedName: Map<String, NavNode>,
    private val resolver: CalleeResolver,
    private val maxDepth: Int,
    private val entryHostingFunctions: Set<KtNamedFunction>,
    private val entryRegistrationLambdas: Set<KtLambdaExpression>,
    private val ownEntryRegistrationLambda: KtLambdaExpression?,
    private val warnings: MutableList<String>,
) {
    // Keyed on (root, substitutions), not just root: the same PSI subtree is legitimately reached
    // more than once with *different* route substitutions whenever more than one call site invokes
    // the same route-carrying closure with a different route (e.g. 4 separate
    // `navigateTo(SomeRoute)` calls all bottoming out at the same shared `{ key ->
    // backStack.add(key) }` closure body) - deduping on root alone would silently keep only
    // whichever one happened to be enqueued first. Still bounded (so BFS still terminates in the
    // presence of cycles): substitutions maps are built once per landing lambda/function from a
    // finite set of PSI expressions, so (root, substitutions) still ranges over a finite space.
    private val visited = HashSet<Pair<KtExpression, Map<String, KtExpression>>>()

    fun run(
        rootBody: KtExpression,
        owningFunction: KtNamedFunction?,
    ): List<NavEdge> {
        val edges = mutableListOf<NavEdge>()
        val queue = ArrayDeque<SearchContext>()
        queue.add(SearchContext(rootBody, 0, owningFunction?.functionTypedParamNames().orEmpty(), owningFunction))
        while (queue.isNotEmpty()) {
            val context = queue.removeFirst()
            if (!visited.add(context.root to context.substitutions)) continue
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
                isCallee && isBackStackMutationCall(name, enclosingCall) ->
                    handleBackStackMutationCall(enclosingCall, name, context.substitutions, edges)
                context.owningFunction != null && name in context.liveParams -> {
                    val callArguments = if (isCallee) enclosingCall.callArgumentExpressions() else null
                    expandParameterInvocation(name, context.owningFunction, context.depth, queue, callArguments)
                }
                isCallee -> {
                    val callArguments = enclosingCall.callArgumentExpressions()
                    val owner = context.owningFunction
                    val resolvedLocally = owner != null &&
                        resolveLocalCallbackReference(name, owner, context.depth, queue, callArguments)
                    if (!resolvedLocally) {
                        val target = resolver.resolveFunction(name, ref.containingKtFile, ref) ?: return@forEach
                        enqueueFunctionBody(target, context.depth, queue)
                    }
                }
                else -> Unit
            }
        }
        return edges
    }

    /**
     * True if [call] — a call to [methodName] — is written as `<receiver>.methodName(...)` with a
     * bare-name receiver whose name is one of [backStackAliasNames]. This is checked structurally
     * (never a bare name check on `methodName` alone, which would false-positive on every unrelated
     * `.add(...)`/`.addAll(...)` call in the codebase — `add` is a far more generic method name
     * than `navigateTo` ever was) by walking from [call] up to its enclosing
     * [KtDotQualifiedExpression] and confirming [call] is that expression's own selector (not, for
     * instance, itself a receiver further down a longer chain).
     */
    private fun isBackStackMutationCall(
        methodName: String,
        call: KtCallExpression,
    ): Boolean {
        if (methodName !in BACKSTACK_MUTATION_METHOD_NAMES) return false
        val dotQualified = call.parent as? KtDotQualifiedExpression ?: return false
        if (dotQualified.selectorExpression !== call) return false
        val receiver = dotQualified.receiverExpression as? KtNameReferenceExpression ?: return false
        return receiver.getReferencedName() in backStackAliasNames
    }

    private fun handleBackStackMutationCall(
        call: KtCallExpression,
        methodName: String,
        substitutions: Map<String, KtExpression>,
        edges: MutableList<NavEdge>,
    ) {
        val routeExpressions = resolveMutationRouteArguments(call, methodName) ?: return
        routeExpressions.forEach { resolveRouteArgumentAndEmitEdge(it, substitutions, edges) }
    }

    /**
     * The route-argument expression(s) [call] — a call to [methodName], already confirmed by
     * [isBackStackMutationCall] to be a mutation of the tracked `NavBackStack` — supplies, or
     * `null` if [call]'s own shape can't be made sense of (dropped with a warning, not guessed):
     * - `add(element)`: the single argument.
     * - `add(index, element)`: the named `element` argument if present, else (two positional
     *   arguments) the last one.
     * - `addAll(elements)` / `addAll(index, elements)`: the collection argument (the only argument,
     *   or the last of two) must itself be a call-shaped collection literal (e.g.
     *   `listOf(RouteA, RouteB)`) to be unwrapped into its own individual route arguments — a
     *   collection built any other way (e.g. a plain variable reference) can't be enumerated
     *   syntactically, so is dropped with a warning rather than guessed at.
     */
    private fun resolveMutationRouteArguments(
        call: KtCallExpression,
        methodName: String,
    ): List<KtExpression>? {
        val args: List<ValueArgument> = call.valueArguments
        return when (methodName) {
            "add" -> when (args.size) {
                1 -> listOfNotNull(args[0].getArgumentExpression())
                2 -> {
                    val named = args.firstOrNull {
                        it.isNamed() && it.getArgumentName()?.asName?.asString() == "element"
                    }
                    listOfNotNull((named ?: args.last()).getArgumentExpression())
                }
                else -> null
            }
            "addAll" -> {
                val collectionArg = when (args.size) {
                    1 -> args[0]
                    2 -> args.last()
                    else -> null
                } ?: return null
                val collectionExpr = collectionArg.getArgumentExpression() ?: return null
                unwrapCollectionLiteralOrNull(collectionExpr) ?: run {
                    warnings += "could not resolve the collection argument to addAll(...) as a recognizable " +
                        "collection literal at ${call.location()}; nav edges via this call are dropped"
                    null
                }
            }
            else -> null
        }
    }

    private fun unwrapCollectionLiteralOrNull(expression: KtExpression): List<KtExpression>? =
        (expression as? KtCallExpression)?.valueArguments?.mapNotNull { it.getArgumentExpression() }

    /**
     * Resolves [routeExpression] (a mutation call's route argument) to a target route and, on a
     * hit, emits the edge. [substitutions] is checked first (see the class kdoc's "Route-carrying
     * closures" section): if [routeExpression] is itself a bare reference to a route-carrying
     * closure's own parameter, the expression actually passed at the invocation that led here is
     * used instead of the parameter name itself (which could never resolve to a real route). Falls
     * back to resolving [routeExpression] exactly as written when it isn't a substituted name (the
     * common case: a literal route reference written directly as the mutation call's argument).
     */
    private fun resolveRouteArgumentAndEmitEdge(
        routeExpression: KtExpression,
        substitutions: Map<String, KtExpression>,
        edges: MutableList<NavEdge>,
    ) {
        val resolvedExpression = (routeExpression as? KtNameReferenceExpression)
            ?.let { substitutions[it.getReferencedName()] }
            ?: routeExpression
        val chain = resolvedExpression.routeReferenceChainOrNull() ?: return
        val target = resolveQualifiedTarget(chain, resolvedExpression)
        if (target != null) {
            edges += NavEdge(sourceRoute, target.qualifiedName)
        }
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

    /**
     * True (after recording a warning) if [expression] is lexically nested inside — or is itself —
     * some *other* route's own `entry<X> {}` trailing lambda (any member of
     * [entryRegistrationLambdas] besides [ownEntryRegistrationLambda]). This is the same scope-leak
     * category [blockedAsEntryHostingFunction] guards against, but reached through a different code
     * path: a shared, general-purpose composable that takes more than one function-typed parameter
     * (e.g. a Scaffold-style wrapper with both `onBack` and a `content` slot) is not itself an
     * "entry-hosting function" — nothing about it calls `entry<...> {}` — so
     * [blockedAsEntryHostingFunction] has nothing to refuse. But when *that* wrapper's `content`
     * parameter is itself invoked in its body, [expandParameterInvocation] finds every call site of
     * the wrapper project-wide and, for each one, threads through here — including a call site that
     * lives inside some unrelated aggregator route's own registration, whose `content` argument is a
     * lambda (or, equally, a callable reference — see [resolveBoundExpression]'s
     * `KtCallableReferenceExpression` branch, which passes the reference expression itself, not its
     * resolved target's body) holding that aggregator's own, entirely real mutation calls. Scanning
     * that argument here would misattribute the aggregator's own edges to whatever route's search
     * happened to reach the shared wrapper - checked by walking up [expression]'s PSI ancestors
     * rather than requiring exact identity, since the leaking argument is typically nested several
     * calls deep inside the other registration's trailing lambda (as in the Scaffold example above),
     * not necessarily written as its immediate body. Deliberately takes the *bound argument
     * expression itself* (as written at its call site), never the callable reference's resolved
     * target — an ordinary named function reached this way (e.g. `RouteBTopContent` in the example
     * above) is not itself nested inside anything and would never trip this check if it were passed
     * the target instead, which is exactly how this leak survived scanning only the lambda-literal
     * form of the same mistake.
     */
    private fun blockedAsAnotherRoutesEntryRegistration(expression: KtExpression): Boolean {
        var current: PsiElement? = expression
        while (current != null) {
            if (current is KtLambdaExpression && current in entryRegistrationLambdas) {
                if (current == ownEntryRegistrationLambda) return false
                warnings += "nav edge candidate unresolved from route \"$sourceRoute\": the bound callback " +
                    "is itself part of another route's own entry<...> registration, and is only ever " +
                    "scanned as that registration's own search root, never re-entered from another route's " +
                    "search, at ${expression.location()}"
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun expandParameterInvocation(
        paramName: String,
        owningFunction: KtNamedFunction,
        foundAtDepth: Int,
        queue: ArrayDeque<SearchContext>,
        callArguments: List<KtExpression>?,
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
            resolveBoundExpression(boundExpression, newDepth, queue, callArguments)
        }
    }

    /**
     * Best-effort local-`val`-closure resolution (class kdoc's case 3): if [owner]'s own body
     * declares a local property named [name] with an initializer, that initializer is treated as
     * "one more BFS hop out" (same [maxDepth] bound/warning shape as [expandParameterInvocation])
     * and the search continues from it via [resolveBoundExpression] — reusing its
     * `KtLambdaExpression`/`KtCallableReferenceExpression` handling (leak guards included) rather
     * than duplicating it. [callArguments], if [name] is itself being invoked here with concrete
     * arguments, is threaded through unchanged so it can be bound once a lambda/function is
     * actually found (see the class kdoc's "Route-carrying closures" section).
     *
     * Returns `true` the instant a local property named [name] is found at all — even when the
     * depth bound stops the search from actually continuing through it — so callers never also try
     * resolving [name] some other way (e.g. as an outer parameter or a top-level function): a local
     * declaration shadows any identically-named outer binding in real Kotlin scoping, so once one
     * is found under this name, that's the whole story for this name at this point in the code,
     * whether or not the search can actually continue through it.
     *
     * Deliberately a flat name search over [owner]'s whole body with no real scoping/shadowing
     * model (same "best effort, syntactic only" philosophy as the rest of this class) — e.g. two
     * local vals with the same name in different nested blocks aren't disambiguated.
     */
    private fun resolveLocalCallbackReference(
        name: String,
        owner: KtNamedFunction,
        foundAtDepth: Int,
        queue: ArrayDeque<SearchContext>,
        callArguments: List<KtExpression>?,
    ): Boolean {
        val initializer = resolveLocalCallbackInitializer(name, owner) ?: return false
        val newDepth = foundAtDepth + 1
        if (newDepth > maxDepth) {
            warnings += "nav edge candidate unresolved beyond depth $maxDepth from route \"$sourceRoute\" " +
                "resolving local callback \"$name\" declared in ${owner.location()}"
            return true
        }
        resolveBoundExpression(initializer, newDepth, queue, callArguments)
        return true
    }

    /**
     * [callArguments], when non-null, is what a specific invocation of the callback this bound
     * expression ultimately represents was actually called with — carried through unchanged from
     * wherever this resolution started (either [expandParameterInvocation] or
     * [resolveLocalCallbackReference]) until a real lambda/function is found below, at which point
     * it's bound, positionally, to that lambda's/function's own declared parameters and becomes the
     * new [SearchContext]'s `substitutions` (see the class kdoc's "Route-carrying closures"
     * section). `null` for an ordinary `() -> Unit`-shaped callback pass-through, where there is
     * nothing to bind.
     */
    private fun resolveBoundExpression(
        expression: KtExpression,
        depth: Int,
        queue: ArrayDeque<SearchContext>,
        callArguments: List<KtExpression>? = null,
    ) {
        when (expression) {
            is KtLambdaExpression -> {
                if (blockedAsAnotherRoutesEntryRegistration(expression)) return
                val body = expression.bodyExpression ?: return
                val closureOwner = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                val substitutions = bindArguments(expression.valueParameters.mapNotNull { it.name }, callArguments)
                queue.add(
                    SearchContext(
                        body,
                        depth,
                        closureOwner?.functionTypedParamNames().orEmpty(),
                        closureOwner,
                        substitutions,
                    ),
                )
            }
            is KtCallableReferenceExpression -> {
                // Checked against the reference expression itself (as written at its call site),
                // not the function it resolves to: an ordinary named function's own body is never
                // "nested inside" anything, so only the call site's own lexical position can reveal
                // that this callable reference is another route's own registration wiring being
                // passed through a shared wrapper (see blockedAsAnotherRoutesEntryRegistration's
                // kdoc for the full scenario).
                if (blockedAsAnotherRoutesEntryRegistration(expression)) return
                val refName = expression.callableReference.getReferencedName()
                val target = resolver.resolveFunction(refName, expression.containingKtFile, expression) ?: return
                if (blockedAsEntryHostingFunction(target)) return
                val body = target.searchableBody() ?: return
                val substitutions = bindArguments(target.valueParameters.mapNotNull { it.name }, callArguments)
                queue.add(SearchContext(body, depth, target.functionTypedParamNames(), target, substitutions))
            }
            is KtNameReferenceExpression -> {
                // A pass-through: the bound argument is itself just a reference to a callback the
                // enclosing function/lambda has access to — either a function-typed parameter it
                // was itself given (a wiring function forwarding its own callback parameter one
                // level further up under the same shape), or a local val declared in its own body.
                // Local resolution is tried first: it would shadow the parameter case in real
                // Kotlin scoping anyway (see resolveLocalCallbackReference's kdoc). Keep unwinding
                // one hop at a time until a real lambda/callable reference is found.
                val enclosingFunction = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                val refName = expression.getReferencedName()
                when {
                    enclosingFunction != null &&
                        resolveLocalCallbackReference(refName, enclosingFunction, depth, queue, callArguments) -> Unit
                    enclosingFunction != null && refName in enclosingFunction.functionTypedParamNames() ->
                        expandParameterInvocation(refName, enclosingFunction, depth, queue, callArguments)
                    else -> {
                        warnings += "could not resolve bound argument \"$refName\" for a navigate-relevant " +
                            "parameter at ${expression.location()}"
                    }
                }
            }
            else -> {
                warnings += "could not resolve bound argument expression for a navigate-relevant " +
                    "parameter at ${expression.location()}"
            }
        }
    }

    /** Positionally binds [paramNames] to [callArguments], or an empty map if either is absent/empty. */
    private fun bindArguments(
        paramNames: List<String>,
        callArguments: List<KtExpression>?,
    ): Map<String, KtExpression> =
        callArguments?.let { args -> paramNames.zip(args).toMap() }.orEmpty()

    /**
     * Resolves [chain] (e.g. `["TodoRoute", "Detail"]` for a written qualifier, or just `["Detail"]`
     * for a bare reference - every shape goes through this, unambiguous or not) to one canonical
     * fully-qualified route name, then looks that up directly in [routesByQualifiedName] - an exact
     * match, never a suffix filter over same-leaf-name candidates the way a written qualifier like
     * `TodoRoute.Detail` could otherwise coincidentally (and wrongly) match an unrelated,
     * differently-nested route also ending in `.TodoRoute.Detail`.
     *
     * The chain's root identifier (its first segment, or its only segment for a single-segment
     * chain) is resolved to its own real qualified name first: an import in [routeExpression]'s
     * containing file whose fully-qualified name's last segment equals the root identifier wins if
     * one exists; otherwise the root is assumed to resolve within that file's own package, since
     * Kotlin doesn't require an import for a same-package reference. [routeExpression]'s own file is
     * used (not the mutation call's) so a route argument resolved through a substitution (see the
     * class kdoc's "Route-carrying closures" section) is resolved against the imports of wherever it
     * was actually *written*, not wherever the mutation call it ended up bound to happens to live.
     * The rest of the written chain, if any, is appended onto the resolved root name and looked up
     * exactly.
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
        routeExpression: KtExpression,
    ): NavNode? {
        val rootFqn = resolveRootFqn(chain.first(), routeExpression.containingKtFile)
        val candidateFqn = if (chain.size > 1) "$rootFqn.${chain.drop(1).joinToString(".")}" else rootFqn
        val target = routesByQualifiedName[candidateFqn]
        if (target == null) {
            warnings += "ambiguous navigate target route \"${chain.joinToString(".")}\" at ${routeExpression.location()}"
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

/** [KtCallExpression.getValueArguments]' own argument expressions, in written order. */
private fun KtCallExpression.callArgumentExpressions(): List<KtExpression> =
    valueArguments.mapNotNull { it.getArgumentExpression() }

/** A local property in [owner]'s body named [name] with an initializer, if any. */
private fun resolveLocalCallbackInitializer(
    name: String,
    owner: KtNamedFunction,
): KtExpression? =
    PsiTreeUtil.findChildrenOfType(owner, KtProperty::class.java)
        .firstOrNull { it.name == name }
        ?.initializer

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

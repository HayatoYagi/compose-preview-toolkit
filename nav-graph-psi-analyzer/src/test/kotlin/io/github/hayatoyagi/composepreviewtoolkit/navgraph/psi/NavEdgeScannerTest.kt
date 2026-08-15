package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class NavEdgeScannerTest {
    private lateinit var parser: KotlinPsiParser

    @BeforeEach
    fun setUp() {
        parser = KotlinPsiParser()
    }

    @AfterEach
    fun tearDown() {
        parser.close()
    }

    @Test
    fun `direct construction and use - a NavBackStack local val is mutated one hop from the entry block, no closure indirection`() {
        val file = parser.parse(
            "Direct.kt",
            """
            package com.example.direct

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object DirectSourceRoute : NavKey
            object DirectTargetRoute : NavKey

            fun registerDirect() {
                val backStack = NavBackStack<NavKey>()
                entry<DirectSourceRoute> { backStack.add(DirectTargetRoute) }
                entry<DirectTargetRoute> { DirectTargetScreen() }
            }

            fun DirectTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.direct.DirectSourceRoute", "com.example.direct.DirectTargetRoute")),
            result.edges,
        )
    }

    @Test
    fun `mirrors the real sample's exact wiring shape - an inline local-val closure call, a zero-arg threaded callback, and a closure threaded as a parameter all resolve`() {
        // Mirrors sample/app/AppNavHost.kt + sample/feature-a + sample/feature-b exactly:
        // - HomeRoute -> FeatureARoute: navigateTo is a local val (not a parameter), called
        //   directly inside HomeRoute's own entry block.
        // - FeatureARoute -> FeatureBRoute: featureANavEntries's onProceedClick is an ordinary
        //   zero-arg callback, threaded up to AppNavHost and only invoked there.
        // - FeatureBRoute -> FeatureARoute: featureBNavEntries is handed the navigateTo closure
        //   itself (not a single-purpose callback) and invokes it directly in its own entry block.
        val appNavHost = parser.parse(
            "AppNavHost.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider
            import com.example.featurea.FeatureARoute
            import com.example.featurea.featureANavEntries
            import com.example.featureb.FeatureBRoute
            import com.example.featureb.featureBNavEntries

            object HomeRoute : NavKey

            fun appNavHost() {
                val backStack = NavBackStack<NavKey>(HomeRoute)
                val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }
                entryProvider<NavKey> {
                    entry<HomeRoute> {
                        HomeScreen(onGoToFeatureAClick = { navigateTo(FeatureARoute) })
                    }
                    featureANavEntries(onProceedClick = { navigateTo(FeatureBRoute) })
                    featureBNavEntries(navigateTo = navigateTo)
                }
            }

            fun HomeScreen(onGoToFeatureAClick: () -> Unit) = Unit
            """.trimIndent(),
        )
        val featureA = parser.parse(
            "FeatureANavEntries.kt",
            """
            package com.example.featurea

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey

            object FeatureARoute : NavKey

            fun EntryProviderScope<NavKey>.featureANavEntries(onProceedClick: () -> Unit) {
                entry<FeatureARoute> { FeatureAScreen(onProceedClick) }
            }

            fun FeatureAScreen(onProceedClick: () -> Unit) {
                Button(onClick = onProceedClick)
            }

            fun Button(onClick: () -> Unit) = Unit
            """.trimIndent(),
        )
        val featureB = parser.parse(
            "FeatureBNavEntries.kt",
            """
            package com.example.featureb

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import com.example.featurea.FeatureARoute

            object FeatureBRoute : NavKey

            fun EntryProviderScope<NavKey>.featureBNavEntries(navigateTo: (NavKey) -> Unit) {
                entry<FeatureBRoute> {
                    FeatureBScreen(onRestartClick = { navigateTo(FeatureARoute) })
                }
            }

            fun FeatureBScreen(onRestartClick: () -> Unit) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(appNavHost, featureA, featureB))

        assertEquals(
            setOf(
                NavEdge("com.example.app.HomeRoute", "com.example.featurea.FeatureARoute"),
                NavEdge("com.example.featurea.FeatureARoute", "com.example.featureb.FeatureBRoute"),
                NavEdge("com.example.featureb.FeatureBRoute", "com.example.featurea.FeatureARoute"),
            ),
            result.edges.toSet(),
        )
    }

    @Test
    fun `the NavBackStack instance itself is threaded as a typed parameter into a wiring function, not wrapped in a closure`() {
        val app = parser.parse(
            "AppNavHost2.kt",
            """
            package com.example.app2

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.feature2.FeatureRoute
            import com.example.feature2.feature2NavEntries

            object HomeRoute2 : NavKey

            fun appNavHost2() {
                val backStack = NavBackStack<NavKey>(HomeRoute2)
                entry<HomeRoute2> { backStack.add(FeatureRoute) }
                feature2NavEntries(backStack)
            }
            """.trimIndent(),
        )
        val feature2 = parser.parse(
            "Feature2NavEntries.kt",
            """
            package com.example.feature2

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.app2.HomeRoute2

            object FeatureRoute : NavKey

            fun EntryProviderScope<NavKey>.feature2NavEntries(backStack: NavBackStack<NavKey>) {
                entry<FeatureRoute> { backStack.add(HomeRoute2) }
            }
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(app, feature2))

        assertEquals(
            setOf(
                NavEdge("com.example.app2.HomeRoute2", "com.example.feature2.FeatureRoute"),
                NavEdge("com.example.feature2.FeatureRoute", "com.example.app2.HomeRoute2"),
            ),
            result.edges.toSet(),
        )
    }

    @Test
    fun `add(index, element) prefers the named element argument, and addAll unwraps a call-shaped collection literal`() {
        val file = parser.parse(
            "Multi.kt",
            """
            package com.example.multi

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object MultiSourceRoute : NavKey
            object MultiIndexedTargetRoute : NavKey
            object MultiFirstTargetRoute : NavKey
            object MultiSecondTargetRoute : NavKey

            fun registerMulti() {
                val backStack = NavBackStack<NavKey>()
                entry<MultiSourceRoute> {
                    backStack.add(0, element = MultiIndexedTargetRoute)
                    backStack.addAll(listOf(MultiFirstTargetRoute, MultiSecondTargetRoute))
                }
                entry<MultiIndexedTargetRoute> { MultiIndexedTargetScreen() }
                entry<MultiFirstTargetRoute> { MultiFirstTargetScreen() }
                entry<MultiSecondTargetRoute> { MultiSecondTargetScreen() }
            }

            fun MultiIndexedTargetScreen() = Unit
            fun MultiFirstTargetScreen() = Unit
            fun MultiSecondTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            setOf(
                NavEdge("com.example.multi.MultiSourceRoute", "com.example.multi.MultiIndexedTargetRoute"),
                NavEdge("com.example.multi.MultiSourceRoute", "com.example.multi.MultiFirstTargetRoute"),
                NavEdge("com.example.multi.MultiSourceRoute", "com.example.multi.MultiSecondTargetRoute"),
            ),
            result.edges.toSet(),
        )
    }

    @Test
    fun `addAll with a collection argument that isn't a recognizable call-shaped literal is dropped with a warning, not guessed`() {
        val file = parser.parse(
            "UnrecognizableAddAll.kt",
            """
            package com.example.unrecognizableaddall

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object SourceRoute : NavKey

            fun register(routes: List<NavKey>) {
                val backStack = NavBackStack<NavKey>()
                entry<SourceRoute> { backStack.addAll(routes) }
            }
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
        assertTrue(
            result.warnings.any { it.contains("addAll", ignoreCase = false) },
            "expected an addAll give-up warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a (NavKey) to Unit typed parameter invoked directly, with no NavBackStack anywhere in scope, is no longer detected - retired-mode confirmation`() {
        // Matches the shape the now-retired declared-callback-type detection used to catch: a
        // non-standard-named callback with an explicit NavKey parameter type, invoked directly.
        // With navigateCallNames and declared-callback-type detection both retired in favor of
        // NavBackStack-mutation tracking, and no NavBackStack construction anywhere in this
        // fixture, this must now find nothing.
        val file = parser.parse(
            "RetiredTypedDirect.kt",
            """
            package com.example.retiredtypeddirect

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object RetiredSourceRoute : NavKey
            object RetiredTargetRoute : NavKey

            fun registerRetiredTypedDirect(goTo: (NavKey) -> Unit) {
                entry<RetiredSourceRoute> { goTo(RetiredTargetRoute) }
                entry<RetiredTargetRoute> { RetiredTargetScreen() }
            }

            fun RetiredTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
    }

    @Test
    fun `multiple separate NavBackStack construction sites are refused rather than guessed`() {
        val file = parser.parse(
            "MultiBackStack.kt",
            """
            package com.example.multibackstack

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object MultiInstanceSourceRoute : NavKey
            object MultiInstanceTargetRoute : NavKey

            fun registerMultiInstance() {
                val backStackA = NavBackStack<NavKey>()
                val backStackB = NavBackStack<NavKey>()
                entry<MultiInstanceSourceRoute> { backStackA.add(MultiInstanceTargetRoute) }
                entry<MultiInstanceTargetRoute> { MultiInstanceTargetScreen() }
            }

            fun MultiInstanceTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
        assertTrue(
            result.warnings.any { it.contains("NavBackStack") && it.contains("single") },
            "expected a multi-instance warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a node with no reachable navigate call contributes zero edges, not an error`() {
        val file = parser.parse(
            "Terminal.kt",
            """
            package com.example.terminal

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object TerminalRoute : NavKey

            fun registerTerminal() {
                entry<TerminalRoute> { TerminalScreen() }
            }

            fun TerminalScreen() {
                println("done")
            }
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
    }

    @Test
    fun `an ambiguous callee simple name is dropped with a warning, not guessed`() {
        val entryFile = parser.parse(
            "EntryPoint.kt",
            """
            package com.example.entry

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object EntryRoute : NavKey

            fun register() {
                entry<EntryRoute> { helper() }
            }
            """.trimIndent(),
        )
        val helperA = parser.parse(
            "HelperA.kt",
            """
            package com.example.a

            fun helper() = Unit
            """.trimIndent(),
        )
        val helperB = parser.parse(
            "HelperB.kt",
            """
            package com.example.b

            fun helper() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(entryFile, helperA, helperB))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
        assertTrue(
            result.warnings.any { it.contains("ambiguous", ignoreCase = true) && it.contains("helper") },
            "expected an ambiguous-callee warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `depth-limit boundary- found within bound, dropped with a warning when exceeded`() {
        fun source(): String = """
            package com.example.depth

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object DeepRoute : NavKey
            object TargetRoute : NavKey

            fun registerDeep() {
                val backStack = NavBackStack<NavKey>()
                entry<DeepRoute> { level1(backStack) }
                entry<TargetRoute> { TargetScreen() }
            }

            fun level1(backStack: NavBackStack<NavKey>) { level2(backStack) }
            fun level2(backStack: NavBackStack<NavKey>) { level3(backStack) }
            fun level3(backStack: NavBackStack<NavKey>) { level4(backStack) }
            fun level4(backStack: NavBackStack<NavKey>) { backStack.add(TargetRoute) }

            fun TargetScreen() = Unit
        """.trimIndent()

        val tooShallow = parser.parse("DeepTooShallow.kt", source())
        val exceededResult = NavEdgeScanner(callGraphResolutionDepth = 3).scan(listOf(tooShallow))
        assertTrue(exceededResult.edges.isEmpty(), "expected no edges but found: ${exceededResult.edges}")
        assertTrue(
            exceededResult.warnings.any { it.contains("depth", ignoreCase = true) },
            "expected a depth-limit warning but got: ${exceededResult.warnings}",
        )

        val deepEnough = parser.parse("DeepEnough.kt", source())
        val successResult = NavEdgeScanner(callGraphResolutionDepth = 4).scan(listOf(deepEnough))
        assertEquals(
            listOf(NavEdge("com.example.depth.DeepRoute", "com.example.depth.TargetRoute")),
            successResult.edges,
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `a cycle in the call graph terminates without hanging and without finding an edge`() {
        val file = parser.parse(
            "Cycle.kt",
            """
            package com.example.cycle

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object CycleRoute : NavKey

            fun registerCycle() {
                entry<CycleRoute> { functionA() }
            }

            fun functionA() { functionB() }
            fun functionB() { functionA() }
            """.trimIndent(),
        )

        // A high depth bound, so termination is demonstrably due to visited-context tracking
        // rather than merely the depth bound cutting things off early.
        val result = NavEdgeScanner(callGraphResolutionDepth = 500).scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
    }

    @Test
    fun `a navigate call qualified with its parent route disambiguates between sealed-hierarchy siblings sharing a leaf name`() {
        // TodoRoute.Detail and NoteRoute.Detail are two different sealed-hierarchy siblings that
        // happen to share the leaf simple name "Detail" (see NavNode's own kdoc for this exact
        // shape). backStack.add(TodoRoute.Detail) carries enough information in its own qualifier
        // to pick the right one without guessing.
        val file = parser.parse(
            "SiblingRoutes.kt",
            """
            package com.example.siblings

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            sealed interface TodoRoute : NavKey {
                object Detail : TodoRoute
            }

            sealed interface NoteRoute : NavKey {
                object Detail : NoteRoute
            }

            object ListRoute : NavKey

            fun registerSiblings() {
                val backStack = NavBackStack<NavKey>()
                entry<ListRoute> { backStack.add(TodoRoute.Detail) }
                entry<TodoRoute.Detail> { TodoDetailScreen() }
                entry<NoteRoute.Detail> { NoteDetailScreen() }
            }

            fun TodoDetailScreen() = Unit
            fun NoteDetailScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.siblings.ListRoute", "com.example.siblings.TodoRoute.Detail")),
            result.edges,
        )
        assertTrue(
            result.warnings.none { it.contains("ambiguous", ignoreCase = true) },
            "expected no ambiguity warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a bare unqualified reference matching multiple sibling leaf names is still dropped with a warning`() {
        // Same sibling shape as above, but the mutation call omits the qualifier entirely, so
        // there's genuinely nothing in the source to disambiguate with - this must still warn
        // rather than guess.
        val file = parser.parse(
            "BareSiblingRoutes.kt",
            """
            package com.example.baresiblings

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            sealed interface TodoRoute : NavKey {
                object Detail : TodoRoute
            }

            sealed interface NoteRoute : NavKey {
                object Detail : NoteRoute
            }

            object ListRoute : NavKey

            fun registerBareSiblings() {
                val backStack = NavBackStack<NavKey>()
                entry<ListRoute> { backStack.add(Detail) }
                entry<TodoRoute.Detail> { TodoDetailScreen() }
                entry<NoteRoute.Detail> { NoteDetailScreen() }
            }

            fun TodoDetailScreen() = Unit
            fun NoteDetailScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
        assertTrue(
            result.warnings.any { it.contains("ambiguous", ignoreCase = true) && it.contains("Detail") },
            "expected an ambiguous-target warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a bare reference disambiguated via the call site's own import, idiomatic Kotlin's avoid-repeating-the-qualifier pattern`() {
        // Same sibling shape as the two tests above, but this time the routes are declared in one
        // file and the mutation call lives in a *different* file that writes
        // `import com.example.siblingsimport.TodoRoute.Detail` and then calls
        // `backStack.add(Detail)` bare - the idiomatic-Kotlin way to avoid repeating the qualifier
        // at every call site. There's no written qualifier at the call site to narrow with (same
        // as the bare-reference test above), but the import alone is enough to pick
        // TodoRoute.Detail over NoteRoute.Detail without needing type resolution.
        val routes = parser.parse(
            "SiblingRoutesForImport.kt",
            """
            package com.example.siblingsimport

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            sealed interface TodoRoute : NavKey {
                object Detail : TodoRoute
            }

            sealed interface NoteRoute : NavKey {
                object Detail : NoteRoute
            }

            fun registerSiblingDetails() {
                entry<TodoRoute.Detail> { TodoDetailScreen() }
                entry<NoteRoute.Detail> { NoteDetailScreen() }
            }

            fun TodoDetailScreen() = Unit
            fun NoteDetailScreen() = Unit
            """.trimIndent(),
        )
        val appNavHost = parser.parse(
            "AppNavHostForImport.kt",
            """
            package com.example.appimport

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.siblingsimport.TodoRoute.Detail

            object ListRoute : NavKey

            fun registerAppImport() {
                val backStack = NavBackStack<NavKey>()
                entry<ListRoute> { backStack.add(Detail) }
            }
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(routes, appNavHost))

        assertEquals(
            listOf(NavEdge("com.example.appimport.ListRoute", "com.example.siblingsimport.TodoRoute.Detail")),
            result.edges,
        )
        assertTrue(
            result.warnings.none { it.contains("ambiguous", ignoreCase = true) },
            "expected no ambiguity warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a written qualifier's coincidental tail match with an unrelated deeper-nested route is not guessed`() {
        // Foo.TodoRoute.Detail is an unrelated, differently-nested route that happens to share its
        // last two segments with the call site's own written qualifier TodoRoute.Detail. A
        // suffix-based tail match would incorrectly treat this as the (uniquely matching) target;
        // the qualifier must instead resolve to a real canonical fully-qualified name (via import
        // or same-package assumption) before any lookup happens, and neither applies here (there is
        // no top-level TodoRoute in this package, nor any import for one), so this must be dropped
        // with a warning rather than guessed.
        val file = parser.parse(
            "QualifierFalsePositive.kt",
            """
            package com.example.qualifierfalsepositive

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object ListRoute : NavKey

            sealed interface Foo : NavKey {
                sealed interface TodoRoute : NavKey {
                    object Detail : TodoRoute
                }
            }

            fun register() {
                val backStack = NavBackStack<NavKey>()
                entry<ListRoute> { backStack.add(TodoRoute.Detail) }
                entry<Foo.TodoRoute.Detail> { NestedDetailScreen() }
            }

            fun NestedDetailScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
        assertTrue(
            result.warnings.any { it.contains("ambiguous", ignoreCase = true) && it.contains("TodoRoute.Detail") },
            "expected a give-up warning but got: ${result.warnings}",
        )
    }

    @Test
    fun `a callback pass-through that bottoms out at a local val with a plain, non-mutating body dead-ends without leaking sibling edges`() {
        // Regression test (adapted from PR #63's fix): FeatureCRoute's only reachable callback
        // (onBack) is threaded up through featureCNavEntries -> AppNavHost -> App, where App's
        // popBack is a *local val*, not a function parameter, whose body just calls a plain
        // restartApp() with no NavBackStack mutation anywhere in it. That must be a dead end: no
        // edge for FeatureCRoute at all. In particular FeatureCRoute must NOT pick up the edge that
        // belongs to featureANavEntries's own, entirely unrelated onSomeAction callback (wired to a
        // sibling call in the same entryProvider block, and made real here via a genuine
        // NavBackStack so there is something real to *not* leak).
        val appFile = parser.parse(
            "App.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entryProvider
            import com.example.featurea.FeatureARoute
            import com.example.featurea.featureANavEntries
            import com.example.featureb.featureBNavEntries
            import com.example.featurec.featureCNavEntries

            fun AppNavHost(
                popBack: () -> Unit,
                navigateTo: (NavKey) -> Unit,
            ) {
                NavDisplay(
                    entryProvider = entryProvider<NavKey> {
                        featureANavEntries(onSomeAction = { navigateTo(FeatureARoute) })
                        featureBNavEntries(onBack = popBack)
                        featureCNavEntries(onBack = popBack)
                    },
                )
            }

            fun App() {
                val backStack = NavBackStack<NavKey>()
                val popBack: () -> Unit = { restartApp() }
                val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }
                AppNavHost(popBack = popBack, navigateTo = navigateTo)
            }

            fun NavDisplay(entryProvider: Any) = Unit
            fun restartApp() = Unit
            """.trimIndent(),
        )
        val featureA = parser.parse(
            "FeatureANavEntries.kt",
            """
            package com.example.featurea

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureARoute : NavKey

            fun EntryProviderScope<NavKey>.featureANavEntries(onSomeAction: () -> Unit) {
                entry<FeatureARoute> { FeatureAScreen(onSomeAction) }
            }

            fun FeatureAScreen(onSomeAction: () -> Unit) = Unit
            """.trimIndent(),
        )
        val featureB = parser.parse(
            "FeatureBNavEntries.kt",
            """
            package com.example.featureb

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureBRoute : NavKey

            fun EntryProviderScope<NavKey>.featureBNavEntries(onBack: () -> Unit) {
                entry<FeatureBRoute> { FeatureBScreen(onBack) }
            }

            fun FeatureBScreen(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )
        val featureC = parser.parse(
            "FeatureCNavEntries.kt",
            """
            package com.example.featurec

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureCRoute : NavKey

            fun EntryProviderScope<NavKey>.featureCNavEntries(onBack: () -> Unit) {
                entry<FeatureCRoute> { FeatureCScreen(onBack = onBack) }
            }

            fun FeatureCScreen(onBack: () -> Unit) {
                FeatureCContent(onBack = onBack)
            }

            private fun FeatureCContent(onBack: () -> Unit) {
                IconButton(onClick = onBack)
            }

            fun IconButton(onClick: () -> Unit) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(appFile, featureA, featureB, featureC))

        assertTrue(
            result.edges.none { it.sourceRouteQualifiedName == "com.example.featurec.FeatureCRoute" },
            "expected zero edges from FeatureCRoute (its only chain dead-ends at a local val) but found: " +
                result.edges.filter { it.sourceRouteQualifiedName == "com.example.featurec.FeatureCRoute" },
        )
    }

    @Test
    fun `an onBack chain that transitively re-enters the nav-host composable must not leak that composable's unrelated sibling wiring`() {
        // Regression test for a real false-positive edge (adapted from PR #63's fix). FeatureCRoute's
        // onBack is bound, at its one wiring call site inside AppNavHost, to a lambda that calls
        // resetNavigation() - a helper that (for a reason unrelated to FeatureCRoute, e.g. tearing
        // down and rebuilding the whole nav graph on some error path) calls AppNavHost(...) again,
        // with inert, non-mutating callbacks.
        //
        // Known-function-call resolution (case 3 in CallGraphTraversal's kdoc) then tries to
        // re-enter AppNavHost's *entire* body at the next depth - which also textually contains
        // featureANavEntries's own, completely unrelated onSomeAction wiring, since that wiring's
        // inline lambda argument is written at its call site *inside* AppNavHost, not inside
        // featureANavEntries itself. Before the fix this would have been misattributed as reachable
        // from FeatureCRoute; entryHostingFunctions must still refuse it under the new detection
        // mode. FeatureARoute's own edge (found via the real App() bootstrap's NavBackStack) must
        // survive untouched.
        val appFile = parser.parse(
            "App.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entryProvider
            import com.example.featurea.FeatureARoute
            import com.example.featurea.featureANavEntries
            import com.example.featurec.featureCNavEntries

            fun AppNavHost(
                popBack: () -> Unit,
                navigateTo: (NavKey) -> Unit,
            ) {
                NavDisplay(
                    entryProvider = entryProvider<NavKey> {
                        featureANavEntries(onSomeAction = { navigateTo(FeatureARoute) })
                        featureCNavEntries(onBack = { resetNavigation() })
                    },
                )
            }

            fun App() {
                val backStack = NavBackStack<NavKey>()
                val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }
                AppNavHost(popBack = {}, navigateTo = navigateTo)
            }

            fun resetNavigation() {
                AppNavHost(popBack = {}, navigateTo = {})
            }

            fun NavDisplay(entryProvider: Any) = Unit
            """.trimIndent(),
        )
        val featureA = parser.parse(
            "FeatureANavEntries.kt",
            """
            package com.example.featurea

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureARoute : NavKey

            fun EntryProviderScope<NavKey>.featureANavEntries(onSomeAction: () -> Unit) {
                entry<FeatureARoute> { FeatureAScreen(onSomeAction) }
            }

            fun FeatureAScreen(onSomeAction: () -> Unit) = Unit
            """.trimIndent(),
        )
        val featureC = parser.parse(
            "FeatureCNavEntries.kt",
            """
            package com.example.featurec

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureCRoute : NavKey

            fun EntryProviderScope<NavKey>.featureCNavEntries(onBack: () -> Unit) {
                entry<FeatureCRoute> { FeatureCScreen(onBack = onBack) }
            }

            fun FeatureCScreen(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(appFile, featureA, featureC))

        // FeatureARoute's own edge (from its own onSomeAction wiring, resolved through the real
        // App() bootstrap's NavBackStack) is legitimate and must survive - it happens to be a
        // self-loop since onSomeAction navigates to FeatureARoute itself. FeatureCRoute must
        // contribute nothing - resetNavigation() rebuilding the nav graph is not FeatureCRoute
        // navigating anywhere.
        assertEquals(
            setOf(NavEdge("com.example.featurea.FeatureARoute", "com.example.featurea.FeatureARoute")),
            result.edges.toSet(),
        )
    }

    @Test
    fun `a route reached only via a shared wrapper's content slot must not inherit an unrelated aggregator's own mutation calls`() {
        // Regression test for a second, distinct real false-positive bug (adapted from PR #65's
        // fix; different mechanism than the entry-hosting-function-reentry bug above, and NOT
        // fixed by that guard). FeatureScaffold is a shared, general-purpose wrapper used by
        // multiple unrelated routes and takes two function-typed parameters: onBack (a real
        // navigation callback) and content (a plain UI composition slot invoked once, inline, in
        // the wrapper's own body).
        //
        // RouteB.Top is the real navigation aggregator: its own entry<RouteB.Top> registration wires
        // 4 real, legitimate mutation calls (to RouteB.Edit, RouteC, RouteA, RouteD, via the real
        // navigateTo closure threaded from App()'s NavBackStack), reached through FeatureScaffold's
        // content slot.
        //
        // RouteC has no mutation call anywhere in its own reachable code - its only callback is a
        // plain onBack threaded through FeatureScaffold, same as RouteB.Top's onBack. Because
        // FeatureScaffold's body invokes content() (case 2's "parameter reference" reverse-edge
        // machinery treats this exactly like invoking onBack), RouteC's search - while resolving its
        // own onBack through FeatureScaffold - also stumbles onto FeatureScaffold's "content"
        // parameter being live, and reverse-searches every project-wide call site of FeatureScaffold,
        // including RouteB.Top's, whose content argument is a lambda holding RouteB.Top's own real
        // mutation calls. Before the fix, that lambda was scanned as if it were reachable from
        // RouteC, producing RouteC's edges as an exact, target-for-target copy of RouteB.Top's own
        // edges - including a nonsensical self-loop, since one of RouteB.Top's real edges targets
        // RouteC itself.
        val shared = parser.parse(
            "Shared.kt",
            """
            package com.example.shared

            fun FeatureScaffold(onBack: () -> Unit, content: () -> Unit) {
                TopBar(onBack)
                content()
            }
            fun TopBar(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )
        val routeA = parser.parse(
            "RouteA.kt",
            """
            package com.example.routea

            import androidx.navigation3.runtime.NavKey

            object RouteA : NavKey
            """.trimIndent(),
        )
        val routeD = parser.parse(
            "RouteD.kt",
            """
            package com.example.routed

            import androidx.navigation3.runtime.NavKey

            object RouteD : NavKey
            """.trimIndent(),
        )
        val routeB = parser.parse(
            "RouteB.kt",
            """
            package com.example.routeb

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.shared.FeatureScaffold
            import com.example.routea.RouteA
            import com.example.routec.RouteC
            import com.example.routed.RouteD

            sealed interface RouteB : NavKey {
                object Top : RouteB
                object Edit : RouteB
            }

            fun EntryProviderScope<NavKey>.routeBNavEntries(navigateTo: (NavKey) -> Unit, popBack: () -> Unit) {
                entry<RouteB.Top> {
                    FeatureScaffold(onBack = {}, content = {
                        RouteBTopScreen(
                            onEditClick = { navigateTo(RouteB.Edit) },
                            onGoC = { navigateTo(RouteC) },
                            onGoA = { navigateTo(RouteA) },
                            onGoD = { navigateTo(RouteD) },
                        )
                    })
                }
                entry<RouteB.Edit> { RouteBEditScreen(onBack = popBack) }
            }

            fun RouteBTopScreen(
                onEditClick: () -> Unit,
                onGoC: () -> Unit,
                onGoA: () -> Unit,
                onGoD: () -> Unit,
            ) = Unit
            fun RouteBEditScreen(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )
        val routeC = parser.parse(
            "RouteC.kt",
            """
            package com.example.routec

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.shared.FeatureScaffold

            object RouteC : NavKey

            fun EntryProviderScope<NavKey>.routeCNavEntries(popBack: () -> Unit) {
                entry<RouteC> { FeatureCScreen(onBack = popBack) }
            }

            fun FeatureCScreen(onBack: () -> Unit) {
                FeatureScaffold(onBack = onBack, content = { FeatureCBody() })
            }

            fun FeatureCBody() = Unit
            """.trimIndent(),
        )
        val appNavHost = parser.parse(
            "AppNavHost.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider
            import com.example.routea.RouteA
            import com.example.routeb.routeBNavEntries
            import com.example.routec.routeCNavEntries
            import com.example.routed.RouteD

            fun AppNavHost(navigateTo: (NavKey) -> Unit, popBack: () -> Unit) {
                entryProvider<NavKey> {
                    entry<RouteA> { RouteAScreen() }
                    routeBNavEntries(navigateTo = navigateTo, popBack = popBack)
                    routeCNavEntries(popBack = popBack)
                    entry<RouteD> { RouteDScreen() }
                }
            }

            fun App() {
                val backStack = NavBackStack<NavKey>()
                val navigateTo: (NavKey) -> Unit = { key -> backStack.add(key) }
                AppNavHost(navigateTo = navigateTo, popBack = {})
            }

            fun RouteAScreen() = Unit
            fun RouteDScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(shared, routeA, routeD, routeB, routeC, appNavHost))

        // RouteB.Top's own 4 real edges must survive untouched.
        assertEquals(
            setOf(
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routeb.RouteB.Edit"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routec.RouteC"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routea.RouteA"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routed.RouteD"),
            ),
            result.edges.toSet(),
        )
        // In particular, RouteC must contribute nothing at all - no copy of RouteB.Top's edges, and
        // no self-loop.
        assertTrue(
            result.edges.none { it.sourceRouteQualifiedName == "com.example.routec.RouteC" },
            "expected zero edges from RouteC but found: " +
                result.edges.filter { it.sourceRouteQualifiedName == "com.example.routec.RouteC" },
        )
    }

    @Test
    fun `a route reached only via a shared wrapper's content slot must not inherit an unrelated aggregator's own mutation calls, when the aggregator binds content by callable reference instead of an inline lambda`() {
        // Same false-positive mechanism and same fixture shape as the test above, but RouteB.Top
        // binds FeatureScaffold's content slot with a callable reference (::RouteBTopContent)
        // instead of writing the mutation-laden code as an inline lambda literal at the call site.
        // resolveBoundExpression's `is KtCallableReferenceExpression` branch is only guarded against
        // re-entering an entryHostingFunction (a function that itself hosts entry<X> {} calls) - it
        // is NOT guarded against resolving into a plain named function whose *call site* (the
        // `::RouteBTopContent` reference expression itself) is lexically nested inside a *different*
        // route's own entry<X> {} registration lambda, the way the lambda-literal branch now is.
        // RouteBTopContent itself is an ordinary function, not an entry-hosting one, so
        // blockedAsEntryHostingFunction never triggers - if this reproduces the leak, it is a
        // distinct, still-unfixed gap in the existing guard rather than the same mechanism restated.
        val shared = parser.parse(
            "Shared.kt",
            """
            package com.example.shared

            fun FeatureScaffold(onBack: () -> Unit, content: () -> Unit) {
                TopBar(onBack)
                content()
            }
            fun TopBar(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )
        val routeA = parser.parse(
            "RouteA.kt",
            """
            package com.example.routea

            import androidx.navigation3.runtime.NavKey

            object RouteA : NavKey
            """.trimIndent(),
        )
        val routeD = parser.parse(
            "RouteD.kt",
            """
            package com.example.routed

            import androidx.navigation3.runtime.NavKey

            object RouteD : NavKey
            """.trimIndent(),
        )
        val routeB = parser.parse(
            "RouteB.kt",
            """
            package com.example.routeb

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.shared.FeatureScaffold
            import com.example.routea.RouteA
            import com.example.routec.RouteC
            import com.example.routed.RouteD

            sealed interface RouteB : NavKey {
                object Top : RouteB
                object Edit : RouteB
            }

            fun EntryProviderScope<NavKey>.routeBNavEntries(popBack: () -> Unit) {
                entry<RouteB.Top> {
                    FeatureScaffold(onBack = {}, content = ::RouteBTopContent)
                }
                entry<RouteB.Edit> { RouteBEditScreen(onBack = popBack) }
            }

            // A plain top-level function - written directly in backStack.add(...) form (matching
            // the direct-construction-and-use shape) so this fixture doesn't need to also thread a
            // navigateTo parameter through a callable reference target, which is a separate,
            // unrelated question from the one this test is isolating.
            fun RouteBTopContent(backStack: NavBackStack<NavKey>) {
                backStack.add(RouteB.Edit)
                backStack.add(RouteC)
                backStack.add(RouteA)
                backStack.add(RouteD)
            }

            fun RouteBEditScreen(onBack: () -> Unit) = Unit
            """.trimIndent(),
        )
        val routeC = parser.parse(
            "RouteC.kt",
            """
            package com.example.routec

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.shared.FeatureScaffold

            object RouteC : NavKey

            fun EntryProviderScope<NavKey>.routeCNavEntries(popBack: () -> Unit) {
                entry<RouteC> { FeatureCScreen(onBack = popBack) }
            }

            fun FeatureCScreen(onBack: () -> Unit) {
                FeatureScaffold(onBack = onBack, content = { FeatureCBody() })
            }

            fun FeatureCBody() = Unit
            """.trimIndent(),
        )
        val appNavHost = parser.parse(
            "AppNavHost.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavBackStack
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider
            import com.example.routea.RouteA
            import com.example.routeb.routeBNavEntries
            import com.example.routec.routeCNavEntries
            import com.example.routed.RouteD

            fun AppNavHost(popBack: () -> Unit) {
                entryProvider<NavKey> {
                    entry<RouteA> { RouteAScreen() }
                    routeBNavEntries(popBack = popBack)
                    routeCNavEntries(popBack = popBack)
                    entry<RouteD> { RouteDScreen() }
                }
            }

            fun App() {
                val backStack = NavBackStack<NavKey>(RouteA)
                AppNavHost(popBack = {})
            }

            fun RouteAScreen() = Unit
            fun RouteDScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(shared, routeA, routeD, routeB, routeC, appNavHost))

        // RouteB.Top's own 4 real edges (via RouteBTopContent, reached by callable reference) must
        // survive untouched - same real-edge set as the inline-lambda variant above. RouteBTopContent's
        // own `backStack` parameter is recognized as a NavBackStack alias directly (see
        // findBackStackAliasNames), with no threading needed from App()'s anchor instance.
        assertEquals(
            setOf(
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routeb.RouteB.Edit"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routec.RouteC"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routea.RouteA"),
                NavEdge("com.example.routeb.RouteB.Top", "com.example.routed.RouteD"),
            ),
            result.edges.filter { it.sourceRouteQualifiedName == "com.example.routeb.RouteB.Top" }.toSet(),
        )
        // In particular, RouteC must contribute nothing at all - no copy of RouteB.Top's edges, and
        // no self-loop. This is what fails if the KtCallableReferenceExpression branch's missing
        // blockedAsAnotherRoutesEntryRegistration guard is the still-unfixed leak.
        assertTrue(
            result.edges.none { it.sourceRouteQualifiedName == "com.example.routec.RouteC" },
            "expected zero edges from RouteC but found: " +
                result.edges.filter { it.sourceRouteQualifiedName == "com.example.routec.RouteC" },
        )
    }
}

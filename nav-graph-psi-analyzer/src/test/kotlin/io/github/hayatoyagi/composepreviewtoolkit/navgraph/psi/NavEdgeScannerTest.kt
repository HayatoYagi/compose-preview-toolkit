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
    fun `pattern (i) callback-threaded and inline edges are both found, mirroring the real sample's exact shape`() {
        // Mirrors sample/app/AppNavHost.kt + sample/feature-a exactly: HomeRoute's navigateTo is
        // written inline in the entry block; FeatureARoute's is threaded up through
        // featureANavEntries's onProceedClick parameter, forwarded once more through
        // FeatureAScreen's own onProceedClick parameter, and only actually called from
        // AppNavHost's own call site of featureANavEntries.
        val appNavHost = parser.parse(
            "AppNavHost.kt",
            """
            package com.example.app

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider
            import com.example.featurea.FeatureARoute
            import com.example.featurea.featureANavEntries
            import com.example.featureb.FeatureBRoute

            object HomeRoute : NavKey

            fun appNavHost() {
                entryProvider<NavKey> {
                    entry<HomeRoute> {
                        HomeScreen(onGoToFeatureAClick = { navigateTo(FeatureARoute) })
                    }
                    featureANavEntries(onProceedClick = { navigateTo(FeatureBRoute) })
                }
            }

            fun navigateTo(route: NavKey) = Unit
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

            object FeatureBRoute : NavKey

            fun EntryProviderScope<NavKey>.featureBNavEntries() {
                entry<FeatureBRoute> { FeatureBScreen() }
            }

            fun FeatureBScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(appNavHost, featureA, featureB))

        assertEquals(
            setOf(
                NavEdge("com.example.app.HomeRoute", "com.example.featurea.FeatureARoute"),
                NavEdge("com.example.featurea.FeatureARoute", "com.example.featureb.FeatureBRoute"),
            ),
            result.edges.toSet(),
        )
    }

    @Test
    fun `pattern (ii) direct navigateTo call, one level deep, with no callback parameter at all`() {
        val file = parser.parse(
            "FeatureC.kt",
            """
            package com.example.featurec

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureCRoute : NavKey
            object FeatureDRoute : NavKey

            fun registerFeatureC() {
                entry<FeatureCRoute> { restartFlow() }
                entry<FeatureDRoute> { FeatureDScreen() }
            }

            fun restartFlow() {
                navigateTo(FeatureDRoute)
            }

            fun FeatureDScreen() = Unit
            fun navigateTo(route: NavKey) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.featurec.FeatureCRoute", "com.example.featurec.FeatureDRoute")),
            result.edges,
        )
    }

    @Test
    fun `pattern (ii) navigateTo called directly inside the entry block itself`() {
        val file = parser.parse(
            "FeatureE.kt",
            """
            package com.example.featuree

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FeatureERoute : NavKey
            object FeatureFRoute : NavKey

            fun registerFeatureE() {
                entry<FeatureERoute> { navigate(FeatureFRoute) }
                entry<FeatureFRoute> { FeatureFScreen() }
            }

            fun FeatureFScreen() = Unit
            fun navigate(route: NavKey) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.featuree.FeatureERoute", "com.example.featuree.FeatureFRoute")),
            result.edges,
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

            import androidx.navigation3.runtime.NavKey

            object RouteFoo : NavKey

            fun helper() {
                navigateTo(RouteFoo)
            }

            fun navigateTo(route: NavKey) = Unit
            """.trimIndent(),
        )
        val helperB = parser.parse(
            "HelperB.kt",
            """
            package com.example.b

            import androidx.navigation3.runtime.NavKey

            object RouteBar : NavKey

            fun helper() {
                navigateTo(RouteBar)
            }

            fun navigateTo(route: NavKey) = Unit
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

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object DeepRoute : NavKey
            object TargetRoute : NavKey

            fun registerDeep() {
                entry<DeepRoute> { level1() }
                entry<TargetRoute> { TargetScreen() }
            }

            fun level1() { level2() }
            fun level2() { level3() }
            fun level3() { level4() }
            fun level4() { navigateTo(TargetRoute) }

            fun TargetScreen() = Unit
            fun navigateTo(route: NavKey) = Unit
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
    fun `a non-standard-named callback with an explicit NavKey parameter type is found via declared type, not name`() {
        val file = parser.parse(
            "TypedDirect.kt",
            """
            package com.example.typeddirect

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object TypedSourceRoute : NavKey
            object TypedTargetRoute : NavKey

            fun registerTypedDirect(goTo: (NavKey) -> Unit) {
                entry<TypedSourceRoute> { goTo(TypedTargetRoute) }
                entry<TypedTargetRoute> { TypedTargetScreen() }
            }

            fun TypedTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.typeddirect.TypedSourceRoute", "com.example.typeddirect.TypedTargetRoute")),
            result.edges,
        )
    }

    @Test
    fun `a callback parameter typed to a specific known route (not NavKey itself) is also recognized`() {
        val file = parser.parse(
            "TypedRouteSpecific.kt",
            """
            package com.example.typedroutespecific

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object TypedRouteSpecificSource : NavKey
            object TypedRouteSpecificTarget : NavKey

            fun registerTypedRouteSpecific(goTo: (TypedRouteSpecificTarget) -> Unit) {
                entry<TypedRouteSpecificSource> { goTo(TypedRouteSpecificTarget) }
                entry<TypedRouteSpecificTarget> { TypedRouteSpecificTargetScreen() }
            }

            fun TypedRouteSpecificTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(
                NavEdge(
                    "com.example.typedroutespecific.TypedRouteSpecificSource",
                    "com.example.typedroutespecific.TypedRouteSpecificTarget",
                ),
            ),
            result.edges,
        )
    }

    @Test
    fun `a non-standard-named callback threaded through wrapper functions is found by declared type at the call site`() {
        // Mirrors pattern (i)'s callback-threading shape (FeatureAScreen references its callback by
        // value, not by invoking it, forcing reverse-threading to the wiring call site) but the
        // wiring call site forwards straight into the app's own NavKey-typed callback instead of a
        // name-matched navigateTo(...) call.
        val file = parser.parse(
            "TypedThreaded.kt",
            """
            package com.example.typedthreaded

            import androidx.navigation3.runtime.EntryProviderScope
            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider

            object ThreadedFeatureRoute : NavKey
            object ThreadedDestRoute : NavKey

            fun threadedAppNavHost(onNavigate: (NavKey) -> Unit) {
                entryProvider<NavKey> {
                    entry<ThreadedDestRoute> { ThreadedDestScreen() }
                    threadedFeatureNavEntries(goTo = { onNavigate(ThreadedDestRoute) })
                }
            }

            fun EntryProviderScope<NavKey>.threadedFeatureNavEntries(goTo: (NavKey) -> Unit) {
                entry<ThreadedFeatureRoute> { ThreadedFeatureScreen(goTo) }
            }

            fun ThreadedDestScreen() = Unit

            fun ThreadedFeatureScreen(goTo: (NavKey) -> Unit) {
                ThreadedButton(onClick = goTo)
            }

            fun ThreadedButton(onClick: (NavKey) -> Unit) = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertEquals(
            listOf(NavEdge("com.example.typedthreaded.ThreadedFeatureRoute", "com.example.typedthreaded.ThreadedDestRoute")),
            result.edges,
        )
    }

    @Test
    fun `a navigate call qualified with its parent route disambiguates between sealed-hierarchy siblings sharing a leaf name`() {
        // TodoRoute.Detail and NoteRoute.Detail are two different sealed-hierarchy siblings that
        // happen to share the leaf simple name "Detail" (see NavNode's own kdoc for this exact
        // shape). navigateTo(TodoRoute.Detail) carries enough information in its own qualifier to
        // pick the right one without guessing.
        val file = parser.parse(
            "SiblingRoutes.kt",
            """
            package com.example.siblings

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
                entry<ListRoute> { navigateTo(TodoRoute.Detail) }
                entry<TodoRoute.Detail> { TodoDetailScreen() }
                entry<NoteRoute.Detail> { NoteDetailScreen() }
            }

            fun TodoDetailScreen() = Unit
            fun NoteDetailScreen() = Unit
            fun navigateTo(route: NavKey) = Unit
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
        // Same sibling shape as above, but the navigate call omits the qualifier entirely, so
        // there's genuinely nothing in the source to disambiguate with - this must still warn
        // rather than guess.
        val file = parser.parse(
            "BareSiblingRoutes.kt",
            """
            package com.example.baresiblings

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
                entry<ListRoute> { navigateTo(Detail) }
                entry<TodoRoute.Detail> { TodoDetailScreen() }
                entry<NoteRoute.Detail> { NoteDetailScreen() }
            }

            fun TodoDetailScreen() = Unit
            fun NoteDetailScreen() = Unit
            fun navigateTo(route: NavKey) = Unit
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
        // file and the navigate call lives in a *different* file that writes
        // `import com.example.siblingsimport.TodoRoute.Detail` and then calls `navigateTo(Detail)`
        // bare - the idiomatic-Kotlin way to avoid repeating the qualifier at every call site.
        // There's no written qualifier at the call site to narrow with (same as the bare-reference
        // test above), but the import alone is enough to pick TodoRoute.Detail over NoteRoute.Detail
        // without needing type resolution.
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

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import com.example.siblingsimport.TodoRoute.Detail

            object ListRoute : NavKey

            fun registerAppImport() {
                entry<ListRoute> { navigateTo(Detail) }
            }

            fun navigateTo(route: NavKey) = Unit
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

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object ListRoute : NavKey

            sealed interface Foo : NavKey {
                sealed interface TodoRoute : NavKey {
                    object Detail : TodoRoute
                }
            }

            fun register() {
                entry<ListRoute> { navigateTo(TodoRoute.Detail) }
                entry<Foo.TodoRoute.Detail> { NestedDetailScreen() }
            }

            fun NestedDetailScreen() = Unit
            fun navigateTo(route: NavKey) = Unit
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
    fun `a callback relying purely on type inference, with no explicit annotation, is not found - known limitation`() {
        // Matches the documented gap: a local val lambda with an untyped parameter is invisible to
        // this purely-syntactic scanner regardless of naming, since it isn't even tracked as a live
        // callable (only named-function value parameters, which always carry an explicit type in
        // Kotlin, are). No edge, no warning, no crash.
        val file = parser.parse(
            "UntypedWrapper.kt",
            """
            package com.example.untyped

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object UntypedSourceRoute : NavKey
            object UntypedTargetRoute : NavKey

            fun registerUntyped() {
                val goSomewhere = { key -> backStack.add(key) }
                entry<UntypedSourceRoute> { goSomewhere(UntypedTargetRoute) }
                entry<UntypedTargetRoute> { UntypedTargetScreen() }
            }

            fun UntypedTargetScreen() = Unit
            """.trimIndent(),
        )

        val result = NavEdgeScanner().scan(listOf(file))

        assertTrue(result.edges.isEmpty(), "expected no edges but found: ${result.edges}")
    }
}

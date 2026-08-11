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
}

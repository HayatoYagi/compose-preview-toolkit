package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NavNodeScannerTest {
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
    fun `top-level object registered via entry is found as a single node`() {
        val file = parser.parse(
            "FooRoute.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider

            object FooRoute : NavKey

            val provider = entryProvider<NavKey> {
                entry<FooRoute> { FooScreen() }
            }
            """.trimIndent(),
        )

        val nodes = NavNodeScanner().scan(listOf(file))

        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("com.example", node.packageName)
        assertEquals("FooRoute", node.simpleName)
        assertEquals("com.example.FooRoute", node.qualifiedName)
    }

    @Test
    fun `route nested in a sealed interface resolves a correctly nested qualifiedName`() {
        val file = parser.parse(
            "ParentRoute.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry
            import androidx.navigation3.runtime.entryProvider

            sealed interface ParentRoute : NavKey {
                data class Detail(val id: String) : ParentRoute
            }

            val provider = entryProvider<NavKey> {
                entry<ParentRoute.Detail> { DetailScreen() }
            }
            """.trimIndent(),
        )

        val nodes = NavNodeScanner().scan(listOf(file))

        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("com.example", node.packageName)
        assertEquals("Detail", node.simpleName)
        assertEquals("com.example.ParentRoute.Detail", node.qualifiedName)
    }

    @Test
    fun `entry calls across multiple separate files are all found`() {
        val fooFile = parser.parse(
            "FooRoute.kt",
            """
            package com.example.foo

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object FooRoute : NavKey

            fun registerFoo() {
                entry<FooRoute> { FooScreen() }
            }
            """.trimIndent(),
        )
        val barFile = parser.parse(
            "BarRoute.kt",
            """
            package com.example.bar

            import androidx.navigation3.runtime.NavKey
            import androidx.navigation3.runtime.entry

            object BarRoute : NavKey

            fun registerBar() {
                entry<BarRoute> { BarScreen() }
            }
            """.trimIndent(),
        )

        val nodes = NavNodeScanner().scan(listOf(fooFile, barFile))

        assertEquals(2, nodes.size)
        assertEquals(
            setOf("com.example.foo.FooRoute", "com.example.bar.BarRoute"),
            nodes.map { it.qualifiedName }.toSet(),
        )
    }

    @Test
    fun `a same-named function that doesn't match the entry shape is not picked up`() {
        val file = parser.parse(
            "NotAnEntry.kt",
            """
            package com.example

            object FooRoute

            // Wrong shape: no type argument, and a regular trailing arg instead of a lambda.
            fun entry(route: FooRoute) = Unit

            fun caller() {
                entry(FooRoute)
            }
            """.trimIndent(),
        )

        val nodes = NavNodeScanner().scan(listOf(file))

        assertTrue(nodes.isEmpty(), "expected no nodes but found: $nodes")
    }

    @Test
    fun `a plain function call is not mistaken for an entry call`() {
        val file = parser.parse(
            "PlainCall.kt",
            """
            package com.example

            fun doSomething() {
                println("hello")
            }
            """.trimIndent(),
        )

        val nodes = NavNodeScanner().scan(listOf(file))

        assertTrue(nodes.isEmpty(), "expected no nodes but found: $nodes")
    }

    @Test
    fun `entryFunctionNames is configurable to match a custom registration function name`() {
        val file = parser.parse(
            "CustomEntry.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey

            object FooRoute : NavKey

            fun register() {
                customEntry<FooRoute> { FooScreen() }
            }
            """.trimIndent(),
        )

        val defaultScanNodes = NavNodeScanner().scan(listOf(file))
        assertTrue(defaultScanNodes.isEmpty(), "default entryFunctionNames should not match customEntry")

        val customScanNodes = NavNodeScanner(entryFunctionNames = setOf("customEntry")).scan(listOf(file))
        assertEquals(1, customScanNodes.size)
        assertEquals("com.example.FooRoute", customScanNodes.single().qualifiedName)
    }
}

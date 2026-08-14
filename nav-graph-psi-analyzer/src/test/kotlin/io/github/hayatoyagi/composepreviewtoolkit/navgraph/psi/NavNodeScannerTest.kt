package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

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
        // Parsed via the in-memory text overload (no real file on disk to relativize), so
        // filePath falls all the way back to the raw name the KtFile was given (PsiFileFactory's
        // underlying LightVirtualFile normalizes a bare, separator-less name to a root-relative
        // "/FooRoute.kt" — still short and non-absolute, exactly what this fallback tier
        // guarantees), and line points at the entry<FooRoute> { ... } call site itself, not the
        // `object FooRoute` declaration.
        assertEquals("/FooRoute.kt", node.filePath)
        assertFalse(node.filePathIsRepoRelative)
        assertEquals(10, node.line)
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
        // The call site is entry<ParentRoute.Detail> { ... }, not the nested `Detail` declaration
        // several lines above it.
        assertEquals(12, node.line)
    }

    @Test
    fun `a real file inside the git repository gets a repo-root-relative filePath`() {
        // Deliberately written under this module's own build/ directory: build/ is still inside
        // the checked-out git working tree (git doesn't care about .gitignore for `rev-parse
        // --show-toplevel`/root detection), so this exercises real git-relativization end to end
        // without needing to fake or mock anything.
        val tempDir = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "navNodeSourceLocationTest")
            .toFile()
        try {
            val kotlinFile = File(tempDir, "RealFileRoute.kt").apply {
                writeText(
                    """
                    package com.example.real

                    import androidx.navigation3.runtime.NavKey
                    import androidx.navigation3.runtime.entry

                    object RealFileRoute : NavKey

                    fun register() {
                        entry<RealFileRoute> { RealFileScreen() }
                    }
                    """.trimIndent(),
                )
            }

            val nodes = NavNodeScanner().scan(listOf(parser.parse(kotlinFile)))

            assertEquals(1, nodes.size)
            val node = nodes.single()
            assertTrue(node.filePathIsRepoRelative, "expected a git-root-relative filePath but got: ${node.filePath}")
            assertFalse(File(node.filePath).isAbsolute, "filePath must never be absolute: ${node.filePath}")
            assertTrue(
                node.filePath.endsWith("RealFileRoute.kt"),
                "expected filePath to end with the real file name but got: ${node.filePath}",
            )
            assertEquals(9, node.line)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `a real file outside the git repository falls back to fallbackBaseDirectory-relative`() {
        val outsideRepoDir = Files.createTempDirectory("navNodeSourceLocationFallbackTest").toFile()
        try {
            val subDir = File(outsideRepoDir, "sub").apply { mkdirs() }
            val kotlinFile = File(subDir, "FallbackRoute.kt").apply {
                writeText(
                    """
                    package com.example.fallback

                    import androidx.navigation3.runtime.NavKey
                    import androidx.navigation3.runtime.entry

                    object FallbackRoute : NavKey

                    fun register() {
                        entry<FallbackRoute> { FallbackScreen() }
                    }
                    """.trimIndent(),
                )
            }

            val nodes = NavNodeScanner().scan(listOf(parser.parse(kotlinFile)), fallbackBaseDirectory = outsideRepoDir)

            assertEquals(1, nodes.size)
            val node = nodes.single()
            assertFalse(node.filePathIsRepoRelative)
            assertEquals("sub/FallbackRoute.kt", node.filePath)
            assertEquals(9, node.line)
        } finally {
            outsideRepoDir.deleteRecursively()
        }
    }

    @Test
    fun `detectGitRepoRoot returns null when pointed at a directory outside any git repository`() {
        val nonRepoDir = Files.createTempDirectory("navNodeSourceLocationNonRepoTest").toFile()
        try {
            assertNull(detectGitRepoRoot(nonRepoDir))
        } finally {
            nonRepoDir.deleteRecursively()
        }
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

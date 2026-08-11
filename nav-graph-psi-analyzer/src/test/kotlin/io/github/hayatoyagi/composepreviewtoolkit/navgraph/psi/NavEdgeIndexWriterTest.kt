package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter

class NavEdgeIndexWriterTest {
    @Test
    fun `formatNavEdgeIndex writes one tab-separated line per edge`() {
        val edges = listOf(
            NavEdge(sourceRouteQualifiedName = "com.example.HomeRoute", targetRouteQualifiedName = "com.example.FeatureARoute"),
            NavEdge(sourceRouteQualifiedName = "com.example.FeatureARoute", targetRouteQualifiedName = "com.example.FeatureBRoute"),
        )

        val formatted = formatNavEdgeIndex(edges)

        assertEquals(
            "com.example.HomeRoute\tcom.example.FeatureARoute\n" +
                "com.example.FeatureARoute\tcom.example.FeatureBRoute\n",
            formatted,
        )
    }

    @Test
    fun `writeNavEdgeIndex writes the same content to a Writer`() {
        val edges = listOf(NavEdge("com.example.HomeRoute", "com.example.FeatureARoute"))
        val writer = StringWriter()

        writeNavEdgeIndex(edges, writer)

        assertEquals(formatNavEdgeIndex(edges), writer.toString())
    }

    @Test
    fun `parseNavEdgeIndex round-trips formatNavEdgeIndex's output`() {
        val edges = listOf(
            NavEdge("com.example.HomeRoute", "com.example.FeatureARoute"),
            NavEdge("com.example.FeatureARoute", "com.example.FeatureBRoute"),
        )

        val parsed = parseNavEdgeIndex(StringReader(formatNavEdgeIndex(edges)))

        assertEquals(edges, parsed)
    }

    @Test
    fun `parseNavEdgeIndex skips blank lines`() {
        val parsed = parseNavEdgeIndex(StringReader("\ncom.example.HomeRoute\tcom.example.FeatureARoute\n\n"))

        assertEquals(listOf(NavEdge("com.example.HomeRoute", "com.example.FeatureARoute")), parsed)
    }

    @Test
    fun `parseNavEdgeIndex rejects a malformed line`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseNavEdgeIndex(StringReader("com.example.HomeRoute\n"))
        }
        assertTrue(exception.message.orEmpty().contains("Invalid nav edge index line"))
    }
}

package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter

class NavNodeIndexWriterTest {
    @Test
    fun `formatNavNodeIndex writes one tab-separated line per node`() {
        val nodes = listOf(
            NavNode(packageName = "com.example.foo", simpleName = "FooRoute", qualifiedName = "com.example.foo.FooRoute"),
            NavNode(
                packageName = "com.example",
                simpleName = "Detail",
                qualifiedName = "com.example.ParentRoute.Detail",
            ),
        )

        val formatted = formatNavNodeIndex(nodes)

        assertEquals(
            "com.example.foo\tFooRoute\tcom.example.foo.FooRoute\n" +
                "com.example\tDetail\tcom.example.ParentRoute.Detail\n",
            formatted,
        )
    }

    @Test
    fun `writeNavNodeIndex writes the same content to a Writer`() {
        val nodes = listOf(NavNode("com.example", "FooRoute", "com.example.FooRoute"))
        val writer = StringWriter()

        writeNavNodeIndex(nodes, writer)

        assertEquals(formatNavNodeIndex(nodes), writer.toString())
    }
}

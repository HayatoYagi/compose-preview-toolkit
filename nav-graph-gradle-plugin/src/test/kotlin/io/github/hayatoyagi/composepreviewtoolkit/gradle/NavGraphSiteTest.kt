package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdge
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NavGraphSiteTest {
    @Test
    fun `parseScreenshotIndex parses three tab-separated columns per line`() {
        val text = "com.example\tGreetingScreenPreview_Screenshot\tcom.example.GreetingScreenPreview\n"

        val entries = parseScreenshotIndex(text)

        assertEquals(
            listOf(
                ScreenshotIndexEntry(
                    packageName = "com.example",
                    wrapperName = "GreetingScreenPreview_Screenshot",
                    callExpression = "com.example.GreetingScreenPreview",
                ),
            ),
            entries,
        )
    }

    @Test
    fun `parseScreenshotIndex skips blank lines`() {
        val entries = parseScreenshotIndex("\ncom.example\tFoo_Screenshot\tcom.example.Foo\n\n")

        assertEquals(1, entries.size)
    }

    @Test
    fun `parseScreenshotIndex rejects a malformed line`() {
        val exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            parseScreenshotIndex("com.example\tonlyTwoColumns\n")
        }
        assertTrue(exception.message.orEmpty().contains("Invalid screenshot index line"))
    }

    @Test
    fun `stripRouteNameSuffix strips the first matching suffix`() {
        assertEquals("FeatureA", stripRouteNameSuffix("FeatureARoute", setOf("Destination", "Route")))
        assertEquals("FeatureB", stripRouteNameSuffix("FeatureBDestination", setOf("Destination", "Route")))
    }

    @Test
    fun `stripRouteNameSuffix leaves a name unchanged when no suffix matches`() {
        assertEquals("Home", stripRouteNameSuffix("Home", setOf("Destination", "Route")))
    }

    @Test
    fun `matchScreenshotEntry finds a case-insensitive substring match against wrapperName`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example.featurea", "featurea_screen_Screenshot", "com.example.featurea.preview"),
        )
        val node = NavNode("com.example.featurea", "FeatureARoute", "com.example.featurea.FeatureARoute")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Destination", "Route"))

        assertEquals(screenshotEntries.single(), matched)
    }

    @Test
    fun `matchScreenshotEntry returns null when nothing matches`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example", "GreetingScreenPreview_Screenshot", "com.example.GreetingScreenPreview"),
        )
        val node = NavNode("com.example", "HomeRoute", "com.example.HomeRoute")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Destination", "Route"))

        assertNull(matched)
    }

    @Test
    fun `matchScreenshotEntry never matches on a blank stripped name`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example", "Route_Screenshot", "com.example.Route"),
        )
        // simpleName "Route" strips entirely to "" via the "Route" suffix.
        val node = NavNode("com.example", "Route", "com.example.Route")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Route"))

        assertNull(matched)
    }

    @Test
    fun `findThumbnailFile picks the lexicographically-first prefix match`() {
        val files = listOf(
            File("FeatureAScreen_Screenshot_Light_abc123_0.png"),
            File("FeatureAScreen_Screenshot_Dark_def456_0.png"),
            File("OtherWrapper_Screenshot_Light_zzz999_0.png"),
        )

        val thumbnail = findThumbnailFile("FeatureAScreen_Screenshot", files)

        assertEquals(File("FeatureAScreen_Screenshot_Dark_def456_0.png"), thumbnail)
    }

    @Test
    fun `findThumbnailFile returns null when no file matches the wrapper prefix`() {
        val files = listOf(File("OtherWrapper_Screenshot_Light_zzz999_0.png"))

        assertNull(findThumbnailFile("FeatureAScreen_Screenshot", files))
    }

    @Test
    fun `buildGalleryEntries pairs a matched node with its thumbnail and leaves others unmatched`() {
        val nodes = listOf(
            NavNode("com.example", "HomeRoute", "com.example.HomeRoute"),
            NavNode("com.example.featurea", "FeatureARoute", "com.example.featurea.FeatureARoute"),
        )
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example.featurea", "FeatureAScreen_Screenshot", "com.example.featurea.preview"),
        )
        val thumbnailFile = File("FeatureAScreen_Screenshot_Light_abc123_0.png")

        val entries = buildGalleryEntries(
            nodes = nodes,
            screenshotEntries = screenshotEntries,
            referenceImages = listOf(thumbnailFile),
            suffixesToStrip = setOf("Destination", "Route"),
        )

        assertEquals(2, entries.size)
        val home = entries.single { it.node.simpleName == "HomeRoute" }
        val featureA = entries.single { it.node.simpleName == "FeatureARoute" }
        assertNull(home.thumbnail)
        assertEquals(thumbnailFile, featureA.thumbnail)
    }

    @Test
    fun `buildGalleryEntries deduplicates by qualifiedName and sorts deterministically`() {
        val nodes = listOf(
            NavNode("com.example", "BRoute", "com.example.BRoute"),
            NavNode("com.example", "ARoute", "com.example.ARoute"),
            NavNode("com.example", "ARoute", "com.example.ARoute"),
        )

        val entries = buildGalleryEntries(nodes, emptyList(), emptyList(), emptySet())

        assertEquals(listOf("com.example.ARoute", "com.example.BRoute"), entries.map { it.node.qualifiedName })
    }

    @Test
    fun `buildGallerySiteHtml renders a card per route and a placeholder for unmatched thumbnails`() {
        val cards = listOf(
            GalleryCard(
                qualifiedName = "com.example.HomeRoute",
                simpleName = "HomeRoute",
                thumbnailDataUri = null,
            ),
            GalleryCard(
                qualifiedName = "com.example.featurea.FeatureARoute",
                simpleName = "FeatureARoute",
                thumbnailDataUri = "data:image/png;base64,AAAA",
            ),
        )

        val html = buildGallerySiteHtml(cards, mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("HomeRoute"))
        assertTrue(html.contains("com.example.HomeRoute"))
        assertTrue(html.contains("FeatureARoute"))
        assertTrue(html.contains("com.example.featurea.FeatureARoute"))
        assertTrue(html.contains("No screenshot"))
        assertTrue(html.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `buildGallerySiteHtml escapes route names`() {
        val cards = listOf(
            GalleryCard(qualifiedName = "com.example.<Weird>", simpleName = "<Weird>", thumbnailDataUri = null),
        )

        val html = buildGallerySiteHtml(cards, mermaidGraph = "graph TD;\n")

        assertFalse(html.contains("<Weird>"))
        assertTrue(html.contains("&lt;Weird&gt;"))
    }

    @Test
    fun `buildGallerySiteHtml embeds the mermaid CDN script and the given graph definition`() {
        val html = buildGallerySiteHtml(emptyList(), mermaidGraph = "graph TD;\nn0[\"Home\"];\n")

        assertTrue(html.contains("mermaid"))
        assertTrue(html.contains("cdn.jsdelivr.net"))
        assertTrue(html.contains("graph TD;"))
        assertTrue(html.contains("n0[\"Home\"];"))
    }

    @Test
    fun `buildMermaidGraph assigns positional ids and keeps qualified names only in labels`() {
        val nodes = listOf(
            NavNode("com.example", "HomeRoute", "com.example.HomeRoute"),
            NavNode("com.example.featurea", "FeatureARoute", "com.example.featurea.FeatureARoute"),
        )
        val edges = listOf(NavEdge("com.example.HomeRoute", "com.example.featurea.FeatureARoute"))

        val graph = buildMermaidGraph(nodes, edges)

        assertTrue(graph.startsWith("graph TD;\n"))
        assertTrue(graph.contains("n0[\"HomeRoute\"];"))
        assertTrue(graph.contains("n1[\"FeatureARoute\"];"))
        assertTrue(graph.contains("n0 --> n1;"))
        // No raw qualified name (dots) should ever appear as a bare Mermaid node id/reference —
        // only inside a quoted label, where dots are harmless.
        assertFalse(graph.contains("com.example.HomeRoute -->"))
        assertFalse(graph.contains("--> com.example"))
    }

    @Test
    fun `buildMermaidGraph renders a node with no outgoing or incoming edges`() {
        val nodes = listOf(NavNode("com.example", "TerminalRoute", "com.example.TerminalRoute"))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("n0[\"TerminalRoute\"];"))
    }

    @Test
    fun `buildMermaidGraph renders a real cycle (A to B and B to A) as two edge lines, not a crash or dedup`() {
        val nodes = listOf(
            NavNode("com.example.featurea", "FeatureARoute", "com.example.featurea.FeatureARoute"),
            NavNode("com.example.featureb", "FeatureBRoute", "com.example.featureb.FeatureBRoute"),
        )
        val edges = listOf(
            NavEdge("com.example.featurea.FeatureARoute", "com.example.featureb.FeatureBRoute"),
            NavEdge("com.example.featureb.FeatureBRoute", "com.example.featurea.FeatureARoute"),
        )

        val graph = buildMermaidGraph(nodes, edges)

        assertTrue(graph.contains("n0 --> n1;"))
        assertTrue(graph.contains("n1 --> n0;"))
    }

    @Test
    fun `buildMermaidGraph drops an edge referencing a route absent from nodes instead of emitting a dangling id`() {
        val nodes = listOf(NavNode("com.example", "HomeRoute", "com.example.HomeRoute"))
        val edges = listOf(NavEdge("com.example.HomeRoute", "com.example.Unknown"))

        val graph = buildMermaidGraph(nodes, edges)

        assertFalse(graph.contains("-->"))
    }

    @Test
    fun `buildMermaidGraph escapes a quote in a label without breaking mermaid's quoted-string syntax`() {
        val nodes = listOf(NavNode("com.example", "Weird\"Route", "com.example.Weird\"Route"))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("n0[\"Weird'Route\"];"))
    }

    @Test
    fun `buildMermaidGraph html-escapes angle brackets and ampersands in a label`() {
        val nodes = listOf(NavNode("com.example", "<Weird&Route>", "com.example.<Weird&Route>"))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertFalse(graph.contains("<Weird"))
        assertTrue(graph.contains("&lt;Weird&amp;Route&gt;"))
    }
}

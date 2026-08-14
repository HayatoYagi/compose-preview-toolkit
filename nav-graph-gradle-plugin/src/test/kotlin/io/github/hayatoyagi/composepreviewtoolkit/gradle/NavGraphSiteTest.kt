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

        assertEquals(screenshotEntries, matched)
    }

    @Test
    fun `matchScreenshotEntry returns every matching wrapper, sorted by wrapperName`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example.home", "HomeScreenPreview_Screenshot", "com.example.home.preview1"),
            ScreenshotIndexEntry("com.example.home", "HomeDetailScreenPreview_Screenshot", "com.example.home.preview2"),
            ScreenshotIndexEntry("com.example.other", "OtherScreen_Screenshot", "com.example.other.preview"),
        )
        val node = NavNode("com.example.home", "HomeRoute", "com.example.home.HomeRoute")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Destination", "Route"))

        assertEquals(
            listOf("HomeDetailScreenPreview_Screenshot", "HomeScreenPreview_Screenshot"),
            matched.map { it.wrapperName },
        )
    }

    @Test
    fun `matchScreenshotEntry returns an empty list when nothing matches`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example", "GreetingScreenPreview_Screenshot", "com.example.GreetingScreenPreview"),
        )
        val node = NavNode("com.example", "HomeRoute", "com.example.HomeRoute")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Destination", "Route"))

        assertTrue(matched.isEmpty())
    }

    @Test
    fun `matchScreenshotEntry never matches on a blank stripped name`() {
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example", "Route_Screenshot", "com.example.Route"),
        )
        // simpleName "Route" strips entirely to "" via the "Route" suffix.
        val node = NavNode("com.example", "Route", "com.example.Route")

        val matched = matchScreenshotEntry(node, screenshotEntries, setOf("Route"))

        assertTrue(matched.isEmpty())
    }

    @Test
    fun `findThumbnailFiles returns every prefix match sorted by file name`() {
        val files = listOf(
            File("FeatureAScreen_Screenshot_Light_abc123_0.png"),
            File("FeatureAScreen_Screenshot_Dark_def456_0.png"),
            File("OtherWrapper_Screenshot_Light_zzz999_0.png"),
        )

        val thumbnails = findThumbnailFiles("FeatureAScreen_Screenshot", files)

        assertEquals(
            listOf(
                File("FeatureAScreen_Screenshot_Dark_def456_0.png"),
                File("FeatureAScreen_Screenshot_Light_abc123_0.png"),
            ),
            thumbnails,
        )
    }

    @Test
    fun `findThumbnailFiles returns an empty list when no file matches the wrapper prefix`() {
        val files = listOf(File("OtherWrapper_Screenshot_Light_zzz999_0.png"))

        assertTrue(findThumbnailFiles("FeatureAScreen_Screenshot", files).isEmpty())
    }

    @Test
    fun `buildGalleryEntries pairs a matched node with all its thumbnails and leaves others unmatched`() {
        val nodes = listOf(
            NavNode("com.example", "HomeRoute", "com.example.HomeRoute"),
            NavNode("com.example.featurea", "FeatureARoute", "com.example.featurea.FeatureARoute"),
        )
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example.featurea", "FeatureAScreen_Screenshot", "com.example.featurea.preview"),
        )
        val lightFile = File("FeatureAScreen_Screenshot_Light_abc123_0.png")
        val darkFile = File("FeatureAScreen_Screenshot_Dark_def456_0.png")

        val entries = buildGalleryEntries(
            nodes = nodes,
            screenshotEntries = screenshotEntries,
            referenceImages = listOf(lightFile, darkFile),
            suffixesToStrip = setOf("Destination", "Route"),
        )

        assertEquals(2, entries.size)
        val home = entries.single { it.node.simpleName == "HomeRoute" }
        val featureA = entries.single { it.node.simpleName == "FeatureARoute" }
        assertTrue(home.thumbnails.isEmpty())
        assertEquals(listOf(darkFile, lightFile), featureA.thumbnails)
    }

    @Test
    fun `buildGalleryEntries aggregates thumbnails across multiple matched wrappers for one node`() {
        val nodes = listOf(NavNode("com.example.home", "HomeRoute", "com.example.home.HomeRoute"))
        val screenshotEntries = listOf(
            ScreenshotIndexEntry("com.example.home", "HomeScreenPreview_Screenshot", "com.example.home.preview1"),
            ScreenshotIndexEntry("com.example.home", "HomeDetailScreenPreview_Screenshot", "com.example.home.preview2"),
        )
        val homeLight = File("HomeScreenPreview_Screenshot_Light_aaa111_0.png")
        val homeDark = File("HomeScreenPreview_Screenshot_Dark_bbb222_0.png")
        val homeDetail = File("HomeDetailScreenPreview_Screenshot_ccc333_0.png")

        val entries = buildGalleryEntries(
            nodes = nodes,
            screenshotEntries = screenshotEntries,
            referenceImages = listOf(homeLight, homeDark, homeDetail),
            suffixesToStrip = setOf("Route"),
        )

        val home = entries.single()
        // All three PNGs across both matched wrappers show up, sorted by file name
        // ("HomeDetail..." < "HomeScreenPreview_Screenshot_Dark..." < "...Light...").
        assertEquals(listOf(homeDetail, homeDark, homeLight), home.thumbnails)
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
    fun `buildSourceLink builds a GitHub blob URL when both env vars are present and the path is repo-relative`() {
        val node = NavNode(
            packageName = "com.example.featurea",
            simpleName = "FeatureARoute",
            qualifiedName = "com.example.featurea.FeatureARoute",
            filePath = "feature-a/src/main/kotlin/com/example/featurea/FeatureAEntries.kt",
            line = 42,
            filePathIsRepoRelative = true,
        )

        val url = buildSourceLink(node, githubRepository = "HayatoYagi/compose-preview-toolkit", githubSha = "abc123")

        assertEquals(
            "https://github.com/HayatoYagi/compose-preview-toolkit/blob/abc123/" +
                "feature-a/src/main/kotlin/com/example/featurea/FeatureAEntries.kt#L42",
            url,
        )
    }

    @Test
    fun `buildSourceLink returns null when either env var is absent`() {
        val node = NavNode(
            packageName = "com.example",
            simpleName = "HomeRoute",
            qualifiedName = "com.example.HomeRoute",
            filePath = "app/src/main/kotlin/com/example/HomeEntries.kt",
            line = 7,
            filePathIsRepoRelative = true,
        )

        assertNull(buildSourceLink(node, githubRepository = null, githubSha = "abc123"))
        assertNull(buildSourceLink(node, githubRepository = "HayatoYagi/compose-preview-toolkit", githubSha = null))
        assertNull(buildSourceLink(node, githubRepository = null, githubSha = null))
    }

    @Test
    fun `buildSourceLink returns null when filePath is only a fallback, not git-root-relative, even with both env vars present`() {
        val node = NavNode(
            packageName = "com.example",
            simpleName = "HomeRoute",
            qualifiedName = "com.example.HomeRoute",
            filePath = "HomeEntries.kt",
            line = 7,
            filePathIsRepoRelative = false,
        )

        assertNull(buildSourceLink(node, githubRepository = "HayatoYagi/compose-preview-toolkit", githubSha = "abc123"))
    }

    @Test
    fun `buildGallerySiteHtml renders a clickable source-location link when a node has a sourceUrl`() {
        val nodes = listOf(
            GalleryNode(
                qualifiedName = "com.example.HomeRoute",
                simpleName = "HomeRoute",
                thumbnails = emptyList(),
                filePath = "app/src/main/kotlin/com/example/HomeEntries.kt",
                line = 7,
                sourceUrl = "https://github.com/HayatoYagi/compose-preview-toolkit/blob/abc123/app/src/main/kotlin/com/example/HomeEntries.kt#L7",
            ),
        )

        val html = buildGallerySiteHtml(nodes, mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("\"filePath\":\"app/src/main/kotlin/com/example/HomeEntries.kt\""))
        assertTrue(html.contains("\"line\":7"))
        assertTrue(
            html.contains(
                "\"sourceUrl\":\"https://github.com/HayatoYagi/compose-preview-toolkit/blob/abc123/" +
                    "app/src/main/kotlin/com/example/HomeEntries.kt#L7\"",
            ),
        )
        // The modal renders an <a> when sourceUrl is present, opening in a new tab.
        assertTrue(html.contains("link.target = \"_blank\""))
        assertTrue(html.contains("link.rel = \"noopener\""))
    }

    @Test
    fun `buildGallerySiteHtml renders source location as plain text (null sourceUrl) when no link could be built`() {
        val nodes = listOf(
            GalleryNode(
                qualifiedName = "com.example.HomeRoute",
                simpleName = "HomeRoute",
                thumbnails = emptyList(),
                filePath = "HomeEntries.kt",
                line = 7,
                sourceUrl = null,
            ),
        )

        val html = buildGallerySiteHtml(nodes, mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("\"filePath\":\"HomeEntries.kt\""))
        assertTrue(html.contains("\"line\":7"))
        assertTrue(html.contains("\"sourceUrl\":null"))
    }

    @Test
    fun `buildGallerySiteHtml renders every node's data and a placeholder-capable modal for unmatched thumbnails`() {
        val nodes = listOf(
            GalleryNode(qualifiedName = "com.example.HomeRoute", simpleName = "HomeRoute", thumbnails = emptyList()),
            GalleryNode(
                qualifiedName = "com.example.featurea.FeatureARoute",
                simpleName = "FeatureARoute",
                thumbnails = listOf(
                    GalleryThumbnail(label = "FeatureARoute_Screenshot_0.png", dataUri = "data:image/png;base64,AAAA"),
                ),
            ),
        )

        val html = buildGallerySiteHtml(nodes, mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("HomeRoute"))
        assertTrue(html.contains("com.example.HomeRoute"))
        assertTrue(html.contains("FeatureARoute"))
        assertTrue(html.contains("com.example.featurea.FeatureARoute"))
        assertTrue(html.contains("No screenshot"))
        assertTrue(html.contains("data:image/png;base64,AAAA"))
        assertTrue(html.contains("FeatureARoute_Screenshot_0.png"))
        // The old separate grid section is gone.
        assertFalse(html.contains("<h2>Screenshots</h2>"))
        assertFalse(html.contains("class=\"grid\""))
    }

    @Test
    fun `buildGallerySiteHtml embeds every thumbnail of a multi-match node in the click data, not just the representative one`() {
        val nodes = listOf(
            GalleryNode(
                qualifiedName = "com.example.home.HomeRoute",
                simpleName = "HomeRoute",
                thumbnails = listOf(
                    GalleryThumbnail(label = "HomeScreenPreview_Screenshot_Dark_bbb222_0.png", dataUri = "data:image/png;base64,DARK"),
                    GalleryThumbnail(label = "HomeScreenPreview_Screenshot_Light_aaa111_0.png", dataUri = "data:image/png;base64,LIGHT"),
                ),
            ),
        )

        val html = buildGallerySiteHtml(nodes, mermaidGraph = "graph TD;\nn0[\"HomeRoute\"];\n")

        assertTrue(html.contains("data:image/png;base64,DARK"))
        assertTrue(html.contains("data:image/png;base64,LIGHT"))
        assertTrue(html.contains("HomeScreenPreview_Screenshot_Dark_bbb222_0.png"))
        assertTrue(html.contains("HomeScreenPreview_Screenshot_Light_aaa111_0.png"))
    }

    @Test
    fun `buildGallerySiteHtml escapes a double quote in a route name so it can't break out of the embedded JS string literal`() {
        val nodes = listOf(
            GalleryNode(qualifiedName = "com.example.Weird\"Route", simpleName = "Weird\"Route", thumbnails = emptyList()),
        )

        val html = buildGallerySiteHtml(nodes, mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("Weird\\\"Route"))
        assertFalse(html.contains("\"simpleName\":\"Weird\"Route\""))
    }

    @Test
    fun `buildGallerySiteHtml embeds the mermaid CDN script, a raised maxTextSize, and the given graph definition`() {
        val html = buildGallerySiteHtml(emptyList(), mermaidGraph = "graph TD;\nn0[\"Home\"];\n")

        assertTrue(html.contains("mermaid"))
        assertTrue(html.contains("cdn.jsdelivr.net"))
        assertTrue(html.contains("securityLevel: \"loose\""))
        assertTrue(html.contains("maxTextSize"))
        assertTrue(html.contains("graph TD;"))
        assertTrue(html.contains("n0[\"Home\"];"))
    }

    @Test
    fun `buildGallerySiteHtml wires a click-to-reveal modal container and handler`() {
        val html = buildGallerySiteHtml(emptyList(), mermaidGraph = "graph TD;\n")

        assertTrue(html.contains("cpt-modal-backdrop"))
        assertTrue(html.contains("function cptShowScreenshots(nodeId)"))
    }

    @Test
    fun `buildMermaidGraph assigns positional ids and keeps qualified names only in labels`() {
        val nodes = listOf(
            GalleryNode("com.example.HomeRoute", "HomeRoute", emptyList()),
            GalleryNode("com.example.featurea.FeatureARoute", "FeatureARoute", emptyList()),
        )
        val edges = listOf(NavEdge("com.example.HomeRoute", "com.example.featurea.FeatureARoute"))

        val graph = buildMermaidGraph(nodes, edges)

        assertTrue(graph.startsWith("graph LR;\n"))
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
        val nodes = listOf(GalleryNode("com.example.TerminalRoute", "TerminalRoute", emptyList()))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("n0[\"TerminalRoute\"];"))
    }

    @Test
    fun `buildMermaidGraph renders a real cycle (A to B and B to A) as two edge lines, not a crash or dedup`() {
        val nodes = listOf(
            GalleryNode("com.example.featurea.FeatureARoute", "FeatureARoute", emptyList()),
            GalleryNode("com.example.featureb.FeatureBRoute", "FeatureBRoute", emptyList()),
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
        val nodes = listOf(GalleryNode("com.example.HomeRoute", "HomeRoute", emptyList()))
        val edges = listOf(NavEdge("com.example.HomeRoute", "com.example.Unknown"))

        val graph = buildMermaidGraph(nodes, edges)

        assertFalse(graph.contains("-->"))
    }

    @Test
    fun `buildMermaidGraph escapes a quote in a label without breaking mermaid's quoted-string syntax`() {
        val nodes = listOf(GalleryNode("com.example.Weird\"Route", "Weird\"Route", emptyList()))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("n0[\"Weird'Route\"];"))
    }

    @Test
    fun `buildMermaidGraph html-escapes angle brackets and ampersands in a label`() {
        val nodes = listOf(GalleryNode("com.example.<Weird&Route>", "<Weird&Route>", emptyList()))

        val graph = buildMermaidGraph(nodes, emptyList())

        assertFalse(graph.contains("<Weird"))
        assertTrue(graph.contains("&lt;Weird&amp;Route&gt;"))
    }

    @Test
    fun `buildMermaidGraph embeds the representative (lexicographically-first) thumbnail as an image-shape node`() {
        val nodes = listOf(
            GalleryNode(
                qualifiedName = "com.example.home.HomeRoute",
                simpleName = "HomeRoute",
                thumbnails = listOf(
                    GalleryThumbnail(label = "HomeScreenPreview_Screenshot_Dark_bbb222_0.png", dataUri = "data:image/png;base64,DARK"),
                    GalleryThumbnail(label = "HomeScreenPreview_Screenshot_Light_aaa111_0.png", dataUri = "data:image/png;base64,LIGHT"),
                ),
            ),
        )

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("n0@{ img: \"data:image/png;base64,DARK\""))
        assertFalse(graph.contains("data:image/png;base64,LIGHT"))
        assertTrue(graph.contains("label: \"HomeRoute\""))
    }

    @Test
    fun `buildMermaidGraph binds a click callback to every node, including unmatched ones`() {
        val nodes = listOf(
            GalleryNode("com.example.HomeRoute", "HomeRoute", emptyList()),
            GalleryNode(
                "com.example.featurea.FeatureARoute",
                "FeatureARoute",
                listOf(GalleryThumbnail("FeatureARoute_Screenshot_0.png", "data:image/png;base64,AAAA")),
            ),
        )

        val graph = buildMermaidGraph(nodes, emptyList())

        assertTrue(graph.contains("click n0 call cptShowScreenshots();"))
        assertTrue(graph.contains("click n1 call cptShowScreenshots();"))
    }
}

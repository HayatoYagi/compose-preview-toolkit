package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdge
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import java.io.File

/**
 * A single entry from Phase 1's `ComposePreviewToolkitScreenshotIndex*.txt` (see
 * `ScreenshotPreviewProcessorProvider`'s `PreviewEntry`/`writeIndex`), tab-separated
 * `packageName\twrapperName\tcallExpression`. Phase 1's own format is trivial enough (three
 * columns, same convention as the nav node index) that this module parses it directly rather than
 * taking a compile dependency on `ksp-processor`'s private `PreviewEntry` type, matching
 * `GenerateScreenshotPreviewTests`/`CleanupScreenshotPreviewReferences`'s own local `PreviewEntry`
 * copies in the Phase 1 plugin.
 */
data class ScreenshotIndexEntry(
    val packageName: String,
    val wrapperName: String,
    val callExpression: String,
)

/** Parses [text] (the full contents of one `ComposePreviewToolkitScreenshotIndex*.txt` file) into [ScreenshotIndexEntry]s. */
fun parseScreenshotIndex(text: String): List<ScreenshotIndexEntry> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "Invalid screenshot index line: $line" }
            ScreenshotIndexEntry(packageName = parts[0], wrapperName = parts[1], callExpression = parts[2])
        }
        .toList()

/**
 * Strips the first suffix in [suffixesToStrip] (iteration order of the set) that
 * [NavNode.simpleName] ends with — case-sensitive suffix match, e.g. `FeatureARoute` with
 * `["Destination", "Route"]` becomes `FeatureA`. Returns [simpleName] unchanged if none match.
 */
fun stripRouteNameSuffix(
    simpleName: String,
    suffixesToStrip: Set<String>,
): String {
    val suffix = suffixesToStrip.firstOrNull { simpleName.endsWith(it) && it.isNotEmpty() }
    return if (suffix != null) simpleName.removeSuffix(suffix) else simpleName
}

/**
 * Best-effort naming-heuristic match of [node] against [screenshotEntries]: strips a configured
 * suffix from the route's simple name (see [stripRouteNameSuffix]), then returns the first
 * screenshot entry whose `wrapperName` case-insensitively contains that stripped name. A blank
 * stripped name (e.g. a route literally named `"Route"`) never matches anything, to avoid every
 * such route spuriously matching every screenshot. Returns `null` when nothing matches — an
 * expected, non-error outcome for most routes.
 */
fun matchScreenshotEntry(
    node: NavNode,
    screenshotEntries: List<ScreenshotIndexEntry>,
    suffixesToStrip: Set<String>,
): ScreenshotIndexEntry? {
    val strippedName = stripRouteNameSuffix(node.simpleName, suffixesToStrip)
    if (strippedName.isBlank()) return null
    return screenshotEntries.firstOrNull { it.wrapperName.contains(strippedName, ignoreCase = true) }
}

/**
 * Locates the reference PNG for [wrapperName] among [referenceImages], matching the
 * `${wrapperName}_<variant>_<hash>_<index>.png` naming convention AGP's screenshot testing writes
 * baselines under (the same prefix-match `CleanupScreenshotPreviewReferences` already relies on
 * for cleanup). There can be more than one PNG per wrapper (e.g. light/dark preview variants) —
 * this gallery shows a single thumbnail per node, so the lexicographically-first match is used;
 * no multi-image support is built here.
 */
fun findThumbnailFile(
    wrapperName: String,
    referenceImages: List<File>,
): File? =
    referenceImages
        .filter { it.name.startsWith("${wrapperName}_") }
        .minByOrNull { it.name }

/** One nav graph node paired (best-effort, possibly `null`) with a matched screenshot thumbnail file. */
data class GalleryEntry(
    val node: NavNode,
    val thumbnail: File?,
)

/**
 * Combines [nodes] (aggregated across `graphModules`) with [screenshotEntries]/[referenceImages]
 * (also aggregated) into one flat, deterministically-ordered list of gallery entries — the full
 * naming-heuristic pipeline ([matchScreenshotEntry] + [findThumbnailFile]) minus any file I/O
 * beyond what's already been read into [referenceImages]/[screenshotEntries], so this stays a pure
 * function callable from a unit test without a Gradle project.
 */
fun buildGalleryEntries(
    nodes: List<NavNode>,
    screenshotEntries: List<ScreenshotIndexEntry>,
    referenceImages: List<File>,
    suffixesToStrip: Set<String>,
): List<GalleryEntry> =
    nodes
        .distinctBy { it.qualifiedName }
        .sortedBy { it.qualifiedName }
        .map { node ->
            val matchedEntry = matchScreenshotEntry(node, screenshotEntries, suffixesToStrip)
            val thumbnail = matchedEntry?.let { entry -> findThumbnailFile(entry.wrapperName, referenceImages) }
            GalleryEntry(node = node, thumbnail = thumbnail)
        }

/**
 * Renders [nodes] + [edges] as a Mermaid.js `graph TD` flowchart definition (the raw text that
 * goes inside a `<pre class="mermaid">...</pre>` block, without the surrounding HTML), one line
 * per node declaration followed by one line per edge.
 *
 * **Node IDs are deliberately NOT the route's qualified/simple name.** Mermaid flowchart node IDs
 * have their own restricted syntax (they can't safely contain `.`, generics-looking `<>`, or other
 * punctuation that a fully-qualified Kotlin name routinely has, e.g. `com.example.ConsultRoute.Detail`
 * would be parsed as several chained node references rather than one id) — so this assigns each
 * node an opaque, always-safe positional id (`n0`, `n1`, ...) derived purely from [nodes]' index,
 * and puts the actual route name in the node's quoted *label* instead (`n0["FeatureARoute"]`),
 * which supports far more characters than an id ever could. [nodes] is expected to already be
 * deduplicated/sorted (see [buildGalleryEntries]) so the id assignment is deterministic across
 * repeated runs with the same input.
 *
 * [edges] referencing a route not present in [nodes] are silently dropped rather than rendered
 * with a dangling id — this is defensive, not expected to trigger against real scanner output,
 * since every [NavEdge] the scanner emits has both ends resolved against the same node registry.
 *
 * No assumption of acyclicity anywhere here: a `sourceRouteQualifiedName`/`targetRouteQualifiedName`
 * pair forming a cycle (e.g. the real sample's `FeatureBRoute` → `FeatureARoute` alongside
 * `FeatureARoute` → `FeatureBRoute`) is just two more edge lines: Mermaid flowcharts render cycles
 * natively, and this function never tries to topologically sort or otherwise reason about DAG-ness.
 */
fun buildMermaidGraph(
    nodes: List<NavNode>,
    edges: List<NavEdge>,
): String {
    val idByQualifiedName = nodes.withIndex().associate { (index, node) -> node.qualifiedName to "n$index" }
    return buildString {
        append("graph TD;\n")
        nodes.forEachIndexed { index, node ->
            append("n$index[\"${node.simpleName.mermaidLabel()}\"]").append(";\n")
        }
        edges.distinct().forEach { edge ->
            val sourceId = idByQualifiedName[edge.sourceRouteQualifiedName] ?: return@forEach
            val targetId = idByQualifiedName[edge.targetRouteQualifiedName] ?: return@forEach
            append("$sourceId --> $targetId").append(";\n")
        }
    }
}

/**
 * Escapes [this] for safe use as a Mermaid quoted node label ([buildMermaidGraph]) that is itself
 * embedded as literal HTML text (inside `<pre class="mermaid">`).
 *
 * Two distinct concerns, handled in this order:
 * 1. A literal `"` in the source text would prematurely close Mermaid's own quoted-label syntax
 *    (`n0["fo"o"]` is not one label) — Mermaid has no backslash-escape for this, so the only safe
 *    option is to not let a literal `"` reach the label text at all; replaced with `'`. Newlines
 *    are likewise replaced with spaces (a label can't contain one).
 * 2. `&`, `<`, `>` need standard HTML-entity escaping because this text is embedded directly as
 *    HTML body text: the browser decodes those entities back to literal characters before handing
 *    the element's text content to Mermaid.js, so (unlike step 1) this round-trips safely and also
 *    prevents a route name from ever being interpreted as markup.
 *
 * In practice a route's `simpleName` is a plain Kotlin identifier and never hits any of this, but
 * nothing here assumes that.
 */
private fun String.mermaidLabel(): String =
    replace('"', '\'')
        .replace('\n', ' ')
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * One rendered gallery card's content, already reduced to strings — [thumbnailDataUri] is a full
 * `data:image/png;base64,...` URI (or `null` for an unmatched route), computed by the task from a
 * [GalleryEntry.thumbnail] file's bytes. Keeping [buildGallerySiteHtml] operating on this instead
 * of raw [File]s is what keeps HTML generation itself pure and unit-testable without touching disk.
 */
data class GalleryCard(
    val qualifiedName: String,
    val simpleName: String,
    val thumbnailDataUri: String?,
)

/**
 * Renders [cards] + [mermaidGraph] as a single self-contained static HTML page with two sections
 * driven by the same underlying node/edge/thumbnail data, kept visually separate rather than
 * merged into one element (per the Phase 2 design doc: cramming thumbnails inside Mermaid node
 * shapes is fragile for a v1):
 * 1. A Mermaid.js `graph TD` flowchart (see [buildMermaidGraph]) showing the actual nav graph
 *    structure — every node, including ones with no matched screenshot, plus every detected edge.
 *    Mermaid itself is loaded from a CDN at page-load time in the viewer's browser (per the design
 *    doc's explicit choice) — this has no effect on build reproducibility, only on what a viewer
 *    sees when they later open the page with network access.
 * 2. The pre-existing thumbnail gallery grid, one card per node.
 *
 * Thumbnails are embedded inline as base64 data URIs rather than copied alongside `index.html` as
 * separate files, so the whole site is exactly one file — simpler to review/host for a gallery
 * than keeping an `index.html` + PNGs directory in sync.
 */
fun buildGallerySiteHtml(
    cards: List<GalleryCard>,
    mermaidGraph: String,
): String {
    val cardsHtml = cards.joinToString("\n") { card -> card.toCardHtml() }
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <title>Nav Graph Gallery</title>
        <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 32px; background: #f5f5f7; color: #1d1d1f; }
        h1 { font-size: 22px; margin: 0 0 4px; }
        h2 { font-size: 17px; margin: 0 0 12px; }
        .subtitle { color: #6e6e73; margin: 0 0 24px; }
        section { margin-bottom: 40px; }
        .graph-container { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); overflow-x: auto; }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
        .card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); display: flex; flex-direction: column; }
        .thumbnail { width: 100%; height: 160px; object-fit: cover; display: block; background: #eaeaec; }
        .thumbnail.placeholder { display: flex; align-items: center; justify-content: center; color: #a1a1a6; font-size: 13px; }
        .card-body { padding: 12px 14px; }
        .route-name { font-weight: 600; font-size: 14px; margin-bottom: 2px; word-break: break-word; }
        .qualified-name { font-size: 12px; color: #6e6e73; word-break: break-all; }
        </style>
        </head>
        <body>
        <h1>Nav Graph Gallery</h1>
        <p class="subtitle">${cards.size} route(s)</p>
        <section>
        <h2>Navigation Graph</h2>
        <div class="graph-container">
        <pre class="mermaid">
        $mermaidGraph
        </pre>
        </div>
        </section>
        <section>
        <h2>Screenshots</h2>
        <div class="grid">
        $cardsHtml
        </div>
        </section>
        <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
        <script>mermaid.initialize({ startOnLoad: true, securityLevel: "loose" });</script>
        </body>
        </html>
    """.trimIndent()
}

private fun GalleryCard.toCardHtml(): String {
    val thumbnailHtml = if (thumbnailDataUri != null) {
        """<img class="thumbnail" src="$thumbnailDataUri" alt="${simpleName.htmlEscape()} thumbnail">"""
    } else {
        """<div class="thumbnail placeholder">No screenshot</div>"""
    }
    return """
        <div class="card">
        $thumbnailHtml
        <div class="card-body">
        <div class="route-name">${simpleName.htmlEscape()}</div>
        <div class="qualified-name">${qualifiedName.htmlEscape()}</div>
        </div>
        </div>
    """.trimIndent()
}

private fun String.htmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

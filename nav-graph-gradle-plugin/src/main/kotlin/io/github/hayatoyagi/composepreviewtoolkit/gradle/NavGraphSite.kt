package io.github.hayatoyagi.composepreviewtoolkit.gradle

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
 * Renders [cards] as a single self-contained static HTML page: a thumbnail gallery grid, one card
 * per node, no edges/graph drawing — edge detection doesn't exist yet, see the Phase 2 design doc
 * for the full nav graph design. Thumbnails are embedded inline as base64 data URIs rather than
 * copied alongside `index.html` as separate files, so the whole site is exactly one file — simpler
 * to review/host for a gallery than keeping an `index.html` + PNGs directory in sync.
 */
fun buildGallerySiteHtml(cards: List<GalleryCard>): String {
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
        .subtitle { color: #6e6e73; margin: 0 0 24px; }
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
        <div class="grid">
        $cardsHtml
        </div>
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

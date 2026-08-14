package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdge
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import java.io.File

/**
 * A single entry from the screenshot-testing plugin's `ComposePreviewToolkitScreenshotIndex*.txt`
 * (see `ScreenshotPreviewProcessorProvider`'s `PreviewEntry`/`writeIndex`), tab-separated
 * `packageName\twrapperName\tcallExpression`. That format is trivial enough (three columns, same
 * convention as the nav node index) that this module parses it directly rather than taking a
 * compile dependency on `ksp-processor`'s private `PreviewEntry` type, matching
 * `GenerateScreenshotPreviewTests`/`CleanupScreenshotPreviewReferences`'s own local `PreviewEntry`
 * copies in `gradle-plugin`.
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
 * suffix from the route's simple name (see [stripRouteNameSuffix]), then returns **every**
 * screenshot entry whose `wrapperName` case-insensitively contains that stripped name — a route
 * can legitimately have more than one matching wrapper (e.g. a screen with two separate
 * `@ScreenshotPreview` functions), not just the previous single-match behavior. Sorted by
 * `wrapperName` so the result is deterministic regardless of [screenshotEntries]' own input order
 * (which, aggregated from possibly-unordered Gradle `FileCollection`s across modules, isn't
 * guaranteed stable). A blank stripped name (e.g. a route literally named `"Route"`) never matches
 * anything, to avoid every such route spuriously matching every screenshot. Returns an empty list
 * when nothing matches — an expected, non-error outcome for most routes.
 */
fun matchScreenshotEntry(
    node: NavNode,
    screenshotEntries: List<ScreenshotIndexEntry>,
    suffixesToStrip: Set<String>,
): List<ScreenshotIndexEntry> {
    val strippedName = stripRouteNameSuffix(node.simpleName, suffixesToStrip)
    if (strippedName.isBlank()) return emptyList()
    return screenshotEntries
        .filter { it.wrapperName.contains(strippedName, ignoreCase = true) }
        .sortedBy { it.wrapperName }
}

/**
 * Locates every reference PNG for [wrapperName] among [referenceImages], matching the
 * `${wrapperName}_<variant>_<hash>_<index>.png` naming convention AGP's screenshot testing writes
 * baselines under (the same prefix-match `CleanupScreenshotPreviewReferences` already relies on
 * for cleanup). There can be more than one PNG per wrapper (e.g. light/dark preview variants via
 * the screenshot-testing plugin's `extraPreviewAnnotationFqn`) — all of them are returned, sorted
 * lexicographically by file name, so callers get every matched screenshot rather than just one.
 * The first element of that sorted result is the file this module treats as the "representative"
 * thumbnail wherever only one image can be shown (e.g. embedded directly in a Mermaid graph node,
 * see [buildMermaidGraph]) — same lexicographically-first convention this module has always used
 * for picking a single deterministic image out of a multi-variant set, just no longer discarding
 * the rest.
 */
fun findThumbnailFiles(
    wrapperName: String,
    referenceImages: List<File>,
): List<File> =
    referenceImages
        .filter { it.name.startsWith("${wrapperName}_") }
        .sortedBy { it.name }

/** One nav graph node paired with every screenshot file matched (best-effort, possibly empty) to it. */
data class GalleryEntry(
    val node: NavNode,
    val thumbnails: List<File>,
)

/**
 * Combines [nodes] (aggregated across `graphModules`) with [screenshotEntries]/[referenceImages]
 * (also aggregated) into one flat, deterministically-ordered list of gallery entries — the full
 * naming-heuristic pipeline ([matchScreenshotEntry] + [findThumbnailFiles]) minus any file I/O
 * beyond what's already been read into [referenceImages]/[screenshotEntries], so this stays a pure
 * function callable from a unit test without a Gradle project. A node can match multiple
 * [ScreenshotIndexEntry] wrappers (see [matchScreenshotEntry]), each of which can itself resolve
 * to multiple PNG files (see [findThumbnailFiles]) — [GalleryEntry.thumbnails] flattens across both,
 * deduplicated and sorted by file name, so a node's full screenshot set is never truncated to one.
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
            val matchedEntries = matchScreenshotEntry(node, screenshotEntries, suffixesToStrip)
            val thumbnails = matchedEntries
                .flatMap { entry -> findThumbnailFiles(entry.wrapperName, referenceImages) }
                .distinct()
                .sortedBy { it.name }
            GalleryEntry(node = node, thumbnails = thumbnails)
        }

/**
 * One base64-embedded screenshot ready for HTML. [label] is a distinguishing display label for
 * this specific screenshot — the matched baseline PNG's own file name (e.g.
 * `HomeScreenPreview_Screenshot_Dark_1d8bfe94_0.png`), which already encodes the
 * variant/hash/index AGP's screenshot-testing naming convention writes (see [findThumbnailFiles]),
 * so no separate variant-name-parsing logic is needed to give each screenshot a meaningful label.
 * [dataUri] is a full `data:image/png;base64,...` URI, computed by the task from the matched
 * file's bytes.
 */
data class GalleryThumbnail(
    val label: String,
    val dataUri: String,
)

/**
 * One nav graph node's full gallery data: [thumbnails] holds *every* screenshot matched to this
 * route (already base64-embedded), not just one — possibly empty when nothing matched. Keeping
 * [buildMermaidGraph]/[buildGallerySiteHtml] operating on this instead of raw [File]s is what
 * keeps HTML generation itself pure and unit-testable without touching disk.
 *
 * [thumbnails] is expected to already be in the same deterministic order [buildGalleryEntries]/
 * [findThumbnailFiles] produce (sorted by file name), so `thumbnails.firstOrNull()` is always the
 * same "representative" thumbnail — the one embedded directly into this node's own Mermaid graph
 * shape, since only one image can reasonably fit there. All of [thumbnails] are shown when a
 * viewer clicks the node (see [buildGallerySiteHtml]).
 *
 * [filePath]/[line] locate this route's `entry<X> { ... }` registration call site (copied from
 * [io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode.filePath]/`.line`), shown as
 * plain text in the click-to-reveal modal. [sourceUrl], when non-null (built by [buildSourceLink]
 * — only possible when both `GITHUB_REPOSITORY`/`GITHUB_SHA` are set, i.e. running in GitHub
 * Actions, and the node's path is git-root-relative), renders that same text as a clickable link
 * to the exact line on GitHub instead.
 */
data class GalleryNode(
    val qualifiedName: String,
    val simpleName: String,
    val thumbnails: List<GalleryThumbnail>,
    val filePath: String = "",
    val line: Int = 0,
    val sourceUrl: String? = null,
)

/**
 * Builds a GitHub blob URL pointing at [node]'s `entry<X> { ... }` registration call site, or
 * `null` when a working link can't be built — this is a best-effort, non-essential feature, so it
 * never guesses:
 * - [githubRepository]/[githubSha] (meant to be `GITHUB_REPOSITORY`/`GITHUB_SHA`) are both
 *   auto-populated by GitHub Actions but absent for a local `./gradlew` run — either missing (or
 *   blank) means no link.
 * - [node]'s [NavNode.filePathIsRepoRelative] must be `true`: a fallback (non-repo-relative)
 *   `filePath` would produce a broken or silently wrong link, since `blob/<sha>/<path>` requires a
 *   path relative to the repository root specifically.
 *
 * The `#L<line>` fragment follows GitHub's own single-line-highlight URL convention.
 */
fun buildSourceLink(
    node: NavNode,
    githubRepository: String?,
    githubSha: String?,
): String? {
    if (githubRepository.isNullOrBlank() || githubSha.isNullOrBlank()) return null
    if (!node.filePathIsRepoRelative) return null
    return "https://github.com/$githubRepository/blob/$githubSha/${node.filePath}#L${node.line}"
}

/**
 * Renders [nodes] + [edges] as a Mermaid.js `graph LR` (left-to-right) flowchart definition (the raw text that
 * goes inside a `<pre class="mermaid">...</pre>` block, without the surrounding HTML), one node
 * declaration line, one click-binding line, followed by one edge line per edge.
 *
 * **Node IDs are deliberately NOT the route's qualified/simple name.** Mermaid flowchart node IDs
 * have their own restricted syntax (they can't safely contain `.`, generics-looking `<>`, or other
 * punctuation that a fully-qualified Kotlin name routinely has, e.g. `com.example.ConsultRoute.Detail`
 * would be parsed as several chained node references rather than one id) — so this assigns each
 * node an opaque, always-safe positional id (`n0`, `n1`, ...) derived purely from [nodes]' index,
 * and puts the actual route name in the node's *label* instead, which supports far more characters
 * than an id ever could. [nodes] is expected to already be deduplicated/sorted (see
 * [buildGalleryEntries]) so the id assignment is deterministic across repeated runs with the same
 * input, and callers (see `GenerateDebugNavGraphSite`) must pass this exact same [nodes] list, in
 * this exact same order, to [buildGallerySiteHtml] — both derive `n$index` ids positionally and
 * independently, so a shared, identically-ordered input is what keeps a click on graph node `n3`
 * looking up the right node's data in the page's click-handler data.
 *
 * **Node thumbnails** are embedded directly in the node itself using Mermaid v11's image-shape
 * node syntax (`nodeId@{ img: "...", label: "...", h: ..., constraint: "on" }`, confirmed against
 * Mermaid's current flowchart docs — this is a distinct mechanism from the markdown-string
 * (backtick) node labels also introduced around v10/v11, which only support text formatting, not
 * embedded images). A node with no matched thumbnail falls back to a plain quoted-label rect node
 * (`nodeId["label"]`) since there's nothing to embed. `h` is fixed at [THUMBNAIL_NODE_HEIGHT_PX] so
 * a large source screenshot never blows up the rendered node size (the *data* is still fully
 * embedded — see [buildGallerySiteHtml]'s `maxTextSize` note for the corresponding fragility this
 * creates and how it's mitigated); `constraint: "on"` leaves `w` for Mermaid to compute from the
 * source image's own aspect ratio, since Android screenshots are routinely tall/portrait and
 * forcing a fixed `w` too (`constraint: "off"`) visibly stretched/distorted them.
 *
 * **Click-to-reveal** is wired via Mermaid's `click nodeId call callbackName()` binding syntax
 * (confirmed against the current docs: the bound JS function receives the clicked node's id as its
 * first argument automatically, so no explicit argument needs to be written here) — every node
 * gets one, including nodes with zero matched thumbnails, so clicking one still opens a modal
 * confirming there's nothing to show rather than doing nothing. This requires
 * `securityLevel: "loose"` at `mermaid.initialize(...)` time (see [buildGallerySiteHtml]) — Mermaid
 * disables click callbacks entirely under the default `"strict"` level.
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
    nodes: List<GalleryNode>,
    edges: List<NavEdge>,
): String {
    val idByQualifiedName = nodes.withIndex().associate { (index, node) -> node.qualifiedName to "n$index" }
    return buildString {
        append("graph LR;\n")
        nodes.forEachIndexed { index, node ->
            val nodeId = "n$index"
            val representative = node.thumbnails.firstOrNull()
            if (representative != null) {
                append(
                    "$nodeId@{ img: \"${representative.dataUri}\", label: \"${node.simpleName.mermaidLabel()}\", " +
                        "pos: \"b\", h: $THUMBNAIL_NODE_HEIGHT_PX, constraint: \"on\" }",
                ).append(";\n")
            } else {
                append("$nodeId[\"${node.simpleName.mermaidLabel()}\"]").append(";\n")
            }
            append("click $nodeId call $NODE_CLICK_CALLBACK()").append(";\n")
        }
        edges.distinct().forEach { edge ->
            val sourceId = idByQualifiedName[edge.sourceRouteQualifiedName] ?: return@forEach
            val targetId = idByQualifiedName[edge.targetRouteQualifiedName] ?: return@forEach
            append("$sourceId --> $targetId").append(";\n")
        }
    }
}

/**
 * Fixed on-screen height (in px) every Mermaid-embedded node thumbnail is constrained to; width is
 * left for Mermaid to compute (`constraint: "on"` in [buildMermaidGraph]) so a screenshot's own
 * aspect ratio — routinely tall/portrait for an Android screen, nothing like square — is preserved
 * instead of being squashed into a fixed box.
 */
private const val THUMBNAIL_NODE_HEIGHT_PX = 320

/** Name of the global JS function [buildMermaidGraph]'s `click ... call ...()` bindings invoke and [buildGallerySiteHtml] defines. Kept as one constant so the two can never drift apart. */
private const val NODE_CLICK_CALLBACK = "cptShowScreenshots"

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
 * nothing here assumes that. The same rules apply equally inside a Mermaid image-shape's `label:`
 * value, so this one helper covers both node-declaration forms in [buildMermaidGraph].
 */
private fun String.mermaidLabel(): String =
    replace('"', '\'')
        .replace('\n', ' ')
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Renders [nodes] + [mermaidGraph] as a single self-contained static HTML page. Earlier versions
 * of this gallery rendered two visually separate sections — a Mermaid graph of plain-text nodes,
 * and a separate thumbnail-card grid below it — deliberately kept apart because embedding
 * thumbnails inside Mermaid node shapes was considered fragile for a v1. That's no longer the
 * design: every node in the graph now shows its own representative thumbnail directly (via
 * Mermaid's image-shape node syntax, see [buildMermaidGraph]), and clicking a node opens an
 * in-page modal listing *every* screenshot matched to that route — so the old grid is redundant
 * and has been removed rather than kept alongside it.
 *
 * The "fragile" concern was Mermaid's `maxTextSize` config (default 50,000 characters) capping the
 * total length of a diagram's definition text: a single embedded screenshot's base64 data URI
 * alone can exceed that (this project's own sample screenshots are ~30KB PNGs, ~40KB once base64-
 * encoded) — so `maxTextSize` is raised well above any realistic gallery size in the
 * `mermaid.initialize(...)` call below. Raising it is safe here specifically because the whole
 * diagram definition is this task's own generated output, never arbitrary/untrusted text Mermaid
 * would otherwise need protecting against.
 *
 * `securityLevel: "loose"` (unchanged from before this redesign) is required for the `click ...
 * call ...()` bindings [buildMermaidGraph] emits to actually execute — Mermaid disables click
 * callbacks under the default `"strict"` level.
 *
 * The click-to-reveal modal's data (every node's full [GalleryNode.thumbnails] list, keyed by the
 * same `n$index` ids [buildMermaidGraph] assigns) is embedded as a plain JS object literal so
 * [NODE_CLICK_CALLBACK] can look it up synchronously with no extra network fetch — consistent with
 * keeping the whole page one file. Thumbnails are embedded inline as base64 data URIs rather than
 * copied alongside `index.html` as separate files, so the whole site is exactly one file — simpler
 * to review/host for a gallery than keeping an `index.html` + PNGs directory in sync.
 */
fun buildGallerySiteHtml(
    nodes: List<GalleryNode>,
    mermaidGraph: String,
): String {
    val galleryDataJson = nodes.withIndex().joinToString(",\n") { (index, node) -> node.toJsDataEntry("n$index") }
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
        .hint { color: #6e6e73; margin: 0 0 12px; font-size: 13px; }
        section { margin-bottom: 40px; }
        .graph-container { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12); overflow-x: auto; }
        .graph-container svg .node { cursor: pointer; }
        .modal-backdrop { display: none; position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5); align-items: center; justify-content: center; padding: 24px; z-index: 1000; }
        .modal-backdrop.open { display: flex; }
        .modal { position: relative; background: #fff; border-radius: 12px; padding: 24px; max-width: 900px; width: 100%; max-height: 85vh; overflow-y: auto; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.3); }
        .modal-close { position: absolute; top: 12px; right: 16px; border: none; background: none; font-size: 24px; line-height: 1; cursor: pointer; color: #6e6e73; }
        .modal h2 { margin: 0 0 4px; font-size: 17px; }
        .modal .qualified-name { font-size: 12px; color: #6e6e73; word-break: break-all; margin-bottom: 4px; }
        .modal .source-location { font-size: 12px; color: #6e6e73; word-break: break-all; margin-bottom: 16px; }
        .modal .source-location a { color: #06c; text-decoration: none; }
        .modal .source-location a:hover { text-decoration: underline; }
        .modal-images { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
        .modal-images figure { margin: 0; display: flex; flex-direction: column; gap: 6px; }
        .modal-image { width: 100%; border-radius: 8px; display: block; background: #eaeaec; }
        .modal-images figcaption { font-size: 11px; color: #6e6e73; word-break: break-all; }
        .thumbnail.placeholder { padding: 24px; text-align: center; color: #a1a1a6; font-size: 13px; }
        </style>
        </head>
        <body>
        <h1>Nav Graph Gallery</h1>
        <p class="subtitle">${nodes.size} route(s)</p>
        <section>
        <h2>Navigation Graph</h2>
        <p class="hint">Click a node to see every screenshot matched to that route.</p>
        <div class="graph-container">
        <pre class="mermaid">
        $mermaidGraph
        </pre>
        </div>
        </section>
        <div id="cpt-modal-backdrop" class="modal-backdrop" onclick="cptCloseModalIfBackdrop(event)">
        <div class="modal">
        <button type="button" class="modal-close" aria-label="Close" onclick="cptCloseModal()">&times;</button>
        <h2 id="cpt-modal-title"></h2>
        <div id="cpt-modal-subtitle" class="qualified-name"></div>
        <div id="cpt-modal-source" class="source-location"></div>
        <div id="cpt-modal-images" class="modal-images"></div>
        </div>
        </div>
        <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
        <script>
        const cptGalleryData = {
        $galleryDataJson
        };
        function $NODE_CLICK_CALLBACK(nodeId) {
          const data = cptGalleryData[nodeId];
          if (!data) return;
          document.getElementById("cpt-modal-title").textContent = data.simpleName;
          document.getElementById("cpt-modal-subtitle").textContent = data.qualifiedName;
          const sourceContainer = document.getElementById("cpt-modal-source");
          sourceContainer.innerHTML = "";
          if (data.filePath) {
            const locationText = data.filePath + ":" + data.line;
            if (data.sourceUrl) {
              const link = document.createElement("a");
              link.href = data.sourceUrl;
              link.target = "_blank";
              link.rel = "noopener";
              link.textContent = locationText;
              sourceContainer.appendChild(link);
            } else {
              sourceContainer.textContent = locationText;
            }
          }
          const imagesContainer = document.getElementById("cpt-modal-images");
          imagesContainer.innerHTML = "";
          if (data.thumbnails.length === 0) {
            const placeholder = document.createElement("div");
            placeholder.className = "thumbnail placeholder";
            placeholder.textContent = "No screenshot";
            imagesContainer.appendChild(placeholder);
          } else {
            data.thumbnails.forEach(function (thumbnail) {
              const figure = document.createElement("figure");
              const img = document.createElement("img");
              img.className = "modal-image";
              img.src = thumbnail.dataUri;
              img.alt = thumbnail.label;
              const caption = document.createElement("figcaption");
              caption.textContent = thumbnail.label;
              figure.appendChild(img);
              figure.appendChild(caption);
              imagesContainer.appendChild(figure);
            });
          }
          document.getElementById("cpt-modal-backdrop").classList.add("open");
        }
        function cptCloseModal() {
          document.getElementById("cpt-modal-backdrop").classList.remove("open");
        }
        function cptCloseModalIfBackdrop(event) {
          if (event.target.id === "cpt-modal-backdrop") {
            cptCloseModal();
          }
        }
        document.addEventListener("keydown", function (event) {
          if (event.key === "Escape") {
            cptCloseModal();
          }
        });
        // maxTextSize raised well above Mermaid's 50,000-char default — see this function's kdoc
        // for why embedding thumbnails directly in node shapes requires this.
        mermaid.initialize({ startOnLoad: true, securityLevel: "loose", maxTextSize: 50000000 });
        </script>
        </body>
        </html>
    """.trimIndent()
}

private fun GalleryNode.toJsDataEntry(nodeId: String): String {
    val thumbnailsJson = thumbnails.joinToString(",") { thumbnail ->
        """{"label":"${thumbnail.label.jsStringEscape()}","dataUri":"${thumbnail.dataUri.jsStringEscape()}"}"""
    }
    val sourceUrlJson = sourceUrl?.let { "\"${it.jsStringEscape()}\"" } ?: "null"
    return """"$nodeId":{"simpleName":"${simpleName.jsStringEscape()}","qualifiedName":"${qualifiedName.jsStringEscape()}",""" +
        """"filePath":"${filePath.jsStringEscape()}","line":$line,"sourceUrl":$sourceUrlJson,"thumbnails":[$thumbnailsJson]}"""
}

/**
 * Escapes [this] for safe inclusion inside a double-quoted JS/JSON string literal embedded
 * directly in generated HTML (see [buildGallerySiteHtml]'s `cptGalleryData` object literal).
 * `</script` is additionally escaped as defense-in-depth: a route or file name is developer-
 * authored, not attacker-controlled, but nothing here should rely on that to avoid prematurely
 * closing the surrounding `<script>` tag.
 */
private fun String.jsStringEscape(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("</script", "<\\/script")

package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import java.io.Reader
import java.io.Writer

/**
 * Base file name for the node index, mirroring the naming convention of
 * `ScreenshotPreviewProcessorProvider`'s `ComposePreviewToolkitScreenshotIndex`. This module only
 * exposes a plain library API to produce/parse the formatted content ([formatNavNodeIndex]/
 * [writeNavNodeIndex]/[parseNavNodeIndex]) — `nav-graph-gradle-plugin` is what actually writes it
 * into a Gradle task's output directory (`GenerateDebugNavGraph`) and reads it back for
 * cross-module aggregation (`GenerateDebugNavGraphSite`).
 */
const val NAV_NODE_INDEX_FILE_NAME = "ComposePreviewToolkitNavNodeIndex"

/**
 * Formats [nodes] as the tab-separated `packageName\tsimpleName\tqualifiedName` index convention
 * established by `ScreenshotPreviewProcessorProvider`'s `PreviewEntry`/`writeIndex`, one line per
 * node, with a trailing newline on each line (including the last).
 */
fun formatNavNodeIndex(nodes: List<NavNode>): String = buildString {
    nodes.forEach { node ->
        append(node.packageName)
            .append('\t')
            .append(node.simpleName)
            .append('\t')
            .append(node.qualifiedName)
            .append('\n')
    }
}

/** Writes [nodes] to [writer] in the same tab-separated format as [formatNavNodeIndex]. */
fun writeNavNodeIndex(
    nodes: List<NavNode>,
    writer: Writer,
) {
    writer.write(formatNavNodeIndex(nodes))
}

/**
 * Parses the tab-separated `packageName\tsimpleName\tqualifiedName` format written by
 * [writeNavNodeIndex]/[formatNavNodeIndex] back into [NavNode]s. The counterpart reader needed by
 * cross-module aggregation (`nav-graph-gradle-plugin`'s site-generation task reads back node
 * indexes written by other modules' `generateDebugNavGraph` task runs), mirroring how
 * `ScreenshotPreviewProcessorProvider`'s `PreviewEntry` format is parsed back on the Phase 1 side
 * (see `GenerateScreenshotPreviewTests`/`CleanupScreenshotPreviewReferences`). Blank lines are
 * skipped so trailing newlines don't produce a spurious malformed-line failure.
 */
fun parseNavNodeIndex(reader: Reader): List<NavNode> =
    reader.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "Invalid nav node index line: $line" }
            NavNode(packageName = parts[0], simpleName = parts[1], qualifiedName = parts[2])
        }
        .toList()

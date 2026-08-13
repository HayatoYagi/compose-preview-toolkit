package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import java.io.Reader
import java.io.Writer

/**
 * Base file name for the edge index, mirroring [NAV_NODE_INDEX_FILE_NAME]'s naming convention.
 * Two tab-separated columns — `sourceRouteQualifiedName`/`targetRouteQualifiedName` (see
 * [formatNavEdgeIndex]) — with every edge in the index coming from [NavEdgeScanner]'s automatic
 * detection. There is deliberately no manual-annotation escape hatch for edges the scanner misses:
 * if a real gap shows up, the scanner itself should improve rather than asking consumers to
 * annotate their real navigation code.
 */
const val NAV_EDGE_INDEX_FILE_NAME = "ComposePreviewToolkitNavEdgeIndex"

/**
 * Formats [edges] as the tab-separated `sourceRouteQualifiedName\ttargetRouteQualifiedName` index
 * convention, mirroring [formatNavNodeIndex], one line per edge, with a trailing newline on each
 * line (including the last).
 */
fun formatNavEdgeIndex(edges: List<NavEdge>): String = buildString {
    edges.forEach { edge ->
        append(edge.sourceRouteQualifiedName)
            .append('\t')
            .append(edge.targetRouteQualifiedName)
            .append('\n')
    }
}

/** Writes [edges] to [writer] in the same tab-separated format as [formatNavEdgeIndex]. */
fun writeNavEdgeIndex(
    edges: List<NavEdge>,
    writer: Writer,
) {
    writer.write(formatNavEdgeIndex(edges))
}

/**
 * Parses the tab-separated `sourceRouteQualifiedName\ttargetRouteQualifiedName` format written by
 * [writeNavEdgeIndex]/[formatNavEdgeIndex] back into [NavEdge]s, mirroring [parseNavNodeIndex].
 * Blank lines are skipped so trailing newlines don't produce a spurious malformed-line failure.
 */
fun parseNavEdgeIndex(reader: Reader): List<NavEdge> =
    reader.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 2) { "Invalid nav edge index line: $line" }
            NavEdge(sourceRouteQualifiedName = parts[0], targetRouteQualifiedName = parts[1])
        }
        .toList()

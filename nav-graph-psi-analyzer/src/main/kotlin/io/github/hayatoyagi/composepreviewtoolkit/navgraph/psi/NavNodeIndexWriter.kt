package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import java.io.Writer

/**
 * Base file name for the node index, mirroring the naming convention of
 * `ScreenshotPreviewProcessorProvider`'s `ComposePreviewToolkitScreenshotIndex`. Actually writing
 * this into a Gradle task's output directory is a later PR's job (`nav-graph-gradle-plugin`) —
 * this module only has a plain library API to produce the formatted content.
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

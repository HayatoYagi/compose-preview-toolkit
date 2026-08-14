package io.github.hayatoyagi.composepreviewtoolkit.gradle

import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavEdge
import io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi.NavNode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Crosses the isolated-Worker boundary [NavGraphScanWorkAction] runs behind: [NavNode]/[NavEdge]
 * only have a handful of primitive fields, so a length-prefixed binary format (via
 * `DataOutputStream`/`DataInputStream`'s `writeUTF`/`readUTF`, which needs no delimiter-escaping
 * for free-form warning text) is simpler than a serialization library for this.
 */
internal fun writeNavGraphScanResult(
    file: File,
    nodes: List<NavNode>,
    edges: List<NavEdge>,
    warnings: List<String>,
) {
    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(nodes.size)
        nodes.forEach { node ->
            out.writeUTF(node.packageName)
            out.writeUTF(node.simpleName)
            out.writeUTF(node.qualifiedName)
            out.writeUTF(node.filePath)
            out.writeInt(node.line)
            out.writeBoolean(node.filePathIsRepoRelative)
        }
        out.writeInt(edges.size)
        edges.forEach { edge ->
            out.writeUTF(edge.sourceRouteQualifiedName)
            out.writeUTF(edge.targetRouteQualifiedName)
        }
        out.writeInt(warnings.size)
        warnings.forEach { warning -> out.writeUTF(warning) }
    }
}

internal data class NavGraphScanResult(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
    val warnings: List<String>,
)

internal fun readNavGraphScanResult(file: File): NavGraphScanResult =
    DataInputStream(file.inputStream().buffered()).use { input ->
        val nodes = (0 until input.readInt()).map {
            NavNode(
                packageName = input.readUTF(),
                simpleName = input.readUTF(),
                qualifiedName = input.readUTF(),
                filePath = input.readUTF(),
                line = input.readInt(),
                filePathIsRepoRelative = input.readBoolean(),
            )
        }
        val edges = (0 until input.readInt()).map {
            NavEdge(
                sourceRouteQualifiedName = input.readUTF(),
                targetRouteQualifiedName = input.readUTF(),
            )
        }
        val warnings = (0 until input.readInt()).map { input.readUTF() }
        NavGraphScanResult(nodes, edges, warnings)
    }

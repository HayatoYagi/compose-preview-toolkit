package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.psi.KtCallExpression
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * One `entry<X> { ... }` call site's resolved location in source, computed by [locateCallSite]
 * and copied verbatim into [NavNode.filePath]/[NavNode.line]/[NavNode.filePathIsRepoRelative].
 * See [NavNode]'s kdoc for what the two path/relative-ness fields mean to callers.
 */
internal data class SourceLocation(
    val filePath: String,
    val line: Int,
    val filePathIsRepoRelative: Boolean,
)

/**
 * Best-effort: resolves [call]'s containing file + 1-based line number for display/linking in the
 * nav graph gallery site (see `nav-graph-gradle-plugin`'s `GenerateDebugNavGraphSite`). Never
 * throws and never returns an absolute, machine-specific path.
 *
 * Two path strategies are tried, in preference order, both by relativizing [call]'s containing
 * file's absolute path (available whenever it was parsed via [KotlinPsiParser.parse]'s [File]
 * overload):
 * 1. Relative to the git repository root (see [detectGitRepoRoot]) — the only form
 *    `GenerateDebugNavGraphSite` can turn into a working GitHub blob URL, since that's what a
 *    `blob/<sha>/<path>` URL expects. [SourceLocation.filePathIsRepoRelative] is `true` only for
 *    this case.
 * 2. Relative to [fallbackBaseDirectory] (typically the owning Gradle module's project
 *    directory), used when the git root can't be determined at all (git not installed, source not
 *    inside a repository, etc.) or [call]'s file isn't actually under it.
 *
 * If neither relativization applies — e.g. a synthetic in-memory [org.jetbrains.kotlin.psi.KtFile]
 * with no real file backing it, as unit tests routinely create via [KotlinPsiParser.parse]'s
 * two-arg text overload — the raw name PSI was given is used as-is, which is already short since
 * it's whatever name the caller constructed the [org.jetbrains.kotlin.psi.KtFile] with.
 */
internal fun locateCallSite(
    call: KtCallExpression,
    fallbackBaseDirectory: File,
): SourceLocation {
    val containingFile = call.containingKtFile
    val line = offsetToLine(containingFile.text, call.textOffset)
    val rawPath = containingFile.virtualFile?.path ?: containingFile.name
    val file = File(rawPath)

    if (file.isAbsolute) {
        val repoRelativePath = detectGitRepoRoot()?.let { relativizeOrNull(it, file) }
        if (repoRelativePath != null) {
            return SourceLocation(filePath = repoRelativePath, line = line, filePathIsRepoRelative = true)
        }

        val fallbackRelativePath = relativizeOrNull(fallbackBaseDirectory, file)
        if (fallbackRelativePath != null) {
            return SourceLocation(filePath = fallbackRelativePath, line = line, filePathIsRepoRelative = false)
        }
    }

    return SourceLocation(filePath = rawPath, line = line, filePathIsRepoRelative = false)
}

/**
 * Converts a 0-based character [offset] into [text] to a 1-based line number by counting `\n`
 * characters up to it — simple and dependency-free, since this standalone PSI setup carries no
 * [org.jetbrains.kotlin.com.intellij.openapi.editor.Document] a
 * [org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager] could otherwise use (confirmed by
 * `NavEdgeScanner`'s own `PsiElement.location()`, which already has to defensively try/catch a
 * `null`/failing `viewProvider.document` for the exact same reason).
 */
private fun offsetToLine(
    text: String,
    offset: Int,
): Int {
    var line = 1
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) {
        if (text[i] == '\n') line++
    }
    return line
}

/**
 * Relativizes [file] against [base], forward-slash-normalized so the result reads the same on
 * every OS (a GitHub blob URL always uses `/`, regardless of what this analysis runs on) — or
 * `null` if [file] isn't actually under [base] (relativizing across drive letters on Windows, or
 * genuinely disjoint trees, throws; walking "up and out" via a leading `..` segment is rejected
 * the same as a hard failure, since that's not a meaningful "under this base" path either).
 */
private fun relativizeOrNull(
    base: File,
    file: File,
): String? =
    runCatching {
        val relative = base.absoluteFile.toPath().normalize().relativize(file.toPath().normalize())
        relative.toString().replace(File.separatorChar, '/').takeUnless { it.startsWith("..") || it.isEmpty() }
    }.getOrNull()

/**
 * Caches [detectGitRepoRoot]'s result per working directory for the lifetime of this JVM process:
 * `git rev-parse --show-toplevel` is a real subprocess spawn, and one analysis run calls
 * [locateCallSite] once per `entry<X> { ... }` registration found — routinely dozens across a
 * real project — all sharing the exact same repo root. Values are [Optional] rather than a
 * plain nullable [File] because [ConcurrentHashMap] cannot store `null` values, and a `null`
 * result (no repo found) is exactly as cacheable/valid as a real one.
 */
private val gitRepoRootCache = ConcurrentHashMap<String, Optional<File>>()

/**
 * Resolves the git repository root containing [workingDirectory] by running `git rev-parse
 * --show-toplevel`, or `null` on any failure: git isn't installed/on `PATH`, [workingDirectory]
 * isn't inside a git repository, or any other I/O error — always best-effort, this never throws.
 * Result is memoized per [workingDirectory] in [gitRepoRootCache] (a `null` result is cached too,
 * so a non-repo working directory doesn't retry the subprocess on every call).
 */
internal fun detectGitRepoRoot(workingDirectory: File = File(".")): File? =
    gitRepoRootCache
        .getOrPut(workingDirectory.absolutePath) { Optional.ofNullable(runGitRevParseShowToplevel(workingDirectory)) }
        .orElse(null)

private fun runGitRevParseShowToplevel(workingDirectory: File): File? =
    try {
        val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.isNotEmpty()) File(output) else null
    } catch (e: java.io.IOException) {
        // git not on PATH, workingDirectory doesn't exist, or any other launch failure.
        null
    } catch (e: InterruptedException) {
        // waitFor() was interrupted; restore the interrupt flag rather than swallowing it
        // silently, per standard practice, while still degrading gracefully (see this file's
        // "best-effort, never fail the build" contract).
        Thread.currentThread().interrupt()
        null
    }

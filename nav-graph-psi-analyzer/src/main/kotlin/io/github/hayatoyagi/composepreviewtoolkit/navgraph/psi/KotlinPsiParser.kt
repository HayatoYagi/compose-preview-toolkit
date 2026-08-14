package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * Wraps the standalone Kotlin PSI setup (a [org.jetbrains.kotlin.com.intellij.openapi.Disposable],
 * a [CompilerConfiguration], and a [KotlinCoreEnvironment]) needed to turn Kotlin source text into
 * a [KtFile], without requiring callers to know any of that setup themselves.
 *
 * This is purely syntactic (no type resolution) — the same technique tools like ktlint/detekt use
 * to get a [KtFile] outside of a real compiler invocation, deliberately avoiding the Kotlin
 * Analysis API (which needs a full classpath and is far more brittle).
 *
 * A single instance is meant to be reused across many [parse] calls (e.g. once per Gradle task
 * execution scanning an entire project's sources) — creating a [KotlinCoreEnvironment] is
 * relatively expensive, and its underlying Disposable-rooted resources must be cleaned up exactly
 * once via [close], not once per file.
 *
 * Opts in to two Kotlin-compiler-internal APIs that this standalone-parsing setup requires:
 * [K1Deprecation] (`KotlinCoreEnvironment.createForProduction` is a K1-frontend API, which is
 * exactly what's wanted here since only syntax/PSI is needed, not full K2 semantic resolution) and
 * [CompilerConfiguration.Internals] (needed to even construct a bare [CompilerConfiguration]).
 */
@OptIn(K1Deprecation::class, CompilerConfiguration.Internals::class)
class KotlinPsiParser : AutoCloseable {
    private val disposable = Disposer.newDisposable("compose-preview-toolkit-nav-graph-psi-analyzer")

    private val environment: KotlinCoreEnvironment by lazy {
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    private val psiFileFactory: PsiFileFactory by lazy {
        PsiFileFactory.getInstance(environment.project)
    }

    /**
     * Parses in-memory Kotlin source text (used directly by unit tests).
     *
     * Normalizes `\r\n`/`\r` to `\n` first: [PsiFileFactory.createFileFromText] asserts the text
     * it's given has no non-`\n` line separators, which real `.kt` files checked out with
     * `core.autocrlf=true` (the common Windows Git default) violate — this keeps [parse] working
     * regardless of the caller's own checkout config, rather than requiring every consumer to
     * normalize line endings themselves before calling in.
     */
    fun parse(
        fileName: String,
        sourceText: String,
    ): KtFile =
        psiFileFactory.createFileFromText(
            fileName,
            KotlinLanguage.INSTANCE,
            sourceText.replace("\r\n", "\n").replace("\r", "\n"),
        ) as KtFile

    /**
     * Parses a real `.kt` file on disk, using its *absolute* path (not just its bare file name)
     * to name the resulting [KtFile] — [findEntryRegistrations]'s call-site source-location
     * computation relativizes this absolute path against the git repository root (or a fallback
     * base directory) to build [NavNode.filePath], so the directory information has to survive
     * into the parsed [KtFile] and not just the leaf file name.
     */
    fun parse(file: File): KtFile = parse(file.absolutePath, file.readText())

    /**
     * Releases the underlying PSI/compiler-frontend resources. Must be called exactly once, after
     * every [parse] call this instance is going to make — subsequent [parse] calls after [close]
     * are not supported.
     */
    override fun close() {
        Disposer.dispose(disposable)
    }
}

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
 * to get a [KtFile] outside of a real compiler invocation, per the Phase 2 design doc's explicit
 * choice to avoid the Kotlin Analysis API (which needs a full classpath and is far more brittle).
 *
 * A single instance is meant to be reused across many [parse] calls (e.g. once per Gradle task
 * execution scanning an entire project's sources) — creating a [KotlinCoreEnvironment] is
 * relatively expensive, and its underlying Disposable-rooted resources must be cleaned up exactly
 * once via [close], not once per file.
 *
 * Opts in to two Kotlin-compiler-internal APIs that this standalone-parsing setup requires:
 * [K1Deprecation] (`KotlinCoreEnvironment.createForProduction` is a K1-frontend API, which is
 * exactly what's wanted here since only syntax/PSI is needed, not full K2 semantic resolution —
 * see the Phase 2 design doc's explicit choice to avoid the Analysis API) and
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

    /** Parses in-memory Kotlin source text (used directly by unit tests). */
    fun parse(
        fileName: String,
        sourceText: String,
    ): KtFile = psiFileFactory.createFileFromText(fileName, KotlinLanguage.INSTANCE, sourceText) as KtFile

    /** Parses a real `.kt` file on disk, using its file name to name the resulting [KtFile]. */
    fun parse(file: File): KtFile = parse(file.name, file.readText())

    /**
     * Releases the underlying PSI/compiler-frontend resources. Must be called exactly once, after
     * every [parse] call this instance is going to make — subsequent [parse] calls after [close]
     * are not supported.
     */
    override fun close() {
        Disposer.dispose(disposable)
    }
}

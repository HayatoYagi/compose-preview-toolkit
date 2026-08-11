package io.github.hayatoyagi.composepreviewtoolkit.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

class ScreenshotPreviewProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = ScreenshotPreviewProcessor(
        codeGenerator = environment.codeGenerator,
        logger = environment.logger,
        moduleName = environment.options[MODULE_NAME_OPTION].orEmpty(),
        annotationFqn = environment.options[ANNOTATION_FQN_OPTION] ?: DEFAULT_ANNOTATION_FQN,
        generatedPackage = environment.options[GENERATED_PACKAGE_OPTION] ?: DEFAULT_GENERATED_PACKAGE,
        indexFileName = environment.options[INDEX_FILE_NAME_OPTION] ?: DEFAULT_INDEX_FILE_NAME,
    )

    companion object {
        /**
         * Set by the Gradle plugin per consuming module, so index files from different
         * modules in the same build don't collide on the KSP output resources classpath.
         */
        const val MODULE_NAME_OPTION = "composePreviewToolkit.moduleName"

        /** Fully-qualified name of the marker annotation to scan for. */
        const val ANNOTATION_FQN_OPTION = "composePreviewToolkit.annotationFqn"

        /** Package the generated index resource file is written into. */
        const val GENERATED_PACKAGE_OPTION = "composePreviewToolkit.generatedPackage"

        /** Base file name (before the per-module suffix) of the generated index resource file. */
        const val INDEX_FILE_NAME_OPTION = "composePreviewToolkit.indexFileName"

        const val DEFAULT_ANNOTATION_FQN = "io.github.hayatoyagi.composepreviewtoolkit.annotations.ScreenshotPreview"
        const val DEFAULT_GENERATED_PACKAGE = "io.github.hayatoyagi.composepreviewtoolkit.generated.screenshotpreview"
        const val DEFAULT_INDEX_FILE_NAME = "ComposePreviewToolkitScreenshotIndex"
    }
}

private class ScreenshotPreviewProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    moduleName: String,
    private val annotationFqn: String,
    private val generatedPackage: String,
    indexFileName: String,
) : SymbolProcessor {
    private var generated = false
    private val indexFileName = indexFileName + moduleName.toIndexFileSuffix()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val symbols = resolver.getSymbolsWithAnnotation(annotationFqn).toList()
        val deferred = symbols.filterNot { it.validate() }
        if (deferred.isEmpty()) {
            val previews =
                symbols
                    .asSequence()
                    .mapNotNull { it as? KSFunctionDeclaration }
                    .filter { it.isComposable() }
                    .filter { it.parameters.isEmpty() }
                    .filterNot { Modifier.PRIVATE in it.modifiers }
                    .mapNotNull { it.toPreviewEntry() }
                    .distinctBy { "${it.packageName}.${it.wrapperName}" }
                    .sortedWith(compareBy<PreviewEntry> { it.packageName }.thenBy { it.wrapperName })
                    .toList()

            writeIndex(resolver, previews)
            generated = true
        }
        return deferred
    }

    private fun KSFunctionDeclaration.toPreviewEntry(): PreviewEntry? {
        val packageName = packageName.asString()
        val functionName = simpleName.asString()
        val parent = parentDeclaration

        val callExpression =
            when {
                parent == null -> "$packageName.$functionName"
                parent is KSClassDeclaration && parent.classKind == ClassKind.OBJECT ->
                    parent.qualifiedName?.asString()?.let { parentName ->
                        "$parentName.$functionName"
                    } ?: logger.skipPreview("Skipping preview without qualified parent: $functionName", this)
                else -> {
                    logger.warn(
                        "Skipping @ScreenshotPreview function outside an object: ${qualifiedName?.asString()}",
                        this,
                    )
                    null
                }
            }

        return callExpression?.let {
            PreviewEntry(
                packageName = packageName,
                wrapperName = buildWrapperName(parent, functionName),
                callExpression = it,
            )
        }
    }

    private fun KSPLogger.skipPreview(
        message: String,
        symbol: KSAnnotated,
    ): String? {
        warn(message, symbol)
        return null
    }

    private fun buildWrapperName(
        parent: KSDeclaration?,
        functionName: String,
    ): String {
        val ownerName = parent?.simpleName?.asString()
        val rawName =
            listOfNotNull(ownerName, functionName, "Screenshot")
                .joinToString("_")
        return rawName.replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    private fun KSFunctionDeclaration.isComposable(): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == COMPOSABLE_ANNOTATION }

    private fun writeIndex(
        resolver: Resolver,
        previews: List<PreviewEntry>,
    ) {
        val dependencies =
            Dependencies(
                aggregating = true,
                sources = resolver.getAllFiles().toList().toTypedArray(),
            )
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = indexFileName,
            extensionName = "txt",
        ).bufferedWriter().use { writer ->
            previews.forEach { preview ->
                writer.append(preview.packageName)
                    .append('\t')
                    .append(preview.wrapperName)
                    .append('\t')
                    .append(preview.callExpression)
                    .appendLine()
            }
        }
    }

    companion object {
        private const val COMPOSABLE_ANNOTATION = "androidx.compose.runtime.Composable"
    }
}

private fun String.toIndexFileSuffix(): String = takeIf { it.isNotBlank() }
    ?.replace(Regex("[^A-Za-z0-9_]"), "_")
    ?.let { "_$it" }
    .orEmpty()

private data class PreviewEntry(
    val packageName: String,
    val wrapperName: String,
    val callExpression: String,
)

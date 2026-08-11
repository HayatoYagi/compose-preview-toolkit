package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.Task
import org.gradle.api.specs.Spec
import java.io.File
import java.io.Serializable

/**
 * `onlyIf` predicate so AGP's `updateDebugScreenshotTest`/`validateDebugScreenshotTest` no-op
 * in modules that have no `@ScreenshotPreview`-annotated functions at all.
 */
class HasGeneratedScreenshotPreviewTests(
    private val generatedDirectory: File,
) : Spec<Task>, Serializable {
    override fun isSatisfiedBy(element: Task): Boolean =
        generatedDirectory
            .walkTopDown()
            .any { file -> file.isFile && file.extension == "kt" }

    companion object {
        private const val serialVersionUID = 1L
    }
}

package io.github.hayatoyagi.composepreviewtoolkit.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the `composePreviewToolkit` Gradle extension.
 */
abstract class ComposePreviewToolkitExtension {
    /**
     * Fully-qualified name of the marker annotation the KSP processor scans for.
     * Defaults to `io.github.hayatoyagi.composepreviewtoolkit.annotations.ScreenshotPreview`.
     * Point this at your own annotation instead if you don't want a dependency on the
     * `compose-preview-toolkit-annotations` artifact.
     */
    abstract val annotationFqn: Property<String>

    /**
     * Fully-qualified name of a `@Preview`-family annotation to stack on every generated
     * screenshot-test wrapper function, alongside AGP's official `@PreviewTest`.
     *
     * This drives what actually gets rendered: the wrapper is a fresh top-level function that
     * only calls your original `@Preview` function, so any `@Preview`s on the original are NOT
     * inherited — the wrapper needs its own. Defaults to the plain
     * `androidx.compose.ui.tooling.preview.Preview` (a single default render, no styling
     * opinions). Point this at your own composite multi-preview annotation (e.g. a light/dark
     * pair) for different or multiple render configurations per wrapper.
     */
    abstract val extraPreviewAnnotationFqn: Property<String>

    /** Overrides the `androidx.compose.ui:ui-tooling-preview` / `ui-tooling` version this plugin adds. */
    abstract val composeVersion: Property<String>

    /** Overrides the `com.android.tools.screenshot:screenshot-validation-api` version this plugin adds. */
    abstract val screenshotValidationApiVersion: Property<String>
}

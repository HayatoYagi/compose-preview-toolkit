package io.github.hayatoyagi.composepreviewtoolkit.annotations

/**
 * Marks a parameterless `@Composable` preview function that should get a generated
 * AGP-official Compose Preview Screenshot Testing wrapper.
 *
 * Apply to a top-level function, or a function nested directly inside a Kotlin `object`.
 * The compose-preview-toolkit Gradle plugin scans for this annotation by default; a
 * different marker annotation can be configured via the plugin's `annotationFqn` extension
 * property instead of depending on this module.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ScreenshotPreview

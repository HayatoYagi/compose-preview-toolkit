package io.github.hayatoyagi.composepreviewtoolkit.annotations

import androidx.compose.ui.tooling.preview.Preview

private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_NIGHT_YES = 0x20
private const val LIGHT_BACKGROUND_COLOR: Long = 0xFFFFFFFF
private const val DARK_BACKGROUND_COLOR: Long = 0xFF121212

/**
 * Optional convenience: stacks a light/dark pair of `@Preview`s. Not required by
 * [ScreenshotPreview] or the Gradle plugin — stack your own preview annotation(s) on a
 * `@ScreenshotPreview` function instead if you don't want this module's opinionated styling.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@Preview(
    name = "Light",
    showBackground = true,
    uiMode = UI_MODE_NIGHT_NO,
    backgroundColor = LIGHT_BACKGROUND_COLOR,
)
@Preview(
    name = "Dark",
    showBackground = true,
    uiMode = UI_MODE_NIGHT_YES,
    backgroundColor = DARK_BACKGROUND_COLOR,
)
annotation class PreviewSet

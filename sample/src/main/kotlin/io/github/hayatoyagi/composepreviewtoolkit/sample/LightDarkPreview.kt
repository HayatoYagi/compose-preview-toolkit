package io.github.hayatoyagi.composepreviewtoolkit.sample

import androidx.compose.ui.tooling.preview.Preview

private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_NIGHT_YES = 0x20
private const val LIGHT_BACKGROUND_COLOR: Long = 0xFFFFFFFF
private const val DARK_BACKGROUND_COLOR: Long = 0xFF121212

/**
 * App-specific light/dark multi-preview annotation, configured as this app's
 * `extraPreviewAnnotationFqn` (see build.gradle.kts) instead of relying on any bundled default
 * from the toolkit — screenshot-preview-toolkit intentionally ships no opinionated styling of
 * its own, so each app defines (and owns) something like this.
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
annotation class LightDarkPreview

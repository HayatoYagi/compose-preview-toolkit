package io.github.hayatoyagi.composepreviewtoolkit.gradle

// Fixed default dependency versions this plugin adds to consumer builds. Consumers can
// override each via the `composePreviewToolkit { ... }` extension; these are not read from
// the consumer's own version catalog on purpose (see ComposePreviewToolkitPlugin) so the
// plugin behaves consistently regardless of what catalog entries a consumer happens to have.
internal const val DEFAULT_COMPOSE_VERSION = "1.11.4"
internal const val DEFAULT_SCREENSHOT_VALIDATION_API_VERSION = "0.0.1-alpha15"

// Annotation stacked on generated wrappers unless the consumer configures
// `extraPreviewAnnotationFqn` to point at their own (e.g. a light/dark multi-preview
// annotation). Defaults to the plain Compose tooling annotation — no app-specific styling
// opinions (background color, uiMode, etc.) baked into this toolkit's own defaults.
internal const val DEFAULT_EXTRA_PREVIEW_ANNOTATION_FQN = "androidx.compose.ui.tooling.preview.Preview"

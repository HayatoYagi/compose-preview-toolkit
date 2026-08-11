# compose-preview-toolkit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hayatoyagi/compose-preview-toolkit-annotations)](https://central.sonatype.com/namespace/io.github.hayatoyagi)
[![Gradle Plugin Portal](https://img.shields.io/badge/Gradle_Plugin_Portal-02303A?logo=gradle&logoColor=white)](https://plugins.gradle.org/plugin/io.github.hayatoyagi.compose-preview-toolkit)

A Gradle plugin that turns a single marker annotation on your Jetpack Compose `@Preview`
functions into AGP's official [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)
wrappers — no hand-duplicated `@PreviewTest` functions in `androidTest`/`screenshotTest`.

## Overview

- Write `@ScreenshotPreview` next to your `@Preview` composable in `src/main` — that's it.
- A KSP processor finds every `@ScreenshotPreview` function at compile time.
- The Gradle plugin generates the matching `@PreviewTest` wrapper directly into AGP's
  `debugScreenshotTest` source set, wires it into `updateDebugScreenshotTest` /
  `validateDebugScreenshotTest`, and cleans up stale baseline images when previews are
  renamed or removed.
- A reusable composite GitHub Action (`.github/actions/update-validate-screenshot-tests`) wraps
  the CI-side "update baselines, commit if changed, then validate" flow for you.

## Motivation

**Before** — the official workflow requires a second, hand-maintained copy of every preview you
want screenshot-tested:

```kotlin
// src/main/kotlin/.../GreetingScreen.kt
@Preview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

```kotlin
// src/screenshotTest/kotlin/.../GreetingScreenshotTest.kt  (you write and maintain this too)
@PreviewTest
@Preview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

**After** — one annotation, nothing to add under `screenshotTest`:

```kotlin
// src/main/kotlin/.../GreetingScreen.kt
@ScreenshotPreview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

## Installation

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

```kotlin
// app or feature module's build.gradle.kts
plugins {
    id("com.android.application") // or com.android.library — AGP 9's built-in Kotlin support
                                   // means you do NOT also apply org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.hayatoyagi.compose-preview-toolkit") version "<version>"
}
```

The plugin applies `com.android.compose.screenshot` and `com.google.devtools.ksp` for you, and
by default adds `io.github.hayatoyagi:compose-preview-toolkit-annotations` so
`@ScreenshotPreview` is available.

## Quick Start

1. Write a normal `@Preview` composable.
2. Add `@ScreenshotPreview` next to it.
3. Run `./gradlew updateDebugScreenshotTest` once to generate the initial baseline image(s).
4. Run `./gradlew validateDebugScreenshotTest` in CI to catch visual regressions.

```kotlin
import io.github.hayatoyagi.composepreviewtoolkit.annotations.ScreenshotPreview

internal object GreetingScreenPreviews {
    @ScreenshotPreview
    @Composable
    internal fun Default() {
        MaterialTheme { GreetingScreen(name = "compose-preview-toolkit") }
    }
}
```

## Features

### `@ScreenshotPreview`

Marks a parameterless `@Composable` preview function for screenshot-test generation. Works on
top-level functions or functions nested directly inside a Kotlin `object`. This is the only
thing that decides which functions get a screenshot test — it has nothing to do with how they
render (see below).

### Configuring what gets rendered

Every generated wrapper gets a plain `androidx.compose.ui.tooling.preview.Preview` (a single
default render) unless you configure `extraPreviewAnnotationFqn` to point at your own multi-preview
annotation — e.g. a light/dark pair:

```kotlin
composePreviewToolkit {
    annotationFqn.set("com.example.app.ScreenshotPreview")           // use your own marker instead of @ScreenshotPreview
    extraPreviewAnnotationFqn.set("com.example.app.LightDarkPreview") // stack your own multi-preview annotation instead
}
```

### Composite GitHub Action

Reusable action docs (requirements, permissions, and full workflow example):

- [.github/actions/update-validate-screenshot-tests/README.md](.github/actions/update-validate-screenshot-tests/README.md)

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v0.1.0
  with:
    github-token: ${{ secrets.GITHUB_TOKEN }}
```

See [action.yml](.github/actions/update-validate-screenshot-tests/action.yml) for all inputs.

## Sample App

`sample/` is a minimal Android app demonstrating end-to-end usage (not published). It's a
**separate Gradle build** (its own `settings.gradle.kts`, `gradlew`) rather than a subproject of
the root build — see the comment at the top of `sample/settings.gradle.kts` for why: it applies
the plugin exactly like a real consumer (`id("io.github.hayatoyagi.compose-preview-toolkit")
version "<version>"`), and that version is always ahead of whatever's actually published while
this repo is under active development.

See [CONTRIBUTING.md](CONTRIBUTING.md) for local development setup and the release process.

## Known limitations

- Only the `debug` build type is supported in v1.
- AGP's Compose Preview Screenshot Testing is still an alpha feature (`0.0.1-alpha1x` as of this
  writing); breaking changes upstream may require a plugin update.

## Roadmap

- **Navigation graph + screenshot site (planned, not started)**: statically extract a Compose
  type-safe navigation graph (nodes from `composable<Route>` declarations, edges via
  best-effort scanning of `navigate(...)` call sites), pair each screen node with its generated
  screenshot baseline, and publish a static site to GitHub Pages for quick visual review of the
  whole app's navigation flow.

## License

Apache-2.0 — see [LICENSE](LICENSE).

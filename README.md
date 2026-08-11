# compose-preview-toolkit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hayatoyagi/compose-preview-toolkit-annotations)](https://central.sonatype.com/namespace/io.github.hayatoyagi)
[![Gradle Plugin Portal](https://img.shields.io/badge/Gradle_Plugin_Portal-02303A?logo=gradle&logoColor=white)](https://plugins.gradle.org/plugin/io.github.hayatoyagi.compose-preview-toolkit)

A Gradle plugin that turns a single marker annotation on your Jetpack Compose `@Preview`
functions into AGP's official [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)
wrappers — no hand-duplicated `@PreviewTest` functions in `androidTest`/`screenshotTest`.

**Before** — two functions in two files, kept in sync by hand:

```kotlin
// src/main/kotlin/.../GreetingScreen.kt — for Android Studio's Preview panel
@Preview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

```kotlin
// src/screenshotTest/kotlin/.../GreetingScreenshotTest.kt — a second copy you write and maintain
@PreviewTest
@Preview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

**After** — one function, one file. `@Preview` stays for Android Studio's Preview panel; adding
`@ScreenshotPreview` next to it is all that's needed for the screenshot test:

```kotlin
// src/main/kotlin/.../GreetingScreen.kt
@Preview
@ScreenshotPreview
@Composable
fun GreetingPreview() { GreetingScreen(name = "Android") }
```

## Overview

- Write `@ScreenshotPreview` next to your `@Preview` composable in `src/main` — that's it.
- A KSP processor finds every `@ScreenshotPreview` function at compile time.
- The Gradle plugin generates the matching `@PreviewTest` wrapper directly into AGP's
  `debugScreenshotTest` source set, wires it into `updateDebugScreenshotTest` /
  `validateDebugScreenshotTest`, and cleans up stale baseline images when previews are
  renamed or removed.
- A reusable composite GitHub Action (`.github/actions/update-validate-screenshot-tests`) wraps
  the CI-side "update baselines, commit if changed, then validate" flow for you.

## Requirements

Tested with:

- Gradle 9.6+
- Android Gradle Plugin (AGP) 9.2.1+ — specifically its built-in Kotlin support (see
  Installation below) and `com.android.compose.screenshot` 0.0.1-alpha15, which this plugin
  applies for you.
- Kotlin 2.4.0+ / KSP 2.3.9+

Untested on older AGP/Gradle combinations. This plugin wires generated sources into AGP's
`screenshotTest` source set via reflection (see `ComposePreviewToolkitPlugin.kt`) because AGP
doesn't yet expose a stable API for it, so behavior on versions outside the above isn't
guaranteed.

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
import androidx.compose.ui.tooling.preview.Preview
import io.github.hayatoyagi.composepreviewtoolkit.annotations.ScreenshotPreview

@Preview
@ScreenshotPreview
@Composable
internal fun GreetingScreenPreview() {
    MaterialTheme { GreetingScreen(name = "compose-preview-toolkit") }
}
```

## Configuration

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

See `ComposePreviewToolkitExtension.kt` for every available property, including overriding the
`compose`/`screenshot-validation-api` versions this plugin adds.

### Composite GitHub Action

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v0.1.0
  with:
    github-token: ${{ secrets.GITHUB_TOKEN }}
```

See [.github/actions/update-validate-screenshot-tests/README.md](.github/actions/update-validate-screenshot-tests/README.md)
for requirements and a full workflow example, or [action.yml](.github/actions/update-validate-screenshot-tests/action.yml)
for every input.

## Nav Graph (experimental, node extraction only)

A **separate** plugin id, `io.github.hayatoyagi.compose-preview-toolkit.navgraph` — deliberately
not bundled into the plugin above, since it pulls in a heavy embedded-Kotlin-compiler dependency
that only Navigation3 users need (see `nav-graph-gradle-plugin`'s kdoc for why). Statically scans a
module's own `src/main/kotlin` for Navigation3 `entry<Route> { ... }` registrations via Kotlin PSI
(no type resolution) and writes a node index:

```kotlin
// feature module's build.gradle.kts
plugins {
    id("io.github.hayatoyagi.compose-preview-toolkit.navgraph") version "<version>"
}
```

```
./gradlew generateDebugNavGraph
```

writes `build/generated/composePreviewToolkit/navGraph/debug/ComposePreviewToolkitNavNodeIndex.txt`
(tab-separated `packageName\tsimpleName\tqualifiedName`), scoped to that module's own sources only
— see `sample/feature-a`/`sample/feature-b` for a worked example, and `docs/ROADMAP.md` for what's
still to come (edge detection, cross-module aggregation, the screenshot-paired site). Configure via
the `composePreviewToolkitNavGraph { ... }` extension (currently just `entryFunctionNames`, in case
your codebase uses a differently-named `entry`-shaped registration function).

## Sample App

`sample/` is a minimal, multi-module Android app demonstrating end-to-end usage (not published).
It's a **separate Gradle build** (its own `settings.gradle.kts`, `gradlew`) rather than a
subproject of the root build — see the comment at the top of `sample/settings.gradle.kts` for why:
it applies the plugin(s) exactly like a real consumer would (`id("io.github.hayatoyagi.compose-preview-toolkit")
version "<version>"`), and that version is always ahead of whatever's actually published while
this repo is under active development.

`sample/app` is a small Navigation3 app wiring together `sample/feature-a` and `sample/feature-b`
(each a separate feature module), demonstrating the nav-graph plugin's node extraction (see "Nav
Graph" above) alongside Phase 1's screenshot-test generation.

## Known limitations

- Only the `debug` build type is supported currently.
- AGP's Compose Preview Screenshot Testing is still an alpha feature (`0.0.1-alpha1x` as of this
  writing); breaking changes upstream may require a plugin update.

## Roadmap

- **Navigation graph + screenshot site (in progress)**: statically extract a Navigation3 nav
  graph and pair each screen node with its generated screenshot baseline, publishing a static
  site to GitHub Pages for quick visual review of the whole app's navigation flow. Node
  extraction (`io.github.hayatoyagi.compose-preview-toolkit.navgraph` plugin, `nav-graph-psi-analyzer`,
  the `generateDebugNavGraph` task) is available now — see `sample/feature-a`/`sample/feature-b`.
  Edge detection, cross-module aggregation, and the site itself are not yet implemented; see
  `docs/ROADMAP.md` for the full design and PR sequence.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local development setup, how a PR gets validated, and
the release process.

## License

Apache-2.0 — see [LICENSE](LICENSE).

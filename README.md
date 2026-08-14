# compose-preview-toolkit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hayatoyagi/compose-preview-toolkit-annotations)](https://central.sonatype.com/artifact/io.github.hayatoyagi/compose-preview-toolkit-annotations)
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

This plugin wires generated sources into AGP's `screenshotTest` source set via reflection (see
`ComposePreviewToolkitPlugin.kt`) because AGP doesn't yet expose a stable API for it — so behavior
on versions outside the above isn't guaranteed.

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
internal fun HomeScreenPreview() {
    MaterialTheme { HomeScreen(onGoToFeatureAClick = {}) }
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
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v0.3.0
  with:
    github-token: ${{ secrets.GITHUB_TOKEN }}
```

See [.github/actions/update-validate-screenshot-tests/README.md](.github/actions/update-validate-screenshot-tests/README.md)
for requirements and a full workflow example, or [action.yml](.github/actions/update-validate-screenshot-tests/action.yml)
for every input.

## Nav Graph

A **separate** plugin id, `io.github.hayatoyagi.compose-preview-toolkit.navgraph`. It statically
scans every discovered module's `src/main/kotlin` via Kotlin PSI (no type resolution) for
Navigation3 `entry<Route> { ... }` registrations (nodes) and `navigateTo`/`navigate`-shaped calls
reachable from each one via a bounded-depth call-graph search (edges):

```kotlin
// feature module's build.gradle.kts
plugins {
    id("io.github.hayatoyagi.compose-preview-toolkit.navgraph") version "<version>"
}
```

Only needs to be applied where you run `generateDebugNavGraphSite` — a dependency module is
discovered and scanned via its dependents without applying this plugin there too. If your build
applies Compose Kotlin Gradle subplugins asymmetrically across subprojects, you may hit a separate
Gradle-core issue, "The Kotlin Gradle plugin was loaded multiple times in different subprojects":
declare every plugin used anywhere in the build once in the root `build.gradle.kts`'s `plugins {}`
block with `apply false` to fix it — see `nav-graph-gradle-plugin`'s kdoc for why.

Edge detection is best-effort and name-based, not type resolution: ambiguous callee names and calls
beyond the configured depth are dropped with a warning rather than guessed, and there's no
escape-hatch annotation for gaps — if the scanner misses something real, the scanner should improve
rather than asking you to annotate your navigation code. Configure via
`composePreviewToolkitNavGraph { ... }`'s `entryFunctionNames`, `navigateCallNames`, and
`callGraphResolutionDepth`.

### Gallery site (nodes + edges + screenshots)

On an "aggregator" module (typically your app module, the one that actually wires every feature's
routes into its own `NavDisplay`), just run `generateDebugNavGraphSite`:

```
./gradlew :app:generateDebugNavGraphSite
```

This task:

- Runs one project-wide PSI scan across every discovered project's raw `.kt` sources to find both
  nodes and edges together, rather than aggregating each module's own precomputed index — a
  route's `entry { ... }` registration, its declaration, and the `navigateTo(...)` call that
  reaches it often all live in different modules, which a single-module scan can't resolve. A
  route whose declaration genuinely can't be found anywhere in that scan fails the build, naming
  the route and its `entry<X> { ... }` call site.
- Also aggregates each discovered project's screenshot index and reference baselines (for
  projects also applying the `io.github.hayatoyagi.compose-preview-toolkit` plugin) — via a real
  Gradle cross-project task dependency, so running the aggregator's task alone triggers every
  graph module's own `kspDebugKotlin` first.
- Pairs each node with a screenshot thumbnail by a best-effort naming heuristic: strip a
  configurable suffix (`routeNameSuffixesToStrip`, default `["Destination", "Route"]`) from the
  route's simple name, then case-insensitively substring-match the remainder against the
  screenshot wrapper name. Unmatched routes render as thumbnail-less cards — not an error.

Output is a single self-contained `build/composePreviewToolkit/navGraphSite/debug/index.html`: a
Mermaid.js graph diagram of every node and detected edge (Mermaid loaded from a CDN at page-load
time — affects only the viewer's browser, not build reproducibility), plus a thumbnail gallery
(thumbnails embedded as base64 data URIs). Clicking a node's modal also shows its `entry<X> { ... }`
registration's `filePath:line`; when `GITHUB_REPOSITORY`/`GITHUB_SHA` are both set (as GitHub
Actions does automatically) and that node's path is git-root-relative, it's rendered as a clickable
link to that exact line on GitHub — otherwise it's shown as plain, non-interactive text. See
"Sample App" below for a worked example.

### Composite GitHub Action

`mode: 'build'` (the default) just runs the Gradle task; `mode: 'github-pages'` additionally
manages a persisted main site plus live per-PR previews on GitHub Pages:

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v0.3.0
  with:
    site-task: ':app:generateDebugNavGraphSite'
    site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
    mode: 'github-pages' # 'build' (default) | 'github-pages'
```

See [.github/actions/deploy-nav-graph-site/README.md](.github/actions/deploy-nav-graph-site/README.md)
for the full mode-by-mode breakdown, required permissions/settings, and a worked example, or
[action.yml](.github/actions/deploy-nav-graph-site/action.yml) for every input.

## Sample App

`sample/` is a minimal, multi-module Android app demonstrating end-to-end usage (not published),
applying the plugin(s) exactly like a real consumer would.

`sample/app` is a Navigation3 app wiring together `sample/feature-a` and `sample/feature-b`,
demonstrating the nav-graph plugin's node extraction and gallery site generation (see "Nav Graph"
above) alongside the screenshot-test plugin. All three modules apply the screenshot-test plugin, so
every route gets a real thumbnail in the generated gallery; only `sample/app` applies the nav-graph
plugin, demonstrating "apply once, on the aggregator only" (see "Nav Graph" above). `sample/`'s own
root `build.gradle.kts` declares every plugin used anywhere in the sample build with `apply false`,
which is what keeps that topology safe from the classloader-mismatch pitfall described there.

The sample also demonstrates both edge-detection shapes: `feature-a`'s `onProceedClick`, written at
the app level and passed in as a callback (`AppNavHost.kt`/`FeatureANavEntries.kt`), and
`feature-b`'s "Restart from Feature A" button, which calls `navigateTo(FeatureARoute)` directly
with no callback indirection (`FeatureBNavEntries.kt`). Both are found by the same call-graph
algorithm — see `nav-graph-psi-analyzer`'s `NavEdgeScanner` kdoc for how.

See it live at
[hayatoyagi.github.io/compose-preview-toolkit](https://hayatoyagi.github.io/compose-preview-toolkit/),
generated from this same sample on every push to `main`.

## Known limitations

- Only the `debug` build type is supported currently.
- AGP's Compose Preview Screenshot Testing is still an alpha feature (`0.0.1-alpha1x` as of this
  writing); breaking changes upstream may require a plugin update.
- The nav-graph plugin's node/edge analysis is name-based, not type-resolved, throughout, so
  results are best-effort; node↔screenshot matching is a configurable naming heuristic, not a
  guaranteed pairing.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local development setup, how a PR gets validated, and
the release process.

## License

Apache-2.0 — see [LICENSE](LICENSE).

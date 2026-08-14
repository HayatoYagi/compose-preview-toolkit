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
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v0.1.0
  with:
    github-token: ${{ secrets.GITHUB_TOKEN }}
```

See [.github/actions/update-validate-screenshot-tests/README.md](.github/actions/update-validate-screenshot-tests/README.md)
for requirements and a full workflow example, or [action.yml](.github/actions/update-validate-screenshot-tests/action.yml)
for every input.

## Nav Graph (experimental)

A **separate** plugin id, `io.github.hayatoyagi.compose-preview-toolkit.navgraph` — not bundled
into the plugin above, since it pulls in a heavy embedded-Kotlin-compiler dependency that only
Navigation3 users need. It statically scans a module's
own `src/main/kotlin` via Kotlin PSI (no type resolution) for Navigation3 `entry<Route> { ... }`
registrations (nodes) and `navigateTo`/`navigate`-shaped calls reachable from each one via a
bounded-depth call-graph search (edges), then writes a node + edge index:

```kotlin
// feature module's build.gradle.kts
plugins {
    id("io.github.hayatoyagi.compose-preview-toolkit.navgraph") version "<version>"
}
```

```
./gradlew generateDebugNavGraph
```

Writes, under `build/generated/composePreviewToolkit/navGraph/debug/` (scoped to that module's own
sources):

- `ComposePreviewToolkitNavNodeIndex.txt` — tab-separated `packageName\tsimpleName\tqualifiedName`
- `ComposePreviewToolkitNavEdgeIndex.txt` — tab-separated `sourceRouteQualifiedName\ttargetRouteQualifiedName`

Edge detection handles two navigation-wiring shapes with one algorithm: a callback threaded through
intermediate composables before finally being invoked far from where it's declared (e.g. a feature
module's `onProceedClick`, only actually invoked at an app-level `NavHost`'s call site), and a
direct `navigateTo(...)` call with no callback indirection. Analysis is best-effort and name-based,
not type resolution: ambiguous callee names and calls beyond the configured depth are dropped with
a warning rather than guessed, and there's no escape-hatch annotation for gaps — if the scanner
misses something real, the scanner should improve rather than asking you to annotate your
navigation code. Configure via `composePreviewToolkitNavGraph { ... }`'s `entryFunctionNames`,
`navigateCallNames`, and `callGraphResolutionDepth`.

### Gallery site (nodes + edges + screenshots)

On an "aggregator" module (typically your app module, the one that actually wires every feature's
routes into its own `NavDisplay`), configure `graphModules` with every project path that
contributes to the graph, then run `generateDebugNavGraphSite`:

```kotlin
// app module's build.gradle.kts
composePreviewToolkitNavGraph {
    graphModules.set(setOf(":app", ":feature-a", ":feature-b"))
}
```

```
./gradlew :app:generateDebugNavGraphSite
```

This task:

- Aggregates each `graphModules` project's node index, plus (for projects also applying the
  `io.github.hayatoyagi.compose-preview-toolkit` plugin) their
  `ComposePreviewToolkitScreenshotIndex*.txt` + `src/screenshotTestDebug/reference/**/*.png`
  baselines — via real Gradle cross-project task dependencies, so running the aggregator's task
  alone triggers every graph module's own `generateDebugNavGraph`/`kspDebugKotlin` first.
- Re-scans edges project-wide across every `graphModules` project's raw sources, rather than
  purely aggregating each module's own edge index — a route's `entry { ... }` registration and the
  `navigateTo(...)` call that reaches it often live in different modules, which a single-module
  scan can't resolve.
- Pairs each node with a screenshot thumbnail by a best-effort naming heuristic: strip a
  configurable suffix (`routeNameSuffixesToStrip`, default `["Destination", "Route"]`) from the
  route's simple name, then case-insensitively substring-match the remainder against the
  screenshot wrapper name. Unmatched routes render as thumbnail-less cards — not an error.

Output is a single self-contained `build/composePreviewToolkit/navGraphSite/debug/index.html`: a
Mermaid.js graph diagram of every node and detected edge (Mermaid loaded from a CDN at page-load
time — affects only the viewer's browser, not build reproducibility), plus a thumbnail gallery
(thumbnails embedded as base64 data URIs). See "Sample App" below for a worked example.

### Composite GitHub Action

Two modes, selected by the `mode` input:

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v0.1.0
  with:
    site-task: ':app:generateDebugNavGraphSite'
    site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
    mode: 'github-pages' # 'build' (default) | 'github-pages'
```

- **`mode: 'build'`** (the default): only runs the Gradle task — no Pages permissions needed,
  useful for build-only CI dogfooding.
- **`mode: 'github-pages'`**: the full managed GitHub-Pages-with-previews experience from one
  call, branching internally on the triggering event: `push` deploys `site-directory` as the
  persisted main site; `pull_request` (`opened`/`reopened`/`synchronize`) deploys a live per-PR
  preview with a sticky PR comment; `pull_request: closed` tears that preview down. Both share the
  same branch — requires repo **Settings → Pages → Source** = **Deploy from branch** pointed at
  `pages-branch`.

This repo's own [`ci.yml`](.github/workflows/ci.yml) is a concrete worked example: a single
`mode: 'github-pages'` call, in one workflow, handles a persisted main site, live PR previews, and
teardown for `sample/app`'s nav graph.

See [.github/actions/deploy-nav-graph-site/README.md](.github/actions/deploy-nav-graph-site/README.md)
for the full mode-by-mode breakdown and required permissions/settings, or
[action.yml](.github/actions/deploy-nav-graph-site/action.yml) for every input.

## Sample App

`sample/` is a minimal, multi-module Android app demonstrating end-to-end usage (not published),
applying the plugin(s) exactly like a real consumer would.

`sample/app` is a Navigation3 app wiring together `sample/feature-a` and `sample/feature-b`,
demonstrating the nav-graph plugin's node extraction and gallery site generation (see "Nav Graph"
above) alongside the screenshot-test plugin. All three modules apply both plugins, so every route
gets a real thumbnail in the generated gallery.

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

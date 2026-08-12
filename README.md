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

A **separate** plugin id, `io.github.hayatoyagi.compose-preview-toolkit.navgraph` — deliberately
not bundled into the plugin above, since it pulls in a heavy embedded-Kotlin-compiler dependency
that only Navigation3 users need (see `nav-graph-gradle-plugin`'s kdoc for why). Statically scans a
module's own `src/main/kotlin` via Kotlin PSI (no type resolution) for Navigation3
`entry<Route> { ... }` registrations (nodes) and `navigateTo`/`navigate`-shaped calls reachable
from each one via a bounded-depth call-graph search (edges), and writes a node + edge index:

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
(tab-separated `packageName\tsimpleName\tqualifiedName`) and `...NavEdgeIndex.txt` (tab-separated
`sourceRouteQualifiedName\ttargetRouteQualifiedName`), scoped to that module's own sources only.
Edge detection handles two navigation-wiring shapes through one algorithm, with no special-casing:
a callback threaded through intermediate composables before finally being invoked far from where
it's declared (e.g. a feature module's `onProceedClick`, only actually invoked at an app-level
`NavHost`'s call site), and a direct `navigateTo(...)` call with no callback indirection at all.
This is deliberately best-effort, name-based analysis, not type resolution: ambiguous callee names
and calls beyond the configured depth are dropped with a warning rather than guessed, and there is
no escape-hatch annotation for cases the scanner misses — if a real gap shows up, the scanner
itself should improve rather than asking you to annotate your real navigation code. Configure via
the `composePreviewToolkitNavGraph { ... }` extension's `entryFunctionNames`, `navigateCallNames`,
and `callGraphResolutionDepth`.

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

This aggregates each `graphModules` project's node index and (if that project also applies the
Phase 1 plugin above) its `ComposePreviewToolkitScreenshotIndex*.txt` + `src/screenshotTestDebug/reference/**/*.png`
baselines, real Gradle cross-project task dependencies included — running the aggregator's task
alone is enough to trigger every graph module's own `generateDebugNavGraph`/`kspDebugKotlin` first,
no manual ordering required. Edges are additionally (re-)scanned project-wide across every
`graphModules` project's raw sources in this same task, rather than purely aggregated from each
module's own edge index — a route's `entry { ... }` registration and the `navigateTo(...)` call
site that reaches it routinely live in different modules, which a single-module scan can't resolve
on its own. Each node is best-effort paired with a screenshot thumbnail by a naming heuristic:
strip a configurable suffix (`routeNameSuffixesToStrip`, default `["Destination", "Route"]`) from
the route's simple name, then case-insensitively substring-match the remainder against the
screenshot wrapper name. Unmatched routes render as thumbnail-less cards — not an error, the
expected outcome for some routes.

Output is a single self-contained `build/composePreviewToolkit/navGraphSite/debug/index.html` with
two sections: a Mermaid.js graph diagram of every node and detected edge (Mermaid itself loaded
from a CDN at page-load time — this affects only the viewer's browser, not build reproducibility),
and the thumbnail gallery (thumbnails embedded as base64 data URIs, no separate PNG files to keep
in sync). See `sample/app`/`sample/feature-a`/`sample/feature-b` for a worked example — including
`feature-b`'s "Restart from Feature A" button, which demonstrates the direct-call edge pattern.

### Composite GitHub Action

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v0.1.0
  with:
    site-task: ':app:generateDebugNavGraphSite'
    site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
    deploy: 'true' # omit or set to 'false' to only build the site (e.g. PR dogfooding)
```

`deploy: 'true'` wraps `actions/configure-pages` / `actions/upload-pages-artifact` /
`actions/deploy-pages` around the build step; the calling job needs
`permissions: { pages: write, id-token: write }` and `environment: github-pages` itself — a
composite action can't set job-level permissions or environment on its caller. `deploy: 'false'`
(the default) only runs the Gradle task, which is what this repo's own `ci.yml` uses to dogfood
`generateDebugNavGraphSite` against `sample/app` on every PR without needing Pages permissions at
all. See [action.yml](.github/actions/deploy-nav-graph-site/action.yml) for every input.

## Sample App

`sample/` is a minimal, multi-module Android app demonstrating end-to-end usage (not published).
It's a **separate Gradle build** (its own `settings.gradle.kts`, `gradlew`) rather than a
subproject of the root build — see the comment at the top of `sample/settings.gradle.kts` for why:
it applies the plugin(s) exactly like a real consumer would (`id("io.github.hayatoyagi.compose-preview-toolkit")
version "<version>"`), and that version is always ahead of whatever's actually published while
this repo is under active development.

`sample/app` is a small Navigation3 app wiring together `sample/feature-a` and `sample/feature-b`
(each a separate feature module), demonstrating the nav-graph plugin's node extraction and gallery
site generation (see "Nav Graph" above) alongside Phase 1's screenshot-test generation. Every
module — `app`, `feature-a`, and `feature-b` — applies both the Phase 1 screenshot-testing plugin
and the navgraph plugin, so in the generated gallery all three routes get a real thumbnail:
`HomeRoute` from `HomeScreen`'s `@ScreenshotPreview`-annotated `HomeScreenPreview`, `FeatureARoute`
from `FeatureAScreen`'s `FeatureAScreenPreview`, and `FeatureBRoute` from `FeatureBScreen`'s
`FeatureBScreenPreview` — each naming-matched after stripping the `Route` suffix.

`feature-b` also demonstrates the second of the two navigation-wiring shapes the edge detector
(`NavEdgeScanner`) supports: its "Restart from Feature A" button calls `navigateTo(FeatureARoute)`
directly inside `featureBNavEntries`/`FeatureBNavEntries.kt`, with no callback parameter threaded
up to `sample/app` for that particular edge — unlike `feature-a`'s `onProceedClick`, which is
written at the app level and passed in as a callback (see `AppNavHost.kt`/`FeatureANavEntries.kt`).
Both shapes are found by the exact same call-graph algorithm; see `nav-graph-psi-analyzer`'s
`NavEdgeScanner` kdoc for how.

## Known limitations

- Only the `debug` build type is supported currently.
- AGP's Compose Preview Screenshot Testing is still an alpha feature (`0.0.1-alpha1x` as of this
  writing); breaking changes upstream may require a plugin update.

## Roadmap

- **Navigation graph + screenshot site (done)**: statically extract a Navigation3 nav graph and
  pair each screen node with its generated screenshot baseline, rendered as a self-contained
  Mermaid graph + thumbnail gallery site, deployable to GitHub Pages. Node extraction, edge
  detection, cross-module aggregation, the gallery/graph site, and the deploy action
  (`io.github.hayatoyagi.compose-preview-toolkit.navgraph` plugin, `nav-graph-psi-analyzer`, the
  `generateDebugNavGraph`/`generateDebugNavGraphSite` tasks,
  `.github/actions/deploy-nav-graph-site`) are all available now — see "Nav Graph" above and
  `sample/app`/`sample/feature-a`/`sample/feature-b`. See `docs/ROADMAP.md` for the full design.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local development setup, how a PR gets validated, and
the release process.

## License

Apache-2.0 — see [LICENSE](LICENSE).

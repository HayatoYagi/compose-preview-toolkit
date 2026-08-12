# Roadmap

## Phase 1 — screenshot-test generation (current)

- `annotations`: `@ScreenshotPreview` marker annotation.
- `ksp-processor`: discovers `@ScreenshotPreview` functions, writes a per-module index resource.
- `gradle-plugin`: generates AGP-official `@PreviewTest` wrappers from that index into the
  `debugScreenshotTest` source set; wires `updateDebugScreenshotTest` / `validateDebugScreenshotTest`;
  cleans up stale baseline images.
- `.github/actions/update-validate-screenshot-tests`: reusable composite action wrapping the
  CI-side update/validate flow.

Known Phase 1 limitations: `debug` build type only; tracks an alpha AGP feature
(`com.android.compose.screenshot`), so upstream breaking changes may require a plugin bump.

## Phase 2 — navigation graph + screenshot site (done)

Goal: statically analyze a Jetpack Compose Navigation3 navigation graph and render it as a static
site — each screen node linked to its already-generated screenshot baseline — deployed to GitHub
Pages, so a whole app's navigation flow (with visuals) can be reviewed at a glance without running
the app. Targets Navigation3 (`NavDisplay`/`entryProvider<NavKey> { entry<Route> { ... } }`), not
classic Navigation Compose — validated against a real Nav3 consumer during design.

Pieces, roughly in build order:

- **`nav-graph-psi-analyzer`** (done): a Kotlin PSI-based library, no Gradle/Android dependency.
  - `NavNodeScanner`: finds every `entry<Route> { ... }` registration project-wide and produces one
    node per route (including routes nested inside a sealed interface/class).
  - `NavEdgeScanner`: a bounded-depth breadth-first search over a lazily-discovered call graph,
    starting from each node's `entry { ... }` block, to find `navigateTo`/`navigate`-shaped calls
    reachable from it. Handles both a callback threaded through intermediate composables before
    being invoked far from where it's declared, and a direct call with no callback indirection at
    all, through one algorithm with no special-casing between the two shapes.
  - Both are syntactic (no type resolution) and best-effort by design: ambiguous callee names and
    calls beyond the configured depth bound are dropped with a warning rather than guessed. There
    is deliberately no escape-hatch annotation for cases the scanner misses — if a real gap shows
    up, the scanner itself should improve; this toolkit doesn't ask consumers to modify their real
    navigation code just to make graph generation work.
- **`nav-graph-gradle-plugin`** (done): a separate plugin id
  (`io.github.hayatoyagi.compose-preview-toolkit.navgraph`) from Phase 1's, since it transitively
  depends on a heavy embedded Kotlin compiler frontend that only Navigation3 users need.
  - `generateDebugNavGraph`: runs the node and edge scanners over a module's own sources.
  - `generateDebugNavGraphSite`: aggregates node indexes plus Phase 1's screenshot
    indexes/baselines across a configured set of modules, and re-scans every configured module's
    combined sources project-wide for edges (a module-local edge index alone misses most real
    edges, since a route's `entry {}` registration and the `navigateTo(...)` call reaching it
    routinely live in different modules), into a single self-contained gallery `index.html` with
    a Mermaid.js graph diagram alongside the thumbnail cards.
- **`.github/actions/deploy-nav-graph-site`** (done): a reusable composite action that always
  runs a nav-graph site-generation task, and optionally publishes the result via its `mode` input
  (`"build"` default / `"pages"` / `"pr-preview"`). `"build"` only runs the task, which is what
  this repo's own `ci.yml` uses to dogfood it on every PR without needing any Pages permissions.
  `"pages"` deploys to a single shared GitHub Pages site via `actions/deploy-pages`, needing
  `pages`/`id-token` permissions and a `github-pages` environment on the calling job (only a
  workflow, not a composite action, can set those). `"pr-preview"` wraps
  `rossjrw/pr-preview-action` to publish a live, per-pull-request preview URL instead — GitHub's
  Pages Deployments API (`"pages"` mode) only supports one live deployment per repo, so it
  structurally can't host concurrent PR previews; `"pr-preview"` pushes each PR's site to its own
  subdirectory on a branch instead, with a sticky PR comment linking to it and automatic teardown
  on PR close. `"pages"` and `"pr-preview"` require mutually exclusive repo Pages source settings
  (GitHub Actions vs. Deploy from branch), so a repo picks one as its main-site mechanism — see
  README.md's "Nav Graph" section for how to combine both. Left to a separate,
  publish-dedicated workflow for consumers who want either.

Known Phase 2 limitations so far: name-based (not type-resolved) analysis throughout, so results
are best-effort; node↔screenshot matching is a configurable naming heuristic, not a guaranteed
pairing.

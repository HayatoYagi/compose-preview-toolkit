# Roadmap

## v1 — screenshot-test generation (this scaffold)

- `annotations`: `@ScreenshotPreview` marker annotation.
- `ksp-processor`: discovers `@ScreenshotPreview` functions, writes a per-module index resource.
- `gradle-plugin`: generates AGP-official `@PreviewTest` wrappers from that index into the
  `debugScreenshotTest` source set; wires `updateDebugScreenshotTest` / `validateDebugScreenshotTest`;
  cleans up stale baseline images.
- `.github/actions/update-validate-screenshot-tests`: reusable composite action wrapping the
  CI-side update/validate flow.

Known v1 limitations: `debug` build type only; tracks an alpha AGP feature
(`com.android.compose.screenshot`), so upstream breaking changes may require a plugin bump.

## v2 — navigation graph + screenshot site (not started)

Goal: statically analyze a Compose type-safe navigation graph and render it as a static site —
each screen node linked to its already-generated screenshot baseline — deployed to GitHub Pages,
so a whole app's navigation flow (with visuals) can be reviewed at a glance without running the
app.

Planned pieces:

- **Nodes**: a new KSP processor (or an extension of `ksp-processor`) enumerates
  `composable<Route> { ... }` declarations inside `NavHost` blocks — this is straightforward
  declaration-level KSP analysis, same category of work as v1's preview scanning.
- **Edges**: best-effort detection of `navController.navigate(RouteB(...))` call sites and which
  route they construct. This is a source/PSI-level analysis problem, not a declaration-level one
  — KSP's symbol resolution alone is not expected to be sufficient for arbitrary call sites
  (conditional navigation, navigation triggered from callbacks passed across composables, etc.).
  Expect this to need either a lightweight best-effort scanner (accepting some false
  negatives/positives) or, if that proves too unreliable, a fallback to an explicit
  `@NavigatesTo(RouteB::class)` annotation on routes as an opt-in escape hatch.
- **Site generation**: a Gradle task that reads the node/edge graph plus the `screenshotTestDebug`
  reference PNGs already produced by v1's pipeline (matched by wrapper-function naming
  convention), and renders a static HTML page (e.g. Mermaid.js/D3 graph with embedded/linked
  thumbnails).
- **Deployment**: a second reusable composite action (or workflow) wrapping
  `actions/deploy-pages` for consumers who want this in their own CI.

This is intentionally scoped out of v1: it is a materially different technical problem (source
call-graph analysis vs. declaration scanning) and a larger surface area. Revisit once v1 has
real-world usage and the edge-detection approach has been prototyped.

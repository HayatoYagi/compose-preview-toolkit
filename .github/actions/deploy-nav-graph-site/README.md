# deploy-nav-graph-site

Reusable composite GitHub Action that runs a compose-preview-toolkit nav-graph site generation
task (`generateDebugNavGraphSite`), and optionally publishes the result via one of two mutually
exclusive mechanisms selected by the `mode` input: a single shared GitHub Pages site (`pages`), or
a per-pull-request preview posted as a sticky PR comment (`pr-preview`). Building always happens
except when tearing down a closed PR's preview; publishing is opt-in via `mode`, so the same action
covers build-only CI dogfooding (every PR, the default), a real Pages deploy, or per-PR previews.

## Requirements

- GitHub Actions runner with `bash` and `git` available (for example `ubuntu-latest`)
- `actions/checkout` executed before this action
- A Gradle build with `gradlew` in `working-directory`, with `site-task` available in it
- Depending on `mode`:
  - `mode: 'build'` (default): no extra permissions or repo settings needed.
  - `mode: 'pages'`: the calling job needs `permissions: { pages: write, id-token: write }` and
    `environment: github-pages` (a composite action can't set job-level permissions or environment
    on its caller). Requires the repo's **Settings → Pages → Source** set to **GitHub Actions**.
  - `mode: 'pr-preview'`: the calling job needs `permissions: { contents: write, pull-requests:
    write }` (or pass a token with equivalent access as `github-token`). The calling workflow must
    trigger on `pull_request` events **including `closed`** (e.g. `types: [opened, reopened,
    synchronize, closed]`) so the preview is torn down when the PR closes. Requires the repo's
    **Settings → Pages → Source** set to **Deploy from branch** pointed at `pr-preview-branch`.

If your project also generates screenshot baselines (e.g. via this repo's own screenshot-testing
plugin) and you want the gallery thumbnails to reflect the latest ones, run whatever step updates
those baselines **before** this action in the same job — this action just reads whatever reference
images exist on disk at the time it runs, it doesn't regenerate them itself. Calling it from a
separate, independently-triggered workflow risks racing your baseline-update step and publishing a
site with stale thumbnails.

## Usage

```yaml
- uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v0.1.0
  with:
    site-task: ':app:generateDebugNavGraphSite'
    site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
    mode: 'build' # 'build' (default) | 'pages' | 'pr-preview'
```

If the site-generating module lives in a separate Gradle build (own `gradlew`), set
`working-directory` — e.g. this repo's own `sample/` (see `sample/settings.gradle.kts` for why
it's separate).

## Modes

- **`mode: 'build'`** (the default): only runs the Gradle task. No Pages permissions needed at
  all — useful for dogfooding `generateDebugNavGraphSite` on every PR without touching Pages.
- **`mode: 'pages'`**: additionally wraps `actions/configure-pages` / `actions/upload-pages-artifact`
  / `actions/deploy-pages` around the build step to deploy `site-directory` to a single, shared
  GitHub Pages site (one live deployment for the whole repo — every run replaces it).
- **`mode: 'pr-preview'`**: publishes the built site to a per-pull-request preview instead of one
  shared site, via [`rossjrw/pr-preview-action`](https://github.com/rossjrw/pr-preview-action).
  Each PR gets its own live URL at `pr-preview/pr-<number>/` (configurable via
  `pr-preview-umbrella-dir`) on a branch (`pr-preview-branch`, default `gh-pages`), pushed with
  retry-safe git so concurrent PRs' CI runs don't clobber each other's subdirectories, with a
  sticky PR comment linking to it. `pr-preview-action` detects the `closed` event itself and
  switches to cleanup mode; this action skips its own build step on that event since there's
  nothing to publish.

**Caveat if you also want a persisted "main" site**: GitHub Pages' source setting
(**Deploy from branch** vs **GitHub Actions**) is repo-wide, not per-workflow, so `mode: 'pages'`
(which requires the **GitHub Actions** source) and `mode: 'pr-preview'` (which requires
**Deploy from branch**) cannot both publish to the *same* repo's Pages site — you have to pick one
as your main-site mechanism. If you want per-PR previews *and* a persisted main deployment, deploy
the main site with a branch-based action too (e.g.
[`JamesIves/github-pages-deploy-action`](https://github.com/JamesIves/github-pages-deploy-action)
pushing to the same `pr-preview-branch`), and pass it `clean-exclude: pr-preview` (matching
`pr-preview-umbrella-dir`) so the main deploy doesn't wipe currently-live PR previews, and
`force: false` so it doesn't force-push over them either.

## How this repo uses it

This repo dogfoods both a persisted main site and PR previews for `sample/app`'s generated nav
graph, all without ever setting `mode: 'pages'`. All three steps below live in `ci.yml`'s single
job, positioned after its screenshot-baseline update step (see the note above on why) — not a
separately-triggered workflow:

- On `push` to `main`: calls this action with `mode: 'build'` (build-only validation, no Pages
  involved), then deploys that same `site-directory` to the `gh-pages` branch root via
  `JamesIves/github-pages-deploy-action` (`clean-exclude: pr-preview`, `force: false`) — the
  branch-based persisted-main-site mechanism described in the caveat above, sharing `gh-pages` with
  the pr-preview deploys instead of conflicting with them.
- On `pull_request`: calls this action with `mode: 'pr-preview'` instead — it builds and deploys
  the preview in one step, so a separate `mode: 'build'` call isn't needed (and would just build
  the same thing twice).
- [`nav-graph-pr-preview-teardown.yml`](../../workflows/nav-graph-pr-preview-teardown.yml) calls
  this action with `mode: 'pr-preview'` on `pull_request: closed` to tear the preview down — kept
  as its own minimal workflow since teardown needs none of the Gradle/JDK/mavenLocal setup the
  build-triggering steps need.

## Inputs

See [`action.yml`](./action.yml).

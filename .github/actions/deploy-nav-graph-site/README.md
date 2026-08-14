# deploy-nav-graph-site

Reusable composite GitHub Action that runs a compose-preview-toolkit nav-graph site generation
task (`generateDebugNavGraphSite`) and optionally publishes the result via the `mode` input — see
"Modes" below.

## Requirements

- GitHub Actions runner with `bash` and `git` available (for example `ubuntu-latest`)
- `actions/checkout` executed before this action
- A Gradle build with `gradlew` in `working-directory`, with `site-task` available in it
- Depending on `mode`:
  - `mode: 'build'` (default): no extra permissions or repo Pages configuration needed.
  - `mode: 'github-pages'`: the calling job needs `permissions: { contents: write, pull-requests:
    write }` (or pass a token with equivalent access as `github-token`); the calling workflow must
    trigger on both `push` (to publish the persisted main site) and `pull_request` **including
    `closed`** (e.g. `types: [opened, reopened, synchronize, closed]`, to publish/tear down
    previews — see "Usage" below); and repo **Settings → Pages → Source** must be **Deploy from
    branch**, pointed at `pr-preview-branch` (default `gh-pages`).

If your project also generates screenshot baselines (e.g. via this repo's own screenshot-testing
plugin) and you want the gallery thumbnails to reflect the latest ones, run whatever step updates
those baselines **before** this action in the same job — this action just reads whatever reference
images exist on disk at the time it runs, it doesn't regenerate them itself. Calling it from a
separate, independently-triggered workflow risks racing your baseline-update step and publishing a
site with stale thumbnails.

## Usage

The whole point of `mode: 'github-pages'` is that a consumer's workflow needs no more boilerplate
than this — one action call, correct behavior for push/open/sync/close all handled internally:

```yaml
on:
  push:
    branches: [main]
  pull_request:
    types: [opened, reopened, synchronize, closed]

permissions:
  contents: write
  pull-requests: write

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v0.1.0
        with:
          site-task: ':app:generateDebugNavGraphSite'
          site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
          mode: 'github-pages' # 'build' (default) | 'github-pages'
```

If the site-generating module lives in a separate Gradle build (own `gradlew`), set
`working-directory` — e.g. this repo's own `sample/` (see `sample/settings.gradle.kts` for why
it's separate).

## Modes

- **`mode: 'build'`** (the default): only runs the Gradle task. No Pages permissions or repo
  settings needed at all — useful for dogfooding `generateDebugNavGraphSite` on every PR without
  touching Pages.
- **`mode: 'github-pages'`**: publishes the built site, branching on the triggering event:
  - On `push`: deploys `site-directory` as the **persisted main site** — a branch-based deploy
    (via [`JamesIves/github-pages-deploy-action`](https://github.com/JamesIves/github-pages-deploy-action))
    to `pr-preview-branch`'s root, with `clean-exclude: pr-preview-umbrella-dir` so it never wipes
    currently-live PR previews.
  - On `pull_request` (`opened`/`reopened`/`synchronize`): deploys a **live per-PR preview** via
    [`rossjrw/pr-preview-action`](https://github.com/rossjrw/pr-preview-action) to
    `pr-preview-branch`, under `pr-preview-umbrella-dir/pr-<number>/` (configurable), pushed with
    retry-safe git so concurrent PRs' CI runs don't clobber each other's subdirectories, with a
    sticky PR comment linking to it.
  - On `pull_request: closed`: **tears down** that PR's preview — `pr-preview-action` detects the
    `closed` event itself and switches to cleanup mode; this action skips its own build step on
    this event since there's nothing to publish.
  - Any other event type: fails fast with a clear error rather than silently doing nothing, since
    it almost always means the calling workflow's trigger is misconfigured.

  Both the main-site and preview deploys are branch-based and share the same `pr-preview-branch`,
  so only one repo-wide Pages **Source** setting is needed for both.

## How this repo uses it

This repo dogfoods the full main-site-plus-previews experience for `sample/app`'s generated nav
graph, positioned in `ci.yml`'s single job right after its screenshot-baseline update step (see
the Requirements note on why):

- `ci.yml` calls this action once with `mode: 'github-pages'`, which builds the site itself before
  publishing: on `push` to `main` it deploys the persisted main site; on `pull_request` (`ci.yml`'s
  trigger has no `types:` filter, so only the implicit default `[opened, synchronize, reopened]`
  reach it) it deploys a live preview.
- [`nav-graph-pr-preview-teardown.yml`](../../workflows/nav-graph-pr-preview-teardown.yml) calls
  this action with `mode: 'github-pages'` on `pull_request: closed` to tear the preview down —
  kept as its own minimal workflow since teardown needs none of the Gradle/JDK/mavenLocal setup
  the build-and-publish steps need, and `ci.yml`'s own `pull_request` trigger never sees `closed`
  (see the comment at the top of that workflow file).

## Inputs

See [`action.yml`](./action.yml).

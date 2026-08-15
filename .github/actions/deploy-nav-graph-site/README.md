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
    branch**, pointed at `pages-branch` (default `gh-pages`).
- **Fresh screenshot baselines before this action runs, in the same job.** Thumbnails paired with
  the nav graph are the actual value of this gallery site — a graph with no thumbnails is the
  unusual case, not the common one — so most consumers pairing this action with a screenshot-testing
  setup (e.g. this repo's own screenshot-testing plugin) need a baseline-update step ahead of it.
  This action never generates or refreshes baselines itself; it only reads whatever reference
  images already exist on disk in `working-directory` at the time it runs. Run your
  baseline-update step **before** this action, **in the same job** — see "Usage" below. Calling
  this action from a separate, independently-triggered workflow risks that workflow checking out
  and building before the baseline-update step's auto-commit push has landed, publishing a site
  with stale thumbnails.

## Usage

One workflow handles the whole `mode: 'github-pages'` lifecycle — persisted main site, PR
previews, and teardown on close — matching
[`rossjrw/pr-preview-action`'s own recommended pattern](https://github.com/rossjrw/pr-preview-action#usage):
a single `pull_request` trigger including `closed`, with the expensive steps (anything this action
itself doesn't need to tear a preview down) skipped on that event via `if:`, rather than a second
workflow file just for cleanup. The realistic default also has a screenshot-testing setup feeding
this action's gallery thumbnails, so the example below runs a baseline-update step first, in the
same job — swap it for whatever your project uses to regenerate and commit its baselines (this
repo's own [`update-validate-screenshot-tests`](../update-validate-screenshot-tests) composite
action is one example); what matters is that it runs before this action, in the same job, so its
baseline commit is on disk when this action reads reference images:

```yaml
on:
  push:
    branches: [main]
  pull_request:
    types: [opened, reopened, synchronize, closed]

# Recommended by rossjrw/pr-preview-action's own README: never cancel an in-progress run in this
# group, since a cancelled run could be interrupted between pushing the preview and updating its
# PR comment, leaving the two out of sync.
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: false

permissions:
  contents: write
  pull-requests: write

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # Skipped when the PR closes — nothing here is needed to tear down a preview. Swap for
      # whatever step regenerates your project's baselines; this repo's own
      # update-validate-screenshot-tests is shown here as a concrete, runnable example.
      - name: Update screenshot baselines
        if: github.event_name != 'pull_request' || github.event.action != 'closed'
        uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v1.0.0
        with:
          github-token: ${{ secrets.GITHUB_TOKEN }}
          skip-validate: 'true'

      - uses: HayatoYagi/compose-preview-toolkit/.github/actions/deploy-nav-graph-site@v1.0.0
        with:
          site-task: ':app:generateDebugNavGraphSite'
          site-directory: 'app/build/composePreviewToolkit/navGraphSite/debug'
          mode: 'github-pages' # 'build' (default) | 'github-pages'
```

This action call itself stays unconditional: it already skips its own build step on `closed` (see
"Modes" below), so it runs fast on that event even though everything above it was skipped too. If
your project has no screenshot baselines at all (or doesn't care about gallery thumbnails), drop
the baseline-update step — the rest is unchanged.

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
    to `pages-branch`'s root, with `clean-exclude: pr-preview-umbrella-dir` so it never wipes
    currently-live PR previews.
  - On `pull_request` (`opened`/`reopened`/`synchronize`): deploys a **live per-PR preview** via
    [`rossjrw/pr-preview-action`](https://github.com/rossjrw/pr-preview-action) to
    `pages-branch`, under `pr-preview-umbrella-dir/pr-<number>/` (configurable), pushed with
    retry-safe git so concurrent PRs' CI runs don't clobber each other's subdirectories, with a
    sticky PR comment linking to it.
  - On `pull_request: closed`: **tears down** that PR's preview — `pr-preview-action` detects the
    `closed` event itself and switches to cleanup mode; this action skips its own build step on
    this event since there's nothing to publish.
  - Any other event type: fails fast with a clear error rather than silently doing nothing, since
    it almost always means the calling workflow's trigger is misconfigured.

  Both the main-site and preview deploys are branch-based and share the same `pages-branch`,
  so only one repo-wide Pages **Source** setting is needed for both.

## How this repo uses it

`ci.yml`'s single job/workflow follows exactly the "Usage" shape above — one `pull_request`
trigger including `closed`, the same `concurrency` block, screenshot-baseline update then this
action, expensive steps skipped on `closed` — with this repo's own extra setup at the front
(building its own plugin modules and publishing them to `mavenLocal`, since
`compose-preview-toolkit` is the plugin's own source repo dogfooding its own not-yet-published
code; a real consumer applies a published plugin version and skips that step entirely). On `push`
to `main` it deploys the persisted main site; on `pull_request` opened/reopened/synchronize it
deploys a live preview; on `pull_request: closed` it tears that preview down — all from this one
workflow, no separate teardown file to configure.

## Inputs

See [`action.yml`](./action.yml).

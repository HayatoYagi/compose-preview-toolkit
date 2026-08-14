# Contributing

## Local development

`annotations`, `ksp-processor`, `gradle-plugin`, `nav-graph-psi-analyzer`, and
`nav-graph-gradle-plugin` are a normal multi-project Gradle build at the repo root. `sample/` is a
**separate** Gradle build (its own `settings.gradle.kts`/`gradlew`) that applies the plugins
exactly like a real consumer would — see the comment at the top of `sample/settings.gradle.kts`
for why. That means it needs the current in-progress version resolvable via `mavenLocal()` before
it can build at all:

```
./gradlew :annotations:publishToMavenLocal :ksp-processor:publishToMavenLocal :gradle-plugin:publishToMavenLocal \
          :nav-graph-psi-analyzer:publishToMavenLocal :nav-graph-gradle-plugin:publishToMavenLocal
cd sample
./gradlew updateDebugScreenshotTest
./gradlew validateDebugScreenshotTest
./gradlew :app:generateDebugNavGraphSite
```

Re-run the `publishToMavenLocal` step after any change to `annotations`, `ksp-processor`,
`gradle-plugin`, `nav-graph-psi-analyzer`, or `nav-graph-gradle-plugin` to pick it up in `sample`.

`sample/` is itself multi-module: `sample/app` (Nav3 host, applies both the screenshot-test and
navgraph plugins), `sample/feature-a`, and `sample/feature-b` (navgraph plugin only — see
`AppNavHost.kt` for how they're wired). `:app` is the aggregator: running `generateDebugNavGraphSite`
there discovers `feature-a`/`feature-b` via `:app`'s own project dependencies and scans all three
together (see README.md's "Gallery site" subsection).

## Opening a PR

`ci.yml` runs on every PR: it builds `annotations`/`ksp-processor`/`gradle-plugin`/
`nav-graph-psi-analyzer`/`nav-graph-gradle-plugin`, publishes them to `mavenLocal`, then dogfoods
both composite actions against `sample/` — so a broken plugin/processor change or a broken
`action.yml` both surface directly in the PR's checks. First, `update-validate-screenshot-tests`
runs `updateDebugScreenshotTest`/`validateDebugScreenshotTest` (which Gradle resolves to just
`:app` since only `sample/app` applies the screenshot-testing plugin). Then `deploy-nav-graph-site`
runs `:app:generateDebugNavGraphSite` in `mode: 'github-pages'`, which builds across all three
sample modules and, on a pull_request run, also deploys a live per-PR preview of the result to
`gh-pages`.

## Releasing a new version

Versioning is manual and driven entirely through GitHub Actions — nothing is published from a
local machine.

1. From the **Actions** tab, run the **Bump Version** workflow (`workflow_dispatch`), choosing
   `patch`, `minor`, or `major`. Equivalently from the CLI:
   ```
   gh workflow run bump-version.yml -f bump=patch
   ```
   This opens a PR bumping `version=` in the root `gradle.properties` and the plugin `version`(s)
   in `sample/app`, `sample/feature-a`, and `sample/feature-b`'s `build.gradle.kts` (kept in sync
   — see the comment in `sample/settings.gradle.kts`).
2. Review and merge that PR into `main`.
3. Merging triggers **Tag Release** automatically: it creates and pushes a `vX.Y.Z` tag, creates
   a GitHub Release, then calls **Publish**.
4. **Publish** pushes `gradle-plugin` to the Gradle Plugin Portal and `annotations`/
   `ksp-processor` to Maven Central. Both go fully live automatically — Maven Central publishing
   is set to automatic release (`mavenCentralAutomaticPublishing=true` in `gradle.properties`),
   so there's no manual "Publish" click on central.sonatype.com to remember.
5. **First release of a new plugin ID only**: Gradle Plugin Portal holds a brand-new plugin ID's
   initial version for manual moderation before it becomes publicly visible — this only happens
   once (re-triggered only by changing the Maven group or plugin ID), and there's nothing to do
   but wait for their approval email. Version bumps after that publish immediately.

If **Publish** fails partway (e.g. missing secrets) after the tag/release were already created,
don't re-run **Bump Version** for a new version to "retry" it. Bumping again would leave the
earlier tag/release permanently dangling with nothing actually published under it. Instead, once
the actual problem is fixed, manually dispatch **Publish** for just the side that failed —
Gradle Plugin Portal rejects re-publishing a version that already succeeded there, so re-running
both unconditionally isn't an option:

```
gh workflow run publish.yml -f target=maven-central -f ref=vX.Y.Z
# or -f target=plugin-portal, or -f target=both if neither side succeeded
```

(Re-running the failed job directly from the **Tag Release** run's page looks like it should
work too, but gets stuck permanently in a "queued" state with zero jobs ever scheduled — use the
manual dispatch above instead.)

### Required repository configuration

These aren't set up by anything in this repo's code — a maintainer configures them once:

- **Settings → Actions → General → Workflow permissions**: "Read and write permissions" (needed
  for `ci.yml`'s baseline auto-commit and `tag-release.yml`'s tag push).
- **Settings → Secrets and variables → Actions**, six repository secrets:
  - `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` — from a [Gradle Plugin Portal](https://plugins.gradle.org)
    account, API Keys tab.
  - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — a User Token from a
    [Central Portal](https://central.sonatype.com) account with the `io.github.hayatoyagi`
    namespace verified.
  - `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` — a PGP key used to sign Maven Central publications.
    **GitHub Secrets are write-only** — once saved, nobody (including the person who set it) can
    read the value back out. Keep your own backup of the private key, passphrase, and a
    [revocation certificate](https://www.gnupg.org/gph/en/manual/c14.html) somewhere safe (a
    password manager, not just this repo) before registering it here.

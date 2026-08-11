# Contributing

## Local development

`annotations`, `ksp-processor`, and `gradle-plugin` are a normal multi-project Gradle build at
the repo root. `sample/` is a **separate** Gradle build (its own `settings.gradle.kts`/`gradlew`)
that applies the plugin exactly like a real consumer would — see the comment at the top of
`sample/settings.gradle.kts` for why. That means it needs the current in-progress version
resolvable via `mavenLocal()` before it can build at all:

```
./gradlew :annotations:publishToMavenLocal :ksp-processor:publishToMavenLocal :gradle-plugin:publishToMavenLocal
cd sample
./gradlew updateDebugScreenshotTest
./gradlew validateDebugScreenshotTest
```

Re-run the `publishToMavenLocal` step after any change to `annotations`, `ksp-processor`, or
`gradle-plugin` to pick it up in `sample`.

## Opening a PR

`ci.yml` runs on every PR: it builds `annotations`/`ksp-processor`/`gradle-plugin`, publishes
them to `mavenLocal`, then runs this repo's own `update-validate-screenshot-tests` composite
action against `sample/` — so a broken plugin/processor change or a broken `action.yml` both
surface directly in the PR's checks.

## Releasing a new version

Versioning is manual and driven entirely through GitHub Actions — nothing is published from a
local machine.

1. From the **Actions** tab, run the **Bump Version** workflow (`workflow_dispatch`), choosing
   `patch`, `minor`, or `major`. Equivalently from the CLI:
   ```
   gh workflow run bump-version.yml -f bump=patch
   ```
   This opens a PR bumping `version=` in the root `gradle.properties` and the plugin `version` in
   `sample/build.gradle.kts` (kept in sync — see the comment there).
2. Review and merge that PR into `main`.
3. Merging triggers **Tag Release** automatically: it creates and pushes a `vX.Y.Z` tag, creates
   a GitHub Release, then calls **Publish**.
4. **Publish** pushes `gradle-plugin` to the Gradle Plugin Portal and `annotations`/
   `ksp-processor` to Maven Central.

If **Publish** fails partway (e.g. missing secrets) after the tag/release were already created,
don't re-run **Bump Version** for a new version to "retry" — re-run the failed `publish` job
directly from the **Tag Release** run instead (Actions → that run → Re-run failed jobs), once
the actual problem is fixed. Bumping again would leave the earlier tag/release permanently
dangling with nothing actually published under it.

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

# update-validate-screenshot-tests

Reusable composite GitHub Action that runs AGP screenshot baseline update and validation in one flow:

1. Run `updateDebugScreenshotTest`
2. If baseline images changed, auto-commit and push the diff
3. Run `validateDebugScreenshotTest` (unless skipped)

## Requirements

- GitHub Actions runner with `bash` and `git` available (for example `ubuntu-latest`)
- `actions/checkout` executed before this action
- Workflow/job permission: `contents: write` (required for push)
- A Gradle build with `gradlew` in `working-directory`
- Screenshot tasks available in that build (`updateDebugScreenshotTest`, `validateDebugScreenshotTest`)

## Usage

```yaml
name: Update and Validate Screenshots

on:
  pull_request:

permissions:
  contents: write

jobs:
  screenshot-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Update baselines and validate
        uses: HayatoYagi/compose-preview-toolkit/.github/actions/update-validate-screenshot-tests@v1.0.0
        with:
          github-token: ${{ secrets.GITHUB_TOKEN }}
          working-directory: sample
```

If your screenshot-tested build lives at the repository root, omit `working-directory`.

## Inputs

See [`action.yml`](./action.yml).

Common options:

- `working-directory`: set when screenshot-tested module is in another Gradle build (for example `sample`)
- `gradle-args`: pass extra args to both Gradle tasks
- `skip-validate`: set to `"true"` to only update baselines
- `push-ref`: override target branch ref for the auto-commit push


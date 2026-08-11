// A separate, standalone Gradle build (own settings.gradle.kts) — NOT a subproject of the root
// build, and this is permanent, not a one-time bootstrap step. This module applies the
// compose-preview-toolkit plugin by id+version exactly like a real consumer would. Between
// releases, that version is always ahead of what's actually published (e.g. after 0.1.0 ships,
// local development immediately starts targeting the not-yet-released 0.1.1), so resolving it
// requires a local `publishToMavenLocal` step every time — this never stops being true as long
// as the repo keeps releasing versions. If `sample` were a subproject of the root build, Gradle
// would try (and fail) to configure it on every invocation, including the very
// `publishToMavenLocal` commands meant to make it resolvable in the first place.
//
// Local development: from the repo root, run
//   ./gradlew :annotations:publishToMavenLocal :ksp-processor:publishToMavenLocal :gradle-plugin:publishToMavenLocal
// then this module resolves everything via mavenLocal() below. See the root README.
pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-preview-toolkit-sample"

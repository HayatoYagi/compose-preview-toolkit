enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-preview-toolkit"

include(":annotations")
include(":ksp-processor")
include(":gradle-plugin")
include(":nav-graph-psi-analyzer")

// `sample` is intentionally NOT included here — see sample/settings.gradle.kts for why.

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    // No explicit Kotlin Android plugin: AGP 9's built-in Kotlin support handles it, and
    // applying org.jetbrains.kotlin.android alongside AGP 9 is now a hard error.
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
}

// @ScreenshotPreview is a plain Kotlin annotation with no Compose dependency at all, so this
// module has no Compose Compiler plugin, no compose buildFeature, and no library dependencies.

android {
    namespace = "io.github.hayatoyagi.composepreviewtoolkit.annotations"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    // Only when a signing key is actually configured (CI sets ORG_GRADLE_PROJECT_signingInMemoryKey,
    // exposed here as the "signingInMemoryKey" project property) — otherwise `publishToMavenLocal`
    // for local development would require every contributor to have a GPG key set up.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "compose-preview-toolkit-annotations", project.version.toString())
    pom {
        name.set("compose-preview-toolkit-annotations")
        description.set("Marker annotations for compose-preview-toolkit's screenshot-test generation Gradle plugin.")
        url.set("https://github.com/HayatoYagi/compose-preview-toolkit")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }
        developers {
            developer {
                id.set("HayatoYagi")
                name.set("Hayato Yagi")
                url.set("https://github.com/HayatoYagi")
            }
        }
        scm {
            url.set("https://github.com/HayatoYagi/compose-preview-toolkit")
            connection.set("scm:git:git://github.com/HayatoYagi/compose-preview-toolkit.git")
            developerConnection.set("scm:git:ssh://git@github.com/HayatoYagi/compose-preview-toolkit.git")
        }
    }
}

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api, not implementation: ScreenshotPreviewProcessorProvider implements KSP's
    // SymbolProcessorProvider, and gradle-plugin compiles directly against this class (to
    // reuse its option-key constants), so the KSP API needs to flow transitively.
    api(libs.ksp.symbol.processing.api)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    // Only when a signing key is actually configured (CI sets ORG_GRADLE_PROJECT_signingInMemoryKey,
    // exposed here as the "signingInMemoryKey" project property) — otherwise `publishToMavenLocal`
    // for local development would require every contributor to have a GPG key set up.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "compose-preview-toolkit-ksp-processor", project.version.toString())
    pom {
        name.set("compose-preview-toolkit-ksp-processor")
        description.set(
            "KSP processor that discovers marked Compose @Preview functions and indexes them " +
                "for compose-preview-toolkit's screenshot-test generation Gradle plugin.",
        )
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

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api, not implementation: KtFile/KtCallExpression/etc from this artifact are part of this
    // module's own public API surface (NavNodeScanner takes/returns them), so consumers need
    // them on their compile classpath too.
    api(libs.kotlin.compiler.embeddable)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    // SonatypeHost was removed from the DSL in mavenPublish 0.34.0 (OSSRH shutdown) — Central
    // Portal is now the only target, so a no-arg call is the replacement.
    publishToMavenCentral()
    // Only when a signing key is actually configured (CI sets ORG_GRADLE_PROJECT_signingInMemoryKey,
    // exposed here as the "signingInMemoryKey" project property) — otherwise `publishToMavenLocal`
    // for local development would require every contributor to have a GPG key set up.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "compose-preview-toolkit-nav-graph-psi-analyzer", project.version.toString())
    pom {
        name.set("compose-preview-toolkit-nav-graph-psi-analyzer")
        description.set(
            "PSI-based Kotlin source analyzer that extracts a Compose Navigation3 nav graph " +
                "for compose-preview-toolkit.",
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

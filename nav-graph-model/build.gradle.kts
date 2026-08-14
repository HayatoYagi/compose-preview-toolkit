plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

mavenPublishing {
    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "compose-preview-toolkit-nav-graph-model", project.version.toString())
    pom {
        name.set("compose-preview-toolkit-nav-graph-model")
        description.set(
            "Plain NavNode/NavEdge domain types for compose-preview-toolkit's nav-graph feature, " +
                "with no Kotlin-compiler dependency.",
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

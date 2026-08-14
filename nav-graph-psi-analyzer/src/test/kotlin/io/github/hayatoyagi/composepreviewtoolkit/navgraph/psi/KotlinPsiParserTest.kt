package io.github.hayatoyagi.composepreviewtoolkit.navgraph.psi

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KotlinPsiParserTest {
    private lateinit var parser: KotlinPsiParser

    @BeforeEach
    fun setUp() {
        parser = KotlinPsiParser()
    }

    @AfterEach
    fun tearDown() {
        parser.close()
    }

    @Test
    fun `CRLF files parse without hitting PsiFileFactory's line-separator assertion`() {
        // PsiFileFactory.createFileFromText asserts its input text has no non-LF line separators —
        // real files checked out with core.autocrlf=true (the common Windows Git default) violate
        // that. Writing raw \r\n bytes here (rather than relying on File.writeText's
        // platform-default separator, which is \n on the machines this test actually runs on) is
        // what makes that assertion reachable at all; parsing two files through the same
        // KotlinPsiParser instance mirrors how a real Gradle task scans a whole module.
        //
        // This case alone doesn't reproduce the original failure end-to-end (that needed a real
        // multi-module Gradle build scanning real production sources — verified separately by
        // running generateDebugNavGraphSite against a real consumer repo before/after this fix),
        // but it's still real coverage for the actual defect this fixes: CRLF text reaching
        // createFileFromText unnormalized.
        val tempDir = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "kotlinPsiParserCrlfTest")
            .toFile()
        try {
            val firstFile = File(tempDir, "FirstCrlfRoute.kt").writeCrlf(
                "package com.example\r\n" +
                    "\r\n" +
                    "import androidx.navigation3.runtime.NavKey\r\n" +
                    "import androidx.navigation3.runtime.entry\r\n" +
                    "\r\n" +
                    "object FirstCrlfRoute : NavKey\r\n" +
                    "\r\n" +
                    "fun registerFirst() {\r\n" +
                    "    entry<FirstCrlfRoute> { FirstCrlfScreen() }\r\n" +
                    "}\r\n",
            )
            val secondFile = File(tempDir, "SecondCrlfRoute.kt").writeCrlf(
                "package com.example\r\n" +
                    "\r\n" +
                    "import androidx.navigation3.runtime.NavKey\r\n" +
                    "import androidx.navigation3.runtime.entry\r\n" +
                    "\r\n" +
                    "object SecondCrlfRoute : NavKey\r\n" +
                    "\r\n" +
                    "fun registerSecond() {\r\n" +
                    "    entry<SecondCrlfRoute> { SecondCrlfScreen() }\r\n" +
                    "}\r\n",
            )

            val nodes = NavNodeScanner().scan(listOf(parser.parse(firstFile), parser.parse(secondFile)))

            assertEquals(2, nodes.size)
            // Line 9 (1-indexed) in both CRLF sources above is the entry<...> { ... } call site —
            // confirms line counting isn't thrown off by the normalization either.
            nodes.forEach { assertEquals(9, it.line) }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun File.writeCrlf(text: String): File = apply { writeBytes(text.toByteArray(Charsets.UTF_8)) }
}

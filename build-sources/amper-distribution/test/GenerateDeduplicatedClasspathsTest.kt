/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateDeduplicatedClasspathsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `all jars are copied to the jars dir and listed in the classpath index`() {
        val jar1 = (tempDir / "src1").createFile("kotlin-stdlib.jar")
        val jar2 = (tempDir / "src2").createFile("annotations.jar")
        val jarsDir = tempDir / "jars"
        val indexesDir = tempDir / "indexes"

        generateDeduplicatedClasspaths([NamedClasspath("cli", [jar1, jar2])], indexesDir, jarsDir)

        assertEquals(
            ["annotations.jar", "kotlin-stdlib.jar"].toSet(),
            jarsDir.listDirectoryEntries().map { it.name }.toSet(),
        )
        assertEquals(["kotlin-stdlib.jar", "annotations.jar"], readIndex(indexesDir, "cli"))
        assertEquals(jar1.readText(), (jarsDir / "kotlin-stdlib.jar").readText())
        assertEquals(jar2.readText(), (jarsDir / "annotations.jar").readText())
    }

    @Test
    fun `jars shared between classpaths are copied once and appear in both indexes`() {
        val sharedJar = (tempDir / "shared").createFile("common.jar")
        val jarA = (tempDir / "dirA").createFile("a.jar")
        val jarB = (tempDir / "dirB").createFile("b.jar")
        val jarsDir = tempDir / "jars"
        val indexesDir = tempDir / "indexes"

        generateDeduplicatedClasspaths(
            [
                NamedClasspath("cp1", [sharedJar, jarA]),
                NamedClasspath("cp2", [jarB, sharedJar]),
            ],
            indexesDir,
            jarsDir,
        )

        assertEquals(3, jarsDir.listDirectoryEntries().size)
        assertEquals(["common.jar", "a.jar"], readIndex(indexesDir, "cp1"))
        assertEquals(["b.jar", "common.jar"], readIndex(indexesDir, "cp2"))
    }

    @Test
    fun `jars with the same filename in different classpaths don't collide`() {
        val jarA = (tempDir / "dirA").createFile("lib.jar", content = "A")
        val jarB = (tempDir / "dirB").createFile("lib.jar", content = "B")
        val jarsDir = tempDir / "jars"
        val indexesDir = tempDir / "indexes"

        generateDeduplicatedClasspaths(
            [
                NamedClasspath("cp1", [jarA]),
                NamedClasspath("cp2", [jarB]),
            ],
            indexesDir,
            jarsDir,
        )

        val nameA = readIndex(indexesDir, "cp1").single()
        val nameB = readIndex(indexesDir, "cp2").single()
        assertEquals(2, jarsDir.listDirectoryEntries().size)
        assertEquals("A", (jarsDir / nameA).readText())
        assertEquals("B", (jarsDir / nameB).readText())
        assertTrue(nameA != nameB, "names should be distinct, but both were $nameA")
        [nameA, nameB].forEach {
            assertTrue(it.startsWith("lib"), "original filename should be preserved as prefix, but was $it")
            assertTrue(it.endsWith(".jar"), "extension should be preserved, but was $it")
        }
    }

    @Test
    fun `duplicate jars within a classpath are copied once and listed repeatedly as they originally appeared`() {
        val jar = (tempDir / "src").createFile("dup.jar")
        val jarsDir = tempDir / "jars"
        val indexesDir = tempDir / "indexes"

        generateDeduplicatedClasspaths([NamedClasspath("cli", [jar, jar])], indexesDir, jarsDir)

        assertEquals(1, jarsDir.listDirectoryEntries().size)
        assertEquals(["dup.jar", "dup.jar"], readIndex(indexesDir, "cli"))
    }

    private fun Path.createFile(name: String, content: String = name): Path {
        createDirectories()
        val path = this / name
        path.writeText(content)
        return path
    }

    private fun readIndex(indexesDir: Path, classpathName: String): List<String> =
        (indexesDir / "$classpathName.classpath.txt").readText().split("\n")
}

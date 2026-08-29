/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry.STORED
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarFile: Path get() = tempDir / "out.jar"

    private val inputsDir: Path by lazy { (tempDir / "inputs").createDirectory() }

    private fun Path.readManifest(): Manifest = JarFile(toFile()).use { jar ->
        requireNotNull(jar.manifest) { "No manifest in JAR file $this" }
    }

    private fun Path.readMainAttribute(name: String): String? = readManifest().mainAttributes.getValue(name)

    @Test
    fun `writes the manifest as the first entry`() {
        inputsDir.createTextFile("AAA_before_the_manifest_alphabetically.txt")

        jarFile.writeJar([ZipInput(path = inputsDir, destPathInArchive = Path(""))], JarConfig())

        val expectedNames: List<String> = [
            JarFile.MANIFEST_NAME,
            "AAA_before_the_manifest_alphabetically.txt",
        ]
        assertEquals(expectedNames, jarFile.readZipEntryNames())
    }

    @Test
    fun `writes a minimal manifest by default`() {
        jarFile.writeJar([], JarConfig())

        val manifest = jarFile.readManifest()
        assertEquals("1.0", manifest.mainAttributes.getValue(Attributes.Name.MANIFEST_VERSION))
        assertNull(manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS), "no Main-Class should be present")
    }

    @Test
    fun `writes the main class in the manifest when provided`() {
        jarFile.writeJar([], JarConfig(mainClassFqn = "com.example.MainKt"))

        assertEquals("com.example.MainKt", jarFile.readMainAttribute("Main-Class"))
    }

    @Test
    fun `writes the given manifest properties`() {
        val config = JarConfig(
            manifestProperties = mapOf(
                "Implementation-Title" to "Some title",
                "Implementation-Version" to "1.2.3",
            ),
        )
        jarFile.writeJar([], config)

        assertEquals("Some title", jarFile.readMainAttribute("Implementation-Title"))
        assertEquals("1.2.3", jarFile.readMainAttribute("Implementation-Version"))
    }

    @Test
    fun `manifest properties can override the main class`() {
        val config = JarConfig(
            mainClassFqn = "com.example.MainKt",
            manifestProperties = mapOf("Main-Class" to "com.example.OtherMainKt"),
        )
        jarFile.writeJar([], config)

        assertEquals("com.example.OtherMainKt", jarFile.readMainAttribute("Main-Class"))
    }

    @Test
    fun `writes the input files into the jar`() {
        inputsDir.createTextFile("com/example/Main.class", text = "fake class file")
        inputsDir.createTextFile("resource.txt", text = "some resource")

        jarFile.writeJar([ZipInput(path = inputsDir, destPathInArchive = Path(""))], JarConfig())

        val expectedNames: List<String> = [
            JarFile.MANIFEST_NAME,
            "com/",
            "com/example/",
            "com/example/Main.class",
            "resource.txt",
        ]
        assertEquals(expectedNames, jarFile.readZipEntryNames())
        assertEquals("some resource", jarFile.readZipEntry("resource.txt").text)
    }

    @Test
    fun `ignores the manifest coming from the inputs`() {
        inputsDir.createTextFile("META-INF/MANIFEST.MF", text = "Manifest-Version: 1.0\nMain-Class: com.example.FromInput\n")

        jarFile.writeJar([ZipInput(path = inputsDir, destPathInArchive = Path(""))], JarConfig())

        // the META-INF/ directory entry from the inputs is still written, but not the manifest itself
        val expectedNames: List<String> = [JarFile.MANIFEST_NAME, "META-INF/"]
        assertEquals(expectedNames, jarFile.readZipEntryNames())
        assertNull(jarFile.readMainAttribute("Main-Class"), "the manifest from the inputs must not be used")
    }

    @Test
    fun `uses a fixed timestamp for the manifest entry by default`() {
        jarFile.writeJar([], JarConfig())

        val manifestEntry = jarFile.readZipEntry(JarFile.MANIFEST_NAME)
        assertEquals(FixedFileTime, manifestEntry.lastModifiedTime)
        assertEquals(FixedFileTime, manifestEntry.lastAccessTime)
        assertEquals(FixedFileTime, manifestEntry.creationTime)
    }

    @Test
    fun `leaves the manifest entry timestamps unset when preserving file timestamps`() {
        // The manifest is generated, so there is no source file whose timestamps could be preserved.
        // In that case we don't set any timestamp on the entry, and the JDK falls back to the current time.
        jarFile.writeJar([], JarConfig(zipConfig = ZipConfig(preserveFileTimestamps = true)))

        val manifestEntry = jarFile.readZipEntry(JarFile.MANIFEST_NAME)
        assertNotEquals(FixedFileTime, manifestEntry.lastModifiedTime)
        assertNull(manifestEntry.creationTime, "no creation time should be recorded for the generated manifest")
        assertNull(manifestEntry.lastAccessTime, "no last access time should be recorded for the generated manifest")
    }

    @Test
    fun `applies the zip config to the entries`() {
        inputsDir.createTextFile("lib/some.jar", text = "a".repeat(1000))

        val config = JarConfig(
            zipConfig = ZipConfig(compressionStrategy = CompressionStrategy.StoreAll),
        )
        jarFile.writeJar([ZipInput(path = inputsDir, destPathInArchive = Path(""))], config)

        assertEquals(STORED, jarFile.readZipEntry("lib/some.jar").method)
    }

    @Test
    fun `produces byte-identical jars despite different file timestamps`() {
        inputsDir.createTextFile("a.txt")
        inputsDir.createTextFile("sub/b.txt")

        val config = JarConfig(mainClassFqn = "com.example.MainKt", manifestProperties = mapOf("Custom" to "value"))
        val inputs: List<ZipInput> = [ZipInput(path = inputsDir, destPathInArchive = Path(""))]

        val firstJar = tempDir / "first.jar"
        firstJar.writeJar(inputs, config)

        inputsDir.createTextFile("a.txt", lastModifiedTime = FileTime.from(Instant.parse("2030-01-01T00:00:00Z")))
        inputsDir.createTextFile("sub/b.txt", lastModifiedTime = FileTime.from(Instant.parse("2031-01-01T00:00:00Z")))

        val secondJar = tempDir / "second.jar"
        secondJar.writeJar(inputs, config)

        assertContentEquals(firstJar.readBytes(), secondJar.readBytes())
    }
}

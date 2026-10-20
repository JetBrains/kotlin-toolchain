/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.executablejar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutableJarTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarFile: Path get() = tempDir / "out.jar"

    private val fakeClassesDir: Path by lazy { (tempDir / "classes").createDirectory() }

    /**
     * Creates a fake dependency jar at the given [relativePath] under a temp dir, with the given [text] as content.
     */
    private fun fakeDependency(relativePath: String, text: String = "content of $relativePath"): Path {
        val file = tempDir / "deps" / relativePath
        file.createParentDirectories().writeText(text)
        return file
    }

    @Test
    fun `has a libs dir under BOOT-INF with runtime dependency JARs`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/example-lib-1.0.jar"),
            fakeDependency("com/other/lib/2.0/other-lib-2.0.jar"),
        ]

        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = "com.example.MainKt",
        )

        val libEntries = jarFile.libEntries()
        assertEquals(
            expected = [
                "BOOT-INF/lib/example-lib-1.0.jar",
                "BOOT-INF/lib/other-lib-2.0.jar",
            ],
            actual = libEntries,
            message = "BOOT-INF/lib/ should contain the JARs of the runtime dependencies",
        )
    }

    @Test
    fun `has a correct layer index file`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/example-lib-1.0.jar"),
            fakeDependency("com/other/lib/2.0/other-lib-2.0.jar"),
        ]

        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = "com.example.MainKt",
        )

        val layers = jarFile.readEntryText("BOOT-INF/layers.idx")
        assertEquals(
            expected = """
                - "spring-boot-loader":
                  - "org/"
                - "dependencies":
                  - "BOOT-INF/lib"
                - "application":
                  - "BOOT-INF/classes"
                  - "BOOT-INF/classpath.idx"
                  - "BOOT-INF/layers.idx"
                  - "META-INF/"
            """.trimIndent(),
            actual = layers,
        )
    }

    @Test
    fun `has a classpath index with one entry per line matching the libs dir contents`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/example-lib-1.0.jar"),
            fakeDependency("com/other/lib/2.0/other-lib-2.0.jar"),
        ]

        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = "com.example.MainKt",
        )

        assertEquals(jarFile.libEntries(), jarFile.readClasspathIndex().sorted())
    }

    @Test
    fun `merges class roots with shared directories`() {
        val first = fakeClassesDir / "com/example/First.class"
        first.createParentDirectories().writeText("first")
        val secondRoot = (tempDir / "other-classes").createDirectory()
        val second = secondRoot / "com/example/Second.class"
        second.createParentDirectories().writeText("second")

        jarFile.writeExecutableJar([fakeClassesDir, secondRoot], [], createFakeLoaderDir(), "com.example.MainKt")

        assertEquals("first", jarFile.readEntryText("BOOT-INF/classes/com/example/First.class"))
        assertEquals("second", jarFile.readEntryText("BOOT-INF/classes/com/example/Second.class"))
        assertEquals(1, jarFile.readEntryNames().count { it == "BOOT-INF/classes/com/example/" })
    }

    @Test
    fun `includes all dependencies even when their file names collide`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/lib-1.0.jar", text = "first lib"),
            fakeDependency("org/simple/lib/1.0/lib-1.0.jar", text = "second lib"),
            fakeDependency("com/other/other/1.0/other-1.0.jar", text = "other lib"),
        ]

        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = "com.example.MainKt",
        )

        val libEntries = jarFile.libEntries()
        assertEquals(
            expected = [
                "BOOT-INF/lib/lib-1.0-80b6d614.jar",
                "BOOT-INF/lib/lib-1.0-99a9045b.jar",
                "BOOT-INF/lib/other-1.0.jar",
            ],
            actual = libEntries,
            message = "duplicate lib entries should all be present and renamed (adding their sha256 content hash)",
        )
        assertEquals(["first lib", "other lib", "second lib"], libEntries.map { jarFile.readEntryText(it) }.sorted())
    }

    @Test
    fun `colliding dependency names without extensions are handled correctly`() {
        val dependencies: List<Path> = [
            fakeDependency("first/lib", text = "first"),
            fakeDependency("second/lib", text = "second"),
        ]
        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = null,
        )
        val libEntries = jarFile.libEntries()
        assertEquals(
            expected = [
                "BOOT-INF/lib/lib-16367aac",
                "BOOT-INF/lib/lib-a7937b64",
            ],
            actual = libEntries,
            message = "duplicate lib entries should all be present and renamed (adding their sha256 content hash)",
        )
    }

    @Test
    fun `colliding dependencies are disambiguated reproducibly`() {
        val dependencies: List<Path> = [
            fakeDependency("first/lib", text = "first"),
            fakeDependency("second/lib", text = "second"),
        ]
        val sameDepsDifferentPlace: List<Path> = [
            fakeDependency("otherPlace/first/lib", text = "first"),
            fakeDependency("otherPlace/second/lib", text = "second"),
        ]
        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = null,
        )
        val libEntries = jarFile.libEntries()
        assertEquals(
            expected = [
                "BOOT-INF/lib/lib-16367aac",
                "BOOT-INF/lib/lib-a7937b64",
            ],
            actual = libEntries,
            message = "duplicate lib entries should all be present and renamed (adding their sha256 content hash)",
        )

        val otherJar = tempDir / "other.jar"
        otherJar.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = sameDepsDifferentPlace,
            springBootLoaderJarUnpacked = createFakeLoaderDir(),
            mainClass = null,
        )
        assertEquals(libEntries, otherJar.libEntries())
        assertEquals(jarFile.readClasspathIndex(), otherJar.readClasspathIndex())
    }

    @Test
    fun `fails on dependencies with identical names and contents`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/lib-1.0.jar", text = "same contents"),
            fakeDependency("com/other/lib/1.0/lib-1.0.jar", text = "same contents"),
        ]

        val exception = assertFailsWith<IllegalArgumentException> {
            jarFile.writeExecutableJar(
                compiledClasses = [fakeClassesDir],
                runtimeDependencies = dependencies,
                springBootLoaderJarUnpacked = createFakeLoaderDir(),
                mainClass = "com.example.MainKt",
            )
        }

        assertEquals(
            expected = exception.message,
            actual = """
                Cannot build executable JAR with dependencies that have the same name and contents:
                  ${dependencies[1].absolutePathString()}
                  ${dependencies[0].absolutePathString()}
                Two JARs with identical names are allowed, but not if they have identical contents.
            """.trimIndent(),
        )
    }

    @Test
    fun `fails for duplicate classes instead of renaming them`() {
        val first = fakeClassesDir / "Main.class"
        first.writeText("first")
        val secondRoot = (tempDir / "other-classes").createDirectory()
        (secondRoot / "Main.class").writeText("second")

        assertFailsWith<ZipException> {
            jarFile.writeExecutableJar([fakeClassesDir, secondRoot], [], createFakeLoaderDir(), "Main")
        }
    }

    @Test
    fun `uses the Java 8 compatible launcher entrypoint when present`() {
        jarFile.writeExecutableJar(
            compiledClasses = [fakeClassesDir],
            runtimeDependencies = [],
            springBootLoaderJarUnpacked = createFakeLoaderDir(useLegacyLauncher = true),
            mainClass = "com.example.MainKt",
        )

        JarFile(jarFile.toFile()).use { jar ->
            assertEquals(
                "org.springframework.boot.loader.JarLauncher",
                jar.manifest.mainAttributes.getValue("Main-Class"),
            )
        }
    }

    private fun createFakeLoaderDir(useLegacyLauncher: Boolean = false): Path {
        val loaderDir = tempDir / "loader"
        val launcherPath = if (useLegacyLauncher) {
            "org/springframework/boot/loader/JarLauncher.class"
        } else {
            "org/springframework/boot/loader/launch/JarLauncher.class"
        }
        (loaderDir / launcherPath).createParentDirectories().writeText("launcher")
        return loaderDir
    }

    private fun Path.readEntryText(entryName: String): String = ZipFile(toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry(entryName)) { "No entry '$entryName' in archive $this" }
        zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    private fun Path.readEntryNames(): List<String> = ZipFile(toFile()).use { zip ->
        zip.entries().asSequence().map { it.name }.toList()
    }

    private fun Path.libEntries(): List<String> = readEntryNames().filter { it.startsWith("BOOT-INF/lib/") }.sorted()

    private fun Path.readClasspathIndex(): List<String> = readEntryText("BOOT-INF/classpath.idx")
        .lines()
        .filter { it.isNotEmpty() }
        .map { line ->
            assertTrue(line.startsWith("- \"") && line.endsWith("\""), "malformed index line: '$line'")
            line.removeSurrounding("- \"", "\"")
        }
}

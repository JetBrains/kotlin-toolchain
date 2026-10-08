/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.executablejar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class ExecutableJarTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarFile: Path get() = tempDir / "out.jar"

    private val loaderDir: Path by lazy { (tempDir / "loader").createDirectory() }

    private val classesDir: Path by lazy { (tempDir / "classes").createDirectory() }

    /**
     * Creates a fake dependency jar at the given [relativePath] under a temp dir, with the given [text] as content.
     */
    private fun fakeDependency(relativePath: String, text: String = "content of $relativePath"): Path {
        val file = tempDir / "deps" / relativePath
        file.createParentDirectories().writeText(text)
        return file
    }

    private fun Path.readEntryText(entryName: String): String = ZipFile(toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry(entryName)) { "No entry '$entryName' in archive $this" }
        zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    @Test
    fun `writes a classpath index with one entry per line matching the libs dir contents`() {
        val dependencies: List<Path> = [
            fakeDependency("org/example/lib/1.0/example-lib-1.0.jar"),
            fakeDependency("com/other/lib/2.0/other-lib-2.0.jar"),
        ]

        jarFile.writeExecutableJar(
            compiledClasses = [classesDir],
            runtimeDependencies = dependencies,
            springBootLoaderJarUnpacked = loaderDir,
            mainClass = "com.example.MainKt",
        )

        val expectedIndex = """
            - "BOOT-INF/lib/lib-1.0.jar"
            - "BOOT-INF/lib/lib-1.0_1.jar"

        """.trimIndent()
        assertEquals(expectedIndex, jarFile.readEntryText("BOOT-INF/classpath.idx"))
    }
}

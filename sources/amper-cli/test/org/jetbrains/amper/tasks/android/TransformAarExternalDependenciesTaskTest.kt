/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformAarExternalDependenciesTaskTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `root classes jar is included`() {
        val aar = tempDir.createAar("classes.jar")

        assertEquals(listOf(aar / "classes.jar"), extractedAarClasspathJars(aar))
    }

    @Test
    fun `all direct libs jars are included in deterministic order`() {
        val aar = tempDir.createAar("libs/z.jar", "libs/a.jar", "libs/m.jar")

        assertEquals(
            listOf(aar / "libs/a.jar", aar / "libs/m.jar", aar / "libs/z.jar"),
            extractedAarClasspathJars(aar),
        )
    }

    @Test
    fun `root classes jar precedes libs jars`() {
        val aar = tempDir.createAar("classes.jar", "libs/b.jar", "libs/a.jar")

        assertEquals(
            listOf(aar / "classes.jar", aar / "libs/a.jar", aar / "libs/b.jar"),
            extractedAarClasspathJars(aar),
        )
    }

    @Test
    fun `non jar and nested libs entries are ignored`() {
        val aar = tempDir.createAar("libs/a.jar", "libs/readme.txt", "libs/nested/b.jar")

        assertEquals(listOf(aar / "libs/a.jar"), extractedAarClasspathJars(aar))
    }

    @Test
    fun `missing class jars produce an empty classpath`() {
        val aar = tempDir.createAar()

        assertEquals(emptyList(), extractedAarClasspathJars(aar))
    }

    private fun Path.createAar(vararg entries: String): Path {
        createDirectories()
        entries.forEach { entry ->
            val path = this / entry
            path.createParentDirectories()
            path.writeText("")
        }
        return this
    }
}

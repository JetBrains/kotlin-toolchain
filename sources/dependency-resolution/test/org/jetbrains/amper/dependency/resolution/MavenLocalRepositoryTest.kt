/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.stdlib.hashing.hash
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals

class MavenLocalRepositoryTest {
    @TempDir
    lateinit var mavenRepository: Path

    private val mavenLocalRepository: MavenLocalRepository
        get() = MavenLocalRepository(mavenRepository)

    @Test
    fun `get name`() {
        assertEquals("kotlin-test-1.9.10.jar", "${kotlinTest().coordinates.getNameWithoutExtension()}.jar")
    }

    @Test
    fun `guess path`() = runTestWithMdc {
        val node = kotlinTest()
        val path = mavenLocalRepository.guessPath(node, "${node.coordinates.getNameWithoutExtension()}.jar")
        assertEquals(
            "org/jetbrains/kotlin/kotlin-test/1.9.10/kotlin-test-1.9.10.jar",
            path.relativeTo(mavenRepository).toString().replace('\\', '/')
        )
    }

    @Test
    fun `get path`() {
        val sha1 = randomString().hash("sha1").toHexString()
        val path = mavenLocalRepository.getPath(kotlinTest(), "${kotlinTest().coordinates.getNameWithoutExtension()}.jar", sha1)
        assertEquals(
            "org/jetbrains/kotlin/kotlin-test/1.9.10/kotlin-test-1.9.10.jar",
            path.relativeTo(mavenRepository).toString().replace('\\', '/')
        )
    }

    private fun kotlinTest() = MavenDependencyImpl(
        SettingsBuilder {
            cache = {
                localRepository = mavenLocalRepository
            }
        }.settings,
        "org.jetbrains.kotlin", "kotlin-test", "1.9.10"
    )

    private fun randomString() = UUID.randomUUID().toString()
}

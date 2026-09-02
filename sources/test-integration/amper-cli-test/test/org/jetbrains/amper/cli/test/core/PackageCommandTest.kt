/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.junit.jupiter.api.Tag
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-core")
class PackageCommandTest : CliTestBase() {

    @Test
    fun `package command produces an executable jar`() = runSlowTest {
        val result = runCli(projectDir = testProject("spring-boot"), "package")

        assertTrue("Executable jar file should exist after packaging") {
            (result.getTaskOutputPath(":spring-boot:executableJarJvm") / "spring-boot-jvm-executable.jar").exists()
        }
    }

    @Test
    fun `package command produces the same executable jar after clean`() = runSlowTest {
        val projectDir = testProject("spring-boot")

        suspend fun buildAndHashExecutableJar(): String {
            val result = runCli(projectDir = projectDir, "package")
            return (result.getTaskOutputPath(":spring-boot:executableJarJvm") / "spring-boot-jvm-executable.jar")
                .sha256String()
        }

        val hash1 = buildAndHashExecutableJar()
        val hash2 = buildAndHashExecutableJar()
        assertEquals(hash1, hash2, "Executable JAR should be identical on cache hit")

        runCli(projectDir = projectDir, "clean")

        val hash3 = buildAndHashExecutableJar()
        assertEquals(hash3, hash2, "Executable JAR should be identical after clean")
    }
}

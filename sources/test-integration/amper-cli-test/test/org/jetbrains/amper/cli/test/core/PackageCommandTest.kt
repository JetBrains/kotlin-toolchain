/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-core")
class PackageCommandTest : CliTestBase() {

    @Test
    fun `non-ipa packaging ignores incomplete ASC credentials`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("spring-boot"),
            "package",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_PATH")
            },
        )
        assertTrue((result.getTaskOutputPath(":spring-boot:executableJarJvm") / "spring-boot-jvm-executable.jar").exists())
    }

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

    @ParameterizedTest
    @ValueSource(ints = [8, 11, 16, 17, 21, 25])
    fun `executable jar supports the target release`(release: Int) = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        (projectDir / "module.yaml").writeText("""
            product: jvm/app
            settings:
              jvm:
                release: $release
        """.trimIndent())

        // running with --variant=release to run the executable JAR
        val result = runCli(projectDir = projectDir, "run", "--variant=release")
        result.assertStdoutContains("Hello, world!")
    }

    @ParameterizedTest
    @MacOnly
    @ValueSource(strings = ["KOTLIN_TOOLCHAIN_ASC_KEY_ID", "KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID", "KOTLIN_TOOLCHAIN_ASC_KEY_PATH"])
    fun `ipa auth key environment variables must be provided together`(missingVariable: String) = runSlowTest {
        val result = runCli(
            projectDir = iosProject(),
            "package", "--format=ipa",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID", "issuer-id")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_PATH", "AuthKey.p8")
                remove(missingVariable)
            },
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains(
            "The App Store Connect authentication environment variables must be provided together, or not at all."
        )
    }

    @ParameterizedTest
    @MacOnly
    @ValueSource(strings = ["missing.p8", "."])
    fun `ipa auth key path must be a readable file`(keyPath: String) = runSlowTest {
        val result = runCli(
            projectDir = iosProject(),
            "package", "--format=ipa",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID", "issuer-id")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_PATH", keyPath)
            },
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val expectedError = if (keyPath == "missing.p8") {
            "doesn't exist."
        } else {
            "is not a readable file."
        }
        result.assertStderrContains(expectedError)
    }

    @Test
    fun `ipa export options plist and auth environment variables are accepted`() = runSlowTest {
        val projectDir = testProject("spring-boot")
        (projectDir / "AuthKey.p8").writeText("test key")
        (projectDir / "ExportOptions.plist").writeText("<plist version=\"1.0\"><dict/></plist>")
        val result = runCli(
            projectDir = projectDir,
            "package", "--format=ipa", "--module=spring-boot",
            "--ipa-export-options-plist-path=ExportOptions.plist",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID", "issuer-id")
                put("KOTLIN_TOOLCHAIN_ASC_KEY_PATH", "AuthKey.p8")
            },
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("The 'ipa' package format is only supported for modules with the 'ios/app' product type")
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing.plist", "."])
    fun `ipa export options plist path must be a file`(plistPath: String) = runSlowTest {
        val result = runCli(
            projectDir = testProject("spring-boot"),
            "package", "--ipa-export-options-plist-path=$plistPath",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val reason = if (plistPath == ".") "is a directory" else "does not exist"
        result.assertStderrContains(
            "invalid value for --ipa-export-options-plist-path: file \"$plistPath\" $reason"
        )
    }

    @Test
    fun `ipa format is rejected for a non-ios-app module`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("spring-boot"),
            "package", "--format=ipa", "--module=spring-boot",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains(
            "The 'ipa' package format is only supported for modules with the 'ios/app' product type, " +
                    "but module 'spring-boot' has the 'jvm/app' product type."
        )
    }

    @Test
    @MacOnly
    fun `ipa packaging allows multiple modules and validates credentials`() = runSlowTest {
        val result = runCli(
            projectDir = iosProject("first", "second"),
            "package", "--format=ipa",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_PATH")
            },
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("must be provided together, or not at all")
    }

    @Test
    @MacOnly
    fun `ipa packaging allows multiple variants of one selected module`() = runSlowTest {
        val result = runCli(
            projectDir = iosProject("first", "second"),
            "package", "--format=ipa", "--module=first", "--variant=debug", "--variant=release",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", "ABC123")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID")
                remove("KOTLIN_TOOLCHAIN_ASC_KEY_PATH")
            },
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("must be provided together, or not at all")
    }

    private fun iosProject(vararg modules: String = arrayOf("app")): Path {
        val projectDir = newEmptyProjectDir(setupWrappers = true)
        (projectDir / "project.yaml").writeText("modules: [${modules.joinToString()}]")
        for (module in modules) {
            (projectDir / module).createDirectories()
            (projectDir / module / "module.yaml").writeText("product: ios/app")
        }
        return projectDir
    }
}

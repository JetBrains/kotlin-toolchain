/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertLogStartsWith
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.MinVersions
import org.jetbrains.amper.test.WindowsOnly
import org.jetbrains.amper.test.spans.assertEachKotlinJvmCompilationSpan
import org.jetbrains.amper.test.spans.assertEachKotlinNativeCompilationSpan
import org.jetbrains.amper.test.spans.assertKotlinJvmCompilationSpan
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.event.Level
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

data class CustomVersionCombination(val kotlinCompiler: String, val jdk: Int)

class KotlinVersionTest : AmperCliTestBase() {

    companion object {
        @JvmStatic
        private fun kotlinVersionsCombinations() = [
            // Kotlin 2.2.20 supports only up to JDK 24, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = MinVersions.kotlin.canonical, jdk = 21),
            // Kotlin 2.3 supports up to JDK 25, so we pin this LTS
            CustomVersionCombination(kotlinCompiler = DefaultVersions.kotlin, jdk = 25),
            // Kotlin 2.4 supports up to JDK 26, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = "2.4.10", jdk = 25),
            // Kotlin 2.4 supports up to JDK 26, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = "2.4.20-Beta1", jdk = 25),
        ]
    }

    @ParameterizedTest
    @MethodSource("kotlinVersionsCombinations")
    fun `run kotlin hello world with custom compiler version`(version: CustomVersionCombination) = runSlowTest {
        val projectDir = testProject("kotlin-jvm-helloworld-custom-version")
        val moduleFile = projectDir.resolve("module.yaml")
        moduleFile.writeText(
            moduleFile.readText()
                .replace("{{KOTLIN_COMPILER_VERSION}}", version.kotlinCompiler)
                .replace("{{JDK_VERSION}}", version.jdk.toString())
        )
        val result = runCli(projectDir = projectDir, "run")

        result.readTelemetrySpans().assertKotlinJvmCompilationSpan {
            doesNotHaveCompilerArgument("-language-version")
            doesNotHaveCompilerArgument("-api-version")
            hasAmperModule("kotlin-jvm-helloworld-custom-version")
        }
    }

    @Test
    fun `run jvm with language version 2_1`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-language-version-2.1"), "run")

        result.readTelemetrySpans().assertKotlinJvmCompilationSpan {
            hasCompilerArgument("-language-version=2.1")
            hasAmperModule("jvm-language-version-2.1")
        }
    }

    @Test
    fun `run jvm with language version 2_2`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-language-version-2.2"), "run")

        result.assertStdoutContains("Hello, world!")

        result.readTelemetrySpans().assertKotlinJvmCompilationSpan {
            hasCompilerArgument("-language-version=2.2")
            hasAmperModule("jvm-language-version-2.2")
        }
    }

    @Test
    fun `build native with language version 2_1`() = runSlowTest {
        val result = runCli(projectDir = testProject("native-language-version-2.1"), "build")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=2.1")
        }
    }

    @Test
    fun `build native with language version 2_2`() = runSlowTest {
        val result = runCli(projectDir = testProject("native-language-version-2.2"), "build")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=2.2")
        }
    }

    @Test
    @WindowsOnly
    fun `run native with language version 2_1`() = runSlowTest {
        val result = runCli(projectDir = testProject("native-language-version-2.1"), "run")

        result.assertStdoutContains("Hello, native!")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=2.1")
        }
    }

    @Test
    @WindowsOnly
    fun `run native with language version 2_2`() = runSlowTest {
        val result = runCli(projectDir = testProject("native-language-version-2.2"), "run")

        result.assertStdoutContains("Hello, native!")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=2.2")
        }
    }

    @Test
    fun `build multiplatform with language version 2_1`() = runSlowTest {
        val result = runCli(projectDir = testProject("multiplatform-language-version-2.1"), "build")

        result.withTelemetrySpans {
            assertEachKotlinJvmCompilationSpan {
                hasCompilerArgument("-language-version=2.1")
            }
            assertEachKotlinNativeCompilationSpan {
                hasCompilerArgument("-language-version=2.1")
            }
        }
    }

    @Test
    fun `build multiplatform with language version 2_2`() = runSlowTest {
        val result = runCli(projectDir = testProject("multiplatform-language-version-2.2"), "build")

        result.withTelemetrySpans {
            assertEachKotlinJvmCompilationSpan {
                hasCompilerArgument("-language-version=2.2")
            }
            assertEachKotlinNativeCompilationSpan {
                hasCompilerArgument("-language-version=2.2")
            }
        }
    }
}
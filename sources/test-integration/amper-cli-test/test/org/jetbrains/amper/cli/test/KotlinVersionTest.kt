/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

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
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

data class CustomVersionCombination(val kotlinCompiler: String, val jdk: Int)

class KotlinVersionTest : AmperCliTestBase() {

    companion object {
        @JvmStatic
        private fun kotlinVersionsCombinations() = [
            // Kotlin 2.2.20 supports only up to JDK 24, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = MinVersions.kotlin.canonical, jdk = 21),
            // Kotlin 2.3 supports up to JDK 25, so we pin this LTS
            CustomVersionCombination(kotlinCompiler = "2.3.21", jdk = 25),
            // Kotlin 2.4 supports up to JDK 26, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = DefaultVersions.kotlin, jdk = 25),
            // Kotlin 2.4 supports up to JDK 26, so we pin to the max LTS that fits
            CustomVersionCombination(kotlinCompiler = "2.4.20-Beta1", jdk = 25),
        ]

        @JvmStatic
        private fun languageVersions() = [ "2.2", "2.3", "2.4" ]
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

    @ParameterizedTest
    @MethodSource("languageVersions")
    fun `run jvm with custom language version`(languageVersion: String) = runSlowTest {
        val projectDir = testProject("jvm-custom-language-version")
        val moduleFile = projectDir.resolve("module.yaml")
        moduleFile.writeText(moduleFile.readText().replace("{{LANGUAGE_VERSION}}", languageVersion))

        val result = runCli(projectDir = projectDir, "run")

        result.readTelemetrySpans().assertKotlinJvmCompilationSpan {
            hasCompilerArgument("-language-version=$languageVersion")
            hasAmperModule("jvm-custom-language-version")
        }
    }

    @ParameterizedTest
    @MethodSource("languageVersions")
    fun `build native with custom language version`(languageVersion: String) = runSlowTest {
        val projectDir = testProject("native-custom-language-version")
        projectDir.walk()
            .filter { it.name == "module.yaml" }
            .forEach { moduleFile ->
                moduleFile.writeText(moduleFile.readText().replace("{{LANGUAGE_VERSION}}", languageVersion))
            }

        val result = runCli(projectDir = projectDir, "build")

        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=$languageVersion")
        }
    }

    @ParameterizedTest
    @MethodSource("languageVersions")
    @WindowsOnly
    fun `run native with custom language version`(languageVersion: String) = runSlowTest {
        val projectDir = testProject("native-custom-language-version")
        projectDir.walk()
            .filter { it.name == "module.yaml" }
            .forEach { moduleFile ->
                moduleFile.writeText(moduleFile.readText().replace("{{LANGUAGE_VERSION}}", languageVersion))
            }

        val result = runCli(projectDir = projectDir, "run")
        result.assertStdoutContains("Hello, native!")
        result.readTelemetrySpans().assertEachKotlinNativeCompilationSpan {
            hasCompilerArgument("-language-version=$languageVersion")
        }
    }

    @ParameterizedTest
    @MethodSource("languageVersions")
    fun `build multiplatform with custom language version`(languageVersion: String) = runSlowTest {
        val projectDir = testProject("multiplatform-custom-language-version")
        val templateFile = projectDir.resolve("common.module-template.yaml")
        templateFile.writeText(templateFile.readText().replace("{{LANGUAGE_VERSION}}", languageVersion))

        val result = runCli(projectDir = projectDir, "build")
        result.withTelemetrySpans {
            assertEachKotlinJvmCompilationSpan {
                hasCompilerArgument("-language-version=$languageVersion")
            }
            assertEachKotlinNativeCompilationSpan {
                hasCompilerArgument("-language-version=$languageVersion")
            }
        }
    }
}
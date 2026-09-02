/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.smoke

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.spans.assertJavaCompilationSpan
import org.jetbrains.amper.test.spans.assertKotlinJvmCompilationSpan
import org.jetbrains.amper.test.spans.kotlinJvmCompilationSpans
import org.jetbrains.amper.test.spans.withAmperModule
import kotlin.test.Test

class SmokeTest : CliTestBase() {

    @Test
    fun `help works`() = runSlowTest {
        runCli(newEmptyProjectDir(setupWrappers = true), "--help")
    }

    @Test
    fun `build and test`() = runSlowTest {
        runCli(testProject("jvm-kotlin-test-smoke"), "build")
        runCli(testProject("jvm-kotlin-test-smoke"), "test")
    }

    @Test
    fun `jvm-default-compiler-settings`() = runSlowTest {
        val projectRoot = testProject("jvm-default-compiler-settings")

        val runResult = runCli(projectDir = projectRoot, "run")
        // testing some default compiler arguments
        runResult.readTelemetrySpans().assertKotlinJvmCompilationSpan {
            doesNotHaveCompilerArgument("-language-version")
            doesNotHaveCompilerArgument("-api-version")
            hasCompilerArgument("-Xjdk-release=25")
        }
        runResult.assertStdoutContains("Hello, World")
    }

    @Test
    fun `jvm-explicit-compiler-settings`() = runSlowTest {
        val projectRoot = testProject("jvm-explicit-compiler-settings")

        val runResult = runCli(projectDir = projectRoot, "run")
        with(runResult.readTelemetrySpans()) {
            assertKotlinJvmCompilationSpan {
                hasCompilerArgument("-language-version=2.2")
                hasCompilerArgument("-java-parameters")
                hasCompilerArgument("-verbose")
                hasCompilerArgument("-Werror")
                hasCompilerArgument("-Xjdk-release=17")
                hasCompilerArgument("-Xwarning-level=UNCHECKED_CAST:warning")
            }
            assertJavaCompilationSpan {
                hasCompilerArgument("--release", "17")
                hasCompilerArgument("-parameters")
                hasCompilerArgument("-encoding", "ISO-8859-1")
            }
        }
        runResult.assertStdoutContains("Hello, World")
    }

    @Test
    fun `multi-module can run`() = runSlowTest {
        val projectRoot = testProject("multi-module")

        val runResult = runCli(projectDir = projectRoot, "run")
        with(runResult.readTelemetrySpans()) {
            kotlinJvmCompilationSpans.withAmperModule("app").assertSingle()
            kotlinJvmCompilationSpans.withAmperModule("shared").assertSingle()
        }
        runResult.assertStdoutContains("Hello, World!")

        val testResult = runCli(projectDir = projectRoot, "test")
        testResult.assertStdoutContains("Test run finished after")
    }
}

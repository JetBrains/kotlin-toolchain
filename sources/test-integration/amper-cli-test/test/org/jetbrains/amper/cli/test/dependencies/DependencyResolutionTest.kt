/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.dependencies

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertSomeStderrLineContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@ParameterizedTest(name = "{displayName}; platform={0}")
@ValueSource(strings = ["js", "jvm", "wasmJs", "wasmWasi"])
@Target(AnnotationTarget.FUNCTION)
private annotation class RunForEachKlibPlatformAndJvm

@Tag("cli-test-group-dependencies")
class DependencyResolutionTest : CliTestBase() {

    @Test
    fun `jvm exported dependencies`() = runSlowTest {
        val result = runCli(testProject("jvm-exported-dependencies"), "run", "--module=cli")

        result.assertStdoutContains("From Root Module + OneTwo")
    }

    // KTC-5875
    @RunForEachKlibPlatformAndJvm
    fun `types from non-exported transitive module dependencies are not visible`(platform: String) = runSlowTest {
        val result = runCli(
            projectDir = testProject("klib-non-exported-transitive-deps"),
            "build", "--module=leaking-consumer", "--platform=$platform",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        val expectedDiagnostic = when (platform) {
            // The JVM compilation reports diagnostics through Kotlin Toolchain's own problem reporter...
            "jvm" -> "ERROR: Unresolved reference 'Model'."
            // ...while the KLIB compilations forward the raw Kotlin compiler output.
            else -> "LeakingConsumer.kt:2:22: error: unresolved reference 'Model'."
        }
        result.assertSomeStderrLineContains(expectedDiagnostic)
    }

    // KTC-5875
    @RunForEachKlibPlatformAndJvm
    fun `types from exported transitive module dependencies are visible`(platform: String) = runSlowTest {
        // 'good-consumer' has test sources, so this also links the test executable, which requires the whole runtime
        // closure of the module dependencies (including the non-exported ones).
        runCli(
            projectDir = testProject("klib-non-exported-transitive-deps"),
            "build", "--module=good-consumer", "--platform=$platform",
        )
    }
}

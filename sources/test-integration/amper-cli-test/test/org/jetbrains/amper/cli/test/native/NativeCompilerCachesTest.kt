/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.native

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.konancSpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.spans.FilteredSpans
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the Kotlin/Native compiler caches (KTC-5422).
 *
 * These tests assert on the compiler arguments (and on which cache entries are reused) rather than on build times,
 * because the actual speedup depends on the machine and on how warm the machine-wide caches already are.
 */
@Tag("cli-test-group-native")
class NativeCompilerCachesTest : CliTestBase() {

    @Test
    @MacOnly
    fun `debug binaries are linked with dependency caches and incremental compilation`() = runSlowTest {
        val buildOutputRoot = tempRoot.resolve("build")
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            buildOutputRoot = buildOutputRoot,
        )

        result.withTelemetrySpans {
            val linkArgs = konancSpans.linkingMainBinary().assertSingle().compilerArgs()

            assertTrue(
                linkArgs.any { it.startsWith("-Xauto-cache-from=") },
                "Expected the external dependency roots to be cacheable, but got:\n$linkArgs",
            )
            // The compiler rejects the arguments if only one of these two is present.
            assertTrue("-Xenable-incremental-compilation" in linkArgs, "Expected incremental compilation:\n$linkArgs")
            assertTrue(
                linkArgs.any { it.startsWith("-Xic-cache-dir=") },
                "Expected an incremental cache directory:\n$linkArgs",
            )
        }

        val icCacheDir = buildOutputRoot / "kotlin-native-ic-cache"
        assertTrue(icCacheDir.exists(), "Expected the incremental caches to be created in $icCacheDir")
    }

    @Test
    @MacOnly
    fun `klib compilations are never given cache arguments`() = runSlowTest {
        // Caches belong to the second compilation stage, which doesn't run when producing a klib.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
        )

        result.withTelemetrySpans {
            konancSpans.producingKlib().all().forEach { span ->
                val args = span.compilerArgs()
                assertTrue(
                    args.none { it.startsWith("-Xauto-cache-from=") || it.startsWith("-Xic-cache-dir=") },
                    "Klib compilations must not get cache arguments, but got:\n$args",
                )
            }
        }
    }

    @Test
    @MacOnly
    fun `release binaries are linked without caches`() = runSlowTest {
        // The compiler ignores all caches when global optimizations are enabled, so we must not pass them.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli", "-v", "release",
        )

        result.withTelemetrySpans {
            val linkArgs = konancSpans.linkingMainBinary().assertSingle().compilerArgs()

            assertTrue("-opt" in linkArgs, "Expected an optimized build:\n$linkArgs")
            assertTrue(
                linkArgs.none { it.startsWith("-Xauto-cache-from=") || it.startsWith("-Xic-cache-dir=") },
                "Optimized binaries must be linked without caches, but got:\n$linkArgs",
            )
        }
    }

    @Test
    @MacOnly
    fun `both settings off means no caches at all`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            modifyProjectBeforeRun = { projectDir ->
                (projectDir / "macos-cli" / "module.yaml").appendText(
                    "\n  kotlin:\n    nativeCompilerCaches: false\n    compileIncrementally: false\n"
                )
            },
        )

        result.withTelemetrySpans {
            assertNoCacheArgs(konancSpans.linkingMainBinary().assertSingle().compilerArgs())
        }
    }

    @Test
    @MacOnly
    fun `incremental linking works without dependency caching`() = runSlowTest {
        // The two settings are independent: the compiler enables its cache machinery for either of them, so
        // disabling dependency caching must not silently disable incremental linking as well.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            modifyProjectBeforeRun = { projectDir ->
                (projectDir / "macos-cli" / "module.yaml").appendText(
                    "\n  kotlin:\n    nativeCompilerCaches: false\n    compileIncrementally: true\n"
                )
            },
        )

        result.withTelemetrySpans {
            val linkArgs = konancSpans.linkingMainBinary().assertSingle().compilerArgs()

            assertTrue("-Xenable-incremental-compilation" in linkArgs, "Expected incremental linking:\n$linkArgs")
            assertTrue(
                linkArgs.any { it.startsWith("-Xic-cache-dir=") },
                "Expected an incremental cache directory:\n$linkArgs",
            )
            assertTrue(
                linkArgs.none { it.startsWith("-Xauto-cache-from=") },
                "Dependency caching should be off:\n$linkArgs",
            )
        }
    }

    @Test
    @MacOnly
    fun `relinking with warm incremental caches picks up source changes`() = runSlowTest {
        // This is the failure mode of per-file incremental caches: if a stale cache entry is reused for a klib that
        // did change, the binary silently keeps behaving like the old code.
        val buildOutputRoot = tempRoot.resolve("build")
        val projectDir = testProject("simple-multiplatform-cli")
        // 'macos-cli' has no sources of its own, this is the actual-declaration linked into its binary.
        val worldSource = projectDir / "shared" / "src@macos" / "World.kt"

        val firstRun = runCli(
            projectDir = projectDir,
            "run", "--module=macos-cli",
            buildOutputRoot = buildOutputRoot,
        )
        firstRun.assertStdoutContains("Multiplatform CLI 12: Mac World")

        val icCacheDir = buildOutputRoot / "kotlin-native-ic-cache"
        val coldRunCacheState = icCacheDir.fileStates()

        val secondRun = runCli(
            projectDir = projectDir,
            "run", "--module=macos-cli",
            buildOutputRoot = buildOutputRoot,
            modifyProjectBeforeRun = {
                worldSource.writeText(worldSource.readText().replace("Mac World", "Cached World"))
            },
        )
        // The whole point: the cached link must reflect the change instead of reusing the stale cache entry.
        secondRun.assertStdoutContains("Multiplatform CLI 12: Cached World")

        secondRun.withTelemetrySpans {
            val linkArgs = konancSpans.linkingMainBinary().assertSingle().compilerArgs()
            assertTrue(
                linkArgs.any { it.startsWith("-Xic-cache-dir=${icCacheDir.pathString}") },
                "The second link must reuse the incremental caches from $icCacheDir, but got:\n$linkArgs",
            )
        }

        // The compiler stores the caches per klib and per file, as '<klib>-per-file-cache/<file-id>/...' entries.
        val warmRunCacheState = icCacheDir.fileStates()

        // 'utils' didn't change, so its cache entries must be reused as they are (this is what makes linking faster).
        val coldUtilsCache = coldRunCacheState.entriesOfKlib("utils")
        assertTrue(
            coldUtilsCache.isNotEmpty(),
            "Expected cache entries for the 'utils' klib in $icCacheDir, but got:\n${coldRunCacheState.render()}",
        )
        assertEquals(
            coldUtilsCache,
            warmRunCacheState.entriesOfKlib("utils"),
            "The cache entries of the unchanged 'utils' klib should have been left untouched",
        )

        // 'shared' did change, so at least some of its cache entries must have been recompiled.
        assertNotEquals(
            coldRunCacheState.entriesOfKlib("shared"),
            warmRunCacheState.entriesOfKlib("shared"),
            "The cache entries of the modified 'shared' klib should have been invalidated",
        )
    }

    private fun assertNoCacheArgs(linkArgs: List<String>) {
        assertTrue(
            linkArgs.none {
                it.startsWith("-Xauto-cache-from=") ||
                        it.startsWith("-Xic-cache-dir=") ||
                        it == "-Xenable-incremental-compilation"
            },
            "Expected no cache arguments at all, but got:\n$linkArgs",
        )
    }

    /**
     * The link of the module's main binary.
     *
     * Note that `build` also links the test binary, which is a `program` too, so the test runner generation is what
     * tells the two apart.
     */
    private fun FilteredSpans.linkingMainBinary() = filter("produces the main binary") {
        val args = it.compilerArgs()
        "-produce=program" in args && "-generate-test-runner" !in args
    }

    private fun FilteredSpans.producingKlib() =
        filter("produces a klib") { "-produce=library" in it.compilerArgs() }

    private fun SpanData.compilerArgs(): List<String> =
        attributes[AttributeKey.stringArrayKey("args")] ?: emptyList()

    /**
     * The last modification time of every file under this directory, keyed by its relative path.
     *
     * Used to tell reused cache entries (same path, same timestamp) from rebuilt ones.
     */
    private fun Path.fileStates(): Map<String, String> = walk()
        .filter { it.isRegularFile() }
        .associate { it.relativeTo(this).invariantSeparatorsPathString to it.getLastModifiedTime().toString() }

    /**
     * The subset of these cache entries that belongs to the klib with the given [klibName].
     */
    private fun Map<String, String>.entriesOfKlib(klibName: String): Map<String, String> =
        filterKeys { "$klibName-per-file-cache/" in it }

    private fun Map<String, String>.render(): String = entries.sortedBy { it.key }
        .joinToString("\n") { "  ${it.key} (${it.value})" }

    private operator fun Path.div(other: String): Path = resolve(other)
}

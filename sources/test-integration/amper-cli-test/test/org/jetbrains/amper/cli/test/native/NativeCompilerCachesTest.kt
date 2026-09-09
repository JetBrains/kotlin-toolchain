/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.native

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.konancSpans
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.spans.FilteredSpans
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the Kotlin/Native compiler caches (KTC-5422).
 *
 * These tests assert on the compiler arguments (and on which cache entries are reused) rather than on build times,
 * because the actual speedup depends on the machine and on how warm the machine-wide caches already are.
 */
@Tag("cli-test-group-native")
class NativeCompilerCachesTest : CliTestBase() {

    /**
     * The oldest Kotlin version whose compiler can build dependency caches without crashing (KT-88316), and thus the
     * oldest one for which Kotlin Toolchain enables the caches at all.
     *
     * TODO KT-88316 drop this pinning once [DefaultVersions.kotlin] reaches this version.
     */
    private val kotlinVersionSupportingCaches = "2.4.20-RC2"

    /**
     * Pins the Kotlin version of every module to [kotlinVersionSupportingCaches], and adds the given extra
     * `settings.kotlin` [entries] to the module under test.
     */
    private fun withCacheableKotlin(vararg entries: String): (Path) -> Unit = { projectDir ->
        projectDir.walk().filter { it.name == "module.yaml" }.forEach { moduleFile ->
            val extraEntries = if (moduleFile.parent?.name == "macos-cli") entries else emptyArray()
            moduleFile.addKotlinSettings("version: $kotlinVersionSupportingCaches", *extraEntries)
        }
    }

    /**
     * Appends the given `settings.kotlin` [entries] to this module file, creating the `settings` block if needed.
     */
    private fun Path.addKotlinSettings(vararg entries: String) {
        val hasSettingsBlock = readText().lineSequence().any { it.startsWith("settings:") }
        val lines = buildList {
            if (!hasSettingsBlock) add("settings:")
            add("  kotlin:")
            entries.forEach { add("    $it") }
        }
        appendText(lines.joinToString(separator = "\n", prefix = "\n", postfix = "\n"))
    }

    @Test
    @MacOnly
    fun `debug binaries are linked with dependency caches and incremental compilation`() = runSlowTest {
        val buildOutputRoot = tempRoot.resolve("build")
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            buildOutputRoot = buildOutputRoot,
            modifyProjectBeforeRun = withCacheableKotlin(),
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

        val icCacheDir = result.icCacheDirOfMainLink()
        assertTrue(icCacheDir.exists(), "Expected the incremental caches to be created in $icCacheDir")
        // The caches are an implementation detail of the link task, so they live in its own output directory
        // (which therefore cannot be wiped wholesale when re-linking incrementally).
        assertEquals(
            result.getTaskOutputPath(":macos-cli:linkMacosArm64Debug"),
            icCacheDir.parent,
            "Expected the incremental caches in the output directory of the link task",
        )
    }

    @Test
    @MacOnly
    fun `klib compilations are never given cache arguments`() = runSlowTest {
        // Caches belong to the second compilation stage, which doesn't run when producing a klib.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            modifyProjectBeforeRun = withCacheableKotlin(),
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
            modifyProjectBeforeRun = withCacheableKotlin(),
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
            modifyProjectBeforeRun = withCacheableKotlin(
                "nativeCompilerCaches: false",
                "compileIncrementally: false",
            ),
        )

        result.withTelemetrySpans {
            assertNoCacheArgs(konancSpans.linkingMainBinary().assertSingle().compilerArgs())
        }
    }

    @Test
    @MacOnly
    fun `no incremental linking without dependency caching`() = runSlowTest {
        // Without an auto-cache root, the compiler caches every dependency per file in the incremental cache dir
        // instead of monolithically in the shared one. Those caches are private to this binary and much larger, so
        // incremental linking is not worth it on its own and must be off as well.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            modifyProjectBeforeRun = withCacheableKotlin(
                "nativeCompilerCaches: false",
                "compileIncrementally: true",
            ),
        )

        result.withTelemetrySpans {
            assertNoCacheArgs(konancSpans.linkingMainBinary().assertSingle().compilerArgs())
        }
    }

    @Test
    @MacOnly
    fun `the local maven repository is cacheable`() = runSlowTest {
        // Klibs left outside the auto-cache roots are not excluded from caching, they are cached per file in the
        // per-binary incremental cache dir instead, so every klib location must be declared as a root.
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "build", "-p", "macosArm64", "-m", "macos-cli",
            modifyProjectBeforeRun = withCacheableKotlin(),
        )

        result.withTelemetrySpans {
            val linkArgs = konancSpans.linkingMainBinary().assertSingle().compilerArgs()
            val cacheableRoots = linkArgs.filter { it.startsWith("-Xauto-cache-from=") }
                .map { Path(it.removePrefix("-Xauto-cache-from=")) }

            assertTrue(
                Dirs.m2repository in cacheableRoots,
                "Expected the local maven repository ${Dirs.m2repository} among the cacheable roots, but got:" +
                        "\n$cacheableRoots",
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
            modifyProjectBeforeRun = withCacheableKotlin(),
        )
        firstRun.assertStdoutContains("Multiplatform CLI 12: Mac World")

        val icCacheDir = firstRun.icCacheDirOfMainLink()
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
     * The directory that the compiler was given for the incremental caches when linking the main binary.
     */
    private fun AmperCliResult.icCacheDirOfMainLink(): Path {
        val linkArgs = readTelemetrySpans().konancSpans.linkingMainBinary().assertSingle().compilerArgs()
        val icCacheDirArg = linkArgs.singleOrNull { it.startsWith("-Xic-cache-dir=") }
            ?: fail("Expected a single incremental cache directory in the main link, but got:\n$linkArgs")
        return Path(icCacheDirArg.removePrefix("-Xic-cache-dir="))
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

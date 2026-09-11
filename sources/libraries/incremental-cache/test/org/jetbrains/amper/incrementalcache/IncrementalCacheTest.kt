/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.incrementalcache

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import uk.org.webcompere.systemstubs.properties.SystemProperties
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class IncrementalCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val incrementalCache by lazy {
        IncrementalCache(stateRoot = tempDir / "incremental.state", codeVersion = "1")
    }
    private val executionsCount = AtomicInteger(0)
    private val nestedExecutionsCount = AtomicInteger(0)

    @Test
    fun trackingFile() {
        val file = tempDir.resolve("file.txt")

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = listOf(file)) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initial, MISSING state
        call()
        assertEquals(1, executionsCount.get())

        // up to date, the file is still MISSING
        call()
        assertEquals(1, executionsCount.get())

        // changed
        file.writeText("1")
        call()
        assertEquals(2, executionsCount.get())

        // up-to-date
        call()
        assertEquals(2, executionsCount.get())
    }

    @Test
    fun changeCodeVersionNumber() {
        val file = tempDir.resolve("file.txt").also { it.writeText("a") }

        fun call(codeVersion: String) = runBlocking {
            IncrementalCache(tempDir / "incremental.state", codeVersion = codeVersion).execute(
                key = "1",
                inputValues = emptyMap(),
                inputFiles = listOf(file),
            ) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initial
        call("1")
        assertEquals(1, executionsCount.get())

        // up-to-date
        call("1")
        assertEquals(1, executionsCount.get())

        // changed
        call("2")
        assertEquals(2, executionsCount.get())

        // up-to-date
        call("2")
        assertEquals(2, executionsCount.get())
    }

    @Test
    fun trackingFileInSubdirectory() {
        val dir = tempDir.resolve("dir").also { it.createDirectories() }
        val file = dir.resolve("file.txt")

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = listOf(dir)) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initial, MISSING state
        call()
        assertEquals(1, executionsCount.get())

        // up to date, the file is still MISSING
        call()
        assertEquals(1, executionsCount.get())

        // changed
        file.writeText("1")
        call()
        assertEquals(2, executionsCount.get())

        // up to date
        call()
        assertEquals(2, executionsCount.get())
    }

    @Test
    fun trackingEmptyDirectories() {
        val dir = tempDir.resolve("dir").also { it.createDirectories() }
        val subdir = dir.resolve("subdir")

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = listOf(dir)) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initial, MISSING state
        call()
        assertEquals(1, executionsCount.get())

        // up to date, subdir is still MISSING
        call()
        assertEquals(1, executionsCount.get())

        // changed
        subdir.createDirectories()
        call()
        assertEquals(2, executionsCount.get())

        // up to date
        call()
        assertEquals(2, executionsCount.get())
    }

    @Test
    fun `executes on missing output`() {
        runBlocking {
            val output = tempDir.resolve("out.txt")
            val result1 = incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                output.writeText("1")
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult([output])
            }
            assertEquals(listOf(output), result1.outputFiles)
            assertFalse(result1.cacheStatus is CacheHit)
            assertEquals("1", output.readText())

            // up to date
            val result2 = incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                output.writeText("2")
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult([output])
            }
            assertEquals(listOf(output), result2.outputFiles)
            assertTrue(result2.cacheStatus is CacheHit)
            assertEquals("1", output.readText())

            output.deleteExisting()

            // output was deleted
            val result3 = incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                output.writeText("3")
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult([output])
            }
            assertEquals([output], result3.outputFiles)
            assertFalse(result3.cacheStatus is CacheHit)
            assertEquals("3", output.readText())
        }
        assertEquals(2, executionsCount.get())
    }

    @Test
    fun `recalculation reasons on missing state and modified outputs`() {
        val output = tempDir.resolve("out.txt")

        fun call(): CacheMiss? {
            var cacheMiss: CacheMiss? = null
            runBlocking {
                incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                    cacheMiss = this.recalculationReason
                    output.writeText("compiled")
                    IncrementalCache.ExecutionResult([output])
                }
            }
            return cacheMiss
        }

        val initialReason = call()
        assertIs<CacheMiss.NoPreviousState>(initialReason)

        val reasonAfterCacheHit = call()
        assertNull(reasonAfterCacheHit, "No additional recalculation reason should be present on cache hit")

        output.deleteExisting()
        val reasonAfterDeletion = call()
        assertIs<CacheMiss.DataChanged>(reasonAfterDeletion)
        assertEquals(CacheMiss.DataChanged([TrackedElementType.OutputFile]), reasonAfterDeletion, "A deleted output is an output change")

        output.writeText("tampered")
        val reasonAfterTampering = call()
        assertIs<CacheMiss.DataChanged>(reasonAfterTampering)
        assertEquals(CacheMiss.DataChanged([TrackedElementType.OutputFile]), reasonAfterTampering, "A modified output is an output change")
    }

    @Test
    fun `recalculation reasons distinguish input and output changes`() {
        val input = tempDir.resolve("in.txt").also { it.writeText("a") }
        val output = tempDir.resolve("out.txt")

        fun call(inputValues: Map<String, String> = emptyMap()): CacheMiss? {
            var cacheMiss: CacheMiss? = null
            runBlocking {
                incrementalCache.execute(key = "1", inputValues = inputValues, inputFiles = [input]) {
                    cacheMiss = this.recalculationReason
                    output.writeText(input.readText())
                    IncrementalCache.ExecutionResult([output])
                }
            }
            return cacheMiss
        }

        val initialReason = call()
        assertEquals(CacheMiss.NoPreviousState, initialReason)

        input.writeText("b")
        val reasonAfterInputChange = call()
        assertIs<CacheMiss.DataChanged>(reasonAfterInputChange)
        assertEquals(CacheMiss.DataChanged([TrackedElementType.InputFileContents]), reasonAfterInputChange)

        val reasonAfterInputValueChange = call(inputValues = mapOf("k" to "v"))
        assertIs<CacheMiss.DataChanged>(reasonAfterInputValueChange)
        assertEquals(CacheMiss.DataChanged([TrackedElementType.InputValue]), reasonAfterInputValueChange)

        // Both the input and the output changed. The input change alone is enough to invalidate the cache, but the
        // block still needs to know that its outputs are not what it left behind.
        input.writeText("c")
        output.deleteExisting()
        val reasonAfterInputAndOutputChange = call(inputValues = mapOf("k" to "v"))
        assertIs<CacheMiss.DataChanged>(reasonAfterInputAndOutputChange)
        assertEquals(
            CacheMiss.DataChanged([TrackedElementType.InputFileContents, TrackedElementType.OutputFile]),
            reasonAfterInputAndOutputChange,
            "An output change must be reported even when an input changed as well",
        )
    }

    @Test
    fun `recalculation reason on a changed code version`() {
        val output = tempDir.resolve("out.txt")

        fun call(codeVersion: String): CacheMiss? {
            var cacheMiss: CacheMiss? = null
            runBlocking {
                IncrementalCache(tempDir / "incremental.state", codeVersion = codeVersion).execute(
                    key = "1",
                    inputValues = emptyMap(),
                    inputFiles = emptyList(),
                ) {
                    cacheMiss = this.recalculationReason
                    output.writeText("compiled")
                    IncrementalCache.ExecutionResult([output])
                }
            }
            return cacheMiss
        }

        val initialReason = call("1")
        assertEquals(CacheMiss.NoPreviousState, initialReason)

        val reasonAfterCodeVersionChange = call("2")
        assertIs<CacheMiss.CodeChanged>(reasonAfterCodeVersionChange)
    }

    @Test
    fun `forced recalculation is reported as such`() {
        val input = tempDir.resolve("in.txt").also { it.writeText("a") }
        val output = tempDir.resolve("out.txt")

        fun call(forceRecalculation: Boolean): CacheMiss? {
            var cacheMiss: CacheMiss? = null
            runBlocking {
                incrementalCache.execute(
                    key = "1",
                    inputValues = emptyMap(),
                    inputFiles = [input],
                    forceRecalculation = forceRecalculation,
                ) {
                    cacheMiss = this.recalculationReason
                    output.writeText("compiled")
                    IncrementalCache.ExecutionResult([output])
                }
            }
            return cacheMiss
        }

        val initialReason = call(forceRecalculation = false)
        assertEquals(CacheMiss.NoPreviousState, initialReason)

        // Nothing actually changed, but a forced recalculation means nothing may be reused.
        val reasonAfterForcing = call(forceRecalculation = true)
        assertIs<CacheMiss.RecalculationForced>(reasonAfterForcing)
    }

    @Test
    fun `output properties`() {
        runBlocking {
            val result1 = incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                IncrementalCache.ExecutionResult(emptyList(), mapOf("k" to "v", "e" to ""))
            }
            assertEquals("e:|k:v", result1.outputValues.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value}"})

            val result2 = incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                fail("should not reach")
            }
            assertEquals("e:|k:v", result2.outputValues.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value}"})
        }
    }

    @Test
    fun `reporting missing output must fail`() {
        assertFailsWith(NoSuchFileException::class) {
            runBlocking {
                incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                    IncrementalCache.ExecutionResult([tempDir.resolve("1.out")])
                }
            }
        }
    }

    @Test
    fun `executeForSerializable can serialize and handle errors`() {
        @Serializable
        data class Person(val name: String, val age: Int)
        @Serializable
        data class Person2(val name2: String, val age2: Int)

        runBlocking {
            val person = incrementalCache.executeForSerializable(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                Person("John", 25)
            }
            assertEquals(Person("John", 25), person, "Should get the computed value")

            val personCached = incrementalCache.executeForSerializable(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                // This is a trick. Normally, if the code changes, the cache is not reused and the state is replaced.
                // Here we're reusing the same code identifier, so this invalidation doesn't happen, and we just put a
                // different value here to test that we correctly read the value from the cache.
                Person("John", 26)
            }
            assertEquals(Person("John", 25), personCached, "Should reuse the cached state with age 25")

            val person2 = incrementalCache.executeForSerializable(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                Person2("New guy", 42)
            }
            assertEquals(Person2("New guy", 42), person2, "Should handle serialization errors by replacing the state")
        }
    }

    @Test
    fun `excluded outputs are ignored for up-to-date checks`() {
        runBlocking {
            val regularOutput = tempDir.resolve("regular.txt")
            val excludedOutput = tempDir.resolve("excluded.txt")

            fun call() = runBlocking {
                incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                    regularOutput.writeText("regular-1")
                    excludedOutput.writeText("excluded-1")
                    executionsCount.incrementAndGet()
                    IncrementalCache.ExecutionResult(
                        outputFiles = listOf(regularOutput, excludedOutput),
                        excludedOutputFiles = setOf(excludedOutput)
                    )
                }
            }

            call()
            assertEquals(1, executionsCount.get(), "Should execute for the first time")
            assertEquals("regular-1", regularOutput.readText())
            assertEquals("excluded-1", excludedOutput.readText())

            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as no files changed")

            excludedOutput.writeText("excluded-2")
            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as only excluded files changed")
            assertEquals("excluded-2", excludedOutput.readText(), "The change to the excluded file should persist")

            // Modify regular output - should trigger re-execution
            regularOutput.writeText("regular-2")
            call()
            assertEquals(2, executionsCount.get(), "Should execute again due to output change")
            assertEquals("regular-1", regularOutput.readText(), "The new exec should overwrite the regular file")
            assertEquals("excluded-1", excludedOutput.readText(), "The new exec should overwrite the excluded file")
        }
    }

    @Test
    fun `excluded file in parent directory output`() {
        runBlocking {
            val outputDir = tempDir.resolve("output-dir")
            val regularOutput = outputDir.resolve("regular.txt")
            val excludedOutput = outputDir.resolve("excluded.txt")

            fun call() = runBlocking {
                incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                    outputDir.createDirectories()
                    regularOutput.writeText("regular-1")
                    excludedOutput.writeText("excluded-1")
                    executionsCount.incrementAndGet()
                    IncrementalCache.ExecutionResult(
                        outputFiles = listOf(outputDir), // Only the directory is in outputs
                        excludedOutputFiles = setOf(excludedOutput) // But a file inside is excluded
                    )
                }
            }

            call()
            assertEquals(1, executionsCount.get(), "Should execute for the first time")
            assertEquals("regular-1", regularOutput.readText())
            assertEquals("excluded-1", excludedOutput.readText())

            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as no files changed")

            excludedOutput.writeText("excluded-2")
            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as only excluded files changed")
            assertEquals("excluded-2", excludedOutput.readText(), "The change to the excluded file should persist")

            regularOutput.writeText("regular-2")
            call()
            assertEquals(2, executionsCount.get(), "Should execute again due to output change")
            assertEquals("regular-1", regularOutput.readText(), "The new exec should overwrite the regular file")
            assertEquals("excluded-1", excludedOutput.readText(), "The new exec should overwrite the excluded file")
        }
    }

    @Test
    fun `excluded subdirectory`() {
        runBlocking {
            val outputDir = tempDir.resolve("output-dir")
            val regularSubdir = outputDir.resolve("regular-subdir")
            val excludedSubdir = outputDir.resolve("excluded-subdir")
            val regularOutput = regularSubdir.resolve("file.txt")
            val excludedOutput = excludedSubdir.resolve("file.txt")

            fun call() = runBlocking {
                incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                    regularSubdir.createDirectories()
                    excludedSubdir.createDirectories()

                    outputDir.createDirectories()
                    regularOutput.writeText("regular-1")
                    excludedOutput.writeText("excluded-1")
                    executionsCount.incrementAndGet()
                    IncrementalCache.ExecutionResult(
                        outputFiles = listOf(outputDir), // The parent directory is in outputs
                        excludedOutputFiles = setOf(excludedSubdir) // An entire subdirectory is excluded
                    )
                }
            }


            call()
            assertEquals(1, executionsCount.get(), "Should execute for the first time")
            assertEquals("regular-1", regularOutput.readText())
            assertEquals("excluded-1", excludedOutput.readText())

            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as no files changed")

            excludedOutput.writeText("excluded-2")
            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as only excluded files changed")
            assertEquals("excluded-2", excludedOutput.readText(), "The change to the excluded file should persist")

            excludedSubdir.resolve("new-excluded-file").writeText("new-excluded")
            call()
            assertEquals(1, executionsCount.get(), "Should not execute again, as only excluded files changed")
            assertTrue(excludedSubdir.resolve("new-excluded-file").exists(), "The new file should stay")

            regularOutput.writeText("regular-2")
            call()
            assertEquals(2, executionsCount.get(), "Should execute again due to output change")
            assertEquals("regular-1", regularOutput.readText(), "The new exec should overwrite the regular file")
            assertEquals("excluded-1", excludedOutput.readText(), "The new exec should overwrite the excluded file")

            excludedSubdir.deleteRecursively()
            call()
            assertEquals(2, executionsCount.get(), "Should not execute again, as the deleted dir was excluded")
            assertFalse(excludedSubdir.exists(), "The excluded directory should stay removed")

            regularSubdir.deleteRecursively()
            call()
            assertEquals(3, executionsCount.get(), "Should execute again due to output change")
            assertEquals("regular-1", regularOutput.readText(), "The new exec should overwrite the regular file")
            assertEquals("excluded-1", excludedOutput.readText(), "The new exec should overwrite the excluded file")
        }
    }

    @Test
    fun expirationTimeInFuture() {
        val file = tempDir.resolve("file.txt").also { it.writeText("a") }

        fun call(codeVersion: String) = runBlocking {
            IncrementalCache(tempDir / "incremental.state", codeVersion = codeVersion).execute(
                key = "1",
                inputValues = emptyMap(),
                inputFiles = listOf(file),
            ) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList(), expirationTime = Clock.System.now().plus(1.days))
            }
        }

        // initial
        call("1")
        assertEquals(1, executionsCount.get())

        // up to date
        call("1")
        assertEquals(1, executionsCount.get())
    }

    @Test
    fun expirationTimeInPast() {
        val file = tempDir.resolve("file.txt").also { it.writeText("a") }

        fun call(codeVersion: String) = runBlocking {
            IncrementalCache(tempDir / "incremental.state", codeVersion = codeVersion).execute(
                key = "1",
                inputValues = emptyMap(),
                inputFiles = listOf(file),
            ) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList(), expirationTime = Clock.System.now().minus(1.days))
            }
        }

        // initial
        call("1")
        assertEquals(1, executionsCount.get())

        // expired => recalculated
        call("1")
        assertEquals(2, executionsCount.get())
    }

    @Test
    @ExtendWith(SystemStubsExtension::class)
    fun trackingDynamicInputsSystemProperty(systemProperties: SystemProperties) {
        val propertyName = "my.test.output.system.property"

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                executionsCount.incrementAndGet()
                getDynamicInputs().readSystemProperty(propertyName)
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initial, property is missing
        call()
        assertEquals(1, executionsCount.get())

        // up to date, system property is still missing
        call()
        assertEquals(1, executionsCount.get())

        // Set system property affecting cache calculation
        systemProperties.set("my.test.output.system.property", "someValue")

        // changed, system property is defined
        call()
        assertEquals(2, executionsCount.get())

        // up to date, system property has not changed
        call()
        assertEquals(2, executionsCount.get())

        // changed, system property is updated to the new value
        systemProperties.set("my.test.output.system.property", "newValue")
        call()
        assertEquals(3, executionsCount.get())

        // up to date
        call()
        assertEquals(3, executionsCount.get())
    }

    @Test
    @ExtendWith(SystemStubsExtension::class)
    fun trackingDynamicInputsEnvironmentVariable(environmentVariables: EnvironmentVariables) {
        val envVar = "MY_TEST_OUTPUT_ENV_VAR"

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                executionsCount.incrementAndGet()
                getDynamicInputs().readEnv(envVar)
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initially, the environment variable is missing
        call()
        assertEquals(1, executionsCount.get())

        // up to date, the environment variable is still missing
        call()
        assertEquals(1, executionsCount.get())

        // Set environment variable affecting cache calculation
        environmentVariables.set(envVar, "someValue")

        // changed, the environment variable is defined
        call()
        assertEquals(2, executionsCount.get())

        // up to date, the environment variable has not changed
        call()
        assertEquals(2, executionsCount.get())

        // changed, the environment variable is updated to the new value
        environmentVariables.set(envVar, "newValue")
        call()
        assertEquals(3, executionsCount.get())

        // up to date
        call()
        assertEquals(3, executionsCount.get())
    }

    @Test
    fun trackingDynamicInputsPaths() {
        val file = tempDir.resolve("file-${UUID.randomUUID().toString().substring(0..8)}.txt")

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                executionsCount.incrementAndGet()
                getDynamicInputs().checkPathExistence(file)
                IncrementalCache.ExecutionResult(emptyList())
            }
        }

        // initially, the file is missing
        call()
        assertEquals(1, executionsCount.get())

        // up to date, the file is still missing
        call()
        assertEquals(1, executionsCount.get())

        // Create the file
        file.createFile()

        // changed, the file was created
        call()
        assertEquals(2, executionsCount.get())

        // up to date, the file is still there
        call()
        assertEquals(2, executionsCount.get())

        // Update the file content
        file.writeText("newText")

        // up to date, the file is updated but is still there
        call()
        assertEquals(2, executionsCount.get())
        
        // changed, the file is removed
        file.deleteIfExists()
        call()
        assertEquals(3, executionsCount.get())

        // up to date
        call()
        assertEquals(3, executionsCount.get())
    }

    @Test
    @ExtendWith(SystemStubsExtension::class)
    fun trackingNestedDynamicInputsSystemProperty(systemProperties: SystemProperties) {
        val propertyName = "my.test.output.system.property"

        fun call() = runBlocking {
            incrementalCache.execute(key = "1", inputValues = emptyMap(), inputFiles = emptyList()) {
                executionsCount.incrementAndGet()
                IncrementalCache.ExecutionResult(emptyList())

                incrementalCache.execute(key = "2", inputValues = emptyMap(), inputFiles = emptyList()) {
                    nestedExecutionsCount.incrementAndGet()
                    getDynamicInputs().readSystemProperty(propertyName)
                    IncrementalCache.ExecutionResult(emptyList())
                }
            }
        }

        // initial, property is missing
        call()
        assertEquals(1, executionsCount.get())
        assertEquals(1, nestedExecutionsCount.get())

        // up to date, system property is still missing
        call()
        assertEquals(1, executionsCount.get())
        assertEquals(1, nestedExecutionsCount.get())

        // Set system property affecting nested cache calculation
        systemProperties.set("my.test.output.system.property", "someValue")

        // changed, system property is defined, and this change was propagated to the state of the top-level upstream cache.
        call()
        assertEquals(2, executionsCount.get())
        assertEquals(2, nestedExecutionsCount.get())

        // up to date, system property has not changed
        call()
        assertEquals(2, executionsCount.get())
        assertEquals(2, nestedExecutionsCount.get())

        // changed, system property is updated to the new value
        systemProperties.set("my.test.output.system.property", "newValue")
        call()
        assertEquals(3, executionsCount.get())
        assertEquals(3, nestedExecutionsCount.get())

        // up to date
        call()
        assertEquals(3, executionsCount.get())
        assertEquals(3, nestedExecutionsCount.get())
    }
}

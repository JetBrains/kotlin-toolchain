/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("BlockingMethodInNonBlockingContext", "LoggingStringTemplateAsArgument")

package org.jetbrains.amper.incrementalcache

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import org.jetbrains.amper.concurrency.FineGrainedFileMutexGroup
import org.jetbrains.amper.concurrency.withDoubleLock
import org.jetbrains.amper.incrementalcache.DynamicInputsTracker.Companion.withDynamicInputsTracker
import org.jetbrains.amper.incrementalcache.IncrementalCache.Change.ChangeType
import org.jetbrains.amper.stdlib.hashing.hash
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.setMapAttribute
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.EnumSet
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.time.Clock
import kotlin.time.Instant

class IncrementalCache(
    /**
     * The directory where the cache state should be stored.
     *
     * It only contains the tracking state; inputs and outputs can be stored elsewhere.
     */
    private val stateRoot: Path,
    /**
     * Represents the identity of the code being executed. It should change when the cached logic changes.
     * The cache will be discarded and rebuilt if it was stored using a different [codeVersion].
     *
     * One suitable option for this [codeVersion] is to use the hash of the currently running code.
     * The [computeClassPathHash] helper provides a way to create a hash of all jars on the classpath.
     *
     * Note: this doesn't denote a namespace. If multiple instances of [IncrementalCache] are used with the same
     * [stateRoot] but different [codeVersion]s, they will overwrite a single state, not access independent states.
     * Use different [stateRoot]s if you need independent states.
     */
    private val codeVersion: String,
    /**
     * The telemetry instance to use for tracing. If not provided, a no-op instance will be used.
     */
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
    internal val tracer: Tracer
        get() = openTelemetry.getTracer("org.jetbrains.amper.incrementalcache")

    /**
     * Executes the given [block] or returns an existing result from the incremental cache for the given [key].
     *
     * ### Caching
     *
     * The previous result for the same [key] is immediately returned without executing [block] if all the following
     * conditions are met:
     *  * the [inputValues] map has not changed
     *  * the given set of [inputFiles] paths has not changed
     *  * the [inputFiles] themselves have not changed (in terms of size, modification time, and permissions)
     *  * the output files from the latest execution have not changed (in terms of size, modification time,
     *    and permissions)
     *  * the version of the code that produced the cached result is the same as the current version
     *
     * Note: output _values_ from previous executions don't affect caching because the values only exist in the state
     * file itself, so we have nothing to compare that to.
     * In short, there is no concept of "change" that we could track (unlike output files).
     *
     * ### Concurrency
     *
     * The given [block] is always executed under double-locking based on the given [key], which means that 2 calls with
     * the same [key] cannot be executed at the same time by multiple threads or multiple processes.
     * If one call needs to re-run [block] because the cache is invalid, concurrent calls with the same ID will suspend
     * until the first call completes and then resume and use the cache immediately (if possible).
     */
    suspend fun execute(
        key: String,
        inputValues: Map<String, String>,
        inputFiles: List<Path>,
        forceRecalculation: Boolean = false,
        /**
         * Calculation of cache entry.
         * If calculation depends on environment parameters such as
         * system properties, environment variables, or the existence of local paths
         * which are not known in advance (and thus could not be added to input values),
         * such environment parameters should be resolved with help of the dynamic inputs tracker provided by [getDynamicInputs].
         * This way access to environment parameters is tracked,
         * and cache entry will be recalculated automatically on subsequent access
         * if any of these environment parameters changes.
         */
        block: suspend IncrementalExecutionScope.() -> ExecutionResult,
    ): IncrementalExecutionResult = tracer.spanBuilder("inc: run: $key")
        .setMapAttribute("inputValues", inputValues)
        .setListAttribute("inputFiles", inputFiles.map { it.pathString }.sorted())
        .use { span ->

            stateRoot.createDirectories()

            val stateFile = stateFileFor(key)

            // Prevent parallel execution of this 'id' from this or other processes,
            // tracked by a lock on the state file
            withLock(stateFile) { stateFileChannel ->
                val state = tracer.spanBuilder("inc: read-state").use {
                    stateFileChannel.readState(pathForLogs = stateFile)
                }
                val cacheStatus = tracer.spanBuilder("inc: compare-state").use {
                    when {
                        forceRecalculation -> CacheMiss.RecalculationForced
                        state == null -> CacheMiss.NoPreviousState
                        else -> assessCacheStatus(state, inputValues, inputFiles)
                    }
                }
                span.setAttribute("status", cacheStatus.toString())

                when (cacheStatus) {
                    is CacheHit -> {
                        logger.debug("[inc] '$key' is up-to-date according to state file at '{}'", stateFile)
                        val existingResult = ExecutionResult(
                            outputFiles = cacheStatus.previousState.outputFiles.map { Path(it) },
                            outputValues = cacheStatus.previousState.outputValues,
                            expirationTime = cacheStatus.previousState.expirationTime
                        )
                        // Adding dynamic inputs used for calculating this cache entry to the dynamic inputs of the upstream cache (if any).
                        DynamicInputsTracker.getCurrentTracker()?.addFrom(cacheStatus.previousState.dynamicInputs)

                        span.addResult(existingResult, cacheStatus.previousState.dynamicInputs)
                        IncrementalExecutionResult(
                            executionResult = existingResult,
                            changes = [],
                            cacheStatus = cacheStatus,
                        )
                    }
                    is CacheMiss -> {
                        logger.debug("[inc] building '$key'")
                        // dynamic inputs tracker for registering environments parameters used for this cache entry calculation
                        val tracker = DynamicInputsTracker()

                        val result = tracer.spanBuilder("inc: execute").use {
                            withDynamicInputsTracker(tracker) {
                                IncrementalExecutionScope(recalculationReason = cacheStatus).block()
                            }
                        }
                        val dynamicInputsState = tracker.toState()
                        // Adding dynamic inputs used for calculating this cache entry to the dynamic inputs of the upstream cache (if any).
                        DynamicInputsTracker.getCurrentTracker()?.addFrom(dynamicInputsState)

                        span.addResult(result, dynamicInputsState)

                        tracer.spanBuilder("inc: write-state").use {
                            val state = recordState(inputValues, inputFiles, dynamicInputsState, result)
                            stateFileChannel.writeState(state)
                        }

                        val oldOutputFilesState = state?.outputFilesState ?: mapOf()
                        val newOutputFilesState = tracer.spanBuilder("inc: read-new-file-states").use {
                            readFileStates(
                                paths = result.outputFiles,
                                excludedFiles = result.excludedOutputFiles,
                                failOnMissing = false,
                            )
                        }
                        val outputFilesChanges = oldOutputFilesState compare newOutputFilesState

                        val dynamicInputsChanges = state?.dynamicInputs?.changes() ?: []

                        val changes = outputFilesChanges + dynamicInputsChanges

                        IncrementalExecutionResult(
                            executionResult = result,
                            changes = changes,
                            cacheStatus = cacheStatus,
                        ).also {
                            logger.debug("[inc] '$key' changes: {}", changes.joinToString { "'${it.path}' ${it.type}" })
                        }
                    }
                }
            }
        }

    /**
     * Returns the [Path] to the increment state file used to track the given [key].
     */
    private fun stateFileFor(key: String): Path {
        val sanitizedKey = key.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_").take(50)
        // hash includes stateFileFormatVersion to automatically use a different file if the file format was changed
        val hash = shortHash("$key\nstate format version: ${State.formatVersion}")
        return stateRoot.resolve("$sanitizedKey-$hash")
    }

    private fun shortHash(key: String): String = key.hash("MD5").toHexString().take(10)

    private fun Span.addResult(result: ExecutionResult, dynamicInputsState: DynamicInputsState) {
        setListAttribute("output-files", result.outputFiles.map { it.pathString }.sorted())

        // [outputValues] are not added as an attribute since it might be huge (serialized dependency graph)
        // and don't help much in investigating incremental cache behavior

        dynamicInputsState.systemProperties.takeIf { it.isNotEmpty() }
            ?.let { setMapAttribute("dynamic-inputs-system-properties", it) }
        dynamicInputsState.environmentVariables.takeIf { it.isNotEmpty() }
            ?.let { setMapAttribute("dynamic-inputs-environment-variables", it) }
        dynamicInputsState.pathsExistence.takeIf { it.isNotEmpty() }
            ?.let { setMapAttribute("dynamic-inputs-paths-existence", it) }
    }

    private fun recordState(
        inputValues: Map<String, String>,
        inputFiles: List<Path>,
        dynamicInputsState: DynamicInputsState,
        result: ExecutionResult,
    ): State = State(
        codeVersion = codeVersion,
        inputValues = inputValues,
        inputFiles = inputFiles.map { it.pathString }.toSet(),
        inputFilesState = readFileStates(paths = inputFiles, excludedFiles = emptySet(), failOnMissing = false),
        outputValues = result.outputValues,
        outputFiles = result.outputFiles.map { it.pathString }.toSet(),
        outputFilesState = readFileStates(
            result.outputFiles,
            excludedFiles = result.excludedOutputFiles,
            failOnMissing = true
        ),
        excludedOutputFiles = result.excludedOutputFiles.map { it.pathString }.toSet(),
        dynamicInputs = dynamicInputsState,
        expirationTime = result.expirationTime
    )

    private fun assessCacheStatus(
        state: State,
        inputValues: Map<String, String>,
        inputFiles: List<Path>,
    ): CacheStatus {
        if (state.codeVersion != codeVersion) {
            return CacheMiss.CodeChanged
        }
        if (state.expirationTime != null && state.expirationTime < Clock.System.now()) {
            return CacheMiss.StateExpired
        }

        // Note: all the checks below are performed even though a single one of them is enough to consider the state
        // outdated. This is because the executed block sometimes contains its own incremental state management and
        // needs to decide what it can reuse from the previous execution. Better only check once.
        // About performance concerns: note that, on cache hit, we have to perform all of these checks anyway.
        val changes = EnumSet.noneOf(TrackedElementType::class.java)
        if (state.inputValues != inputValues) {
            changes.add(TrackedElementType.InputValue)
        }
        if (state.inputFiles != inputFiles.map { it.pathString }.toSet()) {
            changes.add(TrackedElementType.InputFileSet)
        }
        if (state.inputFilesState != readFileStates(inputFiles, excludedFiles = emptySet(), failOnMissing = false)) {
            changes.add(TrackedElementType.InputFileContents)
        }

        val outputsList = state.outputFiles.map { Path(it) }
        val excludedOutputs = state.excludedOutputFiles.mapTo(mutableSetOf()) { Path(it) }
        val currentOutputsState = readFileStates(outputsList, excludedFiles = excludedOutputs, failOnMissing = false)
        if (state.outputFilesState != currentOutputsState) {
            changes.add(TrackedElementType.OutputFile)
        }

        val currentDynamicInputsState = state.dynamicInputs.calculateCurrentState()
        if (state.dynamicInputs.systemProperties != currentDynamicInputsState.systemProperties) {
            changes.add(TrackedElementType.SystemProperty)
        }
        if (state.dynamicInputs.environmentVariables != currentDynamicInputsState.environmentVariables) {
            changes.add(TrackedElementType.EnvironmentVariable)
        }
        if (state.dynamicInputs.pathsExistence != currentDynamicInputsState.pathsExistence) {
            changes.add(TrackedElementType.PathExistence)
        }
        return if (changes.isEmpty()) CacheHit(previousState = state) else CacheMiss.DataChanged(changes)
    }

    open class ExecutionResult(
        /**
         * The output files and directories created by the computation.
         *
         * The state of these files is recorded and persisted on disk. The next time the computation is run, the new
         * state of the files is compared to the recorded state, and the computation is re-run if anything is changed.
         */
        val outputFiles: List<Path>,
        /**
         * The key-value pairs produced by the computation.
         */
        val outputValues: Map<String, String> = emptyMap(),
        /**
         * The files to ignore when comparing the state of the output files.
         * Changes in these files do not invalidate the cache state.
         */
        val excludedOutputFiles: Set<Path> = emptySet(),
        /**
         * Date and time the cache entry is no longer valid after, and should be recalculated
         */
        val expirationTime: Instant? = null
    )
    
    data class IncrementalExecutionResult(
        private val executionResult: ExecutionResult,
        val changes: List<Change>,
        /**
         * The details about the cache hit or miss of this execution.
         */
        val cacheStatus: CacheStatus,
    ): ExecutionResult(
        executionResult.outputFiles,
        executionResult.outputValues,
        expirationTime = executionResult.expirationTime
    )
    
    data class Change(val path: Path, val type: ChangeType) {
        enum class ChangeType { CREATED, MODIFIED, DELETED }
    }
    
    private infix fun Map<String, String?>.compare(state: Map<String, String?>): List<Change> = buildList {
        for (key in keys union state.keys) {
            when {
                key !in this@compare -> add(Change(Path(key), ChangeType.CREATED))
                key !in state -> add(Change(Path(key), ChangeType.DELETED))
                this@compare[key] != state[key] -> add(Change(Path(key), ChangeType.MODIFIED))
            }
        }    
    }

    private fun DynamicInputsState.calculateCurrentState() =
        DynamicInputsState(
            systemProperties = systemProperties.map { it.key to System.getProperty(it.key) }.toMap(),
            environmentVariables = environmentVariables.map { it.key to System.getenv(it.key) }.toMap(),
            pathsExistence = pathsExistence.map { it.key to Path(it.key).exists().toString() }.toMap()
        )

    private fun DynamicInputsState.changes(): List<Change> = buildList {
        val currentDynamicInputsState = calculateCurrentState()
        addAll(currentDynamicInputsState.environmentVariables compare environmentVariables)
        addAll(currentDynamicInputsState.systemProperties compare systemProperties)
        addAll(currentDynamicInputsState.pathsExistence compare pathsExistence)
    }

    private fun DynamicInputsTracker.toState() =
        DynamicInputsState(
            systemProperties = systemProperties.toMap(),
            environmentVariables = environmentVariables.toMap(),
            pathsExistence = pathsExistence.map { it.key.pathString to it.value.toString() }.toMap(),
        )

    private fun DynamicInputsTracker.addFrom(dynamicInputs: DynamicInputsState) {
        systemProperties.putAll(dynamicInputs.systemProperties)
        environmentVariables.putAll(dynamicInputs.environmentVariables)
        pathsExistence.putAll(dynamicInputs.pathsExistence.map{ Path(it.key) to it.value.toBoolean() }.toMap())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IncrementalCache::class.java)

        // Important: we must not use a StripedFileMutexGroup here, because we have lots of cases of nested incremental
        // cache blocks, and this could lead to unfortunate deadlocks if we're unlucky on the stripes.
        // So far we've lived fine with a fine-grained but memory-unbounded approach. We can revisit the approach if
        // memory turns out to be a problem (having 10k modules would multiply greatly the number of locks in the map).
        private val fileMutexGroup = FineGrainedFileMutexGroup()

        private suspend fun <R> withLock(stateFile: Path, block: suspend (FileChannel) -> R): R =
            fileMutexGroup.withDoubleLock(stateFile) { channel ->
                block(channel)
            }
    }
}

/**
 * The receiver of the computation passed to [execute], giving it information about why it is being run.
 *
 * Most computations don't need this: they always regenerate all their outputs from their inputs, so the reasons
 * for the run are irrelevant. They matter for computations that maintain their own internal incremental state
 * (such as incremental compilation caches).
 */
class IncrementalExecutionScope internal constructor(
    /**
     * The reason why the computation is being run instead of reusing the result of its previous execution.
     */
    val recalculationReason: CacheMiss,
)

/**
 * Executes the given [block] and returns the output file paths, or immediately returns an existing result from the
 * incremental cache for the given [key].
 *
 * This is exactly equivalent to [IncrementalCache.execute], but without the need to wrap and unwrap the results
 * for cases where we just need output files and don't need output values.
 *
 * ### Caching
 *
 * The previous result for the same [key] is immediately returned without executing [block] if all the following
 * conditions are met:
 *  * the [inputValues] map has not changed
 *  * the given set of [inputFiles] paths has not changed
 *  * the [inputFiles] themselves have not changed (in terms of size, modification time, and permissions)
 *  * the output files from the latest execution have not changed (in terms of size, modification time,
 *    and permissions)
 *  * the version of the code that produced the cached result is the same as the current version
 *
 * ### Concurrency
 *
 * The given [block] is always executed under double-locking based on the given [key], which means that 2 calls with
 * the same [key] cannot be executed at the same time by multiple threads or multiple processes.
 * If one call needs to re-run [block] because the cache is invalid, later calls with the same ID will suspend
 * until the first call completes and then resume and use the cache immediately (if possible).
 */
suspend inline fun IncrementalCache.executeForFiles(
    key: String,
    inputValues: Map<String, String>,
    inputFiles: List<Path>,
    forceRecalculation: Boolean = false,
    crossinline block: suspend IncrementalExecutionScope.() -> List<Path>,
): List<Path> = execute(
    key = key,
    inputValues = inputValues,
    inputFiles = inputFiles,
    forceRecalculation = forceRecalculation,
) {
    IncrementalCache.ExecutionResult(block())
}.outputFiles

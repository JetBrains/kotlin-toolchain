/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.incrementalcache

/**
 * Indicates whether a potential previous state can be reused or not, and why.
 */
sealed interface CacheStatus

/**
 * Indicates that the previous state is still up to date in all respects, and thus the result can be reused.
 */
class CacheHit internal constructor(internal val previousState: State) : CacheStatus {
    override fun toString(): String = "CacheHit"
}

/**
 * Indicates that the previous result, if any is present, cannot be reused.
 * Different subclasses define different reasons for the miss.
 */
sealed interface CacheMiss : CacheStatus {

    /**
     * There is no recorded state from a previous execution, so there is nothing to compare the current state to,
     * and nothing may be reused.
     */
    data object NoPreviousState : CacheMiss

    /**
     * Any situation that leads to the complete disregard for the previously recorded state.
     *
     * The computation is expected to delete the output files, or at least consider them obsolete.
     * It must not reuse the files as-is.
     */
    sealed interface StateDiscarded : CacheMiss

    /**
     * The caller of [execute] explicitly requested a recalculation, regardless of the recorded state.
     * The result of the previous execution must not be reused, even if it is still up-to-date.
     */
    data object RecalculationForced : StateDiscarded

    /**
     * The recorded state was produced by a different [version of the code][IncrementalCache.codeVersion],
     * thus must be discarded.
     */
    data object CodeChanged : StateDiscarded

    /**
     * The explicit expiration time of the recorded state has passed, thus the state must be discarded.
     */
    data object StateExpired : StateDiscarded

    /**
     * The recorded state of the previous execution doesn't match the current state anymore.
     * At least one element changed.
     *
     * For optimization purposes, the computation can still look at the old output files even when
     * [TrackedElementType.OutputFile] is part of the [changes], but no guarantees are made about their state.
     * The computation must track them on its own to decide what can be salvaged.
     */
    data class DataChanged(
        /**
         * The types of elements that have changed and caused the state to be stale.
         */
        val changes: Set<TrackedElementType>,
    ) : CacheMiss {
        val outputsChanged: Boolean get() = TrackedElementType.OutputFile in changes
    }
}

/**
 * A type of element that is tracked by the [IncrementalCache] machinery as part of the state.
 */
enum class TrackedElementType {
    InputValue,
    InputFileSet,
    InputFileContents,
    OutputFile,
    SystemProperty,
    EnvironmentVariable,
    PathExistence,
}
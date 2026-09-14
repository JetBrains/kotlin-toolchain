/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.payload

import kotlinx.serialization.Serializable

/**
 * Conventional states understood by the progress tracking system.
 *
 * Each consumer is free to decide how to represent each progress state.
 * Different states can be represented in the same way or fully ignored if the environment doesn't support them.
 */
@Serializable
sealed interface ProgressState {
    /**
     * The operation is ongoing, but no quantitative progress information is available.
     *
     * This is the default operation progress state.
     * Clients may choose to represent this state as an infinite spinner or ignore it if it suits them visually.
     */
    @Serializable
    data object Indeterminate : ProgressState

    /**
     * Generic linear operation progress with no additional semantics.
     */
    @Serializable
    data class Generic(
        /**
         * The value in range [0.0; 1.0] representing the ratio of completion.
         */
        val ratio: Float,
    ) : ProgressState

    /**
     * Downloading progress.
     */
    @Serializable
    data class Downloading(
        /**
         * Quantity of bytes already downloaded.
         */
        val bytesDone: Long,
        /**
         * Quantity of bytes total, if available.
         *
         * When unavailable (`null`) effectively makes the progress indeterminate.
         */
        val bytesTotal: Long?,
        /**
         * Current speed in *bytes per second*.
         * The exact way the speed is measured depends on the download implementation.
         *
         * `null` if the speed can't (maybe yet) be measured.
         */
        val speed: Long?,
    ) : ProgressState
}

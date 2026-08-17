/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

/**
 * Exception that interrupts the task but doesn't fail the build even in the
 * [fail fast mode][org.jetbrains.amper.engine.TaskExecutor.Mode.FAIL_FAST].
 *
 * The exceptions are grouped at the end of the build using the [aggregator], and then reported as a single
 * [UserReadableError].
 */
abstract class SoftTaskFailureException(
    cause: Throwable? = null,
) : RuntimeException(cause) {
    abstract val aggregator: SoftTaskFailureAggregator
}

/**
 * Aggregator for [SoftTaskFailureException].
 *
 * [SoftTaskFailureException]s are grouped using _the aggregator equality_, so the same aggregator should be
 * structurally equal to the rest.
 */
interface SoftTaskFailureAggregator {
    fun aggregate(exceptions: List<SoftTaskFailureException>): UserReadableError
}

/**
 * Groups and aggregates collection of [SoftTaskFailureException] in ready-to-report [UserReadableError]s.
 */
fun Collection<SoftTaskFailureException>.aggregate(): List<UserReadableError> {
    val groupedExceptions = groupBy { it.aggregator }
    return groupedExceptions.map { [aggregator, exceptions] ->
        aggregator.aggregate(exceptions)
    }
}

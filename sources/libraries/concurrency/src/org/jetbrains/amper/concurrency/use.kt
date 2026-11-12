/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.concurrency

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * The suspending counterpart of [AutoCloseable].
 */
interface SuspendingCloseable {

    /**
     * Closes this [SuspendingCloseable], releasing any underlying resources.
     */
    suspend fun close()
}

/**
 * Executes the given [block] function on this resource and then closes it down correctly whether an exception is
 * thrown or not.
 * In case the resource is being closed due to an exception occurred in [block], and the closing also fails with an
 * exception, the latter is added to the suppressed exceptions of the former.
 *
 * @param block a function to process this [SuspendingCloseable] resource.
 *
 * @return the result of block function invoked on this resource.
 */
suspend inline fun <T : SuspendingCloseable?, R> T.use(block: (T) -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        closeFinally(exception)
    }
}

@PublishedApi
internal suspend fun SuspendingCloseable?.closeFinally(cause: Throwable?) {
    if (this == null) {
        return
    }
    if (cause == null) {
        close()
    } else {
        try {
            close()
        } catch (closeException: Throwable) {
            cause.addSuppressed(closeException)
        }
    }
}

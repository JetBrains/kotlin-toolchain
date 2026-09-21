/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.tools

import com.android.ddmlib.*
import kotlinx.coroutines.*
import java.io.*
import kotlin.coroutines.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import com.android.ddmlib.TimeoutException as DdmTimeoutException

/**
 * Waits until this device has finished booting.
 *
 * This function polls the `sys.boot_completed` property of this device every [pollingInterval], until it reports a
 * completed boot or until the given [timeout] elapses.
 */
suspend fun IDevice.awaitBootCompleted(
    pollingInterval: Duration = 500.milliseconds,
    timeout: Duration = 5.minutes,
) {
    // The last transient ADB failure that was ignored while polling, if any
    var lastTransientAdbFailure: Exception? = null

    val bootCompleted = withTimeoutOrNull(timeout) {
        while (true) {
            try {
                val isBootCompleted = executeShellCommandAndGetOutput("getprop sys.boot_completed").trim() == "1"
                if (isBootCompleted) {
                    return@withTimeoutOrNull
                }
            } catch (e: Exception) {
                if (!e.isTransientAdbFailure()) {
                    throw e
                }
                lastTransientAdbFailure = e
            }
            delay(pollingInterval)
        }
    }
    if (bootCompleted == null) {
        throw EmulatorBootFailureException(
            message = "The device '$serialNumber' didn't complete its boot within $timeout. Please check the state " +
                "of the device, and run this command again once the device is ready.",
            cause = lastTransientAdbFailure,
        )
    }
}

class EmulatorBootFailureException(override val message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Whether this exception is an ADB failure that is expected while a device is booting, and thus should not be fatal.
 *
 * ADB can reject commands with a "closed" or "device offline" message while the device's `adbd` daemon is restarting,
 * which happens during the boot. Commands can also time out or fail at the socket level.
 */
private fun Exception.isTransientAdbFailure(): Boolean = when (this) {
    is AdbCommandRejectedException,
    is ShellCommandUnresponsiveException,
    is DdmTimeoutException,
    is IOException,
        -> true
    else -> false
}

private suspend fun IDevice.executeShellCommandAndGetOutput(command: String): String =
    suspendCancellableCoroutine { continuation ->
        var isCancelled = false
        executeShellCommand(command, object : IShellOutputReceiver {
            val stringBuilder = StringBuilder()

            override fun addOutput(data: ByteArray, offset: Int, length: Int) {
                stringBuilder.append(data.decodeToString(offset, offset + length))
            }

            override fun flush() {
                continuation.resume(stringBuilder.toString())
            }

            override fun isCancelled(): Boolean = isCancelled
        })
        continuation.invokeOnCancellation {
            isCancelled = true
        }
    }
